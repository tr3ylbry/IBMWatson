# Durable Local Player Games

Implemented 2026-11-07. This supersedes earlier **memory-only** player restart
limits, not dated test or benchmark evidence. Human review/calibration remain
deferred. Live assistance stays disabled; no provider calls or spending occurred.

## Launch and Recover

From the repository root, with JDK 21/Maven 3.9.x:

```sh
env CLUELAB_ASSISTANCE_ENABLED=false mvn -o clean verify
env CLUELAB_ASSISTANCE_ENABLED=false mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
env CLUELAB_ASSISTANCE_ENABLED=false mvn -o -q exec:java -Dexec.mainClass=main.GameServer
```

If indexes are missing, explicitly build them with `main.PackTool index`; play
never builds them. Omit Maven `-o` only when dependencies need installation.
Use **http://127.0.0.1:8765/**. After stopping/restarting the same configuration,
keep the same browser cookies and use **Reconnect** or reload. The saved game,
scores, current clue, revealed evidence and previous receipts return. Stop only
your own player process. No workshop restart, key setup or paid call is needed.

The standard no/one-argument server and five/six-argument legacy server now use
durable storage automatically. Direct `GameService` constructors remain ephemeral
for existing embedders/tests; the command-line server attaches storage before
opening HTTP. Legacy play retains its sequential standalone cursor and pinned
opened question/commitment/receipt, not a five-clue catalog game.

**First upgrade:** previously memory-only sessions cannot be recovered. An old
cookie, unknown identity or expired session returns Gone (410), never old scores
under new ownership. **Start a New Game Session** asks for explicit confirmation;
`POST /api/session/identity {"confirmed":true}` creates a new identity, not a
replacement under an old start/Play retry. Clearing cookies loses access, even
if files remain. There is no account, secret URL or cross-device recovery.
Downloads remain read-only review copies, never authoritative/importable saves.

F2 recovery-parser follow-up (2026-11-09): the target's game cookie accepts a
canonical UUID unquoted or enclosed in a complete pair of double quotes. Empty,
single/unmatched-quote, malformed and duplicate values return 410 without
issuing an identity, awarding points or changing a save. Unknown/expired durable
identities also return 410. `GET /api/session` without a game cookie still creates
an initial session; cookie-free game reads/mutations do not authorize an existing
game. After rejection, only the explicit confirmed identity action above bypasses
the rejected game cookie. It creates a separate session and preserves existing
owners' games, never transfers or resets them. `GameRecoveryCookieTest` exercises
this contract over real loopback HTTP against an isolated durable target.

### Recovery Focus and Evidence Navigation

Focused client follow-up 2026-11-07: a failed deliberate game action focuses the
visible Reconnect/new-session action. Recovery focuses an active answer or thinking
clue, a finalized outcome (followed by Next Clue), or the completed summary.
Routine polling does not move focus or overwrite typing. The live status includes
"Game recovered." Backend IDs, transitions, scoring and persisted DTOs did not
change; old receipts remain identical. Source/history/observed-revision/license
navigation is derived at presentation time from saved metadata, never guessed
from an answer title or written back to a receipt. Unavailable revisions and
incorrect saved source associations are not silently repaired.

`tools/player-ui.browser.js` reproduces lost committed start/answer/advance/
completion/replay responses using only a fresh isolated QA save root on8793.
`tools/player-presentation.test.mjs` covers focus policy and safe metadata links
without a browser or provider. See the [focused evidence](engineering/2026-11-07-focused-ui-verification.json).

## Storage and Ownership

Default private base: ignored `drafts/local/player-games`, outside `target/`.
`mvn clean` does not erase it. To choose another private location, set
`CLUELAB_GAME_STORE_ROOT` only for the player process, to a subdirectory below
this clone's ignored `drafts/local/` workspace. Other production locations are
rejected so saves cannot be packaged as resources or mixed into archives,
indexes or benchmarks. Do not choose a shared location. Paths containing parent
traversal, any `target` component, or symlinks are rejected; POSIX is required.

