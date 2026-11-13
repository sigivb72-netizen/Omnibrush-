#!/usr/bin/env python3
"""Checks the hand-edited data files and explains mistakes in plain words.

Run automatically by the build workflow, so a typo made in the GitHub mobile app shows up
as a red X with a readable message instead of silently breaking the app's data.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from merge import PRECISIONS, REGIONS, STATUSES, precision_of  # noqa: E402

ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
ORDER = {"exact": 0, "month": 1, "quarter": 2, "year": 3, "TBA": 4}


def check_game(g, where: str) -> list[str]:
    errs: list[str] = []
    if not isinstance(g, dict):
        return [f"{where}: must be an object in curly braces"]
    gid = g.get("id")
    name = f"game '{gid}'" if gid else where
    if not isinstance(gid, str) or not ID_RE.match(gid):
        errs.append(f"{name}: 'id' must be lowercase letters, digits and dashes (like \"my-game-2\")")
    if not isinstance(g.get("title"), str) or not g.get("title", "").strip():
        errs.append(f"{name}: 'title' is missing")
    for key in ("genres", "tags", "platforms"):
        v = g.get(key, [])
        if not isinstance(v, list) or any(not isinstance(x, str) for x in v):
            errs.append(f"{name}: '{key}' must be a list of texts, e.g. [\"RPG\", \"Action\"]")
    if g.get("status", "announced") not in STATUSES:
        errs.append(f"{name}: 'status' is \"{g.get('status')}\" but must be one of: {', '.join(STATUSES)}")
    if not isinstance(g.get("adult", False), bool):
        errs.append(f"{name}: 'adult' must be true or false (no quotes)")
    if not isinstance(g.get("popularity", 0), int):
        errs.append(f"{name}: 'popularity' must be a whole number")
    shots = g.get("screenshots", [])
    if not isinstance(shots, list) or any(not isinstance(x, str) or not x.startswith("http") for x in shots):
        errs.append(f"{name}: 'screenshots' must be a list of image links, e.g. [\"https://...\"]")
    for key in ("cover", "officialSite", "sourceUrl", "trailer"):
        v = g.get(key, "")
        if not isinstance(v, str) or (v and not v.startswith(("http://", "https://"))):
            errs.append(f"{name}: '{key}' must be empty or a link starting with https://")
    links = g.get("storeLinks", {})
    if not isinstance(links, dict) or any(not isinstance(v, str) or not v.startswith("http") for v in links.values()):
        errs.append(f"{name}: 'storeLinks' must look like {{\"googlePlay\": \"https://...\"}}")
    alt = g.get("altTitles", {})
    if not isinstance(alt, dict) or any(not isinstance(v, str) for v in alt.values()):
        errs.append(f"{name}: 'altTitles' must look like {{\"jp\": \"...\", \"cn\": \"...\", \"kr\": \"...\"}}")
    rels = g.get("releases", {})
    if not isinstance(rels, dict):
        errs.append(f"{name}: 'releases' must be an object with regions: {', '.join(REGIONS)}")
        rels = {}
    for region, rel in rels.items():
        if region not in REGIONS:
            errs.append(f"{name}: unknown region '{region}' (use {', '.join(REGIONS)})")
            continue
        if not isinstance(rel, dict):
            errs.append(f"{name}: release for '{region}' must be an object")
            continue
        date = rel.get("date", "TBA")
        shape = precision_of(date) if isinstance(date, str) else "TBA"
        if date != "TBA" and shape == "TBA":
            errs.append(f"{name}: {region} date \"{date}\" is not understood. Use 2026-03-12, 2026-03, 2026-Q2, 2026 or TBA")
        prec = rel.get("precision", shape)
        if prec not in PRECISIONS:
            errs.append(f"{name}: {region} precision \"{prec}\" must be one of: {', '.join(PRECISIONS)}")
        elif ORDER[prec] < ORDER[shape]:
            errs.append(f"{name}: {region} precision is \"{prec}\" but the date \"{date}\" is only {shape}-precise")
        if not isinstance(rel.get("confirmed", False), bool):
            errs.append(f"{name}: {region} 'confirmed' must be true or false (no quotes)")
    eos = g.get("endOfService", "")
    if not isinstance(eos, str) or (eos and precision_of(eos) == "TBA"):
        errs.append(f"{name}: 'endOfService' must be empty or a date like 2026-10-06")
    return errs


def validate(data_dir: Path) -> list[str]:
    errs: list[str] = []

    def load(name):
        try:
            return json.loads((data_dir / name).read_text(encoding="utf-8"))
        except FileNotFoundError:
            return None
        except json.JSONDecodeError as e:
            errs.append(f"{name}: not valid JSON near line {e.lineno}, column {e.colno}: {e.msg}. "
                        f"Usual causes: a missing or extra comma, or a missing quote.")
            return None

    db = load("games.json")
    if isinstance(db, dict) and isinstance(db.get("games"), list):
        seen = set()
        for i, g in enumerate(db["games"]):
            errs += check_game(g, f"games.json entry #{i + 1}")
            gid = g.get("id") if isinstance(g, dict) else None
            if gid in seen:
                errs.append(f"game '{gid}': this id appears twice")
            seen.add(gid)
    elif db is not None:
        errs.append("games.json: must be an object with a \"games\" list")

    ov = load("overrides.json")
    if ov is not None and not isinstance(ov, dict):
        errs.append("overrides.json: must be an object keyed by game id")
    pend = load("pending.json")
    if pend is not None and not (isinstance(pend, dict) and isinstance(pend.get("items", []), list)):
        errs.append("pending.json: must be an object with an \"items\" list")
    news = load("news.json")
    if news is not None and not (isinstance(news, dict) and isinstance(news.get("items", []), list)):
        errs.append("news.json: must be an object with an \"items\" list")
    return errs


def main() -> int:
    data_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "data"
    errs = validate(data_dir)
    if errs:
        print(f"Found {len(errs)} problem(s) in the data files:\n")
        for e in errs:
            print(" - " + e)
        return 1
    print("Data files look good.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
