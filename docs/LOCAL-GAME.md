# Local Five-Clue Games (Stage 2A)

Stage 5A adds [bounded player topic requests](PLAYER-TOPIC-REQUESTS.md), separate
from games. Its private seven-day owner recovery survives player restart;
[durable player games](DURABLE-GAMES.md) now do too, within their separate two-hour lifetime. Ready and Play verify the exact loaded
catalog, requested pack/source versions, eligibility and indexes. Requests never
automatically author, activate, restart, change pinned games or reset cycles.
Legacy five/six-argument single-clue mode does not expose request routes.

For offline/admin content changes, see [controlled pack activation](PACK-ACTIVATION.md).
The running server never hot-reloads the catalog. Activation or rollback changes
the catalog for the next startup; unexpired saved games retain pinned content
and recovery receipts across restart. The isolated Jovian rehearsal did not change the normal
catalog. Source-checked activation is not independent human approval.

Stage 2A adds a complete game around the existing one-clue round. A game pins
one topic, one difficulty, one pack/version, its source collection/version/digest
and five eligible clues on the server. The default catalog browser experience is
now five-clue play. Human content review and difficulty calibration are the
separate, pending Stage 2B workstream; see [the practical checklist](PLAYTEST-CHECKLIST.md).

The current runtime requires JDK 21/Maven 3.9.x and uses Lucene 10.6.0,
CoreNLP 4.6.0 and schema-3 indexes in a separate runtime namespace. See
[modernization and migration](MODERNIZATION.md). The dated Stage 2A verification
below remains historical; [current engineering verification](RETRIEVAL-EXPERIMENT.md#final-cross-milestone-verification)
reports 120 passing tests and fresh game/recovery/browser checks. Human review
and calibration remain deferred, each 0/60 starter clues.

## Launch

From the repository root, with JDK 21 and Maven 3.9.x:

```sh
mvn clean verify
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index data/game.json'
mvn -q exec:java -Dexec.mainClass=main.GameServer
```

Open `http://127.0.0.1:8765/`. Add `-o` to Maven when dependencies are cached.
The existing `data/game.json` still configures the catalog, indexes, scorer and
port; another config can be the server's single argument. Matching source indexes
are reused. Starting or playing a game never rebuilds an index.

Choose a topic and difficulty, then **Start Five-Clue Game**. Each round waits
for the computer commitment, accepts one human answer, and reveals both scores
and evidence. **Next Clue** opens the next round only after scoring. Scoring the
fifth clue completes the game automatically; there is no sixth clue or separate
completion request. Each accepted answer still earns 100 points, with a maximum
of 500 per participant in one game.

The main scores belong to the current game. The smaller **Session Totals** line
includes previous games and any standalone rounds in the same session. A finished
game shows five outcome receipts, a missed-clue filter, answers, public hints,
engine failure explanations and separate retrieved/curated evidence. Review
details are keyboard-operable disclosures. **Previous Games** opens retained
completed receipts between games. Reload recovers the current game, including its
summary, rather than reconstructing scores in the browser.

For Stage 2B, finalized rounds show their clue/round/game IDs and pinned
pack/source versions. **Save Clue Result**, **Save Game Results** and **Save
Previous Game Results** download an already-revealed server snapshot in a local
schema-1 `cluelab-review-receipt` JSON wrapper. This uses no new endpoint, storage
service, telemetry or dependency. Active games cannot be exported as completed
results; future clues/answers remain private. Exports preserve server awards, not
reviewer judgments, and cannot be imported as saves. Restart recovery uses private server state and the browser cookie, not an export.
Hint use, observation date, familiarity and perceived difficulty are manual
observations in the [existing checklist and forms](PLAYTEST-CHECKLIST.md).

## Ownership, Selection and Transitions

`GameService` remains the single scoring authority. Its synchronized operations
own both game and round state; no separate score store or frontend framework was
introduced. Reserving five clues at creation makes capacity and content failures
occur before the first clue, not halfway through a game. The selection is an
immutable list of private clue references, not five pre-disclosed round objects.
Only the opened round has a public UUID or enters retrieval.

```text
Game ACTIVE:
  Clue 1: THINKING -> READY -> FINALIZED -> Next Clue
  Clue 2: THINKING -> READY -> FINALIZED -> Next Clue
  Clue 3: THINKING -> READY -> FINALIZED -> Next Clue
  Clue 4: THINKING -> READY -> FINALIZED -> Next Clue
  Clue 5: THINKING -> READY -> FINALIZED -> Game COMPLETED
```

Topic/difficulty/version cannot change while the game is active, including the
pause after scoring and before Next Clue. Standalone round starts are also blocked
during a game. An already-open standalone round must finish before starting a
game. The old answer endpoint shares the scoring operation, so it cannot bypass
game totals or completion.

The existing shuffled cycles remain scoped by pack/version/difficulty. A game
reserves five unused clues together, with no duplicate clue IDs within the game.
All new starter selections contain exactly five clues per level, so a finished
game exhausts that selection. Original Demo has eight per supported level and
leaves three after one game. A second game cannot silently use those three plus
repeats. **Reset and Replay Five Clues** explicitly replaces the partial/exhausted
cycle with a new shuffle; unused leftovers are released and earlier clues may
reappear. Previous game receipts are retained.

Fewer than five eligible clues makes a selection unavailable, even with reset.
Fewer than five unused clues requires explicit reset. A reset is rejected while
at least five unused clues remain. Invalid topics, difficulties, pack versions
and unsupported request fields never select substitute content. The loader still
allows structurally valid small/draft packs; gameplay advertises their real counts
and applies this five-clue requirement.

## Independence and Evidence

Each round independently invokes the existing scoped computer with only
`(source scope, category, public clue/hint)`. Opening the next round is the only
operation that starts its search. Future clues, aliases, answer keys, support
offsets, player guesses, game scores and retry IDs never enter retrieval. The
source index is the pinned pack's collection across all difficulties, not a
shortlist of the five expected answers.

Hints remain public to the computer even when the player's Show Hint disclosure
stays closed. Reveal/hide does not run another search or change scoring. Engine
no-match, failure and timeout outcomes still permit human scoring and advancement;
late results cannot replace the committed outcome. The final review distinguishes
`opponent-retrieved` evidence from `curated-answer-support`, including when the
computer had no answer. The game does not manufacture retrieval evidence from
the curated support passage.

## Game API

The [existing HTTP boundaries](LOCAL-ROUND.md#local-api) still apply: loopback-only,
same-origin JSON, HttpOnly/SameSite cookie, no-store responses, fixed asset
allowlist, strict UTF-8 and 4096-byte request limit. JSON must be one object with
no duplicate property names. Every game/round is owned by the session cookie.
Unknown/foreign IDs return 404; an expired/missing session returns 410. Invalid
input returns 400 and invalid transitions/capacity/content return 409.

| Request | Body | Effect |
| --- | --- | --- |
| `GET /api/session` | None | Resume/create a session; advertise safe catalog counts, `gameLength: 5`, current `game`, `sessionTotals`, completed-game metadata and the compatibility `round` view. |
| `POST /api/games` | See below | Reserve five clues and open the first round exactly once per start intention. |
| `GET /api/games/{gameId}` | None | Owned active snapshot or immutable completed receipt. |
| `POST /api/games/{gameId}/answer` | `{"roundId":"opened-round-uuid","answer":"guess"}` | Score that owned round once; return the current view of that game. |
| `POST /api/games/{gameId}/advance` | `{"roundId":"round-being-left-uuid"}` | Open one next round only after the named current round is finalized. |

Start example (generate a new canonical lowercase UUID for each new intention):

```json
{
  "requestId": "5bc51454-d06c-4d1a-820c-83c12fb784a1",
  "topicId": "music",
  "difficulty": "easy",
  "packVersion": "1.0.0",
  "replay": false
}
```

Retry the same start using the same request ID and selection. It refers to the
original game even after completion and later replays. Reusing that ID with
different arguments is a conflict. A different start intention during an active
game is rejected; GET session recovers it. A new replay uses a new request ID
and `replay: true`, subject to the explicit reset rules above.

Active game fields are `id`, `number`, `revision`, `state`, `topicId`, `topicLabel`,
`difficulty`, `packId`, `packVersion`, `collectionId`, `collectionVersion`, `length`,
`clueNumber`, `completedRounds`, `playerScore`, `computerScore`, and the current
safe `round`. Before scoring that round has the existing safe fields plus
`gameId`, `clueNumber`, `gamePlayerTotal`, `gameComputerTotal`. It contains no
candidate, accepted answers, clue ID, source support or future clue list. Current
game scores reveal only already-finalized rounds.

Completion stores one immutable game receipt with `round: null`, five finalized
`rounds`, fixed scores and `outcome`. Every finalized round retains its original
guess, aliases, points, source evidence and totals at scoring time. The existing
`GET /api/rounds/{roundId}` and answer endpoint continue returning that original
round receipt. No later game changes those historical totals.

Game action replies are snapshots of the addressed game: a duplicate answer or
advance can show its later current round, but never changes the earlier receipt
or opens an extra clue. An advancement that names an earlier opened round simply
returns the current snapshot. A round from another game is rejected. Completed
game replies are identical, including after later replays. Old requests never
change the session's current-game pointer.

## Recovery and Concurrent Requests

Starts, scoring, advancement and completion run under the existing service lock.
Twenty simultaneous retries are covered by tests; there is no check-then-act gap
between selection, reservation and opening a round. Advancement uses the previous
round ID as its precondition, rather than an unqualified "increment" command.
The fifth score and final receipt are committed in the same server operation.

After an interrupted/unreadable response, the UI blocks further mutations and
offers **Reconnect**. GET session recovers whichever server operation completed;
reloading performs the same recovery. It does not submit the text currently in
the input or guess whether a lost request succeeded. The client ignores stale
polling responses and older game revisions so they cannot replace a newer view.
Active and completed reloads need only the session cookie, not local score data.

## Compatibility and Limits

The original CLI/100-question format, pack format, aliases, source snapshots,
pack review statuses and historical measurement files are preserved. Runtime
dependencies and index locations have since been modernized; see the migration
guide above for measured ranking differences. The existing `POST /api/rounds`, GET and answer contracts remain
available for standalone clients outside an active game. A catalog UI can finish
a recovered standalone round before offering a full game.

The five/six-argument legacy server invocation still provides single-clue play,
its original Easy/Standard selection and original Wikipedia attribution:

```sh
mvn -q compile exec:java -Dexec.mainClass=main.GameServer \
  -Dexec.args='indexes/modern-demo 1 4 data/demo/questions.txt 8765 data/demo/easy-questions.txt'
```

Build that fresh modern index using the README's corpus command if needed.
Legacy file-based mode does not advertise `gameLength`; game creation returns
400 and the browser keeps the standalone round flow.

Storage is now [durable and private](DURABLE-GAMES.md): at most 32 sessions per target, 12 games per session and
64 opened rounds shared with standalone play. Starting a game requires room for
all five rounds. There is no mid-game eviction: every receipt and successful
start intention remains until absolute session expiry. A session
expires two hours after creation, regardless of activity or restart. Restart preserves
unexpired games, scores, recovery and retry IDs; indexes are unchanged. Tabs sharing a
cookie share one active game. No game-abandon action is implemented: finish the
active game or wait for expiry. Restart is not an abandon/reset action.

The single search worker, bounded queues and 15-second deadline are retained.
An uninterruptible engine call can still require a process restart. Public
deployment continues to require ongoing advisory review and the existing
deployment/rights/accessibility work. Stage 2A originally did not add durable saves; the later durable-game milestone does. There are still no player
accounts, automatic playtest telemetry, new answer matching, automatic generation,
new opponents or a niche-topic pack.

## Stage 2A Verification

Verified locally on 2026-11-04, Temurin JDK 21.0.13 and Maven 3.9.16. The baseline
was established with a fresh `mvn -o clean verify`: 83 tests passed before edits.

- Final `mvn -o clean verify`: **105 tests passed**, zero failures/errors/skips;
  the jar packaged successfully. This includes all 83 existing tests, 19 new
  game-service checks and three additional HTTP checks.
- `node --check src/main/resources/web/app.js` and `git diff --check`: passed.
- `main.PackTool validate data/game.json --indexes`: passed for all five source
  collections (68 documents, 76 clues including the preserved demo).
- SHA-256 comparisons confirmed unchanged historical questions/sample/PDFs,
  recorded results JSON/Markdown, catalog, private packs and collection metadata.
  Snapshot fingerprints also passed validation. The coursework archive was not
  written to. Recorded retrieval measurements remain unchanged.
- All three owned preview servers were stopped after browser checks. A missing
  index was not created by the failure run. Temporary browser artifacts/config
  were under ignored `target/` and cleared by the final clean build.

The new service tests exercise exact five-round completion, game/session score
isolation, fixed selection, short packs, insufficient leftovers, 20 concurrent
starts/advancements/submissions, replay, old requests, source privacy, per-round
retrieval, cross-session ownership, engine failures/no-match/late timeout results,
session expiry, capacity and retained receipts. HTTP tests cover the complete
game flow, recovery, duplicate requests, strict bodies, ownership and legacy mode.

Browser QA used scripted Chromium interaction, not human playtesting. Desktop
1280px, 390px touch emulation and 320px layout were checked. A Music/Easy game
completed five distinct clues with a mix of correct, wrong and alias guesses;
the missed filter showed exactly two misses. Keyboard pickers, hint disclosure,
focus, per-round evidence, score separation, previous-game review and replay
worked. A new game started at zero while session totals retained prior points.

Responses were deliberately dropped after server-side start, answer, advance and
fifth-answer operations. Reconnect recovered the existing game/round or immutable
final summary; duplicate requests did not change guesses, points or progress.
Active and completed reload recovery was also verified. All observed main-page
console errors were the four intentionally aborted network responses.

A touch-driven Original Demo game completed five unique clues, then accurately
showed three unused clues. A non-reset start returned 409; Hard/zero availability
disabled starting. A separate real missing-index server completed all five
rounds with player scoring intact, zero computer points, null opponent evidence,
curated support and an unchanged summary after reload. The legacy server still
scored a whitespace/case-normalized BMI alias and recovered its round on reload.
These are software checks, not new retrieval benchmarks or player-study results.

Physical-device, screen-reader and cross-browser audits remain open. Stage 2B
requires actual reviewers and players; no approvals or study results are claimed.
