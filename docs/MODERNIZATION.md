# Runtime Modernization (Milestone A)

Follow-on engineering: [the guided regression is now isolated](REGRESSION-DIAGNOSIS.md)
to Lucene's changed default stop-word policy. The modern policy/rank is retained.
A separate symbol-only source-field defect was fixed; the broad external challenge
built all 279,775 records. [Current verification/results](EXTERNAL-CHALLENGE.md)
report 133 Java and seven Node tests. The original dated observations below and
their measurement artifacts are preserved, not retroactively rewritten.

Checked 2026-11-05, America/Phoenix. This is a local prototype upgrade, not a
deployment approval or a comprehensive vulnerability audit. Human starter-content
review, playtesting and calibration remain deferred, each 0/60.

## Starting State and Decisions

The checkout already contained a partial upgrade and `legacy-baseline/`; both
were preserved. The actual first `mvn -o clean verify` ran 108 tests with **one
failure**, not the recorded Stage 2B 108-pass result. The failing integration test
assumed every computer guess remained correct. British Standards in guided demo
clue 2 now ranks third behind Behistun Inscription. Scoring still correctly gives
the computer zero, so the test now checks its award against the independently
committed guess rather than requiring perfect retrieval. Neither clue nor alias
was edited to hide that regression.

The modern runtime requires **JDK 21 and Maven 3.9.x**, enforced at build time;
Java 8 bytecode is no longer supported. No browser framework was introduced.
The English models and executable CoreNLP jar have the same version. JSON imports
use `jakarta.json`; HTTP JSON shapes and private-answer boundaries are unchanged.

| Component | Historical CLI | Modern Default |
| --- | --- | --- |
| Lucene core/queryparser/analysis | 7.7.1 | 10.6.0 (`lucene-analysis-common` renamed artifact) |
| CoreNLP and English models | 3.9.2, plus redundant parser | 4.6.0, no standalone parser |
| JSON | GlassFish javax.json 1.0.4 | Jakarta JSON API 2.1.3 + Parsson 1.1.9 |
| Logging | Transitive SLF4J 1.7 without provider | SLF4J API/simple 2.0.17 |
| Protobuf | See legacy inventory | Compatible 3.x maintenance version 3.25.8 |
| JUnit | 5.3.1 engine | Jupiter 6.1.3 |
| Build plugins | Preserved legacy POM | Clean 3.5.0, resources 3.4.0, jar 3.5.0, compiler 3.16.0, Surefire 3.6.1, exec 3.6.4, dependency 3.9.0, enforcer 3.6.2 |

Full resolved direct/transitive inventories: [modern](engineering/dependencies-modern.txt)
and [legacy](engineering/dependencies-legacy.txt). `pom.xml` pins direct/build
versions; transitive versions follow the pinned upstream POMs plus the explicit
Protobuf override. Jar output timestamps are fixed for repeatable packaging;
runtime latency and generated index timestamps are not deterministic artifacts.
Two final clean builds on the same toolchain produced the identical packaged jar
SHA-256 `fb144dc662b36af3b19b14353136efd2da8dcccfdb2a679c29963334c1878ca8`.
This does not claim byte-identical output across different JDK patches/platforms.

Unused CoreNLP servlet, holiday/JAXB, XOM/Xerces/Xalan and legacy JSON trees are
excluded from the default classpath. ClueLab uses trusted English word/POS/lemma
models, not the general CoreNLP server, SUTime holiday rules or XML APIs. This is
not a drop-in distribution of every CoreNLP feature. EJML remains at CoreNLP's
tested model-compatible version rather than introducing an unrelated matrix API
migration. No uploaded/remote serialized model is accepted.

## Official Release and Advisory Evidence

