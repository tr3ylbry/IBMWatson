# Isolated Historical Retrieval CLI

This directory preserves the pre-modernization retrieval CLI only. See
`SNAPSHOT.json` for captured source hashes. Lucene 7.7.1/CoreNLP 3.9.2 are unsafe
to treat as a supported public runtime. There is no HTTP game here and no
dependency from the default application to this project.

From the repository root:

```sh
mvn -f legacy-baseline/pom.xml compile
mvn -f legacy-baseline/pom.xml -q exec:java \
  -Dexec.args='evaluate "/read-only/archive/src/resources" 2 4 questions.txt --json'
```

Use only trusted local indexes/models. Querying is read-only. The isolated CLI
can build fixtures into a fresh ignored directory for comparison, but never use
the coursework archive as an output base. Do not mix old/new jars or upgrade old
segments in place. Build modern indexes separately from source snapshots.
Full decisions and reproduction: [MODERNIZATION.md](../docs/MODERNIZATION.md).
