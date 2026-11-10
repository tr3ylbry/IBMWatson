# ClueLab foundation: measurements and remaining questions

Measured 2026-10-29 (America/Phoenix), Temurin JDK 21.0.13, Maven 3.9.16,
Lucene 7.7.1, Stanford CoreNLP 3.9.2. No scorer tuning was introduced.
The repository already contained uncommitted restoration work; it was retained
and extended, not reset. Original PDFs, sample and benchmark remain unchanged.

## Benchmark identity

The original `questions.txt` has 100 questions, including 15 answer lines with
`|` alternatives. SHA-256:

```text
e605426371c86853341ca3e0ed0e9e50e9423321e0b37e64392bed9ced6f2f25
```

Retrieval receives category + clue only. Answers are used afterward for scoring.
Matching splits alternatives, ignores case and collapses whitespace, but does not
remove articles/punctuation or use fuzzy matching. All questions remain in the
denominator, including misses. MRR@10 uses the first accepted rank, or zero.

## Historical references, not new measurements

The 2020 report gives top-one results of 20/9/0/27 for plain text and
21/12/0/25 for stemming (BM25/Boolean/TF-IDF/Jelinek-Mercer), and no lemma scores.
Those used the original evaluator; its entire-pipe-string comparison was wrong.
Do not retroactively substitute the corrected numbers for that report.

The previous exploratory pass used the three archived indexes, each with
279,865 documents, and correctly counted aliases. Its best was lemma/JM:
29 first answers, 48 hits within ten, MRR@10 0.3429. Exact-string matching on
that same run counted only 27 first answers. This is a different run from the
2020 report, even where numbers coincide.

## Archived indexes: exploratory versus corrected engine

Each cell is **correct first / hit within 10 / MRR@10**, over 100 questions.
Both columns query the same archived files without modifying them. The current
engine escapes query syntax so clues are literal text, not Lucene field queries.

| Processing | Scorer | Earlier exploratory pass | Corrected engine |
| --- | --- | --- | --- |
| Plain | BM25 | 21 / 44 / 0.2720 | 21 / 45 / 0.2769 |
| Plain | Boolean | 15 / 38 / 0.2186 | 15 / 39 / 0.2228 |
| Plain | TF-IDF | 0 / 8 / 0.0198 | 0 / 8 / 0.0198 |
| Plain | JM | 26 / 46 / 0.3290 | 27 / 48 / 0.3415 |
| Lemma | BM25 | 20 / 47 / 0.2724 | 20 / 50 / 0.2842 |
| Lemma | Boolean | 15 / 34 / 0.2027 | 15 / 35 / 0.2069 |
| Lemma | TF-IDF | 0 / 8 / 0.0177 | 0 / 8 / 0.0177 |
| Lemma | JM | 29 / 48 / 0.3429 | **31 / 50 / 0.3654** |
| Stem | BM25 | 22 / 47 / 0.2917 | 22 / 50 / 0.3028 |
| Stem | Boolean | 14 / 32 / 0.1982 | 14 / 33 / 0.2032 |
| Stem | TF-IDF | 0 / 10 / 0.0270 | 0 / 10 / 0.0270 |
| Stem | JM | 27 / 48 / 0.3371 | 28 / 50 / 0.3538 |

For lemma/JM, accepted ranks change on questions 2 (4 to 6), 45 (6 to 1),
74 (miss to 2), and 80 (miss to 1). For example, question 45 contains
`In 2009: Sookie Stackhouse`; a colon must not silently create a field query.
The parsing corrections cannot repair categories or boundaries already stored
in archived indexes, so these remain archived-index results, not rebuilt ones.

## Fresh full-corpus rebuild

Read all 80 files from the read-only coursework `wikiPages` directory and wrote
only to the working repository's ignored `indexes/full/1_idxTypeNone`.
The corrected parser produced **279,775 documents**. The 90-record difference
from the archives is reported rather than hidden; a full title-by-title audit
has not been performed, and equal source provenance cannot be inferred from
document counts alone.

A second explicit rebuild using the final strict UTF-8 reader again produced
279,775 documents (commit `segments_2`) and the same scores below, confirming
that repeated indexing does not append duplicates.

| Processing | Scorer | First / 100 | Hit@10 / 100 | MRR@10 |
| --- | --- | ---: | ---: | ---: |
| Plain, freshly rebuilt | JM | 27 | 51 | 0.3498 |
| Plain, freshly rebuilt | TF-IDF | 0 | 4 | 0.0103 |

