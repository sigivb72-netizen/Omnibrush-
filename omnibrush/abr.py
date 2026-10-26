"""Photoshop .abr (v6.2) writer, plus a small reader used for verification.

v6.2 is the format written by Photoshop CS–2025 and is read by Photoshop,
Krita, GIMP, Procreate, Affinity, Fresco, Clip Studio Paint, MediBang and others.
Layout: u16 version=6, u16 sub=2, then 8BIM-tagged sections:
  samp  sampled tip bitmaps
  patt  patterns used by the Texture option
  desc  one action descriptor listing every preset's settings
"""
from __future__ import annotations

import struct
import uuid

import numpy as np

from .model import Brush, Dynamics
from .psdesc import Enum, Obj, UnitFloat, decode, encode

MAX_TIP = 2500  # Photoshop accepts up to 5000; 2500 keeps files small and every importer happy

BLEND_KEYS = {
    "multiply": "Mltp", "subtract": "Sbtr", "darken": "Drkn", "overlay": "Ovrl",
    "color_dodge": "CDdg", "color_burn": "CBrn", "linear_burn": "LnrB",
    "hard_mix": "HrdM", "height": "linearHeight",
}


def _pad4(b: bytes) -> bytes:
    return b + b"\x00" * (-len(b) % 4)


def _section(key: str, data: bytes) -> bytes:
    return b"8BIM" + key.encode() + struct.pack(">I", len(data)) + _pad4(data)


def _packbits_row(row: bytes) -> bytes:
    out = bytearray()
    i, n = 0, len(row)
    while i < n:
        j = i
        while j + 1 < n and row[j + 1] == row[i] and j - i < 127:
            j += 1
        if j > i:  # run of length j-i+1 >= 2
            out += bytes([(257 - (j - i + 1)) & 0xFF, row[i]])
            i = j + 1
            continue
        j = i
        while j < n and j - i < 128 and not (j + 1 < n and row[j] == row[j + 1]):
            j += 1
        out += bytes([j - i - 1]) + row[i:j]
        i = j
    return bytes(out)


def _rle(img: np.ndarray) -> bytes:
    rows = [_packbits_row(img[y].tobytes()) for y in range(img.shape[0])]
    return b"".join(struct.pack(">H", len(r)) for r in rows) + b"".join(rows)


def _fit(img: np.ndarray, limit: int = MAX_TIP) -> np.ndarray:
    h, w = img.shape
    if max(h, w) <= limit:
        return img
    from PIL import Image
    s = limit / max(h, w)
    return np.asarray(Image.fromarray(img).resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS))


def _sample(sid: str, img: np.ndarray) -> bytes:
    img = np.ascontiguousarray(_fit(img), dtype=np.uint8)
    h, w = img.shape
    # Virtual memory array, laid out exactly as Photoshop writes it: 56 declared
    # channels, 55 empty slots, the 8-bit opacity channel, then 2 empty slots.
    chan = struct.pack(">I4iHB", 8, 0, 0, h, w, 8, 1) + _rle(img)
    vm = struct.pack(">4iI", 0, 0, h, w, 56) + b"\x00" * (55 * 4)
    vm += struct.pack(">II", 1, len(chan)) + chan + b"\x00" * 8
    body = bytes([len(sid)]) + sid.encode("ascii")
    body = _pad4(body + struct.pack(">HHII", 1, 0, 3, len(vm)) + vm)
    return struct.pack(">I", len(body)) + body


