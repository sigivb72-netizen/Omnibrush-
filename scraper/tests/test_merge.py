import copy

import merge as m


def game(**kw):
    base = {
        "id": "g1", "title": "Game One", "developer": "Hand Edited Dev", "cover": "",
        "status": "announced", "storeLinks": {}, "altTitles": {"jp": "", "cn": "", "kr": ""},
        "releases": {
            "global": {"date": "2026-12-01", "precision": "exact", "confirmed": True},
            "jp": {"date": "TBA", "precision": "TBA", "confirmed": False},
        },
    }
    base.update(kw)
    return base


def approved(patch, **kw):
    return {"id": "p1", "approved": True, "game": patch, **kw}


def test_unapproved_pending_changes_nothing_and_stays_queued():
    games = [game()]
    item = {"id": "p1", "approved": False, "game": {"id": "g1", "developer": "Scraped"}}
    out, remaining, _ = m.merge(games, [item], {})
    assert out == games
    assert remaining == [item]


def test_approved_pending_never_overwrites_hand_edited_values():
    out, remaining, _ = m.merge([game()], [approved({
        "id": "g1", "developer": "Scraped Dev", "title": "Scraped Title", "status": "released",
        "releases": {"global": {"date": "2027-01-01", "precision": "exact", "confirmed": True}},
    })], {})
    g = out[0]
    assert g["developer"] == "Hand Edited Dev"
    assert g["title"] == "Game One"
    assert g["status"] == "announced"
    assert g["releases"]["global"]["date"] == "2026-12-01"
    assert remaining == []  # consumed


def test_approved_pending_fills_empty_and_tba_fields():
    out, _, log = m.merge([game()], [approved({
        "id": "g1", "cover": "https://img/x.png",
        "storeLinks": {"googlePlay": "https://play.google.com/store/apps/details?id=x"},
        "altTitles": {"jp": "ゲーム"},
        "releases": {"jp": {"date": "2027-02", "confirmed": True}, "kr": {"date": "2027"}},
    })], {})
    g = out[0]
    assert g["cover"] == "https://img/x.png"
    assert g["storeLinks"]["googlePlay"].endswith("id=x")
    assert g["altTitles"]["jp"] == "ゲーム"
    assert g["releases"]["jp"] == {"date": "2027-02", "precision": "month", "confirmed": True}
    assert g["releases"]["kr"] == {"date": "2027", "precision": "year", "confirmed": False}
    assert "filled" in log[0]


def test_tba_in_patch_never_replaces_anything():
    out, _, _ = m.merge([game()], [approved({"id": "g1", "releases": {"global": {"date": "TBA"}}, "cover": ""})], {})
    assert out[0]["releases"]["global"]["date"] == "2026-12-01"


def test_approved_pending_adds_new_game_with_full_skeleton():
    out, _, _ = m.merge([game()], [approved({"title": "Brand New: Game!", "status": "pre-registration", "adult": True})], {}, today="2026-11-12")
    new = out[1]
    assert new["id"] == "brand-new-game"
    assert new["status"] == "pre-registration"
    assert new["adult"] is True
    assert new["added"] == "2026-11-12"
    assert set(new["releases"]) == set(m.REGIONS)
    assert all(r["date"] == "TBA" for r in new["releases"].values())


def test_approved_item_without_usable_data_is_dropped_not_fatal():
    out, remaining, log = m.merge([game()], [
        {"id": "a", "approved": True}, {"id": "b", "approved": True, "game": {"developer": "x"}},
        {"id": "c", "approved": True, "game": {"id": "no-title"}}, "garbage",
    ], {})
    assert len(out) == 1 and remaining == [] and len(log) == 3


def test_overrides_always_win():
    out, _, _ = m.merge([game()], [approved({"id": "g1", "cover": "https://scraped/cover.png"})], {
        "g1": {"developer": "Override Dev", "cover": "https://override/cover.png",
               "releases": {"global": {"date": "2027-03-03"}}},
    })
    g = out[0]
    assert g["developer"] == "Override Dev"
    assert g["cover"] == "https://override/cover.png"
    assert g["releases"]["global"]["date"] == "2027-03-03"
    assert g["releases"]["global"]["precision"] == "exact"  # untouched sibling keys survive
    assert g["releases"]["jp"]["date"] == "TBA"
    assert g["id"] == "g1"


def test_override_can_delete_and_add():
    out, _, _ = m.merge([game(), game(id="g2", title="Two")], [], {
        "g1": {"_delete": True}, "g3": {"title": "Three", "status": "beta"}, "_comment": "ignored", "g4": {"developer": "no title"},
    })
    assert [g["id"] for g in out] == ["g2", "g3"]
    assert out[1]["status"] == "beta"


def test_merge_is_idempotent_and_does_not_mutate_input():
    games = [game()]
    snapshot = copy.deepcopy(games)
    ov = {"g1": {"developer": "Override Dev"}}
    once, _, _ = m.merge(games, [], ov)
    twice, _, log = m.merge(once, [], ov)
    assert games == snapshot
    assert once == twice
    assert log == []


def test_duplicate_ids_keep_first():
    out, _, _ = m.merge([game(), game(title="Dup")], [], {})
    assert len(out) == 1 and out[0]["title"] == "Game One"


def test_add_leads_dedupes_respects_dismissed_and_protects_edited_items():
    edited = {"id": "old-edited", "firstSeen": "2020-01-01", "approved": False, "game": {"title": "WIP"}}
    old = {"id": "old", "firstSeen": "2020-01-02", "approved": False, "game": {}}
    leads = [{"id": f"n{i}", "firstSeen": "2026-11-12", "approved": False, "game": {}} for i in range(3)]
    leads += [{"id": "old", "firstSeen": "x", "game": {}}, {"id": "gone", "firstSeen": "x", "game": {}}]
    out = m.add_leads([edited, old], leads, {"gone"}, limit=4)
    ids = [p["id"] for p in out]
    assert "gone" not in ids and ids.count("old") <= 1
    assert "old-edited" in ids  # never trimmed
    assert "old" not in ids  # oldest untouched lead trimmed first
    assert len(out) == 4


def test_precision_of():
    assert [m.precision_of(d) for d in ["2026-01-02", "2026-01", "2026-Q4", "2026", "TBA", "soon", ""]] == \
        ["exact", "month", "quarter", "year", "TBA", "TBA", "TBA"]
