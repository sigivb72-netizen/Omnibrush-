"""Minimal Photoshop Action Descriptor serializer (and parser, for verification)."""
from __future__ import annotations

import struct
from dataclasses import dataclass


@dataclass
class UnitFloat:
    unit: str  # '#Prc', '#Pxl', '#Ang'
    value: float


@dataclass
class Enum:
    type: str
    value: str


@dataclass
class Obj:
    cls: str
    items: list  # list of (key, value)
    name: str = ""

    def get(self, key, default=None):
        for k, v in self.items:
            if k == key:
                return v
        return default


def _id(key: str) -> bytes:
    b = key.encode("latin-1")
    return struct.pack(">I", 0) + b if len(b) == 4 else struct.pack(">I", len(b)) + b


def _ustr(s: str) -> bytes:
    u = s.encode("utf-16-be")
    return struct.pack(">I", len(u) // 2) + u


def _value(v) -> bytes:
    if isinstance(v, bool):
        return b"bool" + bytes([v])
    if isinstance(v, int):
        return b"long" + struct.pack(">i", v)
    if isinstance(v, float):
        return b"doub" + struct.pack(">d", v)
    if isinstance(v, str):
        return b"TEXT" + _ustr(v + "\x00")
    if isinstance(v, UnitFloat):
        return b"UntF" + v.unit.encode() + struct.pack(">d", v.value)
    if isinstance(v, Enum):
        return b"enum" + _id(v.type) + _id(v.value)
    if isinstance(v, Obj):
        return b"Objc" + _obj(v)
    if isinstance(v, list):
        return b"VlLs" + struct.pack(">I", len(v)) + b"".join(_value(x) for x in v)
    raise TypeError(type(v))


def _obj(o: Obj) -> bytes:
    out = _ustr(o.name) + _id(o.cls) + struct.pack(">I", len(o.items))
    for k, v in o.items:
        out += _id(k) + _value(v)
    return out


def encode(o: Obj) -> bytes:
    """Versioned descriptor (version 16)."""
    return struct.pack(">I", 16) + _obj(o)


# ------------------------------------------------------------------ parser
class _R:
    def __init__(self, b: bytes, p: int = 0):
        self.b, self.p = b, p

    def take(self, n):
        v = self.b[self.p:self.p + n]
        if len(v) != n:
            raise ValueError("truncated descriptor")
        self.p += n
        return v

    def u32(self):
        return struct.unpack(">I", self.take(4))[0]

    def id(self):
        n = self.u32()
        return self.take(n or 4).decode("latin-1")

    def ustr(self):
        n = self.u32()
        return self.take(n * 2).decode("utf-16-be").rstrip("\x00")


def _read_value(r: _R):
    t = r.take(4)
    if t == b"bool":
        return bool(r.take(1)[0])
    if t == b"long":
        return struct.unpack(">i", r.take(4))[0]
    if t == b"doub":
        return struct.unpack(">d", r.take(8))[0]
    if t == b"TEXT":
        return r.ustr()
    if t == b"UntF":
        u = r.take(4).decode()
        return UnitFloat(u, struct.unpack(">d", r.take(8))[0])
    if t == b"enum":
        return Enum(r.id(), r.id())
    if t in (b"Objc", b"GlbO"):
        return _read_obj(r)
    if t == b"VlLs":
        return [_read_value(r) for _ in range(r.u32())]
    if t == b"tdta":
        return r.take(r.u32())
    raise ValueError(f"unsupported descriptor type {t!r}")


def _read_obj(r: _R) -> Obj:
    name = r.ustr()
    cls = r.id()
    items = [(r.id(), _read_value(r)) for _ in range(r.u32())]
    return Obj(cls, items, name)


def decode(b: bytes) -> Obj:
    r = _R(b)
    if r.u32() != 16:
        raise ValueError("unsupported descriptor version")
    return _read_obj(r)
