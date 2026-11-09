# Frozen Retrieval Pilot (Milestones B and C)

Follow-on: [externally authored challenge](EXTERNAL-CHALLENGE.md), 50 development /
150 test questions over all 80 available source shards, now measured independently.
Its different corpus/questions/inputs are not a before/after accuracy comparison
with this pilot. [The guided modernization regression](REGRESSION-DIAGNOSIS.md) is
also isolated. The pilot's frozen inputs and recorded results below remain intact.

This is an operator-only engineering experiment, not a game content pack, a human
study, held-out general-knowledge benchmark or public-deployment approval. Starter
human-reviewed/playtested/calibrated coverage remains 0/60. The original benchmark
and `RESULTS.md`/`PACK-RESULTS.json` remain unchanged.

## Dataset and Construction

Dataset `cluelab-retrieval-pilot@1.0.0` uses source collection
`cluelab-wiki-shard-b@1.0.0`: **645 documents**, the complete first lexicographically
sorted shard of the supplied historical coursework export. Source filename:
`enwiki-20140602-pages-articles.xml-0005.txt`, 10,516,121 bytes, SHA-256:

```text
d3a7b91af2a83d1f61a2fc8b97cb6b5f67dead6fc40532a41619bd8e9d3c3d49
```

Every strict article boundary is included, including redirects/disambiguations.
There is no answer-key-based source shortlist. Forty distinct source documents
support manually authored clues; **605 additional documents** remain in scope,
including related subjects and lexical distractors. They are not exhaustively
judged nonrelevant. This is substantially larger than the eight/fifteen-document
answer-aligned fixtures, but remains an alphabetically restricted shard: 189
titles start with B and 328 with C. All forty authored answers are B subjects,
which adds substantial authoring/answer-selection bias despite the full shard.

The [collection manifest](../data/retrieval-lab/v1/collection.json) records every
document's stable ID, title, source line range and raw-block SHA-256, collection
version, attribution and provenance limitations. Full text stays in ignored
`corpus/`, never Git or web assets. Wikipedia contributor attribution/CC BY-SA 3.0
is inherited from the historical material; exact upstream page revisions and
the original processing history are unknown. The filename's 2014-06-02 date is
not an invented immutable revision. Current article links do not reconstruct
the old text. Embedded third-party quotations require their own rights review.

Twenty development and twenty held-out clues were manually authored from the
frozen source before retrieval trials. The two splits have disjoint clue and
answer-document IDs, and do not reuse the eight demo questions. Each clue has a
supported article-title answer, explicit aliases, a source ID/hash, supporting
quote and current reference link. The support quotes were checked against the
actual saved article blocks. **Assistant excerpt checking is not independent
factual/editorial or human approval.** These historical-source tasks do not assert
that every old statement is current: for example, no old mountain height or
aircraft model is asked, and the Bermuda Triangle clue describes reported popular
belief rather than endorsing a paranormal explanation.

`experiment.json` pins the exact collection and split file hashes before ranking
measurements. Development clues inform configuration selection; the final test
command opens held-out cases only after checking the development selection.
The same assistant authored both sets, related concepts cross splits, and the
author saw source material. Thus **held-out means excluded from parameter/profile
selection**, not independent authorship, model-training exclusion or permanently
unseen data. Once reported, this test set is spent for future tuning decisions;
new tuning needs a new independent evaluation set. No automatic clue-generation
or custom-topic ingestion pipeline was added.

## Reproduce

Use JDK 21/Maven 3.9.x. Node 24 is used only for the source preparation helper.
The source shard must be the byte-identical supplied file (or another trusted
copy with the same hash); no live corpus download is needed. The original
coursework archive is read-only. Do not set it as an index output.

```sh
mvn clean verify
node tools/wiki-shard.mjs '/path/to/enwiki-20140602-pages-articles.xml-0005.txt'
mvn -q exec:java -Dexec.mainClass=main.RetrievalLab \
  -Dexec.args='validate data/retrieval-lab/v1 corpus/retrieval-lab-v1'
mvn -q exec:java -Dexec.mainClass=main.RetrievalLab \
  -Dexec.args='index data/retrieval-lab/v1 corpus/retrieval-lab-v1 indexes/retrieval-lab-v1-lucene10-schema3'
mvn -q exec:java -Dexec.mainClass=main.RetrievalLab \
  -Dexec.args='baseline data/retrieval-lab/v1 indexes/retrieval-lab-v1-lucene10-schema3' > target/lab-baseline-development.json
mvn -q exec:java -Dexec.mainClass=main.RetrievalLab \
  -Dexec.args='develop data/retrieval-lab/v1 indexes/retrieval-lab-v1-lucene10-schema3' > target/lab-development.json
mvn -q exec:java -Dexec.mainClass=main.RetrievalLab \
  -Dexec.args='test data/retrieval-lab/v1 indexes/retrieval-lab-v1-lucene10-schema3 target/lab-development.json' > target/lab-held-out.json
```

