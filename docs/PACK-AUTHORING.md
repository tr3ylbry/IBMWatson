# Pack Authoring and Validation

The [private local topic workshop](TOPIC-BUILDER.md) provides labeled manual
proposal/review forms, JSON import, validation flags, immutable export and the
existing deliberate activation workflow. It is separate from player assets and
opponent inputs. Diagnostic validation does not reserve a published version;
export does. This guide remains the underlying CLI/data contract.

The optional [proposal adapter](PROPOSAL-ASSISTANCE.md) is implemented but live
assistance remains unconfigured. It reuses the same clue schema. Generated work
uses `authoring.method: provider-assisted` with a private `assistance` record:
managed attempt, capture/input/response fingerprints, provider/model/template,
settings, available usage/estimates and original screening flags. The workshop
checks that record against its original managed artifact and restores canonical
server numeric formatting before export. Do not strip/relabel provenance or
invent human reviewers. Manual `operator-supplied` artifacts remain compatible.
Generated proposals/keys/support choices are never collection/index fields.

Schema 1 is a local, file-based contract. It separates reusable searchable sources,
private scoring/authoring data, catalog availability and operator settings. No
database, provider request, Java change or browser edit is required to add another
topic using the supported `article-title` answer type. Dates, quantities and
arbitrary facts inside an article remain unsupported answer types.

For source-first preparation without a provider, use the [offline intake runbook](SOURCE-INTAKE.md).
`main.SourceIntake` emits this same schema into a separate draft-only catalog.
`SourceIntake validate` reconstructs/fingerprints the whole artifact and reuses
`PackCatalog.loadDraft`; normal `PackCatalog.load` still rejects a draft-only game
default. `SourceIntake index` indexes only the collection's titles/categories/text.
Five proposed clues remain zero playable/evaluable clues. Private proposals,
support selections, findings and authoring provenance are not source fields.
Do not point GameServer at the draft catalog or copy it into the active catalog
to bypass review. CLI exports also reject conflicts with active identity/version
pairs and different existing workspace exports, even at another destination.

The modern runtime uses JDK 21 and runtime/schema-namespaced pack indexes (schema
3). See [migration](MODERNIZATION.md). Engine-only upgrades do not alter editorial
pack/source versions. The separate [retrieval pilot](RETRIEVAL-EXPERIMENT.md) is
not registered as playable content and keeps its evaluation keys out of indexing.

## Layout and Identity

```text
data/game.json                         Operator settings
data/packs/catalog.json                Topic/pack/collection references
data/packs/collections/*-sources.json   Public-source metadata, never answer keys
data/packs/snapshots/<topic>/*.txt       Small UTF-8 source snapshots
data/packs/private/*-clues.json         Clues, aliases, review and support offsets
indexes/packs/                         Generated Lucene files (ignored)
```

The `private` directory is private to the game API, not encrypted or secret from
someone with this checkout. Do not put it in web assets or expose the repository
through a static file server. The JDK server serves only five allowlisted player
assets, including the small presentation module; catalogs, keys and saves are not assets.

All JSON files have `schemaVersion: 1`, a stable lowercase slug `id`, and a
`major.minor.patch` content `version`. Use globally unique source IDs and clue IDs,
preferably prefixed by topic. Collection, pack, topic and difficulty IDs must be
unique in their own catalog namespace; subtopic IDs are unique inside a pack.
Only one active version of an ID is registered per catalog. Archive previous
versions outside the active catalog when publishing replacements.

Versions are editorial contracts, not inferred from filenames. Bump the pack
version for changed wording, aliases, difficulty or support; bump the collection
version for changed sources, metadata or snapshots. Update the catalog references
and its version. A pack must pin the exact collection version. Never silently
replace a published version. SHA-256 fingerprints detect local changes but are
not signatures or proof that an upstream page has not changed.

## Source Collections

A collection object requires `schemaVersion`, `id`, `version`, `language`,
`description` and `sources`. Only `language: "en"` is supported by the current
English analyzer. Each source requires:

| Field | Meaning |
| --- | --- |
| `id`, `title`, `category` | Stable source ID, canonical searchable article title, source category. |
| `url`, `referenceTitle`, `publisher` | HTTPS reference and readable source attribution. A canonical game title can differ from the source page title. |
| `attribution` | Credit, excerpt/adaptation notice and known rights/license information. Never infer a blanket license. |
| `snapshot` | Path relative to the catalog directory, not the collection file. |
| `snapshotKind` | `excerpt` or `legacy-adaptation`; describe what was actually saved. |
| `snapshotSha256` | Lowercase SHA-256 of the exact snapshot bytes, including a final newline. |
| `capturedOn` | Local capture date, `YYYY-MM-DD`, not the page's publication date. |
| `revisionId` | Available immutable upstream revision ID, or explicit JSON `null` when unknown. |
| `revisionNote` | Known publication/update metadata, capture limitations and provenance uncertainty. |

