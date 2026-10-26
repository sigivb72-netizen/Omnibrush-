"""App-neutral brush description that every reader produces and every writer consumes."""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


@dataclass
class Dynamics:
    """How an input modulates a parameter. ``minimum`` and ``jitter`` are 0..1."""
    pressure: bool = False
    tilt: bool = False
    velocity: bool = False
    random: bool = False
    minimum: float = 0.0
    jitter: float = 0.0
    curve: list[tuple[float, float]] | None = None  # pressure curve, (x, y) in 0..1

    @property
    def active(self) -> bool:
        return self.pressure or self.tilt or self.velocity or self.random or self.jitter > 0


@dataclass
class Texture:
    name: str
    image: np.ndarray  # uint8 H x W, 255 = full paint density
    scale: float = 1.0  # 1.0 = 100 %
    depth: float = 1.0  # 0..1 strength
    invert: bool = False
    blend: str = "multiply"  # multiply | subtract | darken | overlay | color_burn | linear_burn | hard_mix | height
    brightness: int = 0
    contrast: int = 0
    each_tip: bool = False
    rotation: float = 0.0


@dataclass
class Brush:
    name: str
    tips: list[np.ndarray] = field(default_factory=list)  # uint8 H x W masks, 255 = opaque
    size: float = 20.0  # diameter in px
    spacing: float = 0.1  # fraction of diameter
    hardness: float = 1.0
    roundness: float = 1.0
    angle: float = 0.0
    opacity: float = 1.0
    flow: float = 1.0
    size_dynamics: Dynamics = field(default_factory=Dynamics)
    opacity_dynamics: Dynamics = field(default_factory=Dynamics)
    flow_dynamics: Dynamics = field(default_factory=Dynamics)
    angle_dynamics: Dynamics = field(default_factory=Dynamics)
    angle_follows_stroke: bool = False
    scatter: float = 0.0  # fraction of diameter
    scatter_count: int = 1
    texture: Texture | None = None
    taper_in: float = 0.0  # length in px, informational
    taper_out: float = 0.0
    stabilization: int = 0  # 0..100
    blend_mode: str = "normal"
    notes: list[str] = field(default_factory=list)  # things that could not be carried over
    source: dict = field(default_factory=dict)  # raw source parameters for reference
