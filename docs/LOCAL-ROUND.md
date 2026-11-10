# Local playable round

One round is one clue. The default catalog UI now groups five rounds into a
recoverable game; see [Stage 2A game behavior, API and verification](LOCAL-GAME.md).
This document retains the underlying round contract, standalone compatibility
and earlier verification records. Selectors are locked for the full game in
the current default UI, including pauses between scored rounds.

## Architecture and boundaries

The JDK HTTP server serves five allowlisted player assets and a small same-origin JSON
API. No frontend framework, database, new runtime dependency or external service
is needed. The existing engine, CLI, benchmark and archived indexes are unchanged
by this milestone. The coursework archive was not modified.

`GameService` owns sessions, private packs, computer commitments and scoring.
The default server loads `data/game.json` and its validated, immutable
`PackCatalog`. `CollectionIndex` receives only the collection map and routes
`ScopedComputer(scope, category, clue)` to the pinned source index. The scope
contains only a collection ID, version and digest. Neither boundary receives
aliases, support offsets, review notes, answerSourceId, player answer or round.
The existing `QueryEngine.ask(category + " " + clue, 10)` commits its first result.
The older `Computer(category, clue)` adapter and legacy game arguments still work.

Collections, private clue packs and operator settings are separate versioned
files. Each collection is indexed once and can serve several packs/difficulties.
This avoids duplicate indexes and clue-specific answer shortlists. Per-topic
source isolation is intentional: new starter topics each search 15 excerpt
documents, not the whole coursework corpus. Index metadata and returned titles
are checked against the allowed collection; missing/stale indexes fail closed.
Indexes are opened read-only per search and closed afterward, prioritizing simple
cleanup over high-throughput pooling. No new runtime dependencies were added.

Restart to load changed catalog data; no hot reload is implemented. A round holds
the loaded pack/clue object and immutable topic, difficulty, pack version and
source scope. Server-side shuffled cycles are keyed by pack/version/difficulty,
consume one clue when starting, and require explicit replay after exhaustion.
Reloading an active round neither advances the cycle nor re-runs the search.

State transitions are `THINKING -> READY -> FINALIZED`. A single search worker
keeps the historical NLP stack serialized. A 15-second deadline commits a
no-answer outcome; late results cannot replace it. Exceptions and malformed
engine responses become `unavailable`, empty results become `no-match`, and a
deadline becomes `timeout`. The player can still earn points in all three cases.

All state changes and scoring are synchronized. A finalized round has one
immutable receipt containing guesses, awarded points and totals at that moment.
Every subsequent valid submission to that round returns that same receipt, even
with a different guess. Totals change only once. Old receipts remain unchanged
after later rounds; `/api/session` returns the current round for current totals.

The existing `Evaluation.accepts` supplies alias, case and whitespace matching.
Both participants receive 100 points for an accepted answer or zero otherwise.
No fuzzy matching, handicap or different difficulty multiplier is introduced.

## Local API

Use the exact printed origin, normally `http://127.0.0.1:8765`. The server binds
only to loopback and validates Host, Origin and cross-site fetch metadata. It
does not provide CORS. JSON POST requests require `X-ClueLab-Request: 1` and
`Content-Type: application/json`. Bodies are limited to 4096 bytes and strict
UTF-8. The server, not the browser, validates every guess and transition.

| Request | Body | Response |
| --- | --- | --- |
| `GET /api/session` | None | Creates/resumes an HttpOnly, SameSite=Strict session; safe catalog/counts, current game/session scores and compatibility round or null. See the game API for new fields. |
| `POST /api/rounds` | `{"topicId":"music","difficulty":"easy","packVersion":"1.0.0","replay":false}` | Standalone compatibility: starts/resumes a round with the same selection. Rejected while a five-clue game is active. |
| `GET /api/rounds/{id}` | None | The owned round's current safe view or finalized receipt. |
| `POST /api/rounds/{id}/answer` | `{"answer":"player guess"}` | Finalizes once, or returns the original receipt. |

Before reveal, the exact catalog-mode round fields are `id`, `number`, `difficulty`,
`state`, `category`, `clue`, `hint`, `playerTotal`, `computerTotal`, `topicId`,
`topicLabel`, `packId`, `packVersion`, `collectionId`, `collectionVersion`.
Rounds inside a game also include `gameId`, `clueNumber`, `gamePlayerTotal` and
`gameComputerTotal`; the wrapper game advertises progress without future clues.
No clue ID, answer source ID, aliases, support ranges, candidate, score, timing,
accepted answer, engine failure detail or evidence is returned. IDs are random
UUIDs and are scoped to the session cookie. A foreign/unknown round returns 404;
an absent/expired session returns 410. Premature submission returns 409, invalid
input 400, oversized bodies 413, wrong content type 415, and cross-origin or
missing request-header violations 403. Unsupported paths are not filesystem
lookups. Answer-key files, including Easy variants, are never served.

The catalog allowlist contains labels/descriptions, language, IDs/versions, review
status, document counts and per-difficulty `eligible`, `remaining`, `exhausted`
and `cycle`. It does not serialize pack objects, source titles or private review
notes. `replay: true` is valid only for an exhausted selection; it starts a new
shuffled cycle. No unavailable choice silently falls back to another topic.

