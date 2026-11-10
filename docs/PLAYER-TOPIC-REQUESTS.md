# Player Topic Requests (Stage 5A)

Local, manually prepared five-clue requests now connect the player to the
[private Topic Workshop](TOPIC-BUILDER.md). Requesting does not fetch sources,
generate clues, approve content, activate a catalog or restart a server. No new
dependency, provider or retrieval/scoring algorithm is introduced.

Follow-on [proposal assistance](PROPOSAL-ASSISTANCE.md) is a separate explicit
operator action; live mode remains unconfigured. Request submission still never
calls it. An operator-linked generation job cannot establish approval, activate
content or bypass live readiness. Stage 5A's historical verification below is
preserved; current proposal-adapter verification is documented separately.

## Launch

Use JDK 21/Maven 3.9.x from the repository root. Omit `-o` on the first build if
dependencies are not cached. In the player terminal:

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -o -q compile exec:java -Dexec.mainClass=main.GameServer
```

If indexes have not been built, deliberately run `main.PackTool` with
`-Dexec.args='index data/game.json'` first. The player never builds them. Open
**http://127.0.0.1:8765/**. In a separate operator terminal:

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.TopicBuilderServer
```

Open **http://127.0.0.1:8766/** and enter that process's private access code.
Both processes must run from the same checkout to share the private request
ledger. Neither starts the other. Use the exact addresses, not a tunnel/proxy.

For a safe rehearsal, the workshop creates its separate isolated configuration.
Validate/build its indexes explicitly, then start a separate player:

```sh
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index drafts/local/topic-builder/isolated/game.json'
mvn -o -q exec:java -Dexec.mainClass=main.GameServer -Dexec.args='drafts/local/topic-builder/isolated/game.json'
```

Use **http://127.0.0.1:8787/** for isolated requests. An isolated activation
cannot satisfy a request for the normal target. The default workshop target is
isolated; selecting an inbox request deliberately selects its exact target.

## Player Steps

1. Open **Request a Topic**, enter subject words and choose Easy, Standard or
   Hard. The length is always five clues. Labels are provisional editorial
   difficulty, not calibrated levels.
2. Submit once. Compatible active-pack suggestions use topic labels/IDs only,
   not answer content or semantic guessing. Inspect the label, pack/source
   versions, eligible count and review status, then explicitly **Choose** a
   match. No match means wait for manual preparation, not silent substitution.
3. Use **Reconnect Requests** after an interrupted response. It retries this
   tab's saved intention with the same UUID. Reload keeps that pending intention
   and recovers owned records; no credential is stored in JavaScript storage.
4. Inspect readiness. **Play Requested Topic** rechecks the exact catalog,
   loaded player and index identities. Exhaustion requires the separate
   **Reset and Play Requested Topic (May Repeat)** action. Reset is rejected
   while five unused clues remain; another active game must finish first.
5. Cancel to close a request. Cancellation does not abandon a game, stop a
   shared job, delete content or roll back activation. A game already started
   keeps its pinned versions and its original results.

## Operator Handoff

The private inbox shows retained requests and mounted targets. **Use This
Request and Target** attaches managed IDs to subsequent jobs; selecting alone
does no work. Check the subject, requested difficulty and exact target before
following the existing source/proposal/review workflow. Unknown targets cannot
be substituted. A linked plan or activation against a different target fails.

Preparing is recorded only when a linked worker job actually begins. A saved
review/plan can be reused deliberately; no new capture is required when existing
source versions are suitable. A completed linked plan offers the actual reviewed
candidate and requires five eligible clues at the requested difficulty. It does
not accept that choice for the player. Private drafts, aliases, proposals,
support selections, review notes, artifacts and paths stay in the workshop.
Only its explicitly labeled **Message for Player** is public: never paste
private notes or credentials there.