The source helper refuses different source bytes or an altered existing output.
`validate` checks fingerprints, versions, source IDs, supported answer types,
aliases, support references/actual quotes, and split overlap. Indexing opens only
the public collection/freeze metadata and source shard, never private clue files;
a regression test builds without a private directory at all. An existing lab
index is never silently overwritten: choose a new output base for a new rebuild.
Queries verify source hash, manifest hash, document count and runtime identity.
The default game never loads this private evaluation data or advertises it as a
playable topic. The original 100-question CLI format remains compatible through
both runtimes as documented in [MODERNIZATION.md](MODERNIZATION.md).

## Fixed Protocol

All trials use the same plain-word index, Jelinek-Mercer lambda 0.5, category plus
clue, top ten evaluation, existing exact normalized aliases, and source scope.
Only the public query text and indexed documents reach ranking. Source support,
accepted labels, player guesses and evaluation IDs never enter query construction,
candidate selection or reranking. Reports separately label the opponent's actual
retrieved passage and the curator's answer-support quote.

Three profiles were declared before trials, not a broad search over parameters:

The original combined body already includes article title and category. Extra
field weighting therefore increases their influence; it does not introduce
previously unavailable answer data.

| Profile | Change |
| --- | --- |
| `baseline` | Original combined body, literal escaped query; unchanged game default. |
| `title-category` | Body 1.0, analyzed title 2.0, category 1.5; per-term multi-field OR query. |
| `coverage` | Baseline's top 30, reordered by distinct content-query-term coverage, then raw retrieval score, then document ID. |

Coverage removes the fixed Lucene English stop list and question words listed in
`QueryEngine.QUESTION_WORDS`. This is lexical matching, not semantic confidence
or answer extraction. A relevant result outside the candidate window cannot be
rescued. Weighted fields can over-reward partial title matches; coverage can
over-reward incidental occurrences in long articles. Candidate scores remain the
underlying raw retrieval scores, not the coverage ordering value or probabilities.

Select using development MRR@10, then top-one count, then Hit@10; exact ties prefer
baseline, then weighting, then coverage. The frozen held-out comparison runs only
baseline and that selection, not all variants for further test-set selection.
Experimental profiles remain operator-only; no opponent-strength selector or
difficulty changes are introduced by this pilot.

Each case is asked five times through one open reader/profile, after a separate
warmup. Reports require repeatable accepted ranks and record 100 response samples
per split/profile. Timing includes query preprocessing, retrieval, optional
reranking and evidence construction, but excludes JVM startup and index opening.
Warmup is reported separately. Median/p95 use nearest rank; mean/min/max are also
reported. Repeated queries share caches, profile order is fixed and samples are
not independent. These are machine-local diagnostic timings, not statistically
controlled speed or production-throughput claims.

## Milestone B Checkpoint

Measured 2026-11-05 America/Phoenix, Temurin 21.0.13/Maven 3.9.16, Lucene 10.6.0,
CoreNLP 4.6.0, index schema 3. Structural/source-quote validation passed for 645
documents and 20+20 cases before trials. Source corpus and indexes were written
only to ignored, separate repository locations.

The first modern **development-only baseline**: **19/20 first (95%)**, **20/20
Hit@10 (100%)**, **MRR@10 0.9750**. Median 2.345 ms, p95 5.973 ms over 100 warm
samples; separate warmup 92.656 ms. This high small-pilot score is not generalized.
The [saved baseline report](engineering/2026-11-05-lab-baseline-development.json)
contains exact fingerprints, provenance, predictions and timing. Held-out ranking
results had not been inspected at this checkpoint. The original historical and
tiny-pack measurements remain preserved separately.

## Milestone C Outcome

The frozen development trial selected **baseline**. No data, aliases, profile
parameters or selection rule were changed after the first baseline measurement.

| Development Profile | Top One | Hit@10 | MRR@10 | Median / p95 ms |
| --- | --- | --- | --- | --- |
| Baseline | 19/20 (95%) | 20/20 (100%) | 0.9750 | 2.403 / 5.997 |
| Title/category weighting | 18/20 (90%) | 19/20 (95%) | 0.9250 | 1.492 / 3.175 |
| Query coverage reranking | 19/20 (95%) | 19/20 (95%) | 0.9500 | 18.903 / 24.132 |