def _pattern(pid: str, name: str, gray: np.ndarray) -> bytes:
    """Embedded pattern record (RGB, 24 declared channels, PackBits)."""
    gray = np.ascontiguousarray(gray, dtype=np.uint8)
    h, w = gray.shape
    vm = struct.pack(">4iI", 0, 0, h, w, 24)
    chan = struct.pack(">I4iHB", 8, 0, 0, h, w, 8, 1) + _rle(gray)
    for slot in range(26):
        if slot < 3:
            vm += struct.pack(">II", 1, len(chan)) + chan
        else:
            vm += struct.pack(">I", 0)
    nm = name.encode("utf-16-be")
    rec = struct.pack(">IIhh", 1, 3, h, w) + struct.pack(">I", len(nm) // 2) + nm
    rec += bytes([len(pid)]) + pid.encode("ascii")
    rec = _pad4(rec + struct.pack(">II", 3, len(vm)) + vm)
    return struct.pack(">I", len(rec)) + rec


def _dyn(d: Dynamics | None) -> Obj:
    control = 0
    if d and d.pressure:
        control = 2
    elif d and d.tilt:
        control = 3
    return Obj("brVr", [
        ("bVTy", control),
        ("fStp", 25),
        ("jitter", UnitFloat("#Prc", round((d.jitter if d else 0) * 100, 3))),
        ("Mnm ", UnitFloat("#Prc", round((d.minimum if d else 0) * 100, 3))),
    ])


def _preset(b: Brush, sample_id: str | None, pattern: tuple[str, str] | None) -> Obj:
    if sample_id:
        tip = Obj("sampledBrush", [
            ("Nm  ", b.name),
            ("sampledData", sample_id),
            ("Dmtr", UnitFloat("#Pxl", float(b.size))),
            ("Angl", UnitFloat("#Ang", float(b.angle))),
            ("Rndn", UnitFloat("#Prc", b.roundness * 100)),
            ("Spcn", UnitFloat("#Prc", b.spacing * 100)),
            ("Intr", True),
            ("flipX", False),
            ("flipY", False),
        ])
    else:
        tip = Obj("computedBrush", [
            ("Dmtr", UnitFloat("#Pxl", float(b.size))),
            ("Hrdn", UnitFloat("#Prc", b.hardness * 100)),
            ("Angl", UnitFloat("#Ang", float(b.angle))),
            ("Rndn", UnitFloat("#Prc", b.roundness * 100)),
            ("Spcn", UnitFloat("#Prc", b.spacing * 100)),
            ("Intr", True),
            ("flipX", False),
            ("flipY", False),
        ])
    sd, ad = b.size_dynamics, b.angle_dynamics
    use_tip_dyn = sd.pressure or sd.tilt or sd.jitter > 0 or ad.jitter > 0 or b.angle_follows_stroke
    angle_dyn = _dyn(ad)
    if b.angle_follows_stroke:
        angle_dyn.items[0] = ("bVTy", 6)  # direction
    use_transfer = b.flow_dynamics.active or b.opacity_dynamics.active
    items = [
        ("Nm  ", b.name),
        ("Brsh", tip),
        ("useTipDynamics", bool(use_tip_dyn)),
        ("flipX", False),
        ("flipY", False),
        ("brushProjection", False),
        ("minimumDiameter", UnitFloat("#Prc", sd.minimum * 100)),
        ("minimumRoundness", UnitFloat("#Prc", 25.0)),
        ("tiltScale", UnitFloat("#Prc", 200.0)),
        ("szVr", _dyn(sd)),
        ("angleDynamics", angle_dyn),
        ("roundnessDynamics", _dyn(None)),
        ("useScatter", b.scatter > 0),
        ("Spcn", UnitFloat("#Prc", round(min(1000.0, b.scatter / max(b.size, 1) * 100), 3))),
        ("Cnt ", b.scatter_count),
        ("bothAxes", True),
        ("countDynamics", _dyn(None)),
        ("scatterDynamics", _dyn(None)),
        ("dualBrush", Obj("dualBrush", [("useDualBrush", False)])),
    ]
    t = b.texture
    items.append(("useTexture", bool(pattern and t)))
    if pattern and t:
        pid, pname = pattern
        items += [
            ("TxtC", t.each_tip),
            ("interpretation", False),
            ("textureBlendMode", Enum("BlnM", BLEND_KEYS.get(t.blend, "Mltp"))),
            ("textureDepth", UnitFloat("#Prc", t.depth * 100)),
            ("minimumDepth", UnitFloat("#Prc", 0.0)),
            ("textureDepthDynamics", _dyn(None)),
            ("Txtr", Obj("Ptrn", [("Nm  ", pname), ("Idnt", pid)])),
            ("textureScale", UnitFloat("#Prc", t.scale * 100)),
            ("InvT", t.invert),
            ("protectTexture", True),
            ("textureBrightness", int(t.brightness)),
            ("textureContrast", int(t.contrast)),
        ]
    items += [
        ("usePaintDynamics", bool(use_transfer)),
        ("prVr", _dyn(b.flow_dynamics)),
        ("opVr", _dyn(b.opacity_dynamics)),
        ("wtVr", _dyn(None)),
        ("mxVr", _dyn(None)),
        ("useColorDynamics", False),
        ("clVr", _dyn(None)),
        ("H   ", UnitFloat("#Prc", 0.0)),
        ("Strt", UnitFloat("#Prc", 0.0)),
        ("Brgh", UnitFloat("#Prc", 0.0)),
        ("purity", UnitFloat("#Prc", 0.0)),
        ("colorDynamicsPerTip", True),
        ("Wtdg", False),
        ("Nose", False),
        ("Rpt ", False),
        ("useBrushSize", True),
        ("useBrushPose", False),
        ("toolOptions", Obj("paintbrushTool", [
            ("brushPreset", True),
            ("flow", int(round(b.flow * 100))),
            ("Opct", int(round(b.opacity * 100))),
            ("Md  ", Enum("BlnM", "Nrml")),
            ("smoothing", b.stabilization > 0),
            ("smoothingValue", int(min(100, b.stabilization))),
            ("usePressureOverridesSize", False),
            ("usePressureOverridesOpacity", False),
        ])),
    ]
    return Obj("brushPreset", items)


def write_abr(brushes: list[Brush], textures: bool = True) -> bytes:
    samples, patterns, presets = b"", b"", []
    for b in brushes:
        tips = b.tips or [None]
        pattern = None
        if textures and b.texture is not None:
            pid = str(uuid.uuid4())
            pattern = (pid, b.texture.name)
            img = 255 - b.texture.image if b.texture.invert else b.texture.image
            # Photoshop texture: white = paint passes, black = paint removed
            patterns += _pattern(pid, b.texture.name, img)
        for i, tip in enumerate(tips):
            sid = None
            if tip is not None:
                sid = str(uuid.uuid4())
                samples += _sample(sid, tip)
            preset_brush = b if i == 0 else _renamed(b, f"{b.name} (tip {i + 1})")
            presets.append(_preset(preset_brush, sid, pattern))
    out = struct.pack(">HH", 6, 2)
    out += _section("samp", samples)
    out += _section("patt", patterns)  # Photoshop always writes this section, even when empty
    out += _section("desc", encode(Obj("null", [("Brsh", presets)])))
    return out


def _renamed(b: Brush, name: str) -> Brush:
    from dataclasses import replace
    return replace(b, name=name)


# ------------------------------------------------------------------ reader
def _unrle(data: bytes, w: int, h: int) -> tuple[np.ndarray, int]:
    lens = struct.unpack(f">{h}H", data[:2 * h])
    p = 2 * h
    out = np.zeros((h, w), np.uint8)
    for y, ln in enumerate(lens):
        row, q, end = bytearray(), p, p + ln
        while q < end:
            n = data[q]
            q += 1
            if n < 128:
                row += data[q:q + n + 1]
                q += n + 1
            elif n > 128:
                row += bytes([data[q]]) * (257 - n)
                q += 1
        out[y, :] = np.frombuffer(bytes(row[:w]), np.uint8)
        p = end
    return out, p


def read_abr(data: bytes) -> dict:
    """Parse an ABR v6+ file into {'samples': {id: img}, 'patterns': {id: (name, img)}, 'desc': Obj}."""
    ver, sub = struct.unpack(">HH", data[:4])
    if ver < 6:
        raise ValueError(f"ABR v{ver} not supported by the reader")
    res = {"version": (ver, sub), "samples": {}, "patterns": {}, "desc": None}
    p = 4
    while p + 12 <= len(data):
        sig, key, ln = data[p:p + 4], data[p + 4:p + 8].decode(), struct.unpack(">I", data[p + 8:p + 12])[0]
        body = data[p + 12:p + 12 + ln]
        if sig != b"8BIM":
            raise ValueError("bad section signature")
        if key == "samp":
            q = 0
            while q < len(body):
                n = struct.unpack(">I", body[q:q + 4])[0]
                e = body[q + 4:q + 4 + n]
                sid = e[1:1 + e[0]].decode()
                r = 1 + e[0] + (264 if sub == 2 else 10)
                t, l, bt, rt, depth, comp = struct.unpack(">4iHB", e[r:r + 19])
                w, h = rt - l, bt - t
                px = e[r + 19:]
                img = _unrle(px, w, h)[0] if comp else np.frombuffer(px[:w * h], np.uint8).reshape(h, w)
                res["samples"][sid] = img
                q += 4 + n + (-n % 4)
        elif key == "patt":
            q = 0
            while q < len(body):
                n = struct.unpack(">I", body[q:q + 4])[0]
                e = body[q + 4:q + 4 + n]
                _v, mode, ph, pw, nl = struct.unpack(">IIhhI", e[:16])
                name = e[16:16 + nl * 2].decode("utf-16-be")
                r = 16 + nl * 2
                pid = e[r + 1:r + 1 + e[r]].decode()
                r += 1 + e[r] + 8 + 20  # vm version, length, rect, channel count
                _written, _cl = struct.unpack(">II", e[r:r + 8])
                _d, t, l, bt, rt, _d2, comp = struct.unpack(">I4iHB", e[r + 8:r + 31])
                px = e[r + 31:]
                img = _unrle(px, rt - l, bt - t)[0] if comp else np.frombuffer(px[:(rt - l) * (bt - t)], np.uint8).reshape(bt - t, rt - l)
                res["patterns"][pid] = (name, img)
                q += 4 + n + (-n % 4)
        elif key == "desc":
            res["desc"] = decode(body)
        p += 12 + ln + (-ln % 4)
    return res
