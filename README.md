# MyGachaList

A tracker for gacha games on Android: release calendar, game database, and your own list
with scores, play time and spending. There is no server and no account. The whole database is
one file in this repository, [`data/games.json`](data/games.json), which the app downloads and
keeps for offline use.

- [Install the app on your phone](#install-the-app-on-your-phone)
- [Add or fix a game from your phone](#add-or-fix-a-game-from-your-phone)
- [How the data updates itself](#how-the-data-updates-itself)
- [Project layout](#project-layout)

## Install the app on your phone

Every push to this repository builds an installable APK with GitHub Actions. You need a web
browser that is signed in to GitHub (Chrome works; the GitHub mobile app cannot download
build files).

1. Open this repository in your phone's browser and tap **Actions**.
   If you only see the mobile layout, open the browser menu and choose **Desktop site**.
2. Tap the **Build APK** workflow, then the newest run with a green check mark.
3. Scroll to **Artifacts** at the bottom of the run page and tap **MyGachaList-debug-apk**.
   A `.zip` file downloads.
4. Open the **My Files** app, go to **Downloads**, tap the zip and choose **Extract** (Unzip).
5. Tap the extracted `MyGachaList-debug-….apk`.
6. Android asks whether My Files may install apps: tap **Settings**, switch on
   **Allow from this source**, go back and tap **Install**.
   If Play Protect shows a warning, choose **More details → Install anyway**. The app is not
   from the Play Store, so Android cannot vouch for it.

**Updating:** repeat the steps with a newer build and install it over the old one. Your list
is kept, because every build is signed with the same key (`keystore/debug.keystore`).
To be safe, use **Settings → Export my list** first.

Artifacts are kept for 30 days. If the newest run is older than that, open the run and tap
**Re-run all jobs**, or use **Run workflow** on the Build APK page.

## Add or fix a game from your phone

1. In the GitHub mobile app (or the browser), open `data/games.json`.
2. Tap **⋯ → Edit file**.
3. Change a value, or copy a whole `{ … }` block, paste it after another one and edit it.
   Blocks are separated by commas.
4. Tap **Commit changes**.

A minute or two later the **Build APK** run for your commit shows a green check or a red X.
A red X on the **Validate data files** step means there is a typo; open the step to read what
is wrong, for example:

```
 - game 'my-game': 'status' is "live" but must be one of: announced, pre-registration, beta, released, end-of-service
 - games.json: not valid JSON near line 212, column 5: Expecting ',' delimiter.
```

The app itself never crashes on a bad file: a broken entry is skipped, and a completely broken
file is ignored in favour of the copy already saved on the phone.

Open the app and tap the refresh icon on Home to load your change (it can take about five
minutes for GitHub to serve the new file).

### One game entry

```json
{
  "id": "my-new-game",
  "title": "My New Game",
  "altTitles": { "jp": "", "cn": "", "kr": "" },
  "developer": "Studio",
  "publisher": "Publisher",
  "genres": ["Action RPG"],
  "tags": ["anime", "open world"],
  "platforms": ["Android", "iOS", "PC"],
  "cover": "https://…/image.png",
  "description": "One or two sentences.",
  "officialSite": "https://…",
  "storeLinks": { "googlePlay": "https://play.google.com/store/apps/details?id=…", "appStore": "https://apps.apple.com/app/id…" },
  "status": "pre-registration",
  "releases": {
    "global": { "date": "2027-01-15", "precision": "exact",   "confirmed": true  },
    "jp":     { "date": "2027-Q2",    "precision": "quarter", "confirmed": false },
    "kr":     { "date": "TBA",        "precision": "TBA",     "confirmed": false },
    "cn":     { "date": "TBA",        "precision": "TBA",     "confirmed": false },
    "tw_sea": { "date": "TBA",        "precision": "TBA",     "confirmed": false }
  },
  "endOfService": "",
  "sourceUrl": "https://… where the date was announced",
  "lastVerified": "2026-11-12",
  "added": "2026-11-12",
  "popularity": 50,
  "adult": false
}
```

| Field | Notes |
| --- | --- |
| `id` | Lowercase letters, digits and dashes. Never change it afterwards: people's lists refer to it. |
| `status` | `announced`, `pre-registration`, `beta`, `released` or `end-of-service` |
| `date` | `2027-01-15` (exact), `2027-01` (month), `2027-Q2` (quarter), `2027` (year) or `TBA` |
| `precision` | `exact`, `month`, `quarter`, `year` or `TBA`. Must not claim more than the date shows. |
| `confirmed` | `true` for an official date, `false` for a rumour or estimate |
| `popularity` | 0–100, only used for sorting |
| `added` | Date the entry was created; orders "Recently announced" |
| `adult` | `true` hides the game unless the 18+ setting is on |

Only `id` and `title` are required. **If you are not sure of a date, write `TBA`** or a coarser
date such as the year.

### About the seeded data

The 82 starting entries were written from memory and are only partly checked. Entries with a
`lastVerified` date and a `sourceUrl` were checked against a news article on that day. All
others show "Not verified yet" in the app; where the exact day was uncertain they deliberately
use a year or `TBA`. Store links and cover images were confirmed against the live Google Play
page. Please correct anything you find.

## How the data updates itself

```
RSS feeds + store pages ──► scraper (GitHub Action, every 6 h)
                               │
                               ├─► data/news.json      headlines shown in the app
                               ├─► data/pending.json   suggestions waiting for your review
                               └─► data/games.json     only gap-filling; never overwrites your edits
data/overrides.json ───────────┘   always wins
```

- **`data/games.json`** is yours. The scraper only ever fills a field that is empty or `TBA`
  (in practice: a missing cover image).
- **`data/pending.json`** is a review queue. The scraper adds headlines that look like a new
  game, a pre-registration, a release date or a shutdown. To accept one, fill in its `"game"`
  object (same fields as above) and set `"approved": true`; on the next run it is added as a new
  game or used to fill empty fields. To discard one, set `"rejected": true`.
- **`data/overrides.json`** holds corrections that must always win, keyed by game id:
  `{ "genshin-impact": { "cover": "https://…" } }`. Use `{ "some-id": { "_delete": true } }`
  to remove a game.

> **Scheduled runs need the default branch.** GitHub only runs scheduled workflows from the
> repository's default branch. Until this project is merged into (or made) the default branch,
> the scraper does not run on its own. APKs built from another branch read the data of that
> branch.

### Sources

Checked on 2026-11-12. The scraper re-reads `robots.txt` on every run and skips a source that
disallows it, is unreachable, or does not return a real feed. Edit
[`scraper/sources.json`](scraper/sources.json) to change the list.

| Source | Status | Why |
| --- | --- | --- |
| Pocket Gamer | used | RSS feed, robots.txt allows all |
| Gematsu | used | RSS feed, allowed (one request per run; crawl-delay respected) |
| QooApp News | used | RSS feed (Chinese edition; the English feed URL is empty) |
| 4Gamer (smartphone) | used | RSS 1.0 feed, nothing disallowed |
| Ruliweb | used | RSS feed, path not disallowed |
| Inven | used | RSS feed, path not disallowed |
| GamerBraves | skipped automatically | robots.txt and feed answer with a bot challenge (HTTP 403) |
| Famitsu App | off | no feed (URL returns the HTML homepage) |
| GameWith | off | no feed found |
| TapTap | off | no feed |
| Google Play pages | used | `/store/apps/details` is allowed; read per region for pre-registration status and cover image |
| App Store pages | used | `/{country}/app/…` is allowed; read for pre-order "Expected" dates when a game has an `appStore` link |

Only headlines and links are stored, never article text.

## The app

| Screen | What it shows |
| --- | --- |
| Home | Releasing soon, Just launched, Recently announced, latest headlines |
| Seasonal | Games by season or month (plus a TBA tab), filtered by region, platform, genre, status |
| Search | Text search with filters, sorted by popularity, release date or title |
| Game page | Cover, info table, synopsis, per-region release table, countdown, links, related news |
| My List | Playing, Completed, On Hold, Dropped, Plan to Play; score, dates, region/server, play time, spending, notes |
| Profile | Counts per status, mean score, days played, spending, score distribution, genre breakdown |
| Settings | Default region, theme, notifications, 18+ toggle, export/import of your list |

- **Notifications:** games on *Plan to Play* alert you on release day and when pre-registration
  opens. The check runs about every six hours in the background and when you open the app.
- **Offline:** everything is cached in a local database; the APK also ships with a copy of
  `games.json` for the first launch.
- **18+ games** are hidden by default. When enabled, their covers stay blurred until tapped.
- **Your list stays on your phone.** Use export/import to back it up or move it.

## Project layout

```
app/                    Android app (Kotlin, Jetpack Compose, Room, Retrofit, Coil, WorkManager)
data/games.json         the database
data/news.json          headlines (written by the scraper)
data/pending.json       review queue (written by the scraper, edited by you)
data/overrides.json     corrections that always win
scraper/                Python scraper, data validator and their tests
.github/workflows/      build.yml (APK on every push) and scrape.yml (every 6 hours)
```

Tests run in the build workflow: the JSON parser and date logic, the scraper's merge logic,
a validator for the data files, and a walkthrough that drives the real app through every
screen (its screenshots are attached to each run as the `screenshots` artifact).

To build locally: `./gradlew assembleDebug`. To run the scraper: `pip install -r
scraper/requirements.txt && python scraper/scrape.py`.

Release information is maintained by hand and by an automated scraper and can be wrong.
Game names and cover images belong to their respective owners.
