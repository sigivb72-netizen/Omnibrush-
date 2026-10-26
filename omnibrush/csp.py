"""Reader for Clip Studio Paint sub tool files (.sut)."""
from __future__ import annotations

import io
import re
import sqlite3
import struct
import tarfile
import tempfile
from pathlib import Path

import numpy as np

from .c2f import PartialSQLite, find_offscreens, iter_blocks
from .model import Brush, Dynamics, Texture

# Effector input bits (CSP "effect source" checkboxes).
EFF_PRESSURE = 0x10
EFF_TILT = 0x20
EFF_VELOCITY = 0x40
EFF_RANDOM = 0x80

# CSP texture composite modes -> neutral names (best effort; order of CSP's dropdown).
TEXTURE_BLEND = {
    0: "multiply", 1: "multiply", 2: "darken", 3: "subtract", 4: "subtract",
    5: "overlay", 6: "color_burn", 7: "linear_burn", 8: "hard_mix", 9: "height",
}


def parse_effector(blob: bytes | None) -> Dynamics:
    if not blob or len(blob) < 48:
        return Dynamics()
    w = struct.unpack(">12i", blob[:48])
    flags = w[2]
    d = Dynamics(
        pressure=bool(flags & EFF_PRESSURE),
        tilt=bool(flags & EFF_TILT),
        velocity=bool(flags & EFF_VELOCITY),
        random=bool(flags & EFF_RANDOM),
        minimum=max(0, min(100, w[8])) / 100,
        jitter=(max(0, min(100, w[4])) / 100) if flags & EFF_RANDOM else 0.0,
    )
    if len(blob) > 60:
        _hdr, count, _psize = struct.unpack(">3I", blob[48:60])
        pts = []
        for i in range(count):
            off = 60 + i * 16
            if off + 16 > len(blob):
                break
            pts.append(struct.unpack(">2d", blob[off:off + 16]))
        d.curve = pts or None
    return d


def _material_paths(blob: bytes | None) -> list[str]:
    """Extract the UTF-16LE material paths (e.g. '.:22:32:<uuid>:data:material_0.layer')."""
    if not blob:
        return []
    text = blob.decode("utf-16-le", "replace")
    return re.findall(r"\.:[0-9]+:[0-9]+:[0-9a-f-]+:data:[^\x00]+?\.layer", text)


def _material_layer_bytes(file_data: bytes) -> bytes | None:
    with tarfile.open(fileobj=io.BytesIO(file_data)) as tf:
        for m in tf.getmembers():
            if m.name.endswith(".layer"):
                return tf.extractfile(m).read()
    return None


def _thumbnail_alpha(file_data: bytes) -> np.ndarray | None:
    from PIL import Image
    with tarfile.open(fileobj=io.BytesIO(file_data)) as tf:
        for m in tf.getmembers():
            if m.name.endswith("thumbnail.png"):
                im = Image.open(io.BytesIO(tf.extractfile(m).read())).convert("LA")
                a = np.asarray(im, dtype=np.float32)
                return (a[..., 1] * (255 - a[..., 0]) / 255).astype(np.uint8)
    return None


def decode_layer(layer: bytes) -> np.ndarray:
    """Decode a material .layer into a uint8 density mask (255 = full paint)."""
    db = PartialSQLite.from_c2f(layer)
    offs = [o for o in find_offscreens(db) if any(d for _, d in iter_blocks(o.block_data))]
    if not offs:
        raise ValueError("no pixel data in material")
    o = max(offs, key=lambda o: len(o.block_data))
    h, w = o.blocks_y * 256, o.blocks_x * 256
    density = np.zeros((h, w), np.uint8)
    for idx, data in iter_blocks(o.block_data):
        if data is None:
            continue
        by, bx = divmod(idx, o.blocks_x)
        n = 256 * 256
        if len(data) == n // 4:  # 1-bit alpha plane + 1-bit color plane
            bits = np.unpackbits(np.frombuffer(data, np.uint8))
            alpha = bits[:n].astype(np.float32) * 255
            dark = 1.0 - bits[n:2 * n].astype(np.float32)  # color bit 1 = white
            tile = alpha * dark
        elif len(data) == n * 2:  # 8-bit alpha + 8-bit gray
            alpha = np.frombuffer(data[:n], np.uint8).astype(np.float32)
            gray = np.frombuffer(data[n:], np.uint8).astype(np.float32)
            tile = alpha * (255 - gray) / 255
        elif len(data) == n * 5:  # 8-bit alpha + BGRA
            alpha = np.frombuffer(data[:n], np.uint8).astype(np.float32)
            bgra = np.frombuffer(data[n:], np.uint8).reshape(n, 4).astype(np.float32)
            lum = 0.114 * bgra[:, 0] + 0.587 * bgra[:, 1] + 0.299 * bgra[:, 2]
            tile = alpha * (255 - lum) / 255
        else:
            raise ValueError(f"unknown block size {len(data)}")
        density[by * 256:(by + 1) * 256, bx * 256:(bx + 1) * 256] = tile.reshape(256, 256).clip(0, 255)
    return density[:o.height, :o.width]


def crop_to_content(img: np.ndarray, pad: int = 1) -> np.ndarray:
    ys, xs = np.nonzero(img)
    if len(ys) == 0:
        return img
    y0, y1 = max(ys.min() - pad, 0), min(ys.max() + 1 + pad, img.shape[0])
    x0, x1 = max(xs.min() - pad, 0), min(xs.max() + 1 + pad, img.shape[1])
    return img[y0:y1, x0:x1]


