# Private Local Topic Workshop

The workshop wraps the existing Java source-intake, draft, review and activation
services. It is a separate, explicitly started administrator origin, not a game
feature or a public administration service. There is no shell/command runner,
client-supplied server path or new dependency.

[Source-grounded proposal assistance](PROPOSAL-ASSISTANCE.md) adds a separate,
explicit operator action over selected captures. Its live adapter remains
unconfigured; manual forms/import still work. No model is called on startup or
player request. Tests use injected fixtures only on the test classpath, never a
normal UI fallback. Proposal-assistance checkpoint: 269 Java/seven Node tests, with
an isolated synthetic proposal-to-game/rollback browser rehearsal and zero live
provider calls. The earlier milestone checkpoints below retain their evidence.

Stage 5A connects a [bounded player-request inbox](PLAYER-TOPIC-REQUESTS.md).
Use This Request and Target deliberately links subsequent managed jobs to the
correct player catalog. Selecting is not preparation; actual linked work marks
Preparing. Explicit player choice and live catalog/index readiness are separate
from private review/activation. Closed requests cannot be reopened by late jobs.
The earlier verification checkpoint below is preserved; its request-stage verification is
237 Java/seven Node tests, with isolated handoff, two games and rollback.

## Launch

From the repository root, with JDK 21/Maven 3.9.x and cached dependencies:

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.TopicBuilderServer
```

Open **http://127.0.0.1:8766/** and enter the access code printed by this process.
Keep it private. It changes on restart and is never embedded in assets or URLs.
Omit `-o` for first-time Maven dependency installation. Stop with Ctrl+C. Use the
exact printed address, not localhost, another hostname or a reverse proxy.
POSIX filesystem permissions are required (verified on macOS).

For a deliberately fresh workspace without changing existing history, set these
**operator-process environment variables**, not browser paths or global settings:

| Variable | Default / Rule |
| --- | --- |
| `CLUELAB_WORKSHOP_ROOT` | `drafts/local/topic-builder`; another private subdirectory below this clone's ignored `drafts/local/` only, no traversal/symlinks. |
| `CLUELAB_WORKSHOP_PORT` | 8766; integer 1-65535, distinct from its player. |
| `CLUELAB_ISOLATED_PLAYER_PORT` | 8787 for a new sandbox; a specified port must match an existing configuration, which is never silently rewritten. |

An explicit fresh root has its own cache, jobs, sandbox and bounded identity-scan
workspace. The default retains its existing shared draft scan/cache/index paths.
The 32-job and 1,000-entry scan bounds are unchanged; no histories are deleted to
make room. Identity collision checks are scoped to that workspace plus the normal
active catalog, not a global registry across unrelated workspaces; activation
still independently verifies exact target content/version history. Use unique
IDs/new versions when transferring work. See [the retained fresh-topic candidate](CANDIDATE-SHOWCASE.md)
for a complete isolated launch, review and rollback example. Keep assistance
explicitly disabled with `CLUELAB_ASSISTANCE_ENABLED=false`.

The first launch creates a separate **Isolated Workshop** catalog/configuration
from the normal catalog, with its own ignored index base and player port 8787.
It does not build indexes, activate a pack or start/restart the game. Subsequent
launches retain this workspace. **Normal Local Game** is a separate advertised
target; select it deliberately before preparing a plan if that is truly wanted.
The default target is isolated. Existing configurations cannot be supplied through
the browser, and normal content is not silently copied over an existing workshop.

## Guided Workflow

1. **Find Sources.** Enter an English topic and 1-12 results. Inspect the returned
   article links, explicitly check source IDs and acknowledge source rights.
   Save the selection, then capture it. Source text is treated as data, not
   instructions. No arbitrary URL fetch is offered.
2. **Inspect Capture.** Progress announces the current title and attempted count.
   Cancellation applies to discovery/capture, with bounded stopping latency.
   Captures retain exact text, source references, attribution/license evidence,
   observed revision or null, timestamps, response/text hashes and redirect
   metadata. Expand full saved leads before authoring. Partial results list each
   failed page; replacements require a new selection, never an automatic swap.
3. **Write Clues.** Choose a captured source set. Import private proposal JSON,
   open an existing saved proposal, or start a new labeled form. The editor
   includes pack/topic/version/author/date/subtopics and each clue's ID, distinct
   fact, wording, optional hint, provisional level, answer article, aliases and
   exact support passage/optional UTF-16 start. Include the canonical article
   title among aliases. Keep dates/reviewer fields blank until actual work exists.
   Optionally inspect the assistance scope/limits and request up to five proposals
   after future explicit live configuration. Inspect flags beside full context,
   select what to edit, and retain original assistance provenance. Model output
   is untrusted and cannot resolve review flags or create approvals.
   Validate to see structural errors and editorial flags, then explicitly export
   an immutable draft. Diagnostic validation does not reserve a content version;
   an exported version cannot later be overwritten with edited bytes.
4. **Review Changes.** Choose previous/revised exports. Import an actual review
   decision or start the blank form. Record factual support, wording, aliases,
   hint, distinctness, attribution and provisional difficulty, plus a rationale
   for each flag, blockers and advisories. Reviewer/date fields are not invented.
   Comparison shows before/after text, hints, aliases, support/ranges, references,
   versions and availability. Unresolved/invalid review produces a blocked
   comparison or actionable error, not an eligible candidate. Structural
   validity is not factual approval.
5. **Prepare a Plan.** Choose a completed comparison, target and a strictly newer
   catalog version. This is a dry run. Inspect the exact target, current catalog
   identity, proposed version/topic, pack/source versions, eligible counts,
   limitations and required index provenance/builds. At least five reviewed clues
   at one difficulty are required. No padding, fallback or difficulty relabeling.
6. **Choose Activation or Rollback.** Check the exact-plan/restart confirmation
   and use the separate action buttons. The existing transaction prepares and
   verifies source-only indexes/files before its atomic catalog switch. Stale
   inputs, version conflicts and invalid indexes fail closed. Rollback restores
   the exact known prior catalog and retains artifacts/indexes/history. The UI
   reads current catalog bytes/hash separately from historical receipts.

The manual source-first pipeline is independent of opponent retrieval. Only
source IDs/titles/categories/text enter collection indexes; clues, answer keys,
selected support, authoring notes and review metadata do not. The computer still
gets the public clue/category/hint and allowed source scope. No automatic
generation or benchmark-question reuse is included. Optional proposals require
an explicit operator action and configured adapter; no live calls ran here. Player topic
requests are now a separate manual queue, not retrieval or authoring inputs.

### Offline Rehearsal

**Saved Work** exposes fixed, bounded Jovian examples and this workspace's saved
artifacts through managed IDs. It is not a directory browser. Open **Saved Jovian
Sources** and **Jovian Revised Proposals** to inspect/edit via forms. Preserve
existing IDs/versions unless intentionally creating a new version. The original
and revised Jovian exports plus the actual assistant decision are separately
available; source-checked means assistant source/editorial work, not independent
human approval. To rehearse the existing candidate, choose those two exports,
open its saved decision, build the comparison, then plan against the isolated
target. Inspect limitations before any activation.

For genuinely new sources, exact passages, canonical IDs and metadata must match
the new capture; old proposals/decisions cannot simply be repinned to new source
bytes. Source text changes need contextual review. Reusing a private JSON file
does not make its old review apply to a different manifest. A changed draft needs
a new review and strictly newer reviewed candidate version.

## Privacy and Recovery

The server binds only `127.0.0.1:8766`. Private API calls require a random operator
session, two-hour absolute expiry and HttpOnly/SameSite=Strict cookie. Mutations
also require an exact Origin and per-session CSRF token. Exact Host, Origin and
fetch-site checks reject cross-origin requests, including the player origin.
There is no CORS allowance. Login attempts and operator-session count are bounded.
Public admin assets contain the interface only, not keys or access codes. The
player server's fixed asset/API allowlist exposes none of the workshop routes.
Sources/imports render as text; CSP disallows inline scripts and framing.

The local workspace is restricted to its owner. Anyone with access to the user's
checkout/account can still read private pack files; these are not encrypted or
a protection against hostile same-user processes. Browser cookies are not
port-scoped: separate cookie names plus exact-origin/CSRF checks provide the
application boundary, not an invented port-scoped cookie guarantee. Do not expose
either server through a tunnel, proxy, LAN binding or shared public machine.

One worker serializes jobs; a workspace lock prevents a second admin process.
Each request carries an immutable retry UUID. A double click/retried UUID returns
the same job; changed content under that UUID is rejected. After a lost response,
use **Reconnect** rather than start another operation. The browser retains the
pending request before sending it; recovery finds the job or retries that exact
request, not a fresh mutation. Saved Work reopens source sets, proposals, exports,
comparisons and plans without accepting paths. Job lists carry small summaries;
private payloads are fetched only when needed.

| Event | What Remains |
| --- | --- |
| Browser reload / lost response | Server job/results/artifacts; current-tab selection, private JSON edits and pending request in sessionStorage. Reconnect recovers server state. |
| Tab closes / browser storage cleared | Committed work and job receipts remain on disk; unsaved tab-only edits/selections may be lost. Reopen Saved Work after unlock. |
| Admin-server restart | Committed requests/results/artifacts/plans remain. Old operator cookies/access code do not. Incomplete jobs become `interrupted`, not automatically rerun. |
| Crash during activation | An interrupted admin job is not proof of rollback. Inspect actual catalog state and plan. A new explicit action against that saved plan uses existing transaction recovery/idempotency. |
| Game-server restart | Unexpired [durable player games](DURABLE-GAMES.md) retain pinned clues, commitments, scores and receipts. THINKING becomes interrupted/no-answer, not a rerun. Admin work is separate. |
| Game-session expiry | Two-hour absolute player authorization ends; seven-day requests/admin work are independent. Old memory-only games cannot be recovered. |

A successful historical activation receipt is **not proof of current activation**.
After rollback, replaying the old command returns its original receipt without
reactivating. Create a fresh plan/new catalog version for a new transition.
The game retains its startup-loaded catalog until an operator restarts it; this
interface never restarts it automatically. See [activation semantics](PACK-ACTIVATION.md)
and [game lifecycle](LOCAL-GAME.md).

## Bounds and Limitations

32 durable jobs per workshop; no automatic deletion or receipt eviction. At the
limit, preserve/archive the workspace before starting another, using a deliberate
filesystem maintenance step while the server is stopped. This prototype does
not include a destructive cleanup button. Existing draft/export/history scan
limits can be reached earlier and produce explicit errors. The underlying
immutable/atomic filesystem assumptions and trusted-owner model are unchanged.
Imports are at most 1 MiB each; HTTP JSON requests at most 2 MiB. Requests have
bounded queues, login is limited to ten failed attempts per minute, and there
are at most eight operator sessions. Private administration is one shared local
operator workspace, not multi-user roles/permissions.

Source bounds/cache/retries/cancellation are unchanged from
[SOURCE-INTAKE.md](SOURCE-INTAKE.md): twelve pages maximum, 1 MiB response,
8 MiB operation, 15-second requests/120-second operation, serial requests,
one transient retry and a 32 MiB/256-file/seven-day cache. A cancelled operation
may retain cached successes but never replaces a committed capture/export.
The default contactable project User-Agent can be overridden with
`CLUELAB_WIKI_USER_AGENT` before launch when operating under another identity.
Inspect current Wikimedia terms/policies before intake; public availability is
not blanket rights clearance.

The editor still targets the existing private schema and manual/import workflow.
New arbitrary capture/export directory mounts, collaborative editing,
unrestricted/automatic generation, public auth/deployment and Windows ACL
support are not implemented. No physically tested mobile device, screen-reader
audit, cross-browser study or human difficulty calibration is claimed. Existing
dependency/advisory, rights, accessibility and deployment gates remain separate.

## Verification and Next Step

Actual baseline on 2026-11-06: 196 Java/seven Node tests. Final clean verification
passed **214 Java tests (18 new) and seven Node tests**, zero failures/errors/skips.
Pack/index validation, JavaScript syntax and diff checks passed. All 183 preserved
reference fingerprints match baseline. Traceable lifecycle IDs are in
[the dated record](engineering/2026-11-06-topic-builder-verification.json).
The normal catalog remains v1.0.0/five topics/68 documents/76 clues; preserved
Jovian v1/v2, historical measurements and frozen challenge inputs remain intact.

Live browser discovery/explicit selection/capture used eight Jovian leads, then
manual form editing, validation/export, recorded assistant review, a dry-run plan,
isolated activation, two complete games and rollback. The ignored rehearsal
draft/candidate versions are 1.0.3/1.0.4, not replacements of preserved v1/v2.
Five answer-source text hashes match earlier checked sources; the general Moons
of Jupiter lead changed and was inspected separately. Seven captured records
retain earlier cache timestamps. No new general accuracy measurement is claimed.
Lost selection/plan/activation/rollback responses and reload/restart recovery were
exercised. A separate **synthetic test-classpath-only fixture** exercised partial
failure, cancellation and a markup-like source title; those are not real factual
sources, pack review or player observations. Temporary verification servers stop
after checks; the normal game is never restarted by verification.

Next: use this private workflow for another supported topic with actual source/
editorial review. A later bounded player-request feature can submit a topic for
operator preparation and expose only safe readiness/availability, not private
authoring artifacts. Human starter review/playtest/calibration each remain 0/60,
Jovian each 0/5. Optional model assistance must remain offline, source-grounded
and review-gated; no paid provider or public deployment is included.
