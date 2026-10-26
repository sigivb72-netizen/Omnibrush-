"""Reader for Celsys "C2F" containers (CSP material files such as material_0.layer).

A C2F file wraps an SQLite database. The first few pages are stored in an
obfuscated/compressed ``dATA`` chunk we cannot decode, but the remaining pages
are stored verbatim, and those hold the layer's pixel blocks. We therefore read
the raw pages directly, walk every B-tree leaf cell we can find and decode its
record, following overflow chains for large BLOBs.
"""
from __future__ import annotations

import re
import struct
import zlib
from dataclasses import dataclass

MAGIC = b"\x89C2F\r\n\x1a\n"


class C2FError(ValueError):
    pass


@dataclass
class Chunk:
    flag: int  # 0 = stored verbatim, 1 = encoded (not supported)
    data: bytes


def read_chunks(blob: bytes) -> tuple[int, list[Chunk]]:
    """Split a C2F file into its dATA chunks. Returns (declared size of first chunk, chunks)."""
    if not blob.startswith(MAGIC):
        raise C2FError("not a C2F container")
    pos = 12
    if blob[pos:pos + 4] != b"HEAD":
        raise C2FError("missing HEAD chunk")
    (first_len,) = struct.unpack("<I", blob[pos + 8:pos + 12])
    pos += 12
    starts = [m.start() for m in re.finditer(b"dATA", blob)]
    tail = blob.rfind(b"TAIL")
    chunks: list[Chunk] = []
    # The first chunk has an explicit length; any later chunk runs to the next marker/TAIL.
    if not starts or starts[0] != pos:
        raise C2FError("missing dATA chunk")
    cur = pos
    while cur != -1 and cur < tail:
        flag = blob[cur + 4]
        body_start = cur + 6
        if flag == 1 and not chunks:
            end = body_start + first_len
        else:
            end = tail
        chunks.append(Chunk(flag, blob[body_start:end]))
        # skip trailer bytes between chunks (checksum etc.)
        nxt = blob.find(b"dATA", end)
        cur = nxt if 0 <= nxt < tail else -1
    return first_len, chunks


def _varint(buf: bytes, pos: int) -> tuple[int, int]:
    v = 0
    for i in range(9):
        b = buf[pos + i]
        if i == 8:
            return (v << 8) | b, pos + 9
        v = (v << 7) | (b & 0x7F)
        if b < 0x80:
            return v, pos + i + 1
    raise C2FError("bad varint")


