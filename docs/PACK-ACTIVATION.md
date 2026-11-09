# Controlled Local Pack Activation

The separate [private topic workshop](TOPIC-BUILDER.md) now wraps this workflow
with guided forms, managed artifact IDs and recoverable jobs. The CLI below
remains compatible. The UI defaults to an isolated target and reads current
catalog identity independently from historical receipts; no automatic restart.

This offline/admin workflow separates capture, draft export, source/editorial
review, planning and activation. None of the first four activates a game topic.
It retains the Java engine, schema-1 packs and startup-loaded catalog, without a
database, hot reload or new runtime dependency. The optional workshop above is a
separate admin origin; GameServer gains no administration routes.

## Review Before Planning

Preserve the original export. Change wording, aliases, hints or support in a new
proposal/export version. Record an actual source/editorial decision separately.
The complete example is [Jovian v2](../drafts/jovian-v2/README.md), with private
[decisions](../drafts/jovian-v2/review.json). Do not expose answer-bearing review
files to players before first-exposure testing.

Decision fields: `schemaVersion: 1`, stable `id`, `kind`
(`assistant-source-editorial` or `operator-source-editorial`), `status:
source-checked`, actual `reviewer`/`reviewedOn`, exact `draftManifestSha256`,
strictly newer `candidateVersion`, explicit `limitations`, null `humanReviewer`
and `humanReviewedOn`, and `clues`. Each row needs `clueId`, nonblank `checks`
for `factualSupport`, `wording`, `aliases`, `hint`, `distinctFact`, `attribution`
and `difficulty`, exact `flagDecisions` for every exporter flag (`flag`,
`disposition: resolved`, `rationale`), `blockers` and `advisories`. Extra,
duplicate or missing clues/flags fail. Nonempty blockers prevent eligibility.

These are explicit decisions, not automated semantic verification. Inspect the
sources in context; never clear flags merely to pass validation. This workflow
cannot award `human-reviewed`; use the separate [human procedure](PLAYTEST-CHECKLIST.md).
Provisional difficulty, lead-only context, latest-render uncertainty, unknown
revisions and rights limitations remain visible advisories.

An optional `sourceAdvisories` decision array records a source-wide context
warning as `{sourceId, note, referenceUrl}`. Each source ID must exist and be
unique; note is at most400 characters and reference is credential-free HTTPS,
at most300 characters. The combined original revision note/advisory must fit1000
characters. Review appends an explicitly dated/kind-labeled editorial note only
to the **new collection version's metadata**; it never rewrites a quotation,
changes its upstream revision/capture identity or enters index/search fields.
The decision is fingerprinted in the preview/plan. This is appropriate for a
known contradictory saved claim, not a substitute for an upstream correction or
human factual/rights approval. Older decisions without this field reconstruct
unchanged. Never edit the original capture to disguise an assistant correction.

Run from the repository root with JDK 21/Maven 3.9.x. Omit `-o` only if Maven
dependencies are not cached. No network or provider is needed here.

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.PackTool -Dexec.args='review-preview drafts/jovian-v1/export drafts/jovian-v2/export drafts/jovian-v2/review.json drafts/jovian-v2/preview'
```

Inspect `preview.html` locally or read `preview.json`. They contain private
before/after wording, aliases, hints, support/ranges, source provenance, versions,
flag decisions, blockers/advisories and draft/reviewed counts. HTML is escaped,
script-free and wraps on narrow screens. Blocked reviews produce no eligible
candidate; corrupt structural input may fail before preview construction. A
successful preview creates a separate `candidate/`, not an active catalog change.

## Isolated Rehearsal

Choose new output names: immutable outputs reject changed retries. `sandbox`
copies the referenced catalog closure, not indexes or sessions. Use a separate
index base and nondefault port. These commands never affect the normal catalog:

```sh
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='sandbox data/game.json drafts/local/jovian-rehearsal indexes/jovian-rehearsal 8785'
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index drafts/local/jovian-rehearsal/game.json'
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='activation-plan drafts/local/jovian-rehearsal/game.json drafts/jovian-v2/export drafts/jovian-v2/review.json 1.0.1 drafts/local/jovian-rehearsal-plan'
```

The plan is a dry run: no active catalog files or indexes change. Inspect its
`plan.json`, `before/` and `prepared/`. They pin:

- Configuration path/hash, catalog ID/version/before/after hashes and the exact
  referenced previous closure needed for rollback.
- Draft membership/hashes, review identity/hash, candidate digest, pack and
  collection ID/version/hash, source hashes and proposed topic changes.
- All eligibility counts; the incoming pack needs five reviewed clues at one
  difficulty. No fallback or padding is allowed.
- Required index paths, runtime/schema namespace, processing, document counts,
  collection digests and existing provenance or explicit build requirements.
- Remaining review limitations, manual restart requirements and two-hour recovery limits. New plans report `restartClearsInMemorySessions: false`; older immutable plans/receipts retain their historical value, not current behavior.

Unrelated topics/defaults remain unchanged. Activation reconstructs the intended
merge: editing a plan and recomputing its hash cannot replace valid review/input.
Only after inspecting the plan:

```sh
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='activate drafts/local/jovian-rehearsal/game.json drafts/local/jovian-rehearsal-plan'
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate drafts/local/jovian-rehearsal/game.json --indexes'
mvn -o -q exec:java -Dexec.mainClass=main.GameServer -Dexec.args='drafts/local/jovian-rehearsal/game.json'
```

Open `http://127.0.0.1:8785/`, select **Jovian System / Standard**, finish five
clues, inspect both evidence kinds, reload and explicitly replay. Stop with
Ctrl+C before changing the catalog used by the next process. Then:

```sh
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='rollback drafts/local/jovian-rehearsal/game.json drafts/local/jovian-rehearsal-plan'
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate drafts/local/jovian-rehearsal/game.json --indexes'
```

Rollback restores exact previous catalog bytes and verifies the old source
closure/indexes. It retains releases, snapshots, drafts, indexes and receipts.
Restart to see the restored catalog. A running server keeps its startup catalog;
**unexpired durable games retain pinned content/receipts after restart**. Old memory-only sessions cannot be restored. See [recovery limits](DURABLE-GAMES.md). Revealed downloads can
be saved before stopping, but cannot restore expired sessions.

## Activate the Normal Catalog Later

**Not performed this milestone.** Normal catalog v1.0.0 still has five topics,
68 source documents and 76 clues. A dry run exists at `drafts/local/jovian-normal-plan`.
Reproduce/inspect it, then deliberately choose whether to execute activation.
This adds five Standard clues/eight documents and catalog v1.0.1 without changing
the default topic. Stop an existing normal server before restarting it.

```sh
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='activation-plan data/game.json drafts/jovian-v2/export drafts/jovian-v2/review.json 1.0.1 drafts/local/jovian-normal-plan'
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='activate data/game.json drafts/local/jovian-normal-plan'
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -o -q exec:java -Dexec.mainClass=main.GameServer -Dexec.args='data/game.json'
```

To undo this exact activation later, stop the server, run the following and
restart using the preceding server command:

```sh
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='rollback data/game.json drafts/local/jovian-normal-plan'
```

If pinned inputs change, do not edit/reuse the old plan. Inspect the changes and
create a new output/plan with appropriate new content/catalog versions. Plans
are tied to this clone's absolute paths, not portable deployment artifacts.

## Transaction and Recovery Contract

Activation takes a cooperative lock beside the catalog and revalidates all plan,
draft, review, configuration, current dependency, version and index identities.
Missing new indexes are built source-only without rebuild; incompatible existing
indexes fail. Immutable release files and content-addressed snapshots are
installed/verified before intent journaling and the catalog switch. Historical
release and successful catalog versions cannot be reused for different bytes,
including after rollback. Failed preparation can leave unused files/indexes,
but the old active references remain usable.

Commit forces a temporary file beside the catalog, then requires same-filesystem
`ATOMIC_MOVE` with replacement. There is no partial-copy fallback. This was
exercised on local macOS; atomic replacement support is required elsewhere.
This is atomic visibility for cooperating local operations, not a database or
power-loss guarantee: no parent-directory fsync, cross-filesystem transaction or
protection against malicious local owners. Do not edit files/indexes during apply.

Private immutable intent/result/failure manifests live under
`<catalog-root>/.activation-history/<planId>/{activation,rollback}/`;
pre-validation rejections have separate `rejected/` attempt records. Results
include actual index provenance, switch outcome and time. If the process dies
after switch but before result, retry verifies files/indexes and records recovery.
Before-switch retries prepare/verify again without appending documents. Missing
or incompatible previous artifacts/indexes reject rollback.

Repeated successful commands return the original receipt without another switch.
**After rollback, the old activate command returns its historical receipt; it
does not reactivate.** Check current catalog/hash, not just receipt history. To
activate again, make a new plan against current state with a fresh strictly newer
catalog version, e.g. 1.0.2. Unchanged candidate content versions can be reused.

Bounds: 110 referenced catalog files, 256 files per intake/plan artifact, 512
release scan entries, 1,024 history scan entries. Hitting a bound fails; preserve/
archive evidence deliberately, never silently prune. CLI paths stay within this
clone's draft/catalog/index/target workspace and reject symlinks. File owners
are trusted. `index --rebuild` is not part of activation; archive/benchmark paths
are forbidden. Public administration/deployment needs separate hardening.

## Verified Outcome

On 2026-11-06 the baseline was rerun (169 Java/seven Node tests); final clean
verification passed **196 Java tests and seven Node tests**, no failures/errors/
skips. Twenty-seven new tests cover preview/flags/versioning, offsets, stale/
tampered inputs, short coverage, indexes, failure, atomic readers, concurrency,
receipt recovery/idempotency, rollback and source/private separation.

An isolated activation completed two scripted Chromium games plus one real
missing-index game: aliases/incorrect guesses, evidence separation, replay, stable
receipts, reload, deliberately lost start/answer/advance/final responses, keyboard
controls, touch emulation and 320/390px layouts. Rollback did not hot-reload;
restart advertised the previous five-topic catalog. Owned servers were stopped.
This is software QA, not human playtesting, new retrieval accuracy or a device/
screen-reader audit. See [dated evidence](engineering/2026-11-06-jovian-activation-verification.json).
Human review/playtest/calibration remain deferred: starter each 0/60, Jovian each
0/5. No benchmark tuning or TriviaQA game reuse occurred.
