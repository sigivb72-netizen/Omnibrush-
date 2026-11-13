"""Pure merge logic for games.json. No network, no file access: easy to unit-test.

Rules
-----
1. games.json is the hand-edited database. The scraper never changes a value a human put there.
2. Approved items from pending.json may only *fill gaps*: add a brand-new game, or fill a field
   that is empty / "TBA" / missing in games.json.
3. overrides.json always wins. It is applied last and replaces whatever is there.
   {"_delete": true} in an override removes the game.
"""
from __future__ import annotations

import copy
import re
from typing import Any

REGIONS = ["global", "jp", "kr", "cn", "tw_sea"]
STATUSES = ["announced", "pre-registration", "beta", "released", "end-of-service"]
PRECISIONS = ["exact", "month", "quarter", "year", "TBA"]

_DATE_SHAPES = [
    (re.compile(r"^\d{4}-\d{2}-\d{2}$"), "exact"),
    (re.compile(r"^\d{4}-\d{2}$"), "month"),
    (re.compile(r"^\d{4}-Q[1-4]$"), "quarter"),
    (re.compile(r"^\d{4}$"), "year"),
]


def precision_of(date: str) -> str:
    """Precision implied by the shape of a date string; anything unknown is TBA."""
    for rx, name in _DATE_SHAPES:
        if rx.match(date or ""):
            return name
    return "TBA"


def is_empty(value: Any) -> bool:
    """True for values that count as 'not filled in yet'."""
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == "" or value.strip().upper() in ("TBA", "TBD")
    if isinstance(value, (list, dict)):
        return len(value) == 0
    return False


def _release_is_empty(rel: Any) -> bool:
    return not isinstance(rel, dict) or is_empty(rel.get("date"))


def fill_gaps(base: dict, patch: dict) -> tuple[dict, list[str]]:
    """Copy values from patch into base only where base has nothing. Returns (new, changed keys)."""
    out = copy.deepcopy(base)
    changed: list[str] = []
    for key, value in patch.items():
        if key in ("id", "_delete") or is_empty(value):
            continue
        if key == "releases" and isinstance(value, dict):
            cur = out.setdefault("releases", {})
            for region, rel in value.items():
                if region in REGIONS and not _release_is_empty(rel) and _release_is_empty(cur.get(region)):
                    cur[region] = normalize_release(rel)
                    changed.append(f"releases.{region}")
        elif key in ("storeLinks", "altTitles") and isinstance(value, dict):
            cur = out.setdefault(key, {})
            for k, v in value.items():
                if not is_empty(v) and is_empty(cur.get(k)):
                    cur[k] = v
                    changed.append(f"{key}.{k}")
        elif is_empty(out.get(key)):
            out[key] = copy.deepcopy(value)
            changed.append(key)
    return out, changed


