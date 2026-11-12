import json
from datetime import datetime, timezone
from pathlib import Path

import scrape
import validate

NOW = datetime(2026, 11, 12, tzinfo=timezone.utc)


def write(p: Path, obj):
    p.write_text(json.dumps(obj), encoding="utf-8")


def base_game():
    return {"id": "g1", "title": "Game One", "developer": "Hand", "status": "announced", "releases": {}}


def test_offline_run_merges_and_cleans_pending(tmp_path):
    write(tmp_path / "games.json", {"schemaVersion": 1, "games": [base_game()]})
    write(tmp_path / "overrides.json", {"g1": {"publisher": "Override Pub"}})
    write(tmp_path / "pending.json", {"items": [
        {"id": "ok", "approved": True, "game": {"title": "New Game"}},
        {"id": "no", "rejected": True, "game": {}},
        {"id": "wait", "approved": False, "game": {}},
    ]})
    assert scrape.main(["--data", str(tmp_path), "--no-network"]) == 0
    games = json.loads((tmp_path / "games.json").read_text())["games"]
    assert [g["id"] for g in games] == ["g1", "new-game"]
    assert games[0]["publisher"] == "Override Pub" and games[0]["developer"] == "Hand"
    pending = json.loads((tmp_path / "pending.json").read_text())
    assert [p["id"] for p in pending["items"]] == ["wait"]
    assert set(pending["dismissed"]) == {"ok", "no"}
    # Second run changes nothing.
    before = (tmp_path / "games.json").read_text()
    assert scrape.main(["--data", str(tmp_path), "--no-network"]) == 0
    assert (tmp_path / "games.json").read_text() == before


def test_broken_games_json_is_left_alone(tmp_path):
    (tmp_path / "games.json").write_text('{"games": [ {"id": "a", ]}', encoding="utf-8")
    assert scrape.main(["--data", str(tmp_path), "--no-network"]) == 1
    assert (tmp_path / "games.json").read_text() == '{"games": [ {"id": "a", ]}'


def test_broken_pending_is_left_alone_but_run_continues(tmp_path):
    write(tmp_path / "games.json", {"games": [base_game()]})
    (tmp_path / "pending.json").write_text("{oops", encoding="utf-8")
    assert scrape.main(["--data", str(tmp_path), "--no-network"]) == 0
    assert (tmp_path / "pending.json").read_text() == "{oops"


def test_news_merge_dedupes_sorts_and_expires():
    old = [{"id": "a", "published": "2026-11-01T00:00:00Z", "title": "old a"},
           {"id": "z", "published": "2026-01-01T00:00:00Z", "title": "ancient"}]
    new = [{"id": "a", "published": "2026-11-01T00:00:00Z", "title": "new a"},
           {"id": "b", "published": "2026-11-10T00:00:00Z", "title": "b"}]
    out = scrape.merge_news(old, new, NOW)
    assert [(i["id"], i["title"]) for i in out] == [("b", "b"), ("a", "new a")]


def test_validator_explains_mistakes(tmp_path):
    bad = {"id": "Bad ID", "title": "", "status": "live", "adult": "false", "genres": "RPG",
           "releases": {"usa": {}, "jp": {"date": "next year"}, "kr": {"date": "2026", "precision": "exact"}}}
    write(tmp_path / "games.json", {"games": [bad, {"id": "ok", "title": "OK"}, {"id": "ok", "title": "Again"}]})
    errs = "\n".join(validate.validate(tmp_path))
    for needle in ["'id' must be lowercase", "'title' is missing", "'status' is \"live\"", "'adult' must be true or false",
                   "'genres' must be a list", "unknown region 'usa'", "\"next year\" is not understood",
                   "only year-precise", "appears twice"]:
        assert needle in errs, needle
    (tmp_path / "games.json").write_text('{"games": [,]}', encoding="utf-8")
    assert "not valid JSON near line 1" in validate.validate(tmp_path)[0]


def test_repo_data_files_are_valid():
    assert validate.validate(Path(__file__).resolve().parents[2] / "data") == []
