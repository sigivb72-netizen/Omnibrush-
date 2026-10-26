"""High level conversion: any supported input -> a folder of portable brush files."""
from __future__ import annotations

import json
import re
import zipfile
from dataclasses import asdict
from pathlib import Path

import numpy as np
from PIL import Image

from .abr import write_abr
from .csp import read_sut
from .gbr import write_gbr
from .model import Brush

INPUT_EXTS = {".sut"}


def _safe(name: str) -> str:
    return re.sub(r'[\\/:*?"<>|]+', "_", name).strip() or "brush"


def load(path: Path) -> list[Brush]:
    """Read one file or a pack (folder / .zip of .sut files)."""
    path = Path(path)
    if path.is_dir():
        out = []
        for f in sorted(path.rglob("*")):
            if f.suffix.lower() in INPUT_EXTS or f.suffix.lower() == ".zip":
                out += load(f)
        return out
    ext = path.suffix.lower()
    if ext == ".sut":
        return read_sut(path)
    if ext == ".zip":
        out = []
        with zipfile.ZipFile(path) as z:
            for n in sorted(z.namelist()):
                if Path(n).suffix.lower() == ".sut":
                    out += read_sut(z.read(n))
        return out
    raise ValueError(f"unsupported input: {path.name} (supported: .sut, folders or .zip packs of them)")


def _png(img: np.ndarray, path: Path):
    """Save a density mask as black-on-transparent PNG (works as a stamp in any app)."""
    h, w = img.shape
    rgba = np.zeros((h, w, 4), np.uint8)
    rgba[..., 3] = img
    Image.fromarray(rgba, "RGBA").save(path)


def _grain_png(img: np.ndarray, path: Path):
    """Grain/texture as an opaque grayscale image (Procreate grain, Krita/PS pattern)."""
    Image.fromarray(np.ascontiguousarray(img), "L").save(path)


def _settings(b: Brush) -> dict:
    d = asdict(b)
    d.pop("tips")
    d.pop("source")
    if b.texture is not None:
        d["texture"].pop("image")
    d["source_parameters"] = b.source
    return d


def convert(inputs: list[Path], out_dir: Path, pack_name: str | None = None) -> dict:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    brushes: list[Brush] = []
    for p in inputs:
        brushes += load(Path(p))
    if not brushes:
        raise ValueError("no brushes found")
    pack = _safe(pack_name or (brushes[0].name if len(brushes) == 1 else Path(inputs[0]).stem))
    report = {"pack": pack, "files": [], "brushes": []}

    abr_path = out_dir / f"{pack}.abr"
    abr_path.write_bytes(write_abr(brushes))
    report["files"].append(abr_path.name)

    for b in brushes:
        bdir = out_dir / _safe(b.name)
        bdir.mkdir(exist_ok=True)
        for i, tip in enumerate(b.tips):
            suffix = "" if i == 0 else f"_{i + 1}"
            _png(tip, bdir / f"tip{suffix}.png")
            (bdir / f"{_safe(b.name)}{suffix}.gbr").write_bytes(write_gbr(b, tip))
        if b.texture is not None:
            _grain_png(b.texture.image, bdir / "texture.png")
        (bdir / "settings.json").write_text(json.dumps(_settings(b), indent=2, default=str))
        report["brushes"].append({
            "name": b.name,
            "tips": [list(t.shape[::-1]) for t in b.tips],
            "texture": list(b.texture.image.shape[::-1]) if b.texture is not None else None,
            "not_converted": b.notes,
        })
    return report