def override(base: dict, patch: dict) -> dict:
    """Deep-merge patch over base; patch wins on every key it mentions."""
    out = copy.deepcopy(base)
    for key, value in patch.items():
        if key == "_delete":
            continue
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = override(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


def normalize_release(rel: dict) -> dict:
    date = str(rel.get("date", "TBA")).strip() or "TBA"
    shape = precision_of(date)
    if shape == "TBA":
        return {"date": "TBA", "precision": "TBA", "confirmed": False}
    precision = rel.get("precision") if rel.get("precision") in PRECISIONS else shape
    return {"date": date, "precision": precision, "confirmed": bool(rel.get("confirmed", False))}


def new_game(patch: dict) -> dict:
    """A complete entry skeleton for a game that is not in games.json yet."""
    game = {
        "id": patch["id"], "title": "", "altTitles": {"jp": "", "cn": "", "kr": ""},
        "developer": "", "publisher": "", "genres": [], "tags": [], "platforms": [],
        "cover": "", "screenshots": [], "trailer": "", "description": "", "officialSite": "", "storeLinks": {},
        "status": "announced",
        "releases": {r: {"date": "TBA", "precision": "TBA", "confirmed": False} for r in REGIONS},
        "endOfService": "", "sourceUrl": "", "lastVerified": "", "added": "", "popularity": 0, "adult": False,
    }
    filled, _ = fill_gaps(game, patch)
    if patch.get("status") in STATUSES:
        filled["status"] = patch["status"]
    if "adult" in patch:
        filled["adult"] = bool(patch["adult"])
    if isinstance(patch.get("popularity"), int):
        filled["popularity"] = patch["popularity"]
    return filled


def slugify(title: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return slug or "game"


def merge(games: list[dict], pending: list[dict], overrides: dict[str, dict], today: str = "") -> tuple[list[dict], list[dict], list[str]]:
    """Apply approved pending items, then overrides.

    Returns (new games list, remaining pending items, human-readable log).
    Approved items are consumed; unapproved ones stay in pending untouched.
    """
    by_id: dict[str, dict] = {}
    order: list[str] = []
    for g in games:
        gid = g.get("id")
        if isinstance(gid, str) and gid and gid not in by_id:
            by_id[gid] = copy.deepcopy(g)
            order.append(gid)
    log: list[str] = []
    remaining: list[dict] = []

    for item in pending:
        if not isinstance(item, dict):
            continue
        if not item.get("approved"):
            remaining.append(item)
            continue
        patch = item.get("game")
        if not isinstance(patch, dict) or not patch:
            log.append(f"pending {item.get('id', '?')}: approved but has no 'game' data, dropped")
            continue
        patch = dict(patch)
        gid = patch.get("id") or item.get("gameId") or (slugify(patch["title"]) if patch.get("title") else "")
        if not gid:
            log.append(f"pending {item.get('id', '?')}: approved but has no game id or title, dropped")
            continue
        patch["id"] = gid
        if gid in by_id:
            by_id[gid], changed = fill_gaps(by_id[gid], patch)
            log.append(f"{gid}: filled {', '.join(changed)}" if changed else f"{gid}: nothing to fill (existing values kept)")
        elif not patch.get("title"):
            log.append(f"pending {item.get('id', '?')}: new game '{gid}' needs a title, dropped")
        else:
            if today and is_empty(patch.get("added")):
                patch["added"] = today
            by_id[gid] = new_game(patch)
            order.append(gid)
            log.append(f"{gid}: added from pending")

    for gid, patch in (overrides or {}).items():
        if gid.startswith("_") or not isinstance(patch, dict):
            continue
        if patch.get("_delete"):
            if by_id.pop(gid, None) is not None:
                order.remove(gid)
                log.append(f"{gid}: removed by override")
            continue
        if gid in by_id:
            merged = override(by_id[gid], patch)
            if merged != by_id[gid]:
                log.append(f"{gid}: override applied")
            by_id[gid] = merged
        elif patch.get("title"):
            by_id[gid] = override(new_game({"id": gid}), patch)
            order.append(gid)
            log.append(f"{gid}: added by override")
        if gid in by_id:
            by_id[gid]["id"] = gid

    return [by_id[g] for g in order], remaining, log


def add_leads(pending: list[dict], leads: list[dict], dismissed: set[str], limit: int = 150) -> list[dict]:
    """Append new scraped leads to pending, skipping ones already present or dismissed.

    Hand-edited and approved items are never dropped; only the oldest untouched leads are trimmed.
    """
    seen = {p.get("id") for p in pending if isinstance(p, dict)}
    out = [p for p in pending if isinstance(p, dict)]
    for lead in leads:
        if lead["id"] in seen or lead["id"] in dismissed:
            continue
        seen.add(lead["id"])
        out.append(lead)
    untouched = [p for p in out if not p.get("approved") and not p.get("game")]
    excess = len(out) - limit
    if excess > 0:
        drop = {id(p) for p in sorted(untouched, key=lambda p: p.get("firstSeen", ""))[:excess]}
        out = [p for p in out if id(p) not in drop]
    return out