- [Lucene downloads](https://lucene.apache.org/core/downloads.html) identifies
  10.6.0; [requirements](https://lucene.apache.org/core/systemreqs.html) and
  [change log](https://lucene.apache.org/core/10_6_0/changes/Changes.html) document
  the supported release line. Lucene 10 requires Java 21; stored-field access
  now uses `storedFields().document()`.
- [CoreNLP 4.6.0 release notes](https://github.com/stanfordnlp/CoreNLP/releases/tag/v4.6.0)
  document Java 11 minimum and deserialization/SQL-interface security changes
  (GHSA-8wc5-xrgq-6mgh, GHSA-376p-57mg-3fc4, GHSA-x37j-fc4v-fcr9). The project
  [overview](https://stanfordnlp.github.io/CoreNLP/) notes removal of the old
  Lucene-dependent patterns component. We do not expose those service interfaces;
  newer dependencies do not make Java deserialization safe for untrusted input.
- [Lucene replicator advisory](https://lists.apache.org/thread/3f3oph7bqnqspb9q5p0gm5mgc1b6thjo),
  CVE-2024-45772, concerns its deprecated HTTP replication module; that artifact
  is not present here. Do not confuse it with Lucene core or Lucene.NET advisories.
- [JUnit release notes](https://docs.junit.org/6.1.3/release-notes),
  [Parsson releases](https://github.com/eclipse-ee4j/parsson/releases),
  [JSON-P specification](https://jakarta.ee/specifications/jsonp/2.1/),
  [Protobuf 3.25.8](https://github.com/protocolbuffers/protobuf/releases/tag/v3.25.8),
  [Maven plugin documentation](https://maven.apache.org/plugins/), and
  [exec plugin](https://www.mojohaus.org/exec-maven-plugin/usage.html) were checked.

No automated whole-dependency advisory-database scan or exploit/reachability audit
is claimed. Recheck current advisories and all retained transitives before any
public release. Preserve loopback binding, no uploads, fixed assets and bounded
sessions; licensing, durable storage, access control, rate limits and operational
security remain release work.

Observed warnings are retained rather than suppressed: Lucene reports optional
Vector API/native-access warnings (those optimizations were not enabled for the
measurements), and CoreNLP's simple API logs a multiple-sentence-splitter diagnostic.
Focused tests and a token probe confirm later sentences/public hints survive in
all three preprocessing modes; the upstream diagnostic's exact cause and the
guided-demo rank change remain unresolved. Test compilation also reports a
deprecated API use in the HTTP test harness. No warning is a public-release
clearance or proof of a missing production defect.

## Index Compatibility, Upgrade and Rollback

Lucene 7 files were **tested**, not assumed compatible: the modern CLI rejected
a freshly built legacy index, while the isolated legacy CLI read it. A modern
`--rebuild` against that old index was also rejected and every file fingerprint
remained unchanged. See the [machine-readable evidence](engineering/2026-11-05-compatibility.json).
The original saved demo index was separately opened by the legacy CLI and rejected
by the modern one. An archived lemma query also succeeded through the legacy CLI;
the historical 100-question benchmark was not rerun.

The default strategy is a fresh source rebuild, **not in-place IndexUpgrader** or
codec bridges across three major releases. Pack indexes now live under:

```text
indexes/packs/lucene-10.6.0-nlp-4.6.0-schema-3/<collection-id>-<version>/<processing>/
```

Schema 3 adds an analyzed title field for separately controlled experiments;
the default still searches the original combined `body`. Source/pack content
versions do not change merely because the engine/index format changed. Runtime,
schema, processing and collection identity are verified. Old schema-1 indexes
and the partial upgrade's schema-2 directories remain untouched. Unknown/different
runtime indexes cannot be replaced by modern `--rebuild`; choose a new base.
Within the same runtime, replacement still needs explicit `--rebuild` and retains
the previous commit on failure. Matching modern pack indexes are reused.

```sh
mvn clean verify
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -q exec:java -Dexec.mainClass=main.GameServer
```

For the legacy single-round invocation, build a **new** index base such as
`indexes/modern-demo`, not the old `indexes/demo`, then use that new base in the
five/six-argument server command. [Durable player recovery](DURABLE-GAMES.md) now
preserves unexpired receipts across restart; absolute two-hour expiry remains.
JSON exports remain review records, not importable game saves. Recorded
modernization measurements below are unchanged.

Historical reproduction/rollback uses the isolated CLI, never a legacy web
server or a mixed classpath. No modern jar depends on the old project:

```sh
mvn -f legacy-baseline/pom.xml compile
mvn -f legacy-baseline/pom.xml -q exec:java \
  -Dexec.args='evaluate "/read-only/archive/src/resources" 2 4 questions.txt --json'
```

Run from the repository root so the question path remains explicit. The old
dependencies are retained only for trusted local reproduction; do not deploy
them, open untrusted indexes/models, or set an archive directory as an output.
Rollback of the game itself requires the preserved pre-upgrade source revision
and its matching old indexes, not replacing modern jars in a running JVM. All
original benchmark files/results remain untouched.

## Reproduction and Results

```sh
mvn compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
mvn -f legacy-baseline/pom.xml compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath \
  -Dmdep.outputFile=target/classpath.txt
node tools/compatibility.mjs
```

Node 24 was used for the optional engineering scripts; it is not needed to play.
The script writes fresh indexes to a unique ignored base and a report to
`target/compatibility-results.json`. It never replaces earlier indexes/reports.
Both engines receive the identical corpus and question bytes. All 12 original
demo combinations: **8/8 first, 8/8 Hit@10, MRR@10 1.0** before and after.
Guided plain/JM: **8/8 -> 7/8**, Hit@10 remains 8/8, MRR **1.0 -> 0.9167**.
Dependency-induced changes are not ranking improvements; the precise contribution
of tokenizer/model versus Lucene scoring changes to this miss remains unisolated.
Multiple-sentence/hint preservation is now explicitly tested in all three modes.

On the same 15-document pack scopes, modern plain/JM still obtains General
Knowledge 15/15, World History 15/15, Geography 14/15 (Baikal rank 2), Music 15/15.
Original Demo's 16-clue pack changes 16/16 -> 15/16, MRR 1.0 -> 0.9583.
Hit@10 remains perfect on these tiny scopes. Original recorded results are not
overwritten; [the new report](engineering/2026-11-05-modern-pack-results.json)
identifies runtime/schema/source hashes. These remain answer-aligned development
fixtures, not general accuracy or human-difficulty evidence.

Milestone A's automated checkpoint: **111 passing tests**, zero failures/errors,
plus fresh-index/legacy-read/rebuild-protection checks and pack evaluation. The
final engineering/browser checks are recorded in the retrieval-experiment guide.
