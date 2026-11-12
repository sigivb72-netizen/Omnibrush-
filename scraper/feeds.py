"""Fetching and parsing RSS / Atom / RDF feeds, politely."""
from __future__ import annotations

import hashlib
import re
import time
import urllib.error
import urllib.request
import urllib.robotparser
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from html import unescape
from urllib.parse import urlsplit

from defusedxml import ElementTree as ET

USER_AGENT = "MyGachaListBot/1.0 (+https://github.com/sigivb72-netizen/Omnibrush-; personal release tracker)"
TIMEOUT = 20
MAX_BYTES = 3 * 1024 * 1024
_robots_cache: dict[str, urllib.robotparser.RobotFileParser | None] = {}
_last_hit: dict[str, float] = {}


def http_get(url: str, min_interval: float = 2.0) -> tuple[int, str, bytes]:
    """GET with our User-Agent and a per-host delay. Returns (status, content-type, body)."""
    host = urlsplit(url).netloc
    wait = _last_hit.get(host, 0) + min_interval - time.monotonic()
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Language": "en-US,en;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, resp.headers.get("Content-Type", ""), resp.read(MAX_BYTES)
    except urllib.error.HTTPError as e:
        return e.code, "", b""
    finally:
        _last_hit[host] = time.monotonic()


def allowed_by_robots(url: str) -> bool:
    """True only if robots.txt was readable and allows our bot. Unreadable robots.txt means skip."""
    parts = urlsplit(url)
    root = f"{parts.scheme}://{parts.netloc}"
    if root not in _robots_cache:
        rp: urllib.robotparser.RobotFileParser | None = urllib.robotparser.RobotFileParser()
        try:
            status, _, body = http_get(root + "/robots.txt", min_interval=0)
            if status == 404:
                rp.parse([])  # no robots.txt: everything is allowed
            elif status == 200:
                rp.parse(body.decode("utf-8", "replace").splitlines())
            else:
                rp = None
        except Exception:
            rp = None
        _robots_cache[root] = rp
    rp = _robots_cache[root]
    return rp is not None and rp.can_fetch(USER_AGENT, url)


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def _text(el) -> str:
    return unescape(re.sub(r"<[^>]+>", "", "".join(el.itertext()))).strip() if el is not None else ""


def _parse_date(raw: str) -> str:
    raw = (raw or "").strip()
    if not raw:
        return ""
    try:
        dt = parsedate_to_datetime(raw)
    except Exception:
        try:
            dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except Exception:
            return ""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_feed(xml_bytes: bytes) -> list[dict]:
    """Parse RSS 2.0, RSS 1.0 (RDF) or Atom into [{title, url, published}]. Raises ValueError if not a feed."""
    try:
        root = ET.fromstring(xml_bytes)
    except Exception as e:  # defusedxml raises several types
        raise ValueError(f"not XML: {e}") from e
    if _local(root.tag) not in ("rss", "rdf", "feed"):
        raise ValueError(f"not a feed (root <{_local(root.tag)}>)")
    items = []
    for el in root.iter():
        if _local(el.tag) not in ("item", "entry"):
            continue
        title = url = published = ""
        for child in el:
            name = _local(child.tag)
            if name == "title":
                title = _text(child)
            elif name == "link":
                href = child.attrib.get("href")
                if href and child.attrib.get("rel", "alternate") == "alternate":
                    url = href.strip()
                elif not url and (child.text or "").strip():
                    url = child.text.strip()
            elif name in ("pubdate", "date", "published", "updated") and not published:
                published = _parse_date(child.text or "")
        if title and url.startswith("http"):
            items.append({"title": " ".join(title.split()), "url": url, "published": published})
    return items


def fetch_feed(source: dict) -> tuple[list[dict], str]:
    """Returns (items, reason-if-skipped). Never raises."""
    url = source["url"]
    try:
        if not allowed_by_robots(url):
            return [], "robots.txt disallows it or could not be read"
        status, _, body = http_get(url)
        if status != 200:
            return [], f"HTTP {status}"
        if not body.strip():
            return [], "empty response"
        return parse_feed(body), ""
    except ValueError as e:
        return [], str(e)
    except Exception as e:
        return [], f"{type(e).__name__}: {e}"


def item_id(url: str) -> str:
    return hashlib.sha1(url.encode("utf-8")).hexdigest()[:16]


# ---- relevance -------------------------------------------------------------------------------

GACHA_KEYWORDS = [
    "gacha", "pre-registration", "pre-register", "preregistration", "pre registration",
    "closed beta", "open beta", "end of service", "global launch", "hoyoverse", "mihoyo", "yostar",
    "ガチャ", "事前登録", "配信開始", "サービス終了", "正式サービス", "クローズドβ",
    "사전예약", "사전등록", "가챠", "서비스 종료", "정식 출시",
    "预约", "預約", "事前登錄", "公测", "公測", "停运", "停止營運", "抽卡",
]

LEAD_PATTERNS = [
    ("end-of-service", ["end of service", "shut down", "shutting down", "shuts down", "サービス終了", "서비스 종료", "停运", "停止營運"]),
    ("pre-registration", ["pre-registration", "pre-register", "preregistration", "pre registration", "事前登録", "사전예약", "사전등록", "预约", "預約", "事前登錄"]),
    ("release-date", ["release date", "launches on", "launches ", "launch date", "配信日", "配信開始", "リリース日", "출시일", "정식 출시", "公测", "公測", "上線"]),
    ("beta", ["closed beta", "open beta", "beta test", "cbt", "クローズドβ", "βテスト", "베타 테스트", "測試"]),
    ("announcement", ["announced", "announces", "revealed", "reveals", "発表", "공개", "公布", "发布"]),
]


def _norm(s: str) -> str:
    return re.sub(r"[\s\-_:!?'’.,・：！]+", " ", s.lower()).strip()


def build_title_index(games: list[dict]) -> list[tuple[str, str]]:
    """[(normalized needle, game id)], longest first so 'Arknights: Endfield' beats 'Arknights'."""
    index = []
    for g in games:
        names = [g.get("title", "")] + list((g.get("altTitles") or {}).values())
        # "Some Game 2: Subtitle" is usually written as just "Some Game 2" in headlines.
        names += [n.split(sep)[0] for n in names for sep in (":", "：") if sep in n and re.search(r"\d\s*$", n.split(sep)[0])]
        for name in names:
            n = _norm(name)
            # Very short or generic names would match unrelated headlines.
            if len(n) >= (2 if re.search(r"[^\x00-\x7f]", n) else 6):
                index.append((n, g["id"]))
    return sorted(set(index), key=lambda t: -len(t[0]))


def match_games(title: str, index: list[tuple[str, str]]) -> list[str]:
    hay = f" {_norm(title)} "
    found: list[str] = []
    for needle, gid in index:
        if needle.isascii():
            hit = f" {needle} " in hay
        else:
            # CJK/Korean text has no spaces, so match substrings, but not when a sequel number follows
            # ("소녀전선2" is not "소녀전선").
            hit = re.search(re.escape(needle) + r"(?!\s?\d)", hay) is not None
        if hit:
            if gid not in found:
                found.append(gid)
            hay = hay.replace(needle, " ")  # don't also match a shorter title inside this one
    return found


def detect_lead(title: str) -> str:
    low = title.lower()
    for kind, words in LEAD_PATTERNS:
        if any(w in low for w in words):
            return kind
    return ""


def is_relevant(title: str, game_ids: list[str]) -> bool:
    low = title.lower()
    return bool(game_ids) or any(k in low for k in GACHA_KEYWORDS)
