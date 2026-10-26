import os
from pathlib import Path

import numpy as np
import pytest

from omnibrush.abr import _packbits_row, _unrle, _rle, read_abr, write_abr
from omnibrush.model import Brush, Dynamics, Texture


def test_packbits_roundtrip():
    rng = np.random.default_rng(0)
    img = rng.integers(0, 4, (37, 301), dtype=np.uint8) * 60
    img[5:9] = 255
    data = _rle(img)
    out, end = _unrle(data, img.shape[1], img.shape[0])
    assert end == len(data)
    assert np.array_equal(out, img)
    assert _packbits_row(b"") == b""


def test_abr_roundtrip():
    tip = np.zeros((40, 50), np.uint8)
    tip[10:30, 5:45] = 200
    tex = (np.arange(64 * 32, dtype=np.uint32) % 256).astype(np.uint8).reshape(32, 64)
    b = Brush(
        name="Test Pencil",
        tips=[tip, tip[:, :20]],
        size=12,
        spacing=0.15,
        size_dynamics=Dynamics(pressure=True, minimum=0.4),
        flow_dynamics=Dynamics(pressure=True, minimum=0.2),
        texture=Texture(name="paper", image=tex, scale=0.5, depth=0.7),
    )
    r = read_abr(write_abr([b]))
    assert r["version"] == (6, 2)
    presets = r["desc"].get("Brsh")
    assert [p.get("Nm  ") for p in presets] == ["Test Pencil", "Test Pencil (tip 2)"]
    p = presets[0]
    tipd = p.get("Brsh")
    assert tipd.cls == "sampledBrush"
    assert np.array_equal(r["samples"][tipd.get("sampledData")], tip)
    assert tipd.get("Dmtr").value == 12
    assert abs(tipd.get("Spcn").value - 15) < 1e-9
    assert p.get("useTipDynamics") is True
    assert p.get("szVr").get("bVTy") == 2
    assert abs(p.get("minimumDiameter").value - 40) < 1e-9
    assert p.get("useTexture") is True
    name, img = r["patterns"][p.get("Txtr").get("Idnt")]
    assert name == "paper"
    assert np.array_equal(img, tex)


def test_round_brush_without_tip():
    r = read_abr(write_abr([Brush(name="Round", size=30)]))
    assert r["desc"].get("Brsh")[0].get("Brsh").cls == "computedBrush"
    assert r["samples"] == {}


SAMPLE = os.environ.get("OMNIBRUSH_SAMPLE_SUT")


@pytest.mark.skipif(not SAMPLE, reason="set OMNIBRUSH_SAMPLE_SUT to a .sut file to run")
def test_real_sut(tmp_path):
    from omnibrush.convert import convert
    rep = convert([Path(SAMPLE)], tmp_path)
    b = rep["brushes"][0]
    assert b["tips"], "tip image should be decoded"
    r = read_abr((tmp_path / rep["files"][0]).read_bytes())
    assert len(r["samples"]) == len(b["tips"])


def test_sample_layout_matches_photoshop():
    """Byte layout of a samp entry as written by Photoshop (checked against a real CS5+ file)."""
    import struct
    tip = np.full((10, 7), 255, np.uint8)
    data = write_abr([Brush(name="x", tips=[tip])])
    n = struct.unpack(">I", data[16:20])[0]
    assert n % 4 == 0
    e = data[20:20 + n]
    assert e[0] == 36 and b"$" not in e[1:37]  # bare 36-char UUID
    m = e[37:]
    one, zero, ver, vmlen = struct.unpack(">HHII", m[:12])
    assert (one, zero, ver) == (1, 0, 3)
    assert struct.unpack(">4iI", m[12:32]) == (0, 0, 10, 7, 56)
    assert m[32:252] == b"\x00" * 220
    written, slot_len, depth = struct.unpack(">III", m[252:264])
    assert (written, depth) == (1, 8)
    assert struct.unpack(">4iHB", m[264:283]) == (0, 0, 10, 7, 8, 1)
    assert m[264 - 4 + slot_len:264 - 4 + slot_len + 8] == b"\x00" * 8
    assert 12 + vmlen <= len(m)