Compare plain/JM to the **corrected archived plain/JM** row (27/48/0.3415), not
to lemma/JM. Fresh full lemma and stem indexes have not been built or benchmarked.
The original lemma-build failure remains unexplained; querying the archived
lemma index and rebuilding the small lemma fixture both work.

## Small fixture results

The eight-document attributed demo has eight original clues. Fresh demo indexes
were built for plain text, lemma, and stemming, and all four scorers were tested
against each: **8/8 first, 8/8 within ten, MRR@10 1.0000 in all 12 combinations**.
This intentionally easy fixture validates mechanics, not general accuracy.
With only eight documents, hit@10 is particularly weak evidence of quality.

An example first lemma request returned `Burwash Hall`, readable evidence and
source `articles.txt:9`, in about 451 ms including cold NLP initialization but
excluding JVM/index startup. This is a smoke measurement, not a latency benchmark.

## TF-IDF diagnosis

The zero first-answer score is reproduced, not dismissed as an inferior method
and not repaired by silently changing what "TF-IDF" means.

- Archived segment metadata reports creation major version 7. Inspection of
  the installed Lucene 7.7.1 implementation shows BM25 and ClassicSimilarity
  use compatible `SmallFloat.intToByte4` field lengths for these segments.
  The regression suite verifies compatible norms on a freshly built index.
- Score explanations show short partial matches winning. On clue 1, plain
  TF-IDF ranks `america's next top model, cycle 10` first, score 3.4431, decoded
  length 17, field norm 0.24253562 (= 1/sqrt(17)). Its contributions come from
  `10`, `top`, and the possessive fragment `s`, not newspaper evidence.
  A second result, `bunker hill gazette-news`, has length 104 and score 3.3173.
- A diagnostic-only ablation changes just ClassicSimilarity's length norm to
  1, keeping the same archived plain index, query processing and answer scoring.
  It obtains **11/100 first, 40/100 within ten, MRR@10 0.1904**. This is not a
  production scorer, a recommended fix, or a fresh rebuild. It supports a
  length-normalization/partial-match failure mechanism rather than a norm
  decoder bug. A focused fixture independently reproduces short-document bias.
- The normal scorer succeeds on the unambiguous synthetic and demo queries.
  Fresh full plain TF-IDF still has zero first answers, so parser cleanup alone
  is insufficient. Redirect handling, query term selection and coverage-aware
  ranking need held-out evaluation before making retrieval changes.

Run the read-only `main.ScoringDiagnostics` utility shown in the README to
reproduce explanations and the ablation. It never passes answer strings into
query construction. These diagnostics explain observed cases; they do not
prove every one of the 100 failures has a single cause.

## Foundation verification (before the playable milestone)

23 JUnit tests pass, including all 12 demo configurations, title boundaries,
category reset, explicit replacement, failed rebuild rollback/lock release,
malformed UTF-8, aliases, missing hits, rank 2 and rank 11, input validation,
query punctuation, answer-key separation, JSON escaping, immutable responses,
readable evidence, legacy evidence labels and unknown-schema rejection.

`mvn clean verify` compiles, tests and packages the actual sources successfully.
A real missing-index CLI invocation returned exit code 1 with an actionable
error, without creating an index. Generated indexes and
corpora are ignored. Detailed local run artifacts live under ignored `target/`;
the README commands regenerate them. Build metadata distinguishes ClueLab
rebuilds from legacy indexes without pretending unknown legacy provenance is
known. These metadata labels are provenance hints, not tamper-proof signatures.

The foundation's planned next step was a server-owned round: distribute category/clue, lock player
and independently retrieved computer answers, score against a private answer
key, then reveal source evidence. Before public deployment, modernize/audit the
historical dependencies, decide on public clue licensing, and add HTTP resource
limits and safe rendering. This foundation is intentionally local-only.

## Playable milestone and guided demo

The local round is now implemented; see [design and verification](LOCAL-ROUND.md).
The suite now contains 40 passing tests, including the original 23. No retrieval
scorer changes were made and no full-corpus or archived-index measurements were
repeated for this milestone. The archived lemma/JM reference remains
31/100 first, 50/100 Hit@10 and MRR@10 0.3654 as recorded above.