class PartialSQLite:
    """Best-effort reader for an SQLite database with some pages missing."""

    def __init__(self, pages: dict[int, bytes], page_size: int):
        self.pages = pages
        self.page_size = page_size

    @classmethod
    def from_c2f(cls, blob: bytes, page_size: int = 1024) -> "PartialSQLite":
        _, chunks = read_chunks(blob)
        if len(chunks) < 2 or chunks[1].flag != 0:
            raise C2FError("no verbatim page chunk in C2F container")
        raw = chunks[1].data
        # The encoded first chunk holds whole pages; deduce how many from overflow
        # pointers in the raw region (sequential chains point to page n+1).
        first_page = cls._guess_first_page(raw, page_size)
        pages = {}
        for i in range(len(raw) // page_size):
            pages[first_page + i] = raw[i * page_size:(i + 1) * page_size]
        return cls(pages, page_size)

    @staticmethod
    def _guess_first_page(raw: bytes, page_size: int) -> int:
        votes: dict[int, int] = {}
        n = len(raw) // page_size
        for i in range(n - 1):
            ptr = struct.unpack(">I", raw[i * page_size:i * page_size + 4])[0]
            # an overflow page whose successor is the next physical page
            if 0 < ptr < 1 << 20 and raw[i * page_size] not in (2, 5, 10, 13):
                first = ptr - (i + 1)
                if first >= 1:
                    votes[first] = votes.get(first, 0) + 1
        if not votes:
            raise C2FError("cannot locate page numbering")
        return max(votes, key=votes.get)

    def _payload(self, page: bytes, pos: int) -> bytes | None:
        total, pos = _varint(page, pos)
        _rowid, pos = _varint(page, pos)
        u = self.page_size
        x = u - 35
        if total <= x:
            return page[pos:pos + total]
        m = ((u - 12) * 32 // 255) - 23
        k = m + (total - m) % (u - 4)
        local = k if k <= x else m
        out = bytearray(page[pos:pos + local])
        nxt = struct.unpack(">I", page[pos + local:pos + local + 4])[0]
        seen = set()
        while len(out) < total and nxt:
            if nxt in seen or nxt not in self.pages:
                return None
            seen.add(nxt)
            ov = self.pages[nxt]
            nxt = struct.unpack(">I", ov[:4])[0]
            out += ov[4:4 + min(u - 4, total - len(out))]
        return bytes(out) if len(out) == total else None

    @staticmethod
    def _decode_record(payload: bytes) -> list:
        hlen, p = _varint(payload, 0)
        types = []
        while p < hlen:
            t, p = _varint(payload, p)
            types.append(t)
        vals = []
        d = hlen
        for t in types:
            if t == 0:
                vals.append(None)
            elif 1 <= t <= 6:
                n = [0, 1, 2, 3, 4, 6, 8][t]
                vals.append(int.from_bytes(payload[d:d + n], "big", signed=True))
                d += n
            elif t == 7:
                vals.append(struct.unpack(">d", payload[d:d + 8])[0])
                d += 8
            elif t in (8, 9):
                vals.append(t - 8)
            elif t >= 12:
                n = (t - 12) // 2
                v = payload[d:d + n]
                vals.append(v if t % 2 == 0 else v.decode("utf-8", "replace"))
                d += n
            else:
                vals.append(None)
        return vals

    def records(self):
        """Yield decoded rows from every table-leaf page we hold."""
        for pno in sorted(self.pages):
            page = self.pages[pno]
            if page[0] != 0x0D:
                continue
            ncells = struct.unpack(">H", page[3:5])[0]
            for c in range(ncells):
                off = struct.unpack(">H", page[8 + 2 * c:10 + 2 * c])[0]
                if not 8 <= off < self.page_size:
                    continue
                try:
                    payload = self._payload(page, off)
                    if payload is not None:
                        yield self._decode_record(payload)
                except (IndexError, struct.error, C2FError):
                    continue


# ---------------------------------------------------------------- offscreen

def _utf16(s: str) -> bytes:
    return s.encode("utf-16-be")


@dataclass
class Offscreen:
    width: int
    height: int
    blocks_x: int
    blocks_y: int
    channel_code: int
    block_data: bytes


def parse_attribute(attr: bytes) -> tuple[int, int, int, int, int] | None:
    i = attr.find(_utf16("Parameter"))
    if i < 0:
        return None
    p = i + len(_utf16("Parameter"))
    w, h, bx, by, code = struct.unpack(">5I", attr[p:p + 20])
    return w, h, bx, by, code


def iter_blocks(block_data: bytes):
    """Yield (index, raw decompressed bytes or None) for each BlockDataBeginChunk."""
    name = _utf16("BlockDataBeginChunk")
    pos = 0
    while True:
        i = block_data.find(name, pos)
        if i < 0:
            return
        p = i + len(name)
        idx, _unc, _w, _h, not_empty = struct.unpack(">5I", block_data[p:p + 20])
        p += 20
        data = None
        if not_empty:
            (ln,) = struct.unpack("<I", block_data[p + 4:p + 8])
            data = zlib.decompress(block_data[p + 8:p + 8 + ln])
            p += 8 + ln
        yield idx, data
        pos = p


def find_offscreens(db: PartialSQLite) -> list[Offscreen]:
    out = []
    for row in db.records():
        attr = next((v for v in row if isinstance(v, bytes) and _utf16("Parameter") in v), None)
        data = next((v for v in row if isinstance(v, bytes) and _utf16("BlockDataBeginChunk") in v), None)
        if attr is None or data is None:
            continue
        p = parse_attribute(attr)
        if p:
            out.append(Offscreen(*p, block_data=data))
    return out