The checked-in starter sources are short excerpts, not full-page archives. The
eight historical demo texts are preserved adaptations with unknown revisions.
Never substitute a capture timestamp, content hash or current Wikipedia page URL
for an unavailable historical revision ID.

Snapshot text is the searchable passage. Indexing includes the source's title,
category and text, but never the private clue, aliases, difficulty, answer source,
support offsets or review notes. All difficulties in a pack share its collection;
the engine does not receive a clue-specific shortlist of acceptable answers.
Several packs may reference one collection, which is indexed only once.

Use `shasum -a 256 path/to/snapshot.txt` to obtain its fingerprint. Snapshots are
bounded to 256 KiB each and JSON files to 4 MiB. References must stay inside the
catalog directory, including after symlink resolution. Files must be valid UTF-8.
Large corpora and generated indexes do not belong in version control.

## Private Packs

A pack requires `schemaVersion`, `id`, `version`, `topicId`, `label`, `description`,
`language`, `collectionId`, `collectionVersion`, `subtopics`, `review` and `clues`.
Each subtopic is `{ "id": "space", "label": "Space" }`.

Example clue shape (the supporting source must exist in the pinned collection):

```json
{
  "id": "general-knowledge-sun-01",
  "topicId": "general-knowledge",
  "subtopicId": "space",
  "category": "Space",
  "text": "Which star sits at the heart of our solar system?",
  "hint": "Earth orbits it.",
  "difficulty": "easy",
  "answerType": "article-title",
  "answerSourceId": "general-knowledge-sun",
  "aliases": ["Sun", "The Sun"],
  "support": {"sourceId": "general-knowledge-sun", "start": 0, "end": 53}
}
```

`support.start` is inclusive and `end` exclusive, measured in Java/JavaScript UTF-16
code units in the exact saved snapshot, not bytes. They must select a nonempty
passage inside that file. The Sun example selects its complete sentence. Use the
actual offsets for every new source. Support may point to another source in the
same collection; it does not influence retrieval. Range validation alone cannot
decide whether that passage supports the clue's premise or accepted answer.

Aliases must include the canonical title identified by `answerSourceId`. Use a
nonempty array of trimmed strings, at most 200 characters each, without control
characters or pipes. Matching reuses `Evaluation.accepts`: case-insensitive,
whitespace-collapsing exact matching. Aliases that become duplicates after that
normalization are rejected. No punctuation stripping, fuzzy guesses or automatic
"What is ...?" wrappers. Add genuinely accepted variants explicitly.

Clue text is at most 3000 characters, category 200 and hint 600. Hints are optional
(omit or use `null`); present hints must be nonempty. They are public, not private
answer material. The UI initially collapses them, but retrieval always receives
`category + " " + text + " Hint: " + hint` when a hint exists. Keep this behavior
when migrating older ` Hint: ` clues. Revealing a hint never changes points.

## Catalog and Settings

The catalog requires `schemaVersion`, `id`, `version`, `defaults`, `difficulties`,
`collections`, `packs` and `topics`. Use the existing catalog as a complete example.
Collections and packs are references with `id`, `version`, `path`. Topic entries
pin `{ "id": "music", "packId": "music-clues", "packVersion": "1.0.0" }`.
Every pack must be registered to its matching topic. Difficulty entries contain
`id` and `label`; every clue must refer to a declared difficulty and subtopic.
Defaults contain `topicId` and `difficulty` and must have eligible content.

Topics with no eligible clues for a difficulty remain visible with zero counts.
The UI disables starting them, and the server rejects them without fallback.
There is no 15-clue requirement enforced by the loader: advertise actual counts
rather than filling a short pack with paraphrases. Exact duplicate clue text is
rejected; detecting repeated facts and near-duplicates requires editorial review.

`data/game.json` separately requires `schemaVersion`, `id`, `version`, `catalog`,
`indexBase`, `indexType` (1-3), `scorer` (1-4) and `port` (1-65535). Its paths are
relative to that settings file and may point to operator-owned locations. The
content catalog cannot use that flexibility to escape its own directory.

The server loads one immutable catalog snapshot at startup. Restart after changing
content; [durable games](DURABLE-GAMES.md) now preserve unexpired sessions and exact
private content pins. Existing rounds/games cannot be retargeted through an API
request or catalog change. Hot reload and automatic durable-schema migrations
are not implemented. Original Stage 1 measurements remain historical.

## Review Is Separate From Validation

Every pack records `review.status`, `reviewer`, `reviewedOn`, `factualNotes`,
`editorialNotes` and `limitations`. These are required even for a draft; state what
has not been reviewed rather than inventing a reviewer or date of approval.