After work begins, **Mark Awaiting Review** gives an honest waiting state.
**Ask for Clarification**, **Mark Unavailable** and **Mark Failed** close a
request with a short public explanation. Clarification requires a new, more
specific request; it never edits the original subject/difficulty. Cancelled and
other closed requests cannot be reopened by late job results or transitions.
Detach a closed request before deliberate unrelated private work, such as
rollback. Existing plan confirmations, stale checks and receipts still apply.

## State and Readiness Contract

| State | Meaning |
| --- | --- |
| Requested | Persisted for manual attention; no preparation has run automatically. |
| Preparing | An explicitly linked operator job actually began. A multi-step preparation may still need manual work after that job finishes. |
| Awaiting Review | A choice or preparation result exists, but choice/activation/restart/index gates are not all satisfied. This is not human approval. |
| Ready | The player accepted the exact offer and all live readiness checks pass. Recomputed, not a historical success flag. |
| Failed / Unavailable / Needs Clarification | Closed with explanation; create a deliberate new request if circumstances change. |
| Cancelled | Closed by its owner; late work cannot reopen it. Existing games/content are unchanged. |

Ready requires a live process lease marked active only after HTTP startup;
current configuration/catalog byte fingerprints equal those loaded by that
process; exact topic, pack version/hash, source collection version/digest and
difficulty binding; game eligibility and at least five clues; and a verified
source index with matching provenance. A process crash leaves an unlocked lease,
not a false running status. A draft export, activation on another target,
on-disk switch alone, missing/stale index or old receipt cannot establish Ready.

Catalog/configuration changes report **Restart Required**. The operator must
decide when to stop/restart that player: unexpired [durable games](DURABLE-GAMES.md) retain pinned content and
receipts. No automatic restart or hot reload is included. Rollback also blocks
new requested-topic launches until the live identity agrees, but does not change
already-pinned games. Failed verification never falls back to another pack.

The public API exposes only allowlisted request/availability metadata. The
computer still receives only its public clue/category/hint and the pinned
searchable collection. Neither topic-request text, private authoring data,
accepted answers nor player guesses enter opponent retrieval. Retrieved evidence
and curated answer support remain separately labeled after scoring.

## Ownership, Recovery and Bounds

The file ledger at ignored `drafts/local/topic-requests` is shared by the two
Java processes. Cross-process locks serialize mutations; an atomic same-filesystem
JSON replacement avoids partially committed records. This fits a bounded trusted
local prototype without another service/database. It is not a distributed store
or a power-loss durability guarantee. Private POSIX directory permissions are
required; symlinks are rejected. Unreadable/malformed storage fails closed, never
silently resets ownership. Backups contain sensitive identifiers and secret
hashes; do not publish this directory or include it in verification exports.

A 256-bit random recovery secret in an HttpOnly/SameSite=Strict browser cookie
authorizes an opaque owner ID; disk stores only its SHA-256 hash. Request UUIDs
alone grant no access. The credential is never placed in URLs, response bodies,
logs or JavaScript storage. Mutation requests require the exact player Origin
and custom request header; there is no CORS. Cookie names are separate from game
and workshop sessions, but browser cookies are not port-scoped. Other processes
running as the same OS user remain trusted. Loopback HTTP is not public auth/TLS.

| Bound | Behavior |
| --- | --- |
| Topic | 1-120 trimmed characters, at most 20 words, subject words/simple punctuation including subtitle colons; URLs/schemes, paths, markup and control characters rejected. |
| Owner lifetime | Seven days absolute from creation, not extended by reading/submitting. Requests share that expiry. |
| Owners | 32 retained; at most eight new owners per minute globally. |
| Requests | 128 total; eight retained and four nonterminal per owner; five new requests per ten minutes per owner. |
| Retry storage | 32 accepted owner mutation identities and 32 operator transitions per request. Exact retries do not consume another slot; new mutations stop at capacity, including cancellation. |
| Storage | Ledger at most 4 MiB, JSON depth/node bounds; no unbounded queue or automatic source task. HTTP worker/queue and workshop job limits remain unchanged. |
| Lock contention | A busy cross-process lock fails after two seconds; retry the same intention. |

