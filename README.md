# IBM Watson / ClueLab

A Java question-answering experiment that retrieves Wikipedia article titles from Jeopardy-style clues. Originally a Fall 2020 University of Arizona **CSC 483: Text Retrieval and Web Search** project, now being restored as the foundation for an explainable clue game.

Stage 2A adds complete, recoverable five-clue games to the local human-versus-computer experience. Choose a topic and difficulty, play five rounds, then review both scores, missed clues and separately labeled retrieved/curated evidence. Four starter topics contain 15 clues each; the original eight-article demo remains available. Stage 2B review preparation is available; actual human review, playtesting and difficulty calibration remain pending (each 0/60 starter clues).

Independent engineering milestones A/B/C modernize the runtime, preserve an isolated historical CLI, and add a frozen 645-document retrieval pilot. Two ranking variants did not beat the development baseline, so the playable opponent is unchanged. See [runtime migration and before/after evidence](docs/MODERNIZATION.md) and [experiment commands, results and limitations](docs/RETRIEVAL-EXPERIMENT.md). That checkpoint passed 120 tests; human playtesting remains deferred.

The follow-on milestone [isolates the guided regression](docs/REGRESSION-DIAGNOSIS.md), fixes symbol-only source indexing, and measures a separate [external TriviaQA challenge](docs/EXTERNAL-CHALLENGE.md): 50 development and 150 test questions over 279,775 full historical article records. Test baseline: 13/150 first, 23/150 Hit@10, MRR@10 0.1095; only 69/150 have an accepted title and 23 of those have redirect-only context. These are local research results, not general accuracy or official TriviaQA scores. Final verification passes 133 Java tests and seven Node tests. External data has non-commercial-research terms and stays outside game assets; no opponent, grading or difficulty change was promoted.

## Offline Draft Authoring

The next local engineering slice is implemented: [offline source intake and draft
authoring](docs/SOURCE-INTAKE.md). A Wikipedia-only administrator CLI previews,
explicitly selects and captures bounded sources, then validates operator proposals
and exports immutable schema-1 drafts. The [Jovian demonstration](drafts/jovian-v1/README.md)
contains eight attributed leads and five newly assistant-authored Standard proposals,
**zero eligible clues**, outside the active catalog. No generation provider,
opponent change, human approval or deployment is included.

Actual intake verification: **169 Java tests and seven Node tests passed**;
source/draft/index validation and browser scoring/replay/lost-response checks passed.
See the runbook and dated verification record for scope and remaining limitations.

## Reviewed Local Activation

The [controlled activation workflow](docs/PACK-ACTIVATION.md) separates private
review preview, fingerprinted dry-run plan, explicit activation and exact rollback.
It prepares immutable source-only indexes/files before an atomic catalog switch,
records outcomes and rejects stale/changed/conflicting inputs. The server keeps
its startup catalog; restart is required. Unexpired durable games keep pinned content and receipts.

[Jovian v2](drafts/jovian-v2/README.md) preserves v1, removes answer-revealing
wording/a hint and clarifies support. Its v1.0.1 export stays draft; a separate
assistant source-checked v1.0.2 candidate supports **five Standard clues**, zero
Easy/Hard. Human review/calibration remain deferred. Activation/game/recovery/
rollback were verified with an isolated catalog and separate indexes.
**The normal catalog remains unchanged at v1.0.0**; a normal plan exists but was
not activated. The runbook provides the later operator commands/private preview.

Actual verification: **196 Java tests and seven Node tests passed**, pack/index
checks and three scripted browser games, including engine failure. Historical
results/frozen data are unchanged; game checks are not new accuracy measurements
or human playtests. See [dated evidence](docs/engineering/2026-11-06-jovian-activation-verification.json).

## Private Topic Workshop

