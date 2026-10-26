"""GIMP .gbr brush writer (also loaded by Krita, MyPaint-adjacent tools and Inkscape)."""
from __future__ import annotations

import struct

import numpy as np

from .model import Brush


def write_gbr(brush: Brush, tip: np.ndarray) -> bytes:
    h, w = tip.shape
    name = brush.name.encode("utf-8") + b"\x00"
    spacing = max(1, min(1000, round(brush.spacing * 100)))
    header = struct.pack(">7I", 28 + len(name), 2, w, h, 1, 0x47494D50, spacing)
    # GIMP grayscale brushes: 255 = paint
    return header + name + np.ascontiguousarray(tip, dtype=np.uint8).tobytes()
