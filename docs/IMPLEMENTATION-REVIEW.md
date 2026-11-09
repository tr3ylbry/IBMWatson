# ClueLab Implementation Review

This is an accumulated implementation PR against the recovered `master`, not a
public-service or showcase release. The original assignment, report, 100-question
benchmark and Wikipedia sample are unchanged. Historical measurements remain
dated evidence, not claims that those large-data runs were repeated for this PR.

## Suggested Review Order

1. [Build and runtime migration](MODERNIZATION.md), `pom.xml`, `RuntimeInfo`,
   `Indexer`, `QueryEngine`, `Evaluation` and their foundation/analysis tests.
   [The guided-demo diagnosis](REGRESSION-DIAGNOSIS.md) explains the intentional
   stop-word-policy difference; no clue or grading adjustment restores its score.
2. [Pack contract](PACK-AUTHORING.md), `PackCatalog`, `CollectionIndex` and
   `PackTool`: version validation, source-only indexes and answer independence.
3. [Game lifecycle](LOCAL-GAME.md), `GameService`, `GameServer`, player assets and
   five-clue/HTTP tests; then [durable saves](DURABLE-GAMES.md), `GameStore`,
   `GameState` and persistence/real child-process crash tests. Check that commits
   precede acknowledgements, retries cannot rescore, and old receipts stay stable.
4. [Topic requests](PLAYER-TOPIC-REQUESTS.md), then the separate
   [private workshop](TOPIC-BUILDER.md), [intake](SOURCE-INTAKE.md) and
   [activation](PACK-ACTIVATION.md) boundaries. The player never serves the
   workspace. Activation is deliberate; the player requires an operator restart.
5. [Offline proposal assistance](PROPOSAL-ASSISTANCE.md), provider transport and
   deterministic fixtures. Disabled/unconfigured operation retains manual import;
   no fixture fallback, credential, model or budget is shipped in player assets.
6. [Research limits](EXTERNAL-CHALLENGE.md), [results](RESULTS.md),
   [human review preparation](PLAYTEST-CHECKLIST.md) and source/license records.

The scoped preparation review inspected these boundaries, their tests, the full
changed-file inventory, tracked artifact contents and jar membership. It is not
an exhaustive security audit, independent editorial approval or public-release
clearance. The large accumulated diff still needs maintainer review.

## Clean-Checkout Checks

Use JDK 21, Maven 3.9.x and Node 24. Node is needed for these engineering tests,
not for playing. The first Maven build needs dependency downloads; subsequent
offline runs can reuse the local Maven cache. No private corpus, saved index,
provider key or model call is required by these checks.

```sh
export CLUELAB_ASSISTANCE_ENABLED=false
mvn -B -ntp clean verify
node --check src/main/resources/web/app.js
node --check src/main/resources/admin/builder.js
node --check tools/player-ui.browser.js
node --test tools/prepare-external-challenge.test.mjs tools/review-external-challenge.test.mjs tools/player-presentation.test.mjs
mvn -B -ntp -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json'
mvn -B -ntp -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index data/game.json'
mvn -B -ntp -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -B -ntp -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='validate drafts/jovian-v1/export'
mvn -B -ntp -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='validate drafts/jovian-v2/export'
```

Preparation on 2026-11-09 independently reproduced **308 Java tests and 14 Node
tests**, zero failures/skips, plus packaging and the tracked catalog/authoring
checks, in a separate clean worktree at implementation checkpoint `905d3c8`.
Toolchain: Temurin 21.0.13, Maven 3.9.16, Node 24.14.1 on macOS ARM64, with
previously cached Maven dependencies. The tracked catalog has five topics,
76 clues and 68 source records; structural validation is not factual approval.
See the PR checks for the separate Linux CI result and its exact revision.

The isolated legacy CLI compiled on the same JDK. `tools/compatibility.mjs`
rebuilt all three processing modes in both runtimes, evaluated all 12 original
demo configurations, reproduced guided legacy 8/8 versus modern 7/8, and verified
that rejected modern reads/rebuilds leave the legacy fixture unchanged. New
outputs stayed under ignored `target/` and `indexes/`; no historical record was
replaced. Optional vector/CoreNLP diagnostics remain visible, as documented.

A fresh, isolated browser session completed a tracked World History/Easy game:
keyboard topic selection, private pre-reveal response, deliberately lost committed
answer response, recovery focus, changed duplicate rejection by stable receipt,
five-clue completion, reload recovery, evidence links and replay/old-receipt
stability passed. Emulated 390px touch and 320px reflow passed. The owned player
was stopped afterward. This is scripted regression QA, not player research,
screen-reader testing, physical-device testing or a new retrieval measurement.

The [workflow](../.github/workflows/verify.yml) uses read-only repository
permissions, pinned official actions, a bounded hosted job, no provider secrets,
no deployment step, no artifact uploads and no workspace/dependency cache.
It does not download restricted challenge data or evaluate private showcases.
Action configuration follows the official [checkout](https://github.com/actions/checkout),
[setup-java](https://github.com/actions/setup-java) and
[setup-node](https://github.com/actions/setup-node) documentation.

## Migration and Distribution Boundaries

- Modern Lucene 10/CoreNLP 4 indexes use a runtime/schema namespace. Rebuild from
  trusted sources into a new base; never upgrade historical segments in place.
  The isolated [legacy CLI](../legacy-baseline/README.md) retains old dependencies
  only for trusted local reproduction, not the HTTP service. Reproduce the tiny
  old/new comparison with the commands in [the diagnosis](REGRESSION-DIAGNOSIS.md).
- The Maven jar is a thin library/application jar, not a self-contained executable
  or player-only distribution. It contains both player and operator classes and
  eight fixed web/admin resources, but no clue packs, saves, benchmarks, language
  models or test fixture classes. Use documented Maven launch commands locally.
  A public player-only distribution remains separate work; do not serve the repo.
- `data/demo`, `data/packs` and the two `drafts/jovian-*` examples are intentionally
  versioned, attributed demonstration/authoring data. Their server-side keys and
  moderator review records are spoilers, not player assets or human approvals.
  `data/retrieval-lab` is the small assistant-authored pilot, not external questions.
- External-challenge tracked files contain plans, notices, IDs, hashes, manifests
  and aggregate/ID-level outcomes. Restricted TriviaQA question/key files and
  detailed reports remain excluded; do not copy them into games or PR attachments.
- Local showcase/media, receipts, participant notes, credentials, generated
  indexes and large corpora remain ignored. Pattern checks found no tracked
  private-key/provider/GitHub/AWS token signatures; this is not comprehensive
  secret detection or a license clearance.

## Remaining Decisions

Human review and difficulty calibration remain pending (0/60 starter clues).
Content/source redistribution, code/model licensing, naming and exact showcase
publication decisions are separate from code review. Live assistance has only
offline/loopback verification, not live API verification. Keep it disabled.

This remains a trusted loopback, single-process/POSIX prototype. Public operation
needs a player-only bundle, deployment-specific origin/auth/admission controls,
storage/backup/monitoring work, advisory review and broader accessibility testing;
see [the readiness report](SHOWCASE-READINESS.md). Do not tunnel the workshop.
Draft PR creation does not approve review, merge, human validation or publication.