Equivalent pending requests deduplicate only within the same owner, target and
difficulty using normalized subject case/spacing. Raw changed input under an
existing retry UUID conflicts. The normalization does not change answer grading.
Cancellation and Play serialize through the same ledger lock. A launch ordered
before cancellation may finish; a later new launch cannot. Duplicate successful
Play recovers its existing game, even after cancellation, without starting anew.

Expired owners/requests are removed lazily on a successful ledger transaction;
authorization expires immediately. No live records are evicted automatically to
make room. Capacity returns an explicit error; wait for expiry or, with both
servers stopped, privately archive the whole request directory before deliberately
starting a fresh ledger. That maintenance invalidates all old request identities;
it does not roll back catalogs. Never hand-edit ownership or delete live locks.

Same-browser cookie recovery survives reload and server restart independently of
two-hour game expiry. Clearing/blocking cookies or changing browser loses access;
there is no email recovery, portable secret link or cross-device restore. An
expired/unavailable cookie gets an explicit **Start a New Request Identity**
choice, not automatic access to someone else's records.

F3 recovery follow-up (2026-11-09): request credentials support unquoted values
or a complete pair of double quotes. Malformed, empty, single/unmatched-quote,
duplicate, unknown or expired request cookies return 401 without creating an
identity, changing requests or modifying games. The explicit same-origin
`POST /api/topic-requests/identity {"confirmed":true}` bypasses only the old
topic-request cookie parser, allowing recovery even from malformed/duplicate
values. It still requires the custom request header and exact confirmation body;
it creates a separate owner without transferring or deleting existing records.
Missing-cookie `GET /api/topic-requests` retains initial identity creation, while
cookie-free request reads/mutations do not authorize existing requests. Game
credentials, ownership, expiry and recovery rules remain separate.
`TopicRequestCookieTest` verifies this over isolated real HTTP and durable saves.

Games now persist
for two hours absolute/12 games/64 rounds per session. Play first commits its
intention to this ledger, then starts the durable game under the cancellation
lock. Only the hash of the game cookie is stored. After restart, an existing
unexpired game is recovered before new readiness checks, even after cancellation
or catalog changes. A previous-boot intention with no committed game, a different
identity, or an expired game returns Gone (410), never a replacement. Use
**Reconnect Requests** for the saved intention; create a new deliberate Play
intention only when a new game is desired. Seven-day requests do not resurrect
expired games/scores. Pre-upgrade memory-only games remain unrecoverable.

## Verification and Remaining Work

Actual baseline: **214 Java and seven Node tests passed** before edits. Final
clean verification: **237 Java tests (23 new) and seven Node tests passed**,
zero failures/errors/skips; jar packaged. Normal and isolated pack/index checks,
JavaScript syntax and preservation checks passed. The dated
[engineering record](engineering/2026-11-06-topic-request-verification.json)
records exact rehearsal identities without recovery credentials.

Scripted Chromium, not a player study: request/reload/lost submission, private
handoff, lost review/activation, explicit choice, restart-required rejection,
manual isolated restart/recovery, Ready/lost Play, two complete pinned games,
aliases/incorrect/safe markup text, lost answer/advance, stable final reload and
duplicate result, exhaustion/reset, cross-owner rejection, cancellation/late
operator outcome and isolated rollback. Keyboard and narrow/mobile layouts were
checked; no physical-device, screen-reader or cross-browser audit is claimed.

The normal catalog remains 1.0.0; all 184 preserved reference fingerprints match.
No source/clue versions, historical benchmarks, frozen challenge, opponent or
scorer changed. Functional game points are not new retrieval accuracy. Human
review/playtesting/calibration stay deferred at 0/60 starter and 0/5 Jovian clues.
Next is a separate, bounded assisted-authoring proposal stage behind these manual
review/activation gates, not automatic approval/publication. Rights, advisory,
durable-state, accessibility and deployment hardening remain public-release gates.