The finalized receipt adds `playerAnswer`, `computerAnswer`, `playerPoints`,
`computerPoints`, `acceptedAnswers`, `computerStatus`, `responseTimeMs`, `outcome`,
`clueId`, `evidence` and `curatedEvidence`. `evidence` is the actual opponent's
retrieved passage (`kind: opponent-retrieved`); it is null when there is no answer.
`curatedEvidence` is the pack author's selected supporting passage
(`kind: curated-answer-support`), available even if the computer fails or guesses
wrong. It never masquerades as the computer's search result. Each uses explicit
source title, passage, source/reference/publisher, URL, attribution and available
snapshot/revision metadata, never absolute index paths. Source links use HTTPS.
Raw scores are not confidence probabilities and excerpts do not prove correctness.

In legacy mode, round creation retains the old `{ "difficulty": "easy" }` or
`standard` body, old pre-reveal fields and Wikipedia evidence adapter. The original
100-question evaluator remains a separate CLI, not a browser endpoint.

Responses are non-cacheable and use a restrictive Content Security Policy,
no-referrer and nosniff headers. The UI uses text nodes, not HTML interpolation.
The local HTTP cookie is intentionally not Secure; this is not an HTTPS service.

## Difficulty and interaction

General Knowledge, World History, Geography and Music each have 15 clues, five
per Easy/Standard/Hard level. The default is General Knowledge/Easy. These are
provisional editorial labels pending player testing. The migrated Original Demo
has the same eight Easy variants and eight Standard clues, sharing eight source
adaptations; Hard is unavailable. Its legacy files remain unchanged.

Hints are separate public fields in catalog mode. The migrated demo preserves
the original ` Hint: ` retrieval input exactly. The UI shows a collapsed,
keyboard-operable Show Hint disclosure beneath the question; the computer
receives the public hint even if the player leaves it closed. Showing or hiding
it never re-runs retrieval or alters points.

Each topic/difficulty cycles independently in shuffled order. Standalone play
retains its remaining count and explicit Replay This Selection state between
rounds. In the default five-clue UI, selection/reset is available only between
games and requires five unused clues; see LOCAL-GAME.md. Each picker is a shared custom
select-only combobox with a rotating
chevron, visible focus and an announced active option. Arrow keys, Home/End,
type-ahead, Enter/Space, Tab and Escape are supported; clicking or tapping an
option also works. Body copy stays sentence case; headings and control labels
use title case. Original submitted guesses and source titles are not rewritten.

The interface includes connection/loading states, inline validation, clear
failure outcomes, evidence after reveal and a Reconnect action. A reload or
reconnect retrieves server state rather than trusting browser-held scores. A
lost submission response is safe to recover or retry. Layouts adapt to narrow
screens and respect reduced-motion preferences.

## Stage 1 Verification (2026-11-04)

Actually rerun on Temurin JDK 21.0.13 and Maven 3.9.16:

- Baseline `mvn -o clean verify`: 40 tests passed before changes.
- Final `mvn -o clean verify`: 83 tests passed, zero failures/errors/skips; jar built.
- `node --check src/main/resources/web/app.js`: passed. No frontend build needed.
- Pack validation with `--indexes`, indexing/reuse and independent evaluation
  all succeeded. Five collections contain 68 documents; 76 clues include the
  preserved 16-clue demo. See [results](RESULTS.md#stage-1-topic-packs-small-development-sets).
- New regressions cover invalid schemas/JSON/UTF-8/IDs/paths/aliases/references,
  answer-type and support validation, exact legacy migration, topic isolation,
  source-only index fields, private API allowlists, fixed choices, no-repeat and
  explicit replay, unavailable combinations, engine failure and duplicate scoring.
  A sixth topic/pack was loaded and played using data/catalog changes only.
- Chromium desktop (1280px), 390px touch emulation and 320px layout were checked.
  Topic/difficulty selection, arrow/Home/End/type-ahead/Enter/Tab/Escape behavior,
  hint disclosure, validation, correct/incorrect/alias scoring and attribution
  worked. No horizontal overflow was observed.
- All five Music/Easy clues appeared once, exhaustion was explicit, a start
  without replay returned 409, and explicit replay started cycle two. Original
  Demo/Hard showed zero availability and disabled starting. Changing the active
  topic returned 409. An alias (`Armstrong`) earned 100; a changed duplicate guess
  returned the identical receipt and totals, including after reload.
- Active and completed reload recovery worked. A response deliberately dropped
  after server-side scoring recovered the original finalized guess via Reconnect.
  A real missing-index server scored Moon 100/0, reported `unavailable`, hid
  opponent evidence and still showed separately labeled curated support.
- Browser console entries were only the intentional 409 and dropped-response
  failures. Physical-device, screen-reader and cross-browser audits remain open.
  Preview servers were stopped after testing.

## Historical Verification (2026-10-29)

Measured locally 2026-10-29 on Temurin JDK 21.0.13 and Maven 3.9.16:

- 40 JUnit tests: the original 23 checks plus 17 game/HTTP/integration checks.
- The original demo still passes all 12 processing/scorer combinations.
- A fresh temporary eight-document plain/JM index completed all eight guided
  rounds with accepted aliases, evidence and 100 points per participant.
- Evaluating the guided pack against the existing freshly rebuilt demo index
  gave 8/8 first, 8/8 Hit@10, MRR@10 1.0000. This is a small fixture result, not
  a new archived-index or full-corpus measurement. Guided question SHA-256:
  `f4b143d342f68096f2f912ee270c254e98ae49b9fecfd3418ad73dab9054099b`.
- Browser checks used a real server and index: correct and incorrect guesses,
  BMI aliases with case/whitespace, exact duplicate receipts, stable totals,
  private pre-reveal field allowlists, reload and offline/reconnect. Deliberately
  dropping a response after the server scored a submission also recovered the
  original guess and totals without awarding points twice.
- A real missing-index server scored the player 100 and computer zero, reported
  `unavailable`, and showed neither fabricated evidence nor private paths.
- The custom picker and hint disclosure were checked with keyboard and touch
  emulation, including selection, cancellation, type-ahead and chevron rotation.
  Desktop and 390/320px layouts had no horizontal overflow. Browser scripts
  reported no uncaught errors. Real screen-reader and cross-browser testing
  remain outstanding; touch emulation is not a physical-device audit.

Re-run `mvn clean verify` and `node --check src/main/resources/web/app.js`
(Node is optional and used only for this syntax check). The automated tests
include concurrency, early submissions, foreign IDs, malformed responses,
timeouts/late results and private-asset exposure checks.

### Historical Stage 1 Browser Recheck Recipe

The default UI now uses the five-clue flow documented in LOCAL-GAME.md. This
recipe preserves the earlier single-clue checks and their legacy context.

1. Start the catalog server as shown in the README with a fresh browser session.
   General Knowledge/Easy should be selected. Use Space, arrows, type-ahead,
   Home/End, Escape, Enter and Tab on both menus, then repeat by pointer/touch.
2. Choose Original Demo/Hard and verify explicit unavailability. Select a playable
   choice and start. No guesses or support evidence should appear; choices lock.
   Reload and verify the same round. Hints, when present, start collapsed.
3. Complete Music/Easy's five clues with a mixture of correct, incorrect and
   alias answers (e.g. Armstrong for Louis Armstrong). Verify 100/0 scoring, no
   repeated clue IDs, remaining counts, explicit exhaustion and replay. Order is
   shuffled; do not assume a fixed first clue. Check both evidence sections.
4. In browser developer tools, resend an answer POST with a different guess.
   Confirm an identical receipt and unchanged totals. Reload and confirm the
   same completed round. Interrupt connectivity and use Reconnect to recover.
5. For a catalog engine-failure check, use a temporary game config referencing the
   same catalog but a nonexistent index base and port 8766. Do not index it.
   Open it in an isolated browser context: cookies are host-scoped, not port-scoped,
   so two local servers otherwise overwrite each other's session cookie. The
   earlier legacy smoke command is also still supported:

```sh
mvn -q exec:java -Dexec.mainClass=main.GameServer \
  -Dexec.args='indexes/missing-test 1 4 data/demo/questions.txt 8766'
```

Submit British Standards. The player earns 100, the computer zero, and the
page explains the unavailable engine. This command must not create an index.
Stop both servers with Ctrl+C after checking.

## Limits and next milestone

There is no account login, timer, board or public leaderboard. The later
[durable-game milestone](DURABLE-GAMES.md) adds bounded private local storage.
Sessions expire two hours after creation, have at most 64 rounds, and are capped
at 32 sessions per process. Five-clue games additionally have a 12-game cap and
reserve capacity for all five rounds at creation. Restart now preserves unexpired saved games. Another browser/tab using
the same cookie shares the same session. UUID ownership is a local prototype
boundary, not protection against a person who can read this checkout or its keys.

Search and HTTP queues are bounded, but an uninterruptible engine call may need
a process restart. The basic JDK HTTP server is not hardened for hostile traffic;
slow-client timeouts, rate limits, auth, durable idempotency and observability
need production work. The default dependencies have since been modernized with
isolated historical reproduction; see [migration and retained warnings](MODERNIZATION.md).
**Ongoing advisory/security review is required before any public deployment**;
modernization alone is not release readiness.
Also review clue/source licensing and implement deployment-specific security.

The new packs are assistant source-checked, not independently human-approved.
They use short, deliberately answer-aligned excerpts and become familiar on
replay. See [review limitations](PACK-REVIEW.md); structural validation cannot
establish factual truth, licensing or difficulty. Unknown revisions remain
explicitly unknown. An unavailable or draft selection never receives filler.

Stage 2A now implements five-clue games with server-owned progress and recoverable
final results. Next is Stage 2B human content review and difficulty playtesting;
use [the checklist](PLAYTEST-CHECKLIST.md). A reviewed niche-topic pilot remains
later work. There is no automatic generation, custom-topic ingestion, answer-span
extraction or full game board in the current prototype.