| Status | Eligibility |
| --- | --- |
| `draft` | Structurally loadable but no playable/evaluable clues. |
| `source-checked` | Playable local content with recorded source checks; not necessarily human-reviewed. |
| `human-reviewed` | Only use after actual named human factual/editorial review. |
| `legacy-preserved` | Migrated content, provenance uncertainty retained. |

Before promoting a draft, inspect every clue for factual support in source
context, an unambiguous title answer, useful aliases, distinct facts/subtopics,
misleading qualifiers, optional-hint safety, attribution and provisional
difficulty. Record limitations and reviewer identity honestly. Structural validity
is not factual approval, a licensing decision or a calibrated difficulty rating.
See [the starter review ledger](PACK-REVIEW.md) for the current review depth.

Stage 2B's moderator packet is `docs/review/starter-ledger.json`, not runtime
pack metadata. It pins each of the 60 starter clues to pack SHA-256, collection
version and exact source snapshot, with separate assistant findings and empty
human-review/evidence fields. `ReviewMaterialsTest` detects drift against the
active packs. Human observation and pilot CSV templates remain blank. If real
evidence is added, update the preparation-only blank-field assertions to validate
that evidence, not to fabricate values or suppress review failures.

Review notes alone do not change a published clue version. A wording, alias,
hint, support or difficulty correction still requires a new pack version; source
URL/metadata/text changes require a new collection version and updated pack/catalog
references. Preserve the old version's ledger/receipts and append a new version's
review records. Actual human approval must identify the reviewed version, scope,
date, pseudonym and evidence. A 30-clue playtest is not approval of a 60-clue set,
and `human-reviewed` is not a calibrated difficulty label. Never move moderator
packets or feedback into searchable snapshots or the web asset directory.

## Five-Clue Game Availability

Stage 2A's default UI plays five rounds per game. The schema/validator still
supports smaller packs and reports real eligibility counts. A game requires at
least five eligible clues at its selected difficulty and reserves five unused
clues together. Fewer than five total is unavailable; fewer than five remaining
requires explicit reset/replay. A reset may repeat prior clues and replaces the
partial cycle, while retaining earlier game receipts. Original Demo's eight-clue
levels therefore leave three after one game and require reset for the next.

Do not pad short packs or change review status to bypass those checks. The
game pins pack/source versions and all five clue references at creation. A later
content version cannot retarget an active game. See [the game contract](LOCAL-GAME.md)
and [Stage 2B human review/playtesting procedure](PLAYTEST-CHECKLIST.md). Human
review and difficulty calibration remain pending; software completion does not
promote any pack to `human-reviewed`.

## Commands and Publication Checklist

For bounded offline/admin changes, use the separate
[review preview, dry-run activation and rollback procedure](PACK-ACTIVATION.md).
It preserves frozen drafts, requires explicit source/editorial decisions and a
new candidate version, validates a pinned catalog/index plan and switches only
on an operator command. It does not confer human approval or hot-reload the game.
The compatible commands below are not themselves an atomic catalog activation.

```sh
mvn clean verify
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index data/game.json'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='evaluate data/game.json'
mvn -q exec:java -Dexec.mainClass=main.GameServer -Dexec.args='data/game.json'
```

Add `-o` to Maven once dependencies are cached. `validate` rejects unknown fields,
duplicate JSON keys, unsupported schemas/types/languages, broken references,
invalid aliases/versions/offsets and altered snapshot hashes. It checks canonical
answer titles against the configured searchable collection. `--indexes` also
verifies actual index provenance and document counts. Failure exits nonzero with
file/field/reference context; Maven may include the underlying exception.

Indexing builds new collections or reuses exact matching ones. It never appends.
For an intentional replacement use `index data/game.json --rebuild`. Commits and
rollback are per collection, not one transaction across the whole catalog: after
an interrupted multi-collection build, rerun validation before starting play.
Never configure the read-only coursework archive as an output base.

Evaluation identifies each pack/version/hash and source collection/digest,
records index provenance and per-clue ranks, and reports overall and per-difficulty
accuracy, Hit@10 and MRR@10. Empty draft sets report null metrics, not 100%.
It queries only category, public clue/hint and source scope, then uses aliases
for scoring. Save each result with a date and clearly label development versus
held-out content; do not overwrite historical measurements or tune on the original
100-question benchmark. The old `main.Watson evaluate` format remains supported.

Before distributing a new catalog, run the checks above, review the JSON report,
play each difficulty, exhaust a selection, explicitly replay it, and recover a
round after reload. A regression test adds a sixth topic/pack referencing an
existing collection using data changes alone. Public distribution additionally
requires ongoing dependency/advisory review, licensing review and deployment hardening.