Easy adds eight guided clue variants while Standard preserves the original demo
questions. The guided pack measures **8/8 first, 8/8 Hit@10, MRR@10 1.0000** on
the freshly rebuilt eight-document plain/JM demo index; a temporary fresh index
also passes all eight guided game rounds in the integration suite. Hints are
part of the public clue, never taken from the private scoring answer line.
These small-fixture numbers do not estimate human difficulty or general engine
accuracy. Browser verification includes correct, incorrect, alias, repeated
submission, missing-index, difficulty-picker and optional-hint flows.

## Stage 1 Topic Packs: Small Development Sets

Measured 2026-11-04 (America/Phoenix), Temurin JDK 21.0.13, Maven 3.9.16,
Lucene 7.7.1 and Stanford CoreNLP 3.9.2. Baseline checks were actually rerun:
40 tests passed before changes. The final clean build has **83 passing tests**,
zero failures/errors/skips, and a packaged jar. JavaScript syntax validation,
catalog/index validation and repeat indexing/reuse passed. No dependencies or
scoring algorithms changed. The original 100 questions still parse and the old
12-configuration demo checks still pass.

These are **freshly built, tiny source-collection indexes**, not coursework
archives, full-corpus rebuilds, or held-out evaluations. Each pack is evaluated
independently over its configured source collection. Each new pack has 15 clues
and 15 source excerpts, five clues per provisional difficulty. Original Demo
combines its eight Easy variants and eight Standard clues over eight preserved
article summaries. All versions below are **1.0.0**, with plain processing (1)
and Jelinek-Mercer (4). Public hints are included in retrieval for both players.

| Pack ID | Allowed Source Scope | Clues / Documents | First / Accuracy | Hit@10 | MRR@10 |
| --- | --- | ---: | ---: | ---: | ---: |
| `general-knowledge-clues` | `general-knowledge-sources@1.0.0` | 15 / 15 | 15 / 100.00% | 15 / 100.00% | 1.0000 |
| `world-history-clues` | `world-history-sources@1.0.0` | 15 / 15 | 15 / 100.00% | 15 / 100.00% | 1.0000 |
| `geography-clues` | `geography-sources@1.0.0` | 15 / 15 | 14 / 93.33% | 15 / 100.00% | 0.9667 |
| `music-clues` | `music-sources@1.0.0` | 15 / 15 | 15 / 100.00% | 15 / 100.00% | 1.0000 |
| `coursework-demo-clues` | `coursework-demo-sources@1.0.0` | 16 / 8 | 16 / 100.00% | 16 / 100.00% | 1.0000 |

Geography Standard is 4/5 first, 5/5 Hit@10 and MRR@10 0.9000; its Easy and Hard
subsets are each 5/5, 5/5, 1.0000. Every other new topic/level is 5/5, 5/5,
1.0000. Original Demo's two supported levels are each 8/8, 8/8, 1.0000; Hard has
zero eligible clues, not a perfect score. Lake Baikal's clue retrieves Lake
Tanganyika first and the accepted Baikal answer second. This miss was not tuned
away. Difficulty labels are not a prediction of retrieval rank or human skill.

The complete dated [pack report](PACK-RESULTS.json) retains pack hashes, collection
digests, fresh-index commit/build metadata, per-difficulty metrics and every
prediction/rank. These scopes contain brief, clue-aligned excerpts; the high
numbers show local mechanics on development content, not general improvement or
a comparison to the 100-question benchmark. Full-page or larger-corpus retrieval
will be a different evaluation. No archived-index or full-corpus run was repeated
in this stage; all historical measurements above remain intact.

New source records preserve attribution and available metadata, but no immutable
upstream revision IDs were captured. The 60 new clues received assistant factual
source checks and editorial review, **not independent human sign-off**, rights
clearance or player calibration. See [the review ledger](PACK-REVIEW.md).

Browser verification covered all topic choices, keyboard menus, touch emulation,
320/390px layouts, optional hints, correct/incorrect/alias scores, immutable active
choices, explicit unavailable content, a complete no-repeat cycle and replay,
duplicate receipts, reload and dropped-response recovery, plus real missing-index
failure with player scoring intact. See [the exact checks and limitations](LOCAL-ROUND.md).

Reproduce after building the source indexes:

```sh
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='evaluate data/game.json'
```

Next is independent content review/player testing followed by recoverable
five-clue games. Automatic authoring, custom topics and broader answer extraction
remain future work. Dependency modernization, public rights review and deployment
hardening remain prerequisites for public release.