Baseline places Balkan Wars second behind Balkans. Weighting moves BeOS behind
BeBox and loses Balkan Wars from the top ten (History of Chile first). Coverage
also loses Balkan Wars from the top ten (Cultural Anthropology first). Neither
variant gains a development first answer. These are retained regressions, not
examples removed from the test. Coverage's extra stored-text scans also cost
substantially more in this local run. Fixed order/cache effects prevent a general
speed conclusion about the weighting trial.

After selection, the unchanged **held-out baseline** obtained **20/20 first**,
**20/20 Hit@10**, **MRR@10 1.0000**. Median 2.630 ms, p95 5.286 ms, mean 2.944 ms,
maximum 12.918 ms over 100 warm samples; separate warmup 87.452 ms. Because the
selection is baseline, only that one profile was run on held-out cases. Losing
variants were not selected again using test results. Reports:

- [Development trials and retained predictions](engineering/2026-11-05-lab-development.json).
- [Held-out selection and results](engineering/2026-11-05-lab-held-out.json).
- [Separate first baseline](engineering/2026-11-05-lab-baseline-development.json).

The archived development file preserves its exact measured serialization because
the held-out report pins its byte SHA-256 as `selectionSha256`; do not reformat
that artifact. All reports identify the frozen source/split fingerprints and
modern index/runtime. Warm repeat timings are diagnostic, not independent samples.

**No improved game opponent is promoted.** The game retains the original
body-only plain/Jelinek-Mercer configuration. Difficulty, grading, hints and
private scoring rules are unchanged. The perfect twenty-case test has a ceiling
and same-author/source-selection limitations; it does not demonstrate general
100% accuracy, an independently challenging benchmark, or human calibration.
Do not edit this spent test to manufacture failures. Next retrieval work needs a
new, independently authored, broader and harder frozen challenge before tuning.

## Final Cross-Milestone Verification

[Saved verification summary](engineering/2026-11-05-verification.json), including
the actual scripted-browser result flags and unchanged-input checks.

`mvn -B -ntp clean verify`: **120 tests passed**, zero failures/errors/skips.
Repeated from clean output a second time: again 120 passing tests and an identical
packaged jar fingerprint, as recorded in the modernization guide.
The actual starting checkout ran 108 tests with one failure; the recorded earlier
108-pass result is historical. New tests cover runtime/index protection,
multi-sentence processing, source-only indexing, split/alias/source validation,
selection ties, timing summaries, weighted-field routing and coverage evidence.
Existing game tests retain concurrency, answer independence, scoring and recovery.

Also run successfully: pack/source/index validation (five collections, 68
documents, 76 clues), lab validation against the actual 645-document corpus and
all forty supporting quotes, all twelve legacy/modern demo configurations,
guided-demo comparison, legacy read plus modern read/rebuild rejection with
unchanged index fingerprints, and JavaScript syntax checks. The full historical
100-question benchmark and a modern full-Wikipedia rebuild were **not** run.

Scripted Chromium checks on the modern runtime completed two Music/Easy games,
including correct, incorrect, explicit-alias and safely rendered markup-like
guesses; post-score retrieved/curated evidence; five unique clues; game/session
score isolation; replay; unchanged previous receipts; duplicate start/answer
requests and a late old-game answer. Deliberately dropped responses after start,
answer, advance and completion recovered the committed result without duplicate
awards or skipped clues. Active/completed reloads and saved-result JSON matched
server receipts. Keyboard custom menus/answer submission, touch selection and
320/390-pixel layouts were exercised without horizontal overflow.

A separate real missing-index server completed five General Knowledge/Easy clues
with player 500/computer 0, explicit unavailable guesses, null retrieved evidence,
separate curated support, hints and completed reload recovery. The retained
five/six-argument single-clue server also passed alias scoring, duplicate answer
and reload checks against a fresh modern demo index. These are software checks,
not physical-device/screen-reader testing, human observations or a player study.
Starter human-reviewed/playtested/calibrated coverage remains **0/60**, deferred.
Owned preview servers were stopped. Fingerprints reconfirmed unchanged historical
questions/sample/PDFs, `RESULTS.md`, `PACK-RESULTS.json`, existing catalog/pack/source
metadata, isolated legacy source/POM and all frozen experiment collection/split
files. The missing-index browser check did not create an index. Large source text
and all generated indexes remain ignored; the original archive was never an output.