An explicitly started [local topic-builder interface](docs/TOPIC-BUILDER.md) now
guides source discovery/selection/capture, manual proposal forms/JSON import,
validation/export, private comparison and deliberate activation/rollback. It
calls the existing Java services directly, with no command runner. The optional
proposal adapter described below remains unconfigured for live calls.
The default target is an isolated workshop, not the normal game catalog.

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.TopicBuilderServer
```

Open **http://127.0.0.1:8766/** and enter the access code printed by that process.
The separate operator session uses same-origin/CSRF protection. Jobs and committed
artifacts survive reload/admin restart; unsaved edits are tab-local and unfinished
jobs are explicitly interrupted. Activation never restarts the player server.
No human approval, calibrated difficulty, automatic generation or deployment is
implied. See the guide for limits, privacy, source policy and recovery details.

Actual workshop verification: **214 Java tests (18 new) and seven Node tests
passed**. Live source intake, isolated activation/two games/rollback, lost-response
and restart recovery, mobile layouts and synthetic cancellation/partial-failure/
unsafe-text checks passed. All 183 preserved reference fingerprints match the
starting baseline. Normal catalog remains v1.0.0; no normal activation or player
restart was performed. See [workshop evidence](docs/engineering/2026-11-06-topic-builder-verification.json).

## Player Topic Requests

[Request a Topic](docs/PLAYER-TOPIC-REQUESTS.md) connects the player to the private
workshop with bounded subject/difficulty requests, explicit pack choice and
verified Ready/Play states. Submission never fetches, authors, approves or
activates content. A separate seven-day owner cookie recovers persisted requests
across reload/server restart. Games now recover across restart too, but keep their separate absolute two-hour expiry.
Ready requires the exact active and running catalog/pack/source identities,
five eligible clues and verified indexes. Disk activation alone reports Restart
Required. Cancellation is terminal for the request, not a rollback or game reset.

Launch the existing `main.GameServer` and, separately, `main.TopicBuilderServer`
using the commands below. The workshop inbox explicitly attaches requests to
their correct target; isolated preparation cannot satisfy a normal-game request.
See the guide for limits, recovery, manual restart and isolated rehearsal steps.

Actual Stage 5A verification: **237 Java tests (baseline 214) and seven Node tests
passed**, plus pack/index and isolated browser handoff/two-game/recovery checks.
All **184** preserved reference fingerprints match; normal catalog remains
v1.0.0 unchanged. No new content, retrieval measurement, human approval or public
deployment is claimed. [Dated evidence](docs/engineering/2026-11-06-topic-request-verification.json).

## Source-Grounded Proposal Assistance

The private workshop now includes [bounded proposal assistance](docs/PROPOSAL-ASSISTANCE.md):
an explicit operator action over selected captured sources, structured article-title
proposals with server-derived exact support ranges, screening, selection/editing
and provenance-preserving draft export. Review, activation and player readiness
remain separate. The adapter uses the existing JDK HTTP client; no dependency or
opponent change was added.

**Live assistance is unconfigured. No paid calls or key setup occurred.** The
normal UI clearly says so and retains manual authoring/import. Deterministic
fixtures exist only on the test classpath, never as normal UI content/fallbacks.
Future secure credentials, explicit compatible model, verified token rates and
approved local budgets are server environment settings; restart, not rebuild,
applies them. See the guide for exact configuration and its spending limitations.

Actual verification: **269 Java tests (237 baseline, 32 new) and seven Node tests
passed**. Offline/loopback adapter checks and a synthetic isolated browser flow
covered lost responses/restart, editing/export, blocked blank review, explicit
assistant source review, activation, a five-clue game/recovery/replay and rollback.
All **185** preserved reference fingerprints match; normal catalog stays v1.0.0.
No live model quality, provider usage/bill, human approval or new retrieval
accuracy is claimed. [Dated evidence](docs/engineering/2026-11-06-proposal-assistance-verification.json).

## Durable Player Recovery

Player games now survive process restart in bounded, owner-only saves outside
`target/`. Starts, computer commitments, scoring, advancement, completion and
replay commit before success is returned. The same browser cookie recovers the
original game/receipts within its absolute two-hour lifetime. A crashed unfinished
search becomes an explicit interrupted no-answer, never a replacement guess.
Old memory-only sessions cannot be retroactively recovered. See
[storage, recovery and first-upgrade instructions](docs/DURABLE-GAMES.md).

Keep assistance disabled for local launches:

```sh
env CLUELAB_ASSISTANCE_ENABLED=false mvn -o -q compile exec:java -Dexec.mainClass=main.GameServer
```

Default private saves: `drafts/local/player-games/<target-hash>/state.json`.
`CLUELAB_GAME_STORE_ROOT` can select another private subdirectory below ignored `drafts/local/` for that process.
Do not put saves in an archive, index or public directory. No provider key,
budget, model call or new dependency is needed. Human review stays pending.

## Fresh-Topic Showcase Candidate

**2026-11-08 final-review follow-up:** a separate source/pack **1.0.3** is
launchable on8797, with a narrow supported alias, concise support, two clarity-only
wording revisions and a metadata warning preserving the original quotation.
All nine source texts, original1.0.1, prior media/receipts and normal1.0.0 remain
unchanged. The [candidate guide](docs/CANDIDATE-SHOWCASE.md) links one private
final-review packet:2:57.72 video, captions, accessible transcript/storyboard,
timecoded rights/credits ledger, Labs copy, hashes and three exact Pending decisions.
Fresh verification: **308 Java / 14 Node**, plus direct grading/source checks and
actual browser/restart/media QA. Revised small development result: **3/5 first,
5/5 Hit@10, MRR@10 0.80**, not held out; both misses retained. Nothing published,
deployed or human-approved. Earlier checkpoints below remain historical.

[Kanye West: Albums and Collaborators](docs/CANDIDATE-SHOWCASE.md) is a retained
isolated candidate with five provisional Standard clues and nine newly captured
Wikipedia leads. It exercises the real request/workshop/review/activation path,
durable restart recovery and explicit replay. The guide contains exact launch
commands (player 8793/workshop 8794), a spoiler-free walkthrough, architecture,
actual QA/results and a Labs case-study draft. It remains assistant source-checked,
not independently human-approved or publicly released. Normal catalog v1.0.0 and
historical measurements are unchanged; live assistance remains disabled.

The [readiness report](docs/SHOWCASE-READINESS.md) now separates completed recovery-
focus/evidence-navigation fixes from pending content, rights and release gates.
An unpublished 2:30 local captioned walkthrough, editable transcript/storyboard
and Labs description are linked in the candidate guide, outside served assets.
Fresh follow-up verification: **305 Java / 14 Node tests**, plus actual browser
lost-response/replay/mobile checks and real same-game process-restart recovery.
This is scripted software QA, not human approval or calibrated difficulty.

## Requirements

- JDK 21 and Maven 3.9.x are enforced; the modern runtime no longer targets Java 8.
- Maven 3.9.x (verified with 3.9.16). The first build downloads the pinned Lucene and Stanford CoreNLP dependencies, including several hundred MB of language models. Later runs work offline with `mvn -o`.

The original Java source remains under `src/main`; Maven now explicitly compiles this layout. No IDE setup or API keys are required. Original assignment and report files remain in the repository.

## Build and test

For a review order, clean-checkout verification commands, intentionally tracked
fixtures and remaining release gates, see the [implementation review guide](docs/IMPLEMENTATION-REVIEW.md).
The [CI workflow](.github/workflows/verify.yml) runs the default suite and tracked-demo
checks with live assistance disabled, no provider secrets and no artifact uploads.

```sh
mvn clean verify
```

The regression suite uses temporary, tiny Wikipedia fixtures and loopback HTTP servers. It requires no full corpus or existing indexes and makes no provider/API calls. Maven fails if it discovers no tests. The browser needs JavaScript and cookies; no Node installation or frontend build is required to play.

## Play a local five-clue game

Run from the repository root. Validate the content, build or verify its source-only indexes, then start the server:

```sh
mvn -q compile exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index'
mvn -q compile exec:java -Dexec.mainClass=main.GameServer
```

Open **http://127.0.0.1:8765/**, choose a topic and level, and select **Start Five-Clue Game**. The server reserves five clues and locks the selection for the game. On each clue, the computer commits its answer before the server accepts yours. Each correct answer earns 100 points, otherwise zero. Case, whitespace and listed aliases are accepted; spelling guesses and "What is ...?" wrappers are not automatically normalized. Submitting reveals both guesses, points, accepted answers and attributed evidence. Use **Next Clue** after each of the first four results. The fifth result completes the game, showing its scores, per-clue review and a missed-clue filter. Stop the server with Ctrl+C.

**General Knowledge / Easy** is the default. General Knowledge, World History, Geography and Music each have five Easy, five Standard and five Hard clues. These are provisional editorial labels. **Original Demo** preserves the eight Easy variants and eight Standard clues; Hard is unavailable. Topic, difficulty, pack version, source scope and all five clues stay fixed during a game. Each selection has its own shuffled, no-repeat cycle. Fewer than five unused clues requires **Reset and Replay Five Clues**, explicitly starting a new cycle that may repeat earlier clues. A selection with fewer than five eligible clues is unavailable. Original Demo leaves three unused clues after a game, so it also requires reset before another five-clue game.

Optional **Show Hint** disclosures remain below the question. Hints are public to the computer even when closed; revealing one changes neither points nor its commitment. Both custom menus support arrows, type-ahead, Enter/Space, Home/End, Tab and Escape. Source evidence appears only after scoring, distinguishing **What the Computer Found** from the pack author's **Accepted Answer: Source Check**.

The main scores belong to the current game; **Session Totals** are separate. Replay starts a new game at zero, and **Previous Games** retains earlier receipts. The server only listens on `127.0.0.1`; use that exact address. Reload/reconnect recovers progress and results, including lost start, answer, advance or completion responses and server restarts. After a connection error, use **Reconnect** before continuing. State lasts two hours from session creation, not two hours from restart, within limits of 12 games and 64 total rounds per session. Cookies and the same private save target are required. A missing/broken index still permits player scoring and game completion with an explicit computer no-answer outcome. The server never builds or writes an index during play.

After scoring, **Save Clue Result** saves that revealed receipt. **Save Game Results** saves the completed five-clue result, also available for previous games. The JSON files preserve original guesses, points, clue IDs, pack/source versions, snapshot hashes and evidence; they do not collect hint use or restore an expired game. Keep answer-bearing files private from unexposed players.

## Review and Playtest

Start with the [existing Stage 2B checklist](docs/PLAYTEST-CHECKLIST.md): it includes local launch, player-friendly instructions, a six-game pilot (Standard in all four topics, then Easy/Hard in one familiar topic), blank logs and issue-adjudication guidance. The [private 60-clue ledger](docs/review/starter-ledger.json) supplies version-linked review material and separate assistant/human fields. Do not read it before first-exposure playtesting. Record actual pseudonyms, dates, familiarity, guesses, hint use, fairness and usability; leave uncollected fields empty. Save private notes under ignored `reviews/local/`.

Preparation is not a player study: one pilot covers 30 distinct clues, not all 60, and cannot calibrate difficulty statistically. The [source review record](docs/PACK-REVIEW.md#stage-2b-preparation) documents 49 readable upstream contexts and 11 access failures in the assistant recheck. No human observations or approvals have been supplied; all original pack statuses/versions and historical measurements remain unchanged. The default dependencies have been modernized, but ongoing advisory/licensing review and deployment hardening remain prerequisites for public release.

## Game Configuration

Settings live in `data/game.json`: catalog path, index base, processing, scorer and port. Paths are relative to the settings file, not the terminal directory. Use another configuration with:

```sh
mvn -q exec:java -Dexec.mainClass=main.GameServer -Dexec.args='data/game.json'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='evaluate data/game.json'
```

Pack indexing reuses only matching collection versions and fingerprints; mismatches fail instead of appending or silently rebuilding. Intentional replacement uses `index data/game.json --rebuild`. Collections are shared across difficulties and can be reused by additional packs. The opponent searches only the chosen pack's configured collection, never private clues, aliases or supporting-passage selections. See the [pack-authoring and validation guide](docs/PACK-AUTHORING.md), [content review ledger](docs/PACK-REVIEW.md), and [independent pack measurements](docs/RESULTS.md#stage-1-topic-packs-small-development-sets).

The earlier game invocation is still supported (five arguments expose Standard only; a sixth adds Easy). Build its eight-article index with the corpus command below first, using a new modern base rather than overwriting a historical index:

```sh
mvn -q compile exec:java -Dexec.mainClass=main.GameServer \
  -Dexec.args='indexes/modern-demo 1 4 data/demo/questions.txt 8765 data/demo/easy-questions.txt'
