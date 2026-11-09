# Guided-Demo Modernization Diagnosis

Checked 2026-11-05 America/Phoenix. This supplements, rather than replaces, the
earlier [modernization measurements](MODERNIZATION.md). The actual starting clean
suite passed 120 tests. No clue, alias, grading rule or source was changed.

## Finding

Guided clue 2, category **Made in the UK**, accepts **British Standards**. With
identical eight-article source bytes, complete clue/public hint, aliases and
Jelinek-Mercer lambda 0.5, the isolated legacy runtime ranks it first; modern ranks
it third behind Behistun Inscription and Bob Young (businessman).

**The cause is the default analyzer configuration inherited from an intentional
upstream default change, not a broken scorer or missing hint.** Lucene 7.7.1's
no-argument `StandardAnalyzer` removes 33 English stop words. The default changed
to no stop words in Lucene 8 and remains empty in the installed Lucene 10 runtime.
Primary documentation: [7.7.1 constructor](https://lucene.apache.org/core/7_7_1/core/org/apache/lucene/analysis/standard/StandardAnalyzer.html),
[8.0.0 constructor](https://lucene.apache.org/core/8_0_0/core/org/apache/lucene/analysis/standard/StandardAnalyzer.html),
[modern constructor](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/analysis/standard/StandardAnalyzer.html).
The probe measures the actual installed releases, not just those descriptions.

CoreNLP 3.9.2 versus 4.6.0 also changes hyphen spacing (`two-word` versus
`two - word`, and some stored article fields). Controlled Lucene token streams
are nevertheless identical for **every query/body term** under each fixed stop
policy. Those raw-string differences do not explain this ranking change.
Stored title/category/body, analyzed tokens, constructed queries, native norms,
ranked candidates and complete score explanations are retained in
[the diagnostic artifact](engineering/2026-11-05-guided-diagnosis.json).

## Controlled Isolation

The same probe source compiles separately against each runtime. Each uses only
its native codec; Lucene 7 files are never opened as Lucene 10 or modified in place.
Stored fields are replayed into fresh native scratch indexes, with index-time and
query-time stop removal independently controlled. Scratch indexes are deleted;
original fixture indexes remain separate from historical/archived indexes.

| Index Removes Stops | Query Removes Stops | First Answer, Both Runtimes | British Standards Rank / Approx. Score |
| --- | --- | --- | --- |
| No | No | Behistun Inscription | 3 / 5.678037 |
| No | Yes | British Standards | 1 / 3.460794 |
| Yes | No | British Standards | 1 / 3.321514 |
| Yes | Yes | British Standards | 1 / 3.321514 |

All controlled rankings agree across runtimes; raw scores agree within 0.00001
(minor float rounding is not a rank reversal). In the modern native explanation,
Behistun's entire 8.146557 score comes from `of`, `the`, `with`, `in`; repeated
query occurrences boost `the` fourfold and `in` twofold. Removing those terms
changes both postings/length statistics and the query, explaining the native
difference. This is not evidence that changing stop policy improves general QA.

The modern baseline is now explicitly constructed with `CharArraySet.EMPTY_SET`,
retaining its existing behavior instead of depending on a future library default.
**Guided 7/8 remains 7/8.** No experimental stop-word opponent is promoted. Fresh
all-12 configuration checks still obtain 8/8 on the original eight demo clues;
[new comparison evidence](engineering/2026-11-05-regression-compatibility.json)
does not overwrite the earlier record.

## Minimal Reproducer and Actual Defect

`AnalysisPolicyTest` uses two synthetic documents, not external benchmark clues:
body A `british standards`, body B `the the the of of of`; query
`british standards the the of of`. With plain/JM and no stops, B wins. Removing
stops at either boundary gives A. Separate tests pin the real guided rank, preserve
aliases, and show the hyphen variants produce identical analyzed tokens.

The broad rebuild exposed a **different, established defect**: preprocessing the
added `titleText` field crashed on the symbol-only title U+30A0 at
`enwiki-20140602-pages-articles.xml-0766.txt:112154`. CoreNLP drops that unsupported
symbol and throws an empty-sentence exception. The original title/body and source
article are not empty. The initial broad transaction rolled back; no partial index
was evaluated and no article/question was excluded to make the build succeed.

`Indexer.sourceTokens` now handles only that exact exception **and confirms a zero
PTB-token stream** before returning an empty analyzed field. It retains the raw
title, evidence and document; unrelated exceptions still propagate/roll back.
All three preprocessing modes have a regression test. The corrected fresh plain
index committed all **279,775** frozen records into a separate `-fixed` base.
This repair is not the cause or a fix of guided clue 2's ranking difference.

The [read-only source-field probe](../tools/diagnostics/EmptyInputProbe.java) can
localize the upstream failure without building an index. Its nonzero exit on the
symbol is expected; it deliberately calls the original CoreNLP API, not the fix.
Optional vector/native-access warnings and the CoreNLP duplicate-splitter diagnostic
remain visible; later sentences/hints remain tested. No full lemma rebuild,
historical 100-question rerun or comprehensive dependency security audit is claimed.

## Reproduce

From the repository root, JDK 21/Maven 3.9.x; Node 24 for engineering helpers:

```sh
mvn clean verify
mvn compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
mvn -f legacy-baseline/pom.xml compile org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath -Dmdep.outputFile=target/classpath.txt
node tools/diagnose-modernization.mjs
node tools/compatibility.mjs
```

Reports go to ignored `target/`; fresh fixture indexes use unique ignored bases.
`diagnose-modernization` asserts controlled token equality and rank/score agreement,
not success based on a particular accepted answer. Do not reformat old measured
artifacts or use the coursework archive as an output.
