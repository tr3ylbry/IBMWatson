# External Retrieval Challenge v1

Local non-commercial research benchmark `cluelab-triviaqa-external@1.0.0`, checked
2026-11-05 America/Phoenix. Not a playable pack, official TriviaQA leaderboard
submission, human review, calibrated difficulty study or generally representative
accuracy claim. The old 645-document pilot and historical measurements are intact.

## Provenance and Access Terms

Questions are **verbatim externally authored TriviaQA questions**, not new or
rewritten assistant questions. Joshi, Choi, Weld and Zettlemoyer describe collecting
questions from trivia enthusiasts/sites: [official project](https://nlp.cs.washington.edu/triviaqa/),
[ACL 2017 paper](https://aclanthology.org/P17-1147/). Each local selection retains
QuestionId, QuestionSource, supplied answer/value/aliases/matched entity and a hash
of the exact question. Individual authors/dates/revisions are unknown where not
provided; release date is not question authorship date. Sources were not live-scraped.

Terms were inspected before question import. The [official repository](https://github.com/mandarjoshi90/triviaqa)
states Apache-2.0 applies to code and data, but the downloaded archive's README
also restricts dataset/derived data to **non-commercial research** and disclaims
ownership of underlying copyright. Both unmodified notices and the adaptation
[NOTICE](../data/external-challenge/v1/NOTICE.md) are preserved. We do not resolve
that tension into public/commercial clearance. Raw data, selected questions/keys
and detailed reports stay ignored locally, outside web assets and game configuration.
Do not use them as commercial content or copy them into generated playable packs.

Official unfiltered 1.0 archive SHA-256:

```text
7f0c94207fd0a1eb7bba3d52cdfe0735c98de850b9e5aa4e75f2776ef9a531d0
```

## Fixed Selection and Source Scope

[Plan](../data/external-challenge/v1/plan.json),
[frozen identities/hashes](../data/external-challenge/v1/freeze.json),
[selected IDs](../data/external-challenge/v1/selection.json),
[exclusion ledger](../data/external-challenge/v1/exclusions.json).
These were fixed before retrieval. The preparer reproduces identical bytes and
refuses different existing output. This is a local freeze, not a third-party
timestamped preregistration or cryptographic signature.

- Candidate pools: first 1,000 IDs per upstream train/dev, ordered by SHA-256 of
  UTF-8 `cluelab-external-v1`, a newline, and QuestionId. The first eligible 50
  train cases form development; first eligible 150 dev cases form **our local
  test**, not the upstream hidden test. Full files contain 87,622 and 11,313
  records; 96,935 outside-pool questions are counted, not claimed reviewed.
- Exclude unsupported non-WikipediaEntity answers, invalid text/aliases, deictic
  media requests and duplicate IDs/questions/selected answer aliases, including
  cross-split aliases and exact prior ClueLab question texts. No retrieval result
  or answer-coverage score controls selection. All 2,000 pool IDs have a disposition:
  200 selected, 266 unsupported types, 27 answer duplicates, 1,507 eligible beyond
  the fixed quotas. No media/invalid-text exclusion occurred in these pools.
- Project only upstream matched entity name/value/aliases to article-title labels,
  deduplicating with existing `Evaluation.normalize`: ASCII-edge trim, ROOT
  lowercase, collapse default Java whitespace runs to one space. No new aliases,
  punctuation/article removal, fuzzy grading or wording changes. This differs
  from official TriviaQA normalization and is not its standard QA task/score.
- Broad collection: **all 80** available coursework `.txt` shards, byte-identical,
  with complete processed article context, redirects and disambiguations. No
  question-based article shortlist, context extraction or document insertion.
  **279,775 records, 275,030 distinct normalized titles**, **840,051,120 source
  bytes** (per-file sizes/hashes are in the manifest). This is a subset
  of historical Wikipedia, not all Wikipedia. The archived 279,865-document count
  discrepancy remains unresolved; no historical index was rebuilt or edited.
- [Collection manifest](../data/external-challenge/v1/collection.json) pins every
  shard's bytes/hash/count and a SHA-256 of the full ordered membership JSONL.
  Regenerated membership retains per-article title, source/line range and raw hash.
  Unknown revisions stay null. The filename date is only a hint. Historical
  Wikipedia/CC BY-SA 3.0 attribution and separate third-party rights remain recorded.

The test includes politics/history, space/science, geography/transport, literature,
music and sport. Examples by ID: `wh_1982`, `dpql_5884`, `bb_6913`, `wh_261`,
`wh_430`, `qw_2843`. These are descriptive assistant topic checks after evaluation,
not externally supplied topic labels, balanced quotas or human difficulty approval.
Competing entities, indirect definitions, aliases, redirects and shared-word
distractors are retained. No resampling followed the result.

## Evaluation Contract and Results

The fixed plan runs **only baseline**, development first then test. Plain words,
Jelinek-Mercer 0.5, original combined body, explicit modern no-stop policy, top ten.
The sole engine input is the verbatim upstream question: no invented category,
answer key, aliases, source URLs, authoring metadata, expected passages or player
guess. Source indexing can run with the private directory absent. Actual index
title availability is checked independently before scoring; its metadata must
match the frozen collection, membership, runtime and processing.

No old weighting/coverage variant was run on this challenge. The old pilot used
different questions, source scope and category/hint inputs, so its high scores
are not a before/after comparison or evidence of improvement here.

| Split | Questions | First / Accuracy | Hit@10 | MRR@10 | Accepted-Title Coverage |
| --- | --- | --- | --- | --- | --- |
| Development | 50 | 2 / 4.00% | 7 / 14.00% | 0.072857 | 24 / 48.00% |
| Local test | 150 | 13 / 8.67% | 23 / 15.33% | 0.109508 | 69 / 46.00% |

All denominators include missing coverage. Test: 46 canonical-title matches,
23 alias-only matches, 81 with no accepted normalized title. Of 69 title-covered
cases, 56 are not first and 46 fall outside ten. Development: 16 canonical,
8 alias-only, 26 missing. These are **title-availability**, not factual support,
source completeness or alias-quality certifications. A relevant fact may occur
inside another article without a title this engine can return as an accepted answer.

Post-evaluation [source-shape diagnostics](engineering/external-challenge-v1-source-shapes.json)
refine that caveat without changing labels/denominators:

| Test Source Shape | Cases | First | Hit@10 |
| --- | --- | --- | --- |
| No accepted title | 81 | 0 | 0 |
| At least one non-redirect title record | 46 | 12 | 21 |
| Only redirect title records | 23 | 1 | 2 |

Redirect detection is a heuristic on frozen raw snapshots, not support validation.
The 23 stubs are context gaps even though the declared title-availability metric
counts them. The 34 non-redirect cases not first (25 outside ten) also demonstrate
ranking/context failures; no post-hoc filtered accuracy replaces the full result.

Three repetitions per case, one open reader, separate constant-query warmup:

| Split | Samples | Median ms | p95 ms | Mean ms | Min / Max ms | Separate Warmup ms |
| --- | --- | --- | --- | --- | --- | --- |
| Development | 150 | 7.117 | 23.693 | 8.844 | 1.672 / 33.797 | 110.683 |
| Test | 450 | 6.906 | 18.892 | 8.545 | 1.666 / 48.724 | 88.700 |

Temurin 21.0.13, Maven 3.9.16, Lucene 10.6.0/CoreNLP 4.6.0/schema 3. Timing
includes preprocessing/retrieval/evidence, not JVM/index/dictionary opening.
Nearest-rank percentiles; correlated warm repetitions on a local machine, not a
controlled throughput study. Rankings were repeatable. Saved
[development](engineering/external-challenge-v1-development.json) and
[test](engineering/external-challenge-v1-test.json) summaries retain source/split,
implementation and detailed-report hashes. Detailed keys/evidence persist only
under ignored `corpus/external-challenge-results/`, surviving Maven clean.

## Failure Review and Exposure

The frozen plan named first-three canonical misses, first-two missing-title
cases, first alias-only, first redirect and first shared-term case. The shared-term
locator uses the first failed prediction whose top title contains a question term
of at least four ASCII letters, excluding the fixed question-word list in the
review helper; this is post-hoc diagnostic selection, not tuning or a new metric.

| IDs | Assistant Finding, Not Human Approval |
| --- | --- |
| `wh_4139` | Full definition/translation is in the Deja vu article, but English-language comparison articles win on phrase/English/literally terms; accepted answer outside ten. The first redirect example is the unaccented-title redirect to the same canonical article, which is also present. |
| `wh_1982` | Baldwin is present but foreign prime-minister pages win on prime/minister/date terms. The question's precise date and use of "elected" also need editorial adjudication: the [UK government biography](https://www.gov.uk/government/history/past-prime-ministers/stanley-baldwin) describes the October 1924 election. No correction or exclusion was made. |
| `dpql_5884` | John Glenn is rank 3 despite a source sentence containing the flight, year and age. Longevity articles rank higher: the top explanation rewards oldest (6.499), person (5.460) and 1998 (3.174), not the full flight constraint. This is not a corpus-title miss. |
| `sfq_23352`, `qw_2135` | Accepted Personal computer and Avengers titles are absent. Related computer hardware/comic records are not equivalent title coverage. These misses remain in 150, not silently discarded or repaired by adding answer documents. |
| `qw_7795` | Book is absent; Books is only a redirect to it. Pedinella wins partly on ISBN citation/template text. This shows the difference between nominal alias/title coverage, available context and accidental lexical matches. |
| `bb_1112` | An Australian terminology question retrieves a Trick Pony album through the shared term Pony. This case also lacks an accepted title; it illustrates competing meanings, not a separately established covered ranking failure or a successful new scorer. |

The post-evaluation explanation probe reruns only original question text through
baseline and explains its retrieved document IDs. Labels never become retrieval
queries. Curated support remains null; post-hoc source inspection is not disguised
as opponent evidence. Upstream aliases/entity mappings can be noisy, punctuation
and date wording can be imperfect, and 2017 question material versus a processed
2014-named subset introduces temporal/context mismatch. No full factual audit is
claimed. Inspecting these test outcomes and examples makes this a **spent test /
regression set** for later tuning. Public dataset familiarity/pretraining exposure
is not excluded by external authorship. Future unseen claims need a new sealed
evaluation set and fixed comparison plan. **No opponent is promoted.**

## Reproduction

From the repository root, JDK 21/Maven 3.9.x and Node 24; several GB disk and
`MAVEN_OPTS=-Xmx2g` for the full index. Read both upstream notices first. Download
only for permitted local non-commercial research, verify **before extraction**:

```sh
mkdir -p corpus/external-triviaqa-v1
curl -fL --retry 2 -o corpus/external-triviaqa-v1/triviaqa-unfiltered.tar.gz https://nlp.cs.washington.edu/triviaqa/data/triviaqa-unfiltered.tar.gz
node tools/prepare-external-challenge.mjs archive corpus/external-triviaqa-v1/triviaqa-unfiltered.tar.gz
tar -xzf corpus/external-triviaqa-v1/triviaqa-unfiltered.tar.gz -C corpus/external-triviaqa-v1 triviaqa-unfiltered/README triviaqa-unfiltered/unfiltered-web-train.json triviaqa-unfiltered/unfiltered-web-dev.json
node tools/prepare-external-challenge.mjs collection '/path/to/read-only/coursework/src/resources/wikiPages'
node tools/prepare-external-challenge.mjs questions corpus/external-triviaqa-v1/triviaqa-unfiltered
mvn clean verify
node --test tools/prepare-external-challenge.test.mjs tools/review-external-challenge.test.mjs
mvn -q exec:java -Dexec.mainClass=main.ExternalChallenge -Dexec.args='validate data/external-challenge/v1 corpus/external-triviaqa-v1'
MAVEN_OPTS=-Xmx2g mvn -q exec:java -Dexec.mainClass=main.ExternalChallenge -Dexec.args='index data/external-challenge/v1 corpus/external-triviaqa-v1 indexes/external-triviaqa-v1-lucene10-schema3-fixed'
MAVEN_OPTS=-Xmx2g mvn -q exec:java -Dexec.mainClass=main.ExternalChallenge -Dexec.args='evaluate data/external-challenge/v1 indexes/external-triviaqa-v1-lucene10-schema3-fixed development'
MAVEN_OPTS=-Xmx2g mvn -q exec:java -Dexec.mainClass=main.ExternalChallenge -Dexec.args='evaluate data/external-challenge/v1 indexes/external-triviaqa-v1-lucene10-schema3-fixed test'
```

An existing committed index is refused; reuse it for evaluation, or choose a new
index base for a rebuild. Never write into the archive. Private files intentionally
do not come from Git; preparation recreates their frozen bytes. The multi-GB train
JSON is streamed one bounded record at a time, without a Python/dataframe dependency.
Preparation checks exact source/archive/split bytes, exclusions and pool accounting.
Validation rejects altered fingerprints, identities, unsupported types, invented
aliases, duplicate questions/answers and incorrect coverage. Indexing reads only
the public source manifest/freeze and the source-only `wiki` folder.

For post-hoc review, use the actual local detailed report path printed by evaluation:

```sh
node tools/review-external-challenge.mjs data/external-challenge/v1 /path/to/local-test-details.json corpus/external-triviaqa-v1
mvn dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
javac -cp "target/classes:$(< target/classpath.txt)" -d target/classes tools/diagnostics/ExternalFailureProbe.java
java -cp "target/classes:$(< target/classpath.txt)" main.ExternalFailureProbe indexes/external-triviaqa-v1-lucene10-schema3-fixed /path/to/local-test-details.json wh_4139 wh_1982 dpql_5884 qw_7795
```

## Verification and Next Work

[Saved software verification summary](engineering/2026-11-05-external-verification.json).

Actual baseline: 120 Java tests passed. Final `mvn -B -ntp clean verify`:
**133 passed**, zero failures/errors/skips, jar packaged; **7 Node tests passed**.
New tests cover analysis policy/minimal reversal, real guided rank, symbol-only
source retention in all three modes, frozen data/aliases/splits, source-only
indexing without private files, full denominators, actual title coverage,
answer/player-input independence and summary privacy. HTTP checks deny external
private/selection/corpus/diagnostic paths. Full pack/index validation passed for
five collections, 68 documents, 76 unchanged clues; both legacy/modern original
demo matrices and guided comparison ran. Full broad index/validation and baseline
evaluation passed after the documented symbol-only repair.

Scripted Chromium completed two Music/Easy games, with correct/incorrect/alias and
markup-like guesses, private pre-reveal fields, separately labeled evidence,
duplicate/late requests, replay/previous receipts, saved JSON equality and active/
completed reloads. Deliberately lost start/answer/advance/completion responses
recovered without extra awards/skipped clues. Keyboard custom controls, touch and
320/390px layouts passed; main flow had no page errors. A real missing-index
five-clue game finished at human 500/computer 0 with no fabricated retrieved
evidence, and legacy single-clue alias/duplicate/reload flow passed. Owned servers
were stopped; no missing index was created. These are software checks, not human
playtesting, physical-device/screen-reader/cross-browser review or security audit.

Human-reviewed/playtested/calibrated starter coverage remains **0/60, deferred**.
No content version/status or game contract changed. Next is the bounded,
source-first [assisted draft-pack handoff](ASSISTED-PACK-HANDOFF.md), not a new
opponent, public deployment or reuse of these licensed benchmark questions.
