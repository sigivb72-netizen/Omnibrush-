"""omnibrush command line."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__
from .abr import read_abr
from .convert import convert


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="omnibrush", description="Convert Clip Studio Paint brushes to portable formats.")
    ap.add_argument("--version", action="version", version=__version__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("convert", help="convert .sut files / packs to .abr, .gbr and PNG")
    c.add_argument("inputs", nargs="+", type=Path)
    c.add_argument("-o", "--out", type=Path, default=Path("converted"))
    c.add_argument("-n", "--name", help="name of the output .abr pack")

    i = sub.add_parser("inspect", help="list the presets inside an .abr")
    i.add_argument("abr", type=Path)

    a = ap.parse_args(argv)
    if a.cmd == "convert":
        try:
            rep = convert(a.inputs, a.out, a.name)
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
        print(f"wrote {a.out}/")
        for f in rep["files"]:
            print(f"  {f}")
        for b in rep["brushes"]:
            print(f"  {b['name']}: tips {b['tips']}, texture {b['texture']}")
            for n in b["not_converted"]:
                print(f"    - {n}")
        return 0
    if a.cmd == "inspect":
        r = read_abr(a.abr.read_bytes())
        print(f"ABR v{r['version'][0]}.{r['version'][1]}: {len(r['samples'])} tips, {len(r['patterns'])} patterns")
        for sid, img in r["samples"].items():
            print(f"  tip {sid} {img.shape[1]}x{img.shape[0]}")
        for pid, (name, img) in r["patterns"].items():
            print(f"  pattern {name!r} {img.shape[1]}x{img.shape[0]}")
        if r["desc"]:
            for p in r["desc"].get("Brsh", []):
                tip = p.get("Brsh")
                print(f"  preset {p.get('Nm  ')!r}: {tip.cls} {tip.get('Dmtr').value:g}px spacing {tip.get('Spcn').value:g}%"
                      f" texture={p.get('useTexture')} tipDynamics={p.get('useTipDynamics')} transfer={p.get('usePaintDynamics')}")
        return 0
    return 2
