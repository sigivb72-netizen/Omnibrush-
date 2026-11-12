#!/usr/bin/env python3
"""MyGachaList scraper. Run by .github/workflows/scrape.yml every 6 hours.

What it does, in order:
  1. Loads data/games.json. If that file is broken it stops and changes nothing.
  2. Merges approved items from data/pending.json (gap-fill only) and applies data/overrides.json (always wins).
  3. Reads the RSS feeds in scraper/sources.json (robots.txt permitting) and writes
     relevant headlines to data/news.json.
  4. Turns interesting headlines and store-page observations into review items in data/pending.json.
  5. Fills in a missing cover image from the game's Google Play page.

It never edits a value a human put in games.json.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import feeds  # noqa: E402
import merge as merge_mod  # noqa: E402
import stores  # noqa: E402

NEWS_LIMIT = 150
NEWS_MAX_AGE_DAYS = 45
PENDING_README = (
    "Review queue written by the scraper. To ACCEPT an item: fill in its 'game' object (same fields as "
    "games.json; 'id' or 'title' required) and set 'approved' to true. Accepted items only add new games or "
    "fill empty/TBA fields; they never change existing values. To DISMISS an item set 'rejected' to true. "
    "To change an existing value, edit games.json directly or add it to overrides.json."
)


def load_json(path: Path, default):
    if not path.exists():
        return default
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def dump_json(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


def write_if_changed(path: Path, obj) -> bool:
    text = dump_json(obj)
    if path.exists() and path.read_text(encoding="utf-8") == text:
        return False
    path.write_text(text, encoding="utf-8")
    return True


def collect_news(sources: list[dict], games: list[dict], now: datetime) -> tuple[list[dict], list[dict], list[str]]:
    """Returns (news items, pending leads, log lines)."""
    index = feeds.build_title_index(games)
    by_id = {g["id"]: g for g in games}
    news: list[dict] = []
    leads: list[dict] = []
    log: list[str] = []
    today = now.strftime("%Y-%m-%d")
    for src in sources:
        if not src.get("enabled", True):
            log.append(f"feed {src['id']}: disabled ({src.get('note', '')})")
            continue
        items, reason = feeds.fetch_feed(src)
        if reason:
            log.append(f"feed {src['id']}: skipped - {reason}")
            continue
        kept = 0
        for it in items:
            game_ids = feeds.match_games(it["title"], index)
            # Adult titles never reach the public news list.
            game_ids = [g for g in game_ids if not by_id[g].get("adult")]
            if not feeds.is_relevant(it["title"], game_ids):
                continue
            kept += 1
            nid = feeds.item_id(it["url"])
            news.append({
                "id": nid, "title": it["title"][:300], "url": it["url"], "source": src["name"],
                "lang": src.get("lang", "en"), "published": it["published"] or now.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "gameIds": game_ids,
            })
            kind = feeds.detect_lead(it["title"])
            # A lead is worth a human look when it may change the database:
            # a possible new game, or a date/status change for a game that is not simply "released".
            known_live = game_ids and all(by_id[g].get("status") == "released" for g in game_ids)
            if kind and (kind == "end-of-service" or not known_live):
                leads.append({
                    "id": "news-" + nid, "type": "news", "detected": kind,
                    "gameId": game_ids[0] if game_ids else "",
                    "title": it["title"][:300], "url": it["url"], "source": src["name"],
                    "firstSeen": today, "approved": False, "game": {},
                })
        log.append(f"feed {src['id']}: {len(items)} items, {kept} relevant")
    return news, leads, log


def merge_news(old: list[dict], new: list[dict], now: datetime) -> list[dict]:
    cutoff = (now - timedelta(days=NEWS_MAX_AGE_DAYS)).strftime("%Y-%m-%dT%H:%M:%SZ")
    by_id: dict[str, dict] = {}
    for it in list(old) + list(new):  # newer copy wins (title fixes, new game matches)
        if isinstance(it, dict) and it.get("id") and it.get("published", "") >= cutoff:
            by_id[it["id"]] = it
    return sorted(by_id.values(), key=lambda i: (i.get("published", ""), i["id"]), reverse=True)[:NEWS_LIMIT]


def check_stores(games: list[dict], now: datetime, budget: int) -> tuple[list[dict], list[dict], list[str]]:
    """Returns (games with gaps filled, pending leads, log). Spends at most `budget` games per run."""
    today = now.strftime("%Y-%m-%d")
    leads: list[dict] = []
    log: list[str] = []
    out = []
    # Unreleased games first (their status can change), then games missing a cover.
    def priority(g):
        return (0 if g.get("status") in ("announced", "pre-registration", "beta") else 1, g["id"])
    todo = {g["id"] for g in sorted(games, key=priority)
            if (g.get("storeLinks") or {}).get("googlePlay") or (g.get("storeLinks") or {}).get("appStore")
            if g.get("status") in ("announced", "pre-registration", "beta") or merge_mod.is_empty(g.get("cover"))}
    todo = set(sorted(todo, key=lambda i: priority(next(g for g in games if g["id"] == i)))[:budget])
    for g in games:
        if g["id"] not in todo:
            out.append(g)
            continue
        patch, seen = stores.check_game(g)
        filled, changed = merge_mod.fill_gaps(g, patch)
        if changed:
            log.append(f"store {g['id']}: filled {', '.join(changed)}")
        out.append(filled)
        for obs in seen:
            suggestion = None
            game_patch: dict = {}
            if obs["state"] == "pre-registration" and g.get("status") in ("announced", "beta"):
                suggestion = "Google Play shows pre-registration; consider setting status to 'pre-registration' in games.json"
            elif obs["state"] == "available" and g.get("status") != "released":
                suggestion = "Store page looks live; check whether the game has released in this region"
            elif obs["state"] == "pre-order" and obs.get("expected"):
                cur = (g.get("releases") or {}).get(obs["region"], {})
                if cur.get("date") != obs["expected"]:
                    suggestion = f"App Store expects release on {obs['expected']} (currently {cur.get('date', 'TBA')})"
                    game_patch = {"id": g["id"], "releases": {obs["region"]: {"date": obs["expected"], "precision": "exact", "confirmed": True}}}
            if suggestion:
                key = f"store-{g['id']}-{obs['store']}-{obs['region']}-{obs['state']}-{obs.get('expected', '')}"
                leads.append({
                    "id": key, "type": "store", "detected": obs["state"], "gameId": g["id"],
                    "title": f"{g.get('title', g['id'])} ({obs['region']}): {suggestion}", "url": obs["url"],
                    "source": obs["store"], "firstSeen": today, "approved": False, "game": game_patch,
                })
    return out, leads, log


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", default=str(Path(__file__).resolve().parent.parent / "data"))
    ap.add_argument("--no-network", action="store_true", help="only merge pending/overrides")
    ap.add_argument("--store-budget", type=int, default=12, help="max games to look up on stores per run")
    args = ap.parse_args(argv)
    data = Path(args.data)
    now = datetime.now(timezone.utc)
    today = now.strftime("%Y-%m-%d")

    try:
        db = load_json(data / "games.json", None)
        if not isinstance(db, dict) or not isinstance(db.get("games"), list):
            raise ValueError("expected an object with a 'games' list")
    except Exception as e:
        print(f"ERROR: data/games.json is not usable ({e}). Nothing was changed.", file=sys.stderr)
        return 1

    def tolerant(name, default, kind):
        try:
            value = load_json(data / name, default)
            if not isinstance(value, kind):
                raise ValueError("unexpected shape")
            return value, True
        except Exception as e:
            print(f"WARNING: data/{name} is not usable ({e}); leaving it untouched.", file=sys.stderr)
            return default, False

    overrides, _ = tolerant("overrides.json", {}, dict)
    pending_file, pending_ok = tolerant("pending.json", {"items": [], "dismissed": []}, dict)
    news_file, _ = tolerant("news.json", {"items": []}, dict)
    sources = load_json(Path(__file__).resolve().parent / "sources.json", {"feeds": [], "stores": {}})

    pending_items = [p for p in pending_file.get("items", []) if isinstance(p, dict)]
    dismissed = [d for d in pending_file.get("dismissed", []) if isinstance(d, str)]
    # Rejected and consumed items are remembered so the same headline is not queued again.
    dismissed += [p["id"] for p in pending_items if p.get("id") and (p.get("rejected") or p.get("approved"))]
    pending_items = [p for p in pending_items if not p.get("rejected")]

    games, pending_items, log = merge_mod.merge(db["games"], pending_items, overrides, today)

    if not args.no_network:
        news, news_leads, nlog = collect_news(sources.get("feeds", []), games, now)
        log += nlog
        store_cfg = sources.get("stores", {})
        if store_cfg.get("googlePlay", {}).get("enabled", True) or store_cfg.get("appStore", {}).get("enabled", True):
            games, store_leads, slog = check_stores(games, now, args.store_budget)
            log += slog
        else:
            store_leads = []
        # Overrides are re-applied so they also beat anything the store lookup filled in.
        games, _, _ = merge_mod.merge(games, [], overrides, today)
        pending_items = merge_mod.add_leads(pending_items, news_leads + store_leads, set(dismissed))
        items = merge_news(news_file.get("items", []), news, now)
        if items != news_file.get("items"):
            write_if_changed(data / "news.json", {"updated": now.strftime("%Y-%m-%dT%H:%M:%SZ"), "items": items})

    if games != db["games"]:
        db = dict(db, updated=today, games=games)
        write_if_changed(data / "games.json", db)
        log.append("games.json updated")
    if pending_ok:
        write_if_changed(data / "pending.json", {
            "_readme": PENDING_README,
            "dismissed": sorted(set(dismissed))[-2000:],
            "items": pending_items,
        })
    print("\n".join(log) if log else "nothing to do")
    return 0


if __name__ == "__main__":
    sys.exit(main())