class SutFile:
    def __init__(self, path: str | Path | bytes):
        if isinstance(path, (bytes, bytearray)):
            self._tmp = tempfile.NamedTemporaryFile(suffix=".sut")
            self._tmp.write(path)
            self._tmp.flush()
            path = self._tmp.name
        self.path = Path(path)
        self.db = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)
        self.db.row_factory = sqlite3.Row
        self.materials = {}
        for row in self.db.execute("SELECT OriginalPath, FileData FROM MaterialFile"):
            self.materials[row["OriginalPath"]] = row["FileData"]

    def _material_image(self, ref: str, notes: list[str]) -> np.ndarray | None:
        data = self.materials.get(ref)
        if data is None:
            notes.append(f"material {ref} not embedded in file")
            return None
        layer = _material_layer_bytes(data)
        try:
            if layer is None:
                raise ValueError("no layer")
            return decode_layer(layer)
        except Exception as e:  # fall back to the material's preview image
            img = _thumbnail_alpha(data)
            if img is None:
                raise
            notes.append(f"used low-res thumbnail for {ref} ({e})")
            return img

    def brushes(self) -> list[Brush]:
        out = []
        variants = {r["VariantID"]: r for r in self.db.execute("SELECT * FROM Variant")}
        for node in self.db.execute("SELECT * FROM Node"):
            v = variants.get(node["NodeVariantID"]) or variants.get(node["NodeInitVariantID"])
            if v is None:
                continue
            out.append(self._brush(node["NodeName"] or self.path.stem, v))
        return out

    def _brush(self, name: str, v: sqlite3.Row) -> Brush:
        keys = v.keys()
        g = lambda k, d=None: v[k] if k in keys and v[k] is not None else d  # noqa: E731
        b = Brush(name=name)
        b.source = {k: v[k] for k in keys if not isinstance(v[k], bytes)}
        b.size = float(g("BrushSize", 20))
        b.spacing = max(1.0, float(g("BrushInterval", 10))) / 100
        b.hardness = g("BrushHardness", 100) / 100
        b.roundness = max(1, min(100, g("BrushThickness", 100))) / 100
        b.opacity = g("Opacity", 100) / 100
        b.flow = g("BrushFlow", 100) / 100
        b.size_dynamics = parse_effector(g("BrushSizeEffector"))
        b.opacity_dynamics = parse_effector(g("BrushOpacityEffector"))
        b.flow_dynamics = parse_effector(g("BrushFlowEffector"))
        b.stabilization = int(g("Stickness", 0))

        rot = float(g("BrushRotation", 0))
        rot_src = g("BrushRotationEffector", 0)
        b.angle = rot % 360
        # BrushRotationEffector: 3 = random, 4 = follow stroke direction (observed values).
        if rot_src == 3:
            b.angle_dynamics = Dynamics(random=True, jitter=g("BrushRotationRandomScale", 100) / 100 * min(rot, 360) / 360)
            b.angle = 0.0
        elif rot_src in (4, 5):
            b.angle_follows_stroke = True

        if g("BrushUseSpray", 0):
            b.scatter = float(g("BrushSpraySize", 0))
            b.scatter_count = max(1, min(16, int(g("BrushSprayDensity", 1))))
        if g("BrushUseIn", 0):
            b.taper_in = float(g("BrushInLength", 0))
        if g("BrushUseOut", 0):
            b.taper_out = float(g("BrushOutLength", 0))

        if g("BrushUsePatternImage", 0):
            for ref in _material_paths(g("BrushPatternImageArray")):
                img = self._material_image(ref, b.notes)
                if img is not None:
                    b.tips.append(crop_to_content(img))
        if not b.tips:
            b.notes.append("round tip (no tip image)")

        tex_refs = _material_paths(g("TextureImage"))
        if tex_refs:
            img = self._material_image(tex_refs[0], b.notes)
            if img is not None:
                b.texture = Texture(
                    name=f"{name} texture",
                    image=img,
                    scale=float(g("TextureScale2", 100)) / 100,
                    depth=g("TextureDensity", 100) / 100,
                    invert=bool(g("TextureReverseDensity", 0)),
                    blend=TEXTURE_BLEND.get(g("TextureCompositeMode", 0), "multiply"),
                    brightness=int(g("TextureBrightness", 0)),
                    contrast=int(g("TextureContrast", 0)),
                    each_tip=bool(g("TextureForPlot", 0)),
                    rotation=float(g("TextureRotate", 0)),
                )

        if g("UseDualBrush", 0):
            b.notes.append("dual brush not converted")
        if g("BrushUseWaterColor", 0) or g("BrushUseWaterColor2", 0):
            b.notes.append("color mixing / watercolor not converted")
        if g("BrushUseWaterEdge", 0):
            b.notes.append("watercolor edge not converted")
        if b.taper_in or b.taper_out:
            b.notes.append("start/end taper has no ABR equivalent (most apps have their own taper setting)")
        if b.size_dynamics.velocity or b.flow_dynamics.velocity:
            b.notes.append("velocity dynamics have no ABR equivalent")
        return b


def read_sut(path) -> list[Brush]:
    return SutFile(path).brushes()