Each target has a subdirectory keyed by SHA-256 of the canonical configuration
path, **not** catalog version/content hash. Activation/rollback at that path
therefore retain the target's games. Moving a configuration is a new target;
do not copy/edit saves to merge identities. Legacy namespaces include canonical
question paths, index base/type and scorer. Catalog targets also use distinct
game-cookie names because browser cookies are not port-scoped. Workshop/request
cookies remain separate. A pre-upgrade `cluelab` cookie gets an explicit upgrade
notice until a new target-specific identity has been deliberately created.

`state.json` is a schema-1 envelope with target, SHA-256 corruption checksum and
explicit data DTOs. Source collections/packs are deduplicated immutable pins;
sessions contain absolute timestamps, full cycles/order/cursors, selected clues,
opened rounds, committed candidate/evidence/timing/status, private keys, scores,
receipts, retry IDs and current pointers. No Java serialization, tasks, executors,
provider/workshop credentials or raw browser bearer cookies are stored. Owner
lookup uses a cookie hash; game/round/request UUIDs alone grant no access.
Checksums detect accidental corruption, not malicious edits by the OS owner.

Directories are owner-only (0700), files/lock 0600. An exclusive file lock lasts
for the whole player process; another writer fails startup. No player API or
asset route can read these files. This is trusted single-user local storage,
not encryption, public authentication or protection against same-user processes.

| Limit | Behavior |
| --- | --- |
| Sessions | 32 per target; two hours **absolute** from creation. Reads/restart do not renew expiry. Cookie Max-Age uses remaining time. |
| Games / rounds | 12 games and 64 opened rounds per session, shared with standalone play; a new game reserves five free slots. No live eviction. |
| Content pins | 128 pack/collection versions per target, at most 4,096 sources or clues per saved collection/pack; admission fails closed at capacity. |
| Save size | 32 MiB committed JSON plus at most one 32 MiB staging file; depth 32 / two million JSON events. Large pin sets can hit this before count limits. |
| Retention | Authorization expires immediately at two hours. Expired sessions are removed on startup/new-session creation; inactive files can remain until that maintenance. This is not timed secure erasure. |
| Request ownership | Separate seven-day ledger and limits, not extended or shortened by game restart. |

Do not delete live locks, hand-edit scores, publish saves or use review downloads
as backups. For deliberate reset, stop the player first and privately archive the
**whole target directory**. Old game cookies then return 410; a new session needs
explicit confirmation. Preserve the request ledger separately. Never reset that
ledger just to bypass an old intention or provider/workshop limits.

## Commit and Recovery Policy

Every mutation runs under the game-service lock. The snapshot is written to a
same-directory private staging file, fully written and `force(true)`-flushed,
then atomically renamed over `state.json`. No non-atomic fallback is used.
Starts reserve five clues and save THINKING before search/acknowledgement;
computer READY commitments, scoring, advancement, completion and replay also
save before their result can be observed. Fifth-clue awards and summary share
one commit. Retries retain the same IDs/guesses/receipts and cannot award twice.

Crash guarantee tested: graceful stop and abrupt process termination on the
same POSIX filesystem recover the last atomic snapshot. A response may be lost
after that commit; reconnect reads the original result. Partial staging bytes
are never promoted and are discarded under the writer lock at next startup.
This is **not tested power-loss, disk-loss, network-filesystem or distributed
durability**; directory metadata is not explicitly fsynced. Corrupt/oversized,
unknown-schema, incompatible, invalid-reference or unreadable saves fail startup
clearly, not reset. Preserve the files for investigation/compatible recovery.
Existing state must remain 0600; recovery refuses changed permissions rather than
silently repairing an unreadable or non-private save.

