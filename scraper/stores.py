"""Reading public store pages (Google Play, App Store). HTML is parsed with small, forgiving regexes."""
from __future__ import annotations

import re
from datetime import datetime
from html import unescape
from urllib.parse import parse_qs, urlsplit

from feeds import allowed_by_robots, http_get

PLAY_REGIONS = {"global": "US", "jp": "JP", "kr": "KR", "tw_sea": "TW"}
APPLE_REGIONS = {"global": "us", "jp": "jp", "kr": "kr", "cn": "cn", "tw_sea": "tw"}


def play_package(url: str) -> str:
    try:
        return parse_qs(urlsplit(url).query).get("id", [""])[0]
    except Exception:
        return ""


def play_url(package: str, country: str) -> str:
    return f"https://play.google.com/store/apps/details?id={package}&hl=en&gl={country}"


def apple_id(url: str) -> str:
    m = re.search(r"/id(\d+)", url or "")
    return m.group(1) if m else ""


def apple_url(app_id: str, country: str) -> str:
    return f"https://apps.apple.com/{country}/app/id{app_id}"


def parse_play_page(html: str) -> dict:
    """{'state': 'pre-registration'|'available'|'unknown', 'cover': url, 'title': str}"""
    cover = ""
    m = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]+)"', html)
    if m:
        cover = unescape(m.group(1))
    title = ""
    m = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]+)"', html)
    if m:
        title = re.sub(r"\s+- Apps on Google Play$", "", unescape(m.group(1)))
    if re.search(r">\s*Pre-register\s*<|Pre-registered|Coming soon", html):
        state = "pre-registration"
    elif re.search(r">\s*Install\s*<|\"softwareVersion\"|Downloads</div>", html):
        state = "available"
    else:
        state = "unknown"
    return {"state": state, "cover": cover, "title": title}


def parse_apple_page(html: str) -> dict:
    """{'state': 'pre-order'|'available'|'unknown', 'expected': 'YYYY-MM-DD' or ''}"""
    expected = ""
    m = re.search(r"Expected\s+([A-Z][a-z]{2})\.?\s+(\d{1,2}),\s+(\d{4})", html)
    if m:
        try:
            expected = datetime.strptime(" ".join(m.groups()), "%b %d %Y").strftime("%Y-%m-%d")
        except ValueError:
            expected = ""
    if expected or re.search(r"Pre-?Order", html):
        state = "pre-order"
    elif re.search(r'"@type"\s*:\s*"SoftwareApplication"|>\s*Get\s*<', html):
        state = "available"
    else:
        state = "unknown"
    return {"state": state, "expected": expected}


def fetch_page(url: str) -> tuple[int, str]:
    """Returns (status, html). Status 0 means robots.txt said no or the request failed."""
    try:
        if not allowed_by_robots(url):
            return 0, ""
        status, _, body = http_get(url, min_interval=3.0)
        return status, body.decode("utf-8", "replace")
    except Exception:
        return 0, ""


def check_game(game: dict, max_regions: int = 4) -> tuple[dict, list[dict]]:
    """Look at a game's store pages.

    Returns (gap-fill patch, observations). Observations are facts like
    {'store': 'googlePlay', 'region': 'jp', 'state': 'pre-registration', 'url': ...}.
    """
    patch: dict = {}
    seen: list[dict] = []
    links = game.get("storeLinks") or {}
    pkg = play_package(links.get("googlePlay", ""))
    if pkg:
        for region, country in list(PLAY_REGIONS.items())[:max_regions]:
            url = play_url(pkg, country)
            status, html = fetch_page(url)
            if status == 200:
                info = parse_play_page(html)
                seen.append({"store": "googlePlay", "region": region, "state": info["state"], "url": url})
                if info["cover"] and not patch.get("cover"):
                    patch["cover"] = info["cover"]
            elif status == 404:
                seen.append({"store": "googlePlay", "region": region, "state": "not-listed", "url": url})
            # A released game only needs one page (for the cover).
            if game.get("status") in ("released", "end-of-service"):
                break
    app = apple_id(links.get("appStore", ""))
    if app and game.get("status") not in ("released", "end-of-service"):
        for region, country in list(APPLE_REGIONS.items())[:max_regions]:
            url = apple_url(app, country)
            status, html = fetch_page(url)
            if status == 200:
                info = parse_apple_page(html)
                seen.append({"store": "appStore", "region": region, "state": info["state"], "expected": info["expected"], "url": url})
    return patch, seen