```

Legacy arguments are `index-base index-type scorer standard-questions-file port [easy-questions-file]` and retain single-clue play. The no-argument default loads `data/game.json` for five-clue games with plain-text/Jelinek-Mercer topic collections. See [game design, API, recovery and verification](docs/LOCAL-GAME.md) and the [retained round contract](docs/LOCAL-ROUND.md). This is a local prototype.

## Try ClueLab without the coursework archive

Run from the repository root:

```sh
mvn -q compile exec:java -Dexec.args='index data/demo/corpus indexes/modern-demo 1'
mvn -q exec:java -Dexec.args='ask indexes/modern-demo 1 4 "Henry Sproatt designed this residence with gargoyles and turrets"'
mvn -q exec:java -Dexec.args='evaluate indexes/modern-demo 1 4 data/demo/questions.txt --json'
```

The demo has eight short articles adapted from the included Wikipedia sample, eight original practice clues and eight guided Easy variants. See [data attribution](data/demo/ATTRIBUTION.md). It is a functional fixture, not evidence of general question-answering accuracy. Its answer keys are deliberately outside the corpus directory and public web assets. No archive, API key, external service, or paid model is needed at runtime.

If an index already exists, the index command refuses to overwrite it. To intentionally replace it, repeat the command with `--rebuild`. Keep the original `wiki-example.txt`, `questions.txt`, assignment, and report unchanged as historical references.

## Commands

```text
index <wiki-directory> <index-base> <index-type> [--rebuild]
evaluate <index-base> <index-type> <scorer> [questions-file] [--json]
ask <index-base> <index-type> <scorer> <clue text...>
search <index-base> <index-type> <scorer> <clue text...>
interactive
help
```

Run commands with `mvn exec:java -Dexec.args='...'` after compiling. Quote paths or clue text containing spaces inside the argument string.

| Index type | Processing | Index directory under the base |
| --- | --- | --- |
| 1 | Original words | `1_idxTypeNone` |
| 2 | Lemmatization | `2_idxTypeLemma` |
| 3 | Porter stemming | `3_idxTypeStem` |

Scorers: **1** BM25, **2** Boolean similarity, **3** TF-IDF, **4** Jelinek–Mercer. The options are preserved; dependency-induced ranking differences are measured separately in [the modernization guide](docs/MODERNIZATION.md).

Only explicit indexing commands write an index. The legacy `index` command reads sorted regular UTF-8 `.txt` files, non-recursively. Each must contain at least one article. Explicit `--rebuild` replaces documents only after a successful commit; a failed/empty/malformed-UTF-8 rebuild rolls back and retains the previous committed index. Lucene's writer lock prevents concurrent writers. A successful rebuild replaces that index's contents, so choose the output base deliberately. `ask`, `search`, `evaluate` and the original `interactive` menus reuse existing indexes without modifying them. Failures produce a nonzero process exit and a message on stderr, not a successful empty evaluation.

The full Wikipedia collection and generated indexes are deliberately excluded from Git (`corpus/`, `indexes/`, the original `src/resources/`, and named index folders). Put extracted collection files in a separate directory. Archived Lucene 7 indexes require the isolated `legacy-baseline` CLI; the default Lucene 10 runtime rejects them and refuses to overwrite them even with `--rebuild`. Pack indexes use a new runtime/schema namespace. Never use the coursework archive as an output base. See [upgrade, rollback and reproduction](docs/MODERNIZATION.md). Allow several GB of free disk for a full rebuild; `MAVEN_OPTS=-Xmx2g` is a useful full-collection JVM budget.

For a complete collection or archived indexes:

```sh
mvn -f legacy-baseline/pom.xml exec:java -Dexec.args='evaluate /path/to/archived-index-base 2 4 questions.txt'
```

Evaluation uses `CATEGORY`, `CLUE`, `ANSWER`, and a blank separator per question. The last separator may be omitted. Accepted answer alternatives use `|`. Matching ignores case and whitespace; it does not use fuzzy matching. Category and clue enter retrieval; expected answers are used only for scoring.

Every prediction is reported, including incorrect and missing results. Metrics are **top-one accuracy** (correct first answers / questions), **accepted-answer hit rate@10** (questions with any accepted answer among ten / questions), and **MRR@10** (mean reciprocal rank of the first accepted answer, with misses contributing zero). This hit rate is not document-level recall. The full file is validated before retrieval begins. JSON reports include each accepted rank, a SHA-256 of the exact question file, document count, index commit, configuration, and build metadata where available.

## Query response contract

`ask` emits one JSON object on stdout; use `mvn -q` to suppress Maven logging. `search` presents the same result in readable text. `QueryEngine.ask(clue, limit)` returns an immutable `QueryResponse` containing ranked candidates, raw retrieval scores, response time in milliseconds, and bounded evidence excerpts with source file/line references. There is intentionally no accepted-answer argument.

Fresh indexes retain original-case titles and readable source text separately from processed search fields. Legacy indexes only have processed bodies, explicitly labeled `processed-index-text`, not source quotations. Evidence is a simple matching-term excerpt, not proof of correctness or a generated explanation. Raw scores are not calibrated probabilities and should not be compared across scorers. Response time includes query processing/retrieval/evidence construction, not JVM startup or index opening; the first lemma request can include model initialization.

The game API reuses this Java response model internally, exposes a small explicit allowlist only after scoring, and renders evidence as text (never raw HTML). It never exposes local index/corpus paths from diagnostic metadata. `GameService` holds the private answer key, computer commitment and score; `GameServer` provides a loopback-only JDK HTTP adapter. Plain HTML/CSS/JavaScript keeps the prototype small without a frontend framework or new runtime dependencies.

## Reproduce comparisons

Run all twelve combinations against three existing indexes under one base:

```sh
mvn -q exec:java -Dexec.mainClass=main.Benchmark -Dexec.args='"/path/to/index-base" questions.txt' > target/benchmark.json
```

The complete JSON goes to stdout; per-configuration summaries go to stderr. A missing index or failed query fails the run. To inspect field norms and score explanations for the first benchmark clue, including a diagnostic-only no-length-normalization ablation:

```sh
mvn -q exec:java -Dexec.mainClass=main.ScoringDiagnostics -Dexec.args='"/path/to/index-base" 1 questions.txt'
```

For a fresh full-collection comparison, build into a new base, never the archive:

```sh
MAVEN_OPTS=-Xmx2g mvn -q exec:java -Dexec.args='index "/path/to/wikiPages" indexes/full 1'
mvn -q exec:java -Dexec.args='evaluate indexes/full 1 4 questions.txt --json'
```

## Recovery baseline and limitations

The original assignment targeted approximately 280,000 Wikipedia articles and 100 clues. The recovered archived indexes each contain 279,865 documents. The earlier exploratory audit's best result was lemma/Jelinek-Mercer at **29/100**, **48/100**, **MRR@10 0.3429**, with aliases correctly counted. The same configuration with the corrected literal-query handling now measures **31/100**, **50/100**, **0.3654** against the unchanged archived index. Neither number is a fresh rebuild. See [measured results and diagnosis](docs/RESULTS.md) for separated historical, archived, fresh, and fixture results.

The saved lemma index is readable and tiny lemma rebuilds pass. The report's historical lemmatization problem remains unexplained; a full lemma rebuild is not yet verified. TF-IDF's zero first-answer result is reproducible, but its norm encoding is compatible: score explanations and a controlled ablation implicate short partial-match bias. The historical scorer is preserved rather than silently tuned on the benchmark.

The default runtime now uses Lucene 10.6.0, CoreNLP 4.6.0 and Jakarta JSON; historical reproduction is isolated. This remains a local prototype, **not a publicly deployable service**: current advisory review, reviewed licensing, authentication, deployment-grade storage/backups, resource/rate limits and deployment hardening remain necessary. Local durable saves are not a public hosting architecture. See [the dependency inventory, compatibility results and known warnings](docs/MODERNIZATION.md). Test coverage preserves private rounds, answer independence, aliases, concurrency/recovery, HTTP boundaries, engine failures and all 12 original demo configurations. Modernization is not evidence of higher retrieval accuracy.

The four starter packs meet the 60-clue target and have assistant source checks, not independent human sign-off. They use small, deliberately clue-aligned excerpts and are not held-out evaluations. The original demo retains honestly unknown historical revisions. Stage 2A software verification is recorded in [LOCAL-GAME.md](docs/LOCAL-GAME.md). **Stage 2B human content review and difficulty playtesting is deferred**, with the [practical checklist and blank record](docs/PLAYTEST-CHECKLIST.md) ready. No player study or new approval is claimed. The external challenge is now measured and inspected; it is a regression set, not a permanently unseen test for later tuning. The [source-intake/draft-pack handoff](docs/ASSISTED-PACK-HANDOFF.md) and [bounded proposal adapter](docs/PROPOSAL-ASSISTANCE.md) are implemented locally, without repurposing benchmark questions as game content. Next is a manual source-to-reviewed-pack rehearsal using durable game receipts for feedback. Live assistance remains unconfigured; any budgeted live pilot requires separate authorization and remains deferred. Broader custom-topic automation, multiplayer and public deployment remain later work. The original [assignment](IBM%20Watson%20Spec.pdf) and [report](IBM%20Watson%20Report.pdf) provide the school-project context.