A write failure blocks **all subsequent game reads/mutations** until restart.
Thus no in-memory scores ahead of the durable snapshot can escape. An error
after rename is intentionally uncertain to the client; restart recovers the
commit. Do not blindly resubmit altered guesses. This fail-closed policy favors
consistent local results over continuing to play with unsaved state.

Recovery never repeats a committed computer query. A saved THINKING round becomes
READY once with `computerStatus: interrupted`, no candidate, zero computer points
and no retrieved evidence. Dead tasks/deadlines are not recreated or extended.
The player can score and continue; curated support is revealed only afterward.
Future clues search normally when opened, subject to the original pinned source
identity and the running engine configuration. No model participates in recovery.

## Content Changes and Request Handoff

Exact private clue text, aliases, hints, full source snapshots/provenance,
pack/source versions/digests and shuffled reservations live in the save. A
changed/retired/rolled-back catalog never substitutes content in an existing
game. Completed receipts and an already-committed guess remain readable without
the original source/pack files or index. New games still use the current validated
catalog and never infer readiness from old activation receipts.

For unopened pinned clues, retrieval uses the current router only if it knows
the **exact original collection/version/digest** and its index/runtime metadata
verify. A retired/changed scope or missing/incompatible index produces honest
engine-unavailable/no-answer, not a different collection or rebuilt index. A
retired scope is not automatically re-registered merely because an old index
directory exists. The player can still finish against pinned keys/support.
Startup itself requires a valid current configuration/catalog; if those are
missing or invalid, it stops and retains the save. Restore a valid configuration
at the same path before reconnecting. No automatic content migration is included.

Topic-request Play now has two phases. First the request ledger durably records
the exact retry/selection/replay intention, boot and **hashed** game identity.
Then launch is ordered against cancellation under its ledger lock and the game
store commits the game. Recovery checks existing game retry IDs **before** new
readiness/closed-request checks. A crash after the game commit, including before
the second ledger transaction finishes, recovers that same unexpired game after
restart. A saved intention from an earlier boot with **no** committed game returns
410 and cannot create a replacement. Expired/foreign identities do likewise;
reconnect and use a new deliberate intention if a new game is desired. Existing
memory-only Play bindings are hashed on ledger access but cannot recover old
games. IDs/cookies remain absent from retrieval inputs.

After a lost Play response, reload recovers the game; **Reconnect Requests**
explicitly retries the retained intention. Opening the request disclosure alone
does not resubmit it. Request readiness for *new* games and recovery of a pinned
game are separate decisions.

## Verification and Remaining Work

Starting checkout: 269 Java and seven Node tests passed. Updated checkout:
301 Java tests (32 new: 20 persistence/content/ownership and 12 real child-process
crash checks) and seven Node tests passed, with no failures/errors/skips. The
actual isolated activation/rollback check restores the old pinned Jovian game,
retains its first committed receipt and completes the remaining clues with
honest unavailable outcomes for the retired source scope. No normal activation
or benchmark rerun occurred. The browser checked saved-start/scoring/advance/
fifth-score/replay kills, THINKING interruption, lost Play response, keyboard,
390px touch/320px layout, failure completion, old-cookie confirmation, private
pre-reveal responses and normal-entry-point recovery after clean.

See [dated engineering evidence](engineering/2026-11-07-durable-games-verification.json)
for actual checks, browser game IDs and preserved fingerprints. These are software
checks, not human participants, difficulty calibration or retrieval benchmarks.
Fault/crash barriers and failure opponents exist only on the test classpath;
there is no production selector or canned assistance fallback.

Next: rehearse the existing manual source-to-reviewed-pack workflow using these
durable game receipts for feedback. Live model verification remains a separate,
explicitly authorized/configured/budgeted pilot, not the default next action.
Human approval/playtesting/calibration remain 0/60 starter and 0/5 preserved
Jovian. Public release still requires advisory/dependency review, rights review,
public auth/rate/resource controls, deployment-grade storage/backups and broader
accessibility/browser/device verification.
