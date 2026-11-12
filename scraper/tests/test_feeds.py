import pytest

import feeds
import stores

RSS = b"""<?xml version="1.0"?><rss version="2.0"><channel><title>T</title>
<item><title>Ananta pre-registration &amp; trailer</title><link>https://ex.com/a</link><pubDate>Thu, 12 Nov 2026 10:00:00 +0900</pubDate></item>
<item><title><![CDATA[<b>Bold</b> headline]]></title><link>https://ex.com/b</link></item>
<item><title>No link</title></item>
</channel></rss>"""

ATOM = b"""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Atom entry</title>
<link rel="alternate" href="https://ex.com/atom"/><updated>2026-11-12T01:02:03Z</updated></entry></feed>"""

RDF = b"""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
<item rdf:about="https://ex.com/r"><title>\xe4\xba\x8b\xe5\x89\x8d\xe7\x99\xbb\xe9\x8c\xb2\xe9\x96\x8b\xe5\xa7\x8b</title><link>https://ex.com/r</link><dc:date>2026-11-12T10:00:00+09:00</dc:date></item></rdf:RDF>"""


def test_parse_rss():
    items = feeds.parse_feed(RSS)
    assert [i["url"] for i in items] == ["https://ex.com/a", "https://ex.com/b"]
    assert items[0]["title"] == "Ananta pre-registration & trailer"
    assert items[0]["published"] == "2026-11-12T01:00:00Z"
    assert items[1]["title"] == "Bold headline"
    assert items[1]["published"] == ""


def test_parse_atom_and_rdf():
    assert feeds.parse_feed(ATOM) == [{"title": "Atom entry", "url": "https://ex.com/atom", "published": "2026-11-12T01:02:03Z"}]
    rdf = feeds.parse_feed(RDF)
    assert rdf[0]["title"] == "事前登録開始" and rdf[0]["published"] == "2026-11-12T01:00:00Z"


@pytest.mark.parametrize("body", [b"<html><body>Just a moment...</body></html>", b"", b"not xml at all", b"<a><b></a>"])
def test_non_feeds_raise_value_error(body):
    with pytest.raises(ValueError):
        feeds.parse_feed(body)


def test_entity_bomb_is_rejected():
    bomb = b'<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;">]><rss><channel><item><title>&lol2;</title><link>https://x</link></item></channel></rss>'
    with pytest.raises(ValueError):
        feeds.parse_feed(bomb)


GAMES = [
    {"id": "arknights", "title": "Arknights", "altTitles": {"jp": "アークナイツ", "cn": "明日方舟", "kr": "명일방주"}},
    {"id": "arknights-endfield", "title": "Arknights: Endfield", "altTitles": {"jp": "アークナイツ：エンドフィールド"}},
    {"id": "ananta", "title": "Ananta", "altTitles": {"cn": "无限大"}},
    {"id": "raid", "title": "RAID", "altTitles": {}},
    {"id": "gfl", "title": "Girls' Frontline", "altTitles": {"kr": "소녀전선"}},
    {"id": "gfl2", "title": "Girls' Frontline 2: Exilium", "altTitles": {"kr": "소녀전선2: 망명"}},
]


def test_match_games_prefers_longest_title_and_whole_words():
    idx = feeds.build_title_index(GAMES)
    assert feeds.match_games("Arknights: Endfield gets a new trailer", idx) == ["arknights-endfield"]
    assert feeds.match_games("New Arknights event", idx) == ["arknights"]
    assert feeds.match_games("『アークナイツ：エンドフィールド』新情報", idx) == ["arknights-endfield"]
    assert feeds.match_games("明日方舟 新活动", idx) == ["arknights"]
    assert feeds.match_games("2주년 맞은 소녀전선2, 콜라보까지", idx) == ["gfl2"]  # sequel number is respected
    assert feeds.match_games("소녀전선 신규 이벤트", idx) == ["gfl"]
    assert feeds.match_games("Girls' Frontline 2 anniversary", idx) == ["gfl2"]
    assert feeds.match_games("Anantapur city news", idx) == []  # no partial-word match
    assert feeds.match_games("Police raid a warehouse", idx) == []  # short generic titles are not indexed


def test_lead_detection_and_relevance():
    assert feeds.detect_lead("Ananta pre-registration opens") == "pre-registration"
    assert feeds.detect_lead("Some Game will shut down in June") == "end-of-service"
    assert feeds.detect_lead("新作RPGの事前登録が開始") == "pre-registration"
    assert feeds.detect_lead("Patch notes") == ""
    assert feeds.is_relevant("A new gacha RPG appears", [])
    assert feeds.is_relevant("Patch notes", ["ananta"])
    assert not feeds.is_relevant("Console hardware sales chart", [])


def test_store_page_parsing():
    live = '<meta property="og:image" content="https://play-lh.googleusercontent.com/abc=w600"><meta property="og:title" content="My Game - Apps on Google Play"><button><span>Install</span></button>'
    assert stores.parse_play_page(live) == {"state": "available", "cover": "https://play-lh.googleusercontent.com/abc=w600", "title": "My Game"}
    pre = '<meta property="og:image" content="https://x/y"><button><span>Pre-register</span></button><div>Coming soon</div>'
    assert stores.parse_play_page(pre)["state"] == "pre-registration"
    assert stores.parse_play_page("<html></html>")["state"] == "unknown"
    assert stores.parse_apple_page("<p>Pre-Order</p><p>Expected Jan 15, 2027</p>") == {"state": "pre-order", "expected": "2027-01-15"}
    assert stores.parse_apple_page('{"@type":"SoftwareApplication"}')["state"] == "available"
    assert stores.play_package("https://play.google.com/store/apps/details?id=com.a.b&hl=en") == "com.a.b"
    assert stores.apple_id("https://apps.apple.com/us/app/some-game/id1234567890") == "1234567890"
