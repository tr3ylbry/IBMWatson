# Offline Source Intake and Draft Authoring

For a private graphical interface to these same Java services, see
[the local topic workshop](TOPIC-BUILDER.md). CLI compatibility and source limits
remain unchanged. Only an optional progress observer was added to capture;
neither source intake nor validation automatically activates content.

Follow-on outcome: the original draft and measurements below are preserved.
[Jovian v2](../drafts/jovian-v2/README.md) and the separate
[controlled review/activation runbook](PACK-ACTIVATION.md) resolve local editorial
flags without automatic promotion. Intake/export still never activate content.
The normal catalog was not changed; human approval remains deferred.

A later [bounded proposal adapter](PROPOSAL-ASSISTANCE.md) can consume selected
captured sources through a separate explicit workshop action. Its live mode is
unconfigured; this pass made no paid/API model calls. The CLI, source policy,
immutable captures and historical intake evidence below remain unchanged.

The later [durable-game milestone](DURABLE-GAMES.md) supersedes the memory-only
player-session limitation in the dated intake checkpoint below. It changes no
source snapshots, content review statuses or historical retrieval measurements.

## Permitted Sources and Attribution Policy

This administrator-only tool reads **English Wikipedia article text**, not arbitrary
URLs, images, files, external links, benchmark questions or player submissions.
No paid provider, automatic promotion or player custom-topic feature is included.
Retrieved text is untrusted data, never instructions or executable markup.

Before the first fetch, inspect Wikimedia's [Terms of Use, section 7](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use#7._Licensing_of_Content),
[API guidelines](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_API_Usage_Guidelines),
[User-Agent policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy)
and [Action API etiquette](https://www.mediawiki.org/wiki/API:Etiquette).
Checked 2026-11-06. The text reuse policy requires attribution, license notice,
change notice and share-alike compliance. Public accessibility alone is not a
reuse license. Imported quotations/third-party material can have additional
conditions; an operator must inspect the selected pages before accepting the
source manifest. This tool does not certify rights or clear public distribution.

Use CC BY-SA 4.0 for these new text excerpts, credit Wikipedia contributors through
the article/history URL, link the license, and identify plaintext lead extraction.
Keep original/canonical URLs, observed revision IDs (null if unavailable), retrieval
date, API response hashes and exact text hashes. No media is downloaded. Historical
content and TriviaQA terms are separate and unchanged. **TriviaQA must not be used
as intake material, authoring examples or exported clues.**

The operator's source-selection acknowledgement records an actual policy decision,
not human factual approval. Draft factual/editorial findings stay separate from
empty human review fields. Human starter review/playtesting/calibration remain 0/60.

## Design and Bounds

The existing Java CLI/pack architecture is retained. Discovery, explicit selection,
capture and export are separate operations. Network access is only to
`https://en.wikipedia.org/w/api.php`; titles/page IDs are query values, never URLs.
Use a descriptive contactable User-Agent, serial requests, caching and `maxlag=5`.
The [search API](https://www.mediawiki.org/wiki/API:Search) supplies namespace-zero
candidate titles/IDs. [TextExtracts](https://www.mediawiki.org/wiki/Extension:TextExtracts)
supplies plaintext **lead sections**, with `info`, `revisions` and `pageprops` in
the same query. These are contextual excerpts, not complete page archives or
immutable old-revision renders. An observed revision does not prove that the
extract renderer and transclusions used precisely that revision. Save exact bytes
for reproducibility; later live discovery/capture may differ. The extension's
documented extraction/citation/formatting limitations remain relevant.

No HTTP redirect is followed. The destination and every resolved address are
validated; TLS connects to the validated address with hostname verification and
SNI, not a second DNS lookup. Private, local, reserved and non-global addresses
are rejected. Article redirects are resolved by the Action API, with at most five
links and explicit unresolved/cycle/stub failures. A redirect is not source context.

Conservative fixed v1 limits (not player-adjustable): eight default/twelve maximum
preview/selected pages, 1 MiB transferred and decompressed per response, 8 MiB combined
transferred/decoded work per operation (cache hits also count), 15 seconds per request
including DNS/TLS/body, 120 seconds per operation,
one retry for transient transport/429/503/maxlag failures, serial requests at least
one second apart, bounded backoff (honor Retry-After up to 10 seconds, otherwise
stop), five article redirect links, zero HTTP redirects. Gzip is bounded after
decompression as well as before. Usable leads need 400 characters, 60 word tokens
and two sentence terminators, and must fit 128 KiB; no silent truncation. These
are context heuristics, not factual guarantees. Disambiguations are rejected.

The local response cache retains at most 32 MiB / 256 regular files for seven days.
It is not the permanent provenance archive. Capture/export artifacts preserve
their own response and text hashes after cache expiry. Cancellation checks run
through network reads, delays and each export phase; Ctrl+C also closes the process.
Use a cancellation marker for controlled cancellation. An interrupted staging
directory never replaces a committed output. Outputs are immutable: identical
retries succeed, changed bytes at the same destination fail. Choose a new version
and destination for changed input. Local filesystem ownership is trusted; do not
run concurrent writers in attacker-controlled directories.

## Commands

Run from the repository root with the existing JDK 21/Maven 3.9.x setup. Network
steps require a contactable identity, not an API key. Read the policy above and
inspect candidate pages before acknowledging selection. Replace the example
contact URL with your maintained project/contact URL when operating elsewhere.

```sh
export CLUELAB_WIKI_USER_AGENT='ClueLabSourceIntake/1.0 (https://github.com/tr3ylbry/IBMWatson)'
mvn -o -q compile exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='preview "Jupiter moons" drafts/local/jovian-preview 12'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='select drafts/local/jovian-preview drafts/local/jovian-selection 12505,32657610,38930,54211,1051892,43127,43126,44654 --accept-cc-by-sa'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='capture drafts/local/jovian-selection drafts/local/jovian-capture'
```

The IDs above came from the saved demonstration preview; use only IDs present in
**your** preview. Do not silently substitute if current discovery differs. Maven's
`-o` disables Maven dependency downloads, not the explicit Wikipedia adapter. Omit
it for first-time dependency installation. Discovery and capture each have their
own bounded operation budget. Private operator work below `drafts/local/` is ignored
by Git; the small documented `drafts/jovian-v1/` fixture is intentionally retained.
The CLI restricts all content inputs/outputs below `drafts/`, forbids symlink paths,
and cannot write into packs, archives or benchmarks. The source-only generated
index command writes only to ignored `indexes/source-intake/`.

Author a proposal JSON from the collected text, not from benchmark material. Use
the [Jovian proposal file](../drafts/jovian-v1/proposals.json) as a complete schema
example; change IDs/versions/topics/support to match the new collection. Then:

```sh
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='export drafts/local/jovian-capture drafts/local/proposals.json drafts/local/jovian-draft'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='validate drafts/local/jovian-draft'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='index drafts/local/jovian-draft'
```

To reproduce the **saved** fixture without network or authoring changes:

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='export drafts/jovian-v1/capture drafts/jovian-v1/proposals.json drafts/jovian-v1/export'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='validate drafts/jovian-v1/export'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='index drafts/jovian-v1/export'
```

Identical exports succeed without changing files. Different existing output or
the same identity/version with different manifest bytes elsewhere in the bounded
draft workspace fails. A workspace lock serializes CLI exports; active content
identity/version pairs are also refused. Retain prior exports when versioning;
the tool cannot know about deleted/exported versions outside this workspace.
Atomic directory rename is required; unsupported filesystems fail rather than
falling back to partial publication. Crash leftovers start with a dot and are
never considered a completed artifact; remove abandoned staging folders only
after confirming no exporter is running. Cleanup on ordinary failure/cancellation
is automatic. Hashes detect drift, not malicious tampering by a local owner.

For controlled cancellation, add `--cancel=drafts/local/stop` as the **last** CLI
argument and create that marker to cancel. Remove it before a retry. Ctrl+C is
also supported through process termination. Reads/backoff poll cancellation;
connect/TLS can take up to the 15-second request bound. Cancelled/deadline/byte-budget
operations stop with an error and do not publish a complete capture/export.
Individual source failures produce a committed partial `capture.json` containing
successful sources and per-page errors, then exit nonzero. Cancellation reports
completed-page progress; usable successes remain cached for retry. Inspect the
report and deliberately select replacements/new output. Export can use successful
partial captures but reports their original failures and real proposal shortfall;
it never invents missing sources. Cache capacity/corruption errors require operator
inspection/cleanup, not unbounded eviction or repeated downloads.

## Private Proposal Contract

Root fields: `schemaVersion: 1`, stable `id`, `version`, `topicId`, exact captured
`topic`, `label`, `description`, `subtopics`, `authoring` and `clues`. The importer
rejects unknown fields, duplicate JSON properties, excessive JSON depth/nodes,
unsupported schemas and malformed UTF-8. The authoring object requires
`method: operator-supplied`, actual `author`, `createdOn`, `notes`, and explicit
null `humanReviewer`/`humanReviewedOn`. This importer cannot assert human approval.
Its required pack `reviewedOn` date is the draft assembly date, explicitly **not**
approval; current schema-1 review dates are required even for drafts.

Each proposal requires stable `id`, `factId`, declared `subtopicId`, `category`,
`text`, provisional `difficulty` (`easy`, `standard`, `hard`),
`answerType: article-title`, captured `answerPageId`, explicit `aliases`,
`support`, and actual `reviewNotes`; `hint` may be omitted/null. Existing pack
limits/alias normalization remain unchanged. Aliases must include the canonical
searchable title; no fuzzy grading or automatic wrappers. The proposal limit is
60, not a quota to fill. A single validated proposal is still a valid draft.

Support is `{pageId, text, start?}` referencing a captured page and an exact
nonblank quote, at most 12,000 UTF-16 units. Unique quotations can omit `start`;
repeated quotations need an explicit inclusive UTF-16 start. Export computes the
exclusive end; offsets cannot split surrogate pairs. Invented quotes, wrong
offsets/missing source/title/unsupported answer type fail before publication.
Exact text inclusion does **not** prove that a quote supports the claim.

The private review sidecar flags reused fact IDs/passages, shared aliases and
every noncanonical alias for adjudication. Every clue explicitly retains pending
unsupported-claim, factual, ambiguity, hint and difficulty review. These are
conservative screening flags, not semantic fact checking; paraphrases, misleading
premises, undeserved aliases or hint spoilers can pass structural checks. Record
actual findings in `reviewNotes`; never label assistant work human-approved.

## Artifact and Retrieval Boundary

```text
preview/preview.json          Frozen candidate IDs/titles and response hash
selection/selection.json      Explicit selected list and rights acknowledgement
capture/capture.json          All saved source context/provenance and partial failures
proposals.json                Private operator-authored input
export/catalog.json          Separate draft-only catalog; not active game settings
export/collection.json        Public-source metadata and attribution
export/snapshots/*.txt        Source-only plaintext lead excerpts
export/private/pack.json      Private clues/keys/support offsets, draft status
export/private/review.json    Screening flags/findings, empty human fields
export/private/proposals.json Private original authoring inputs/provenance
export/provenance/capture.json Source capture receipt, no clue selections
export/manifest.json          Exact file membership, hashes and input fingerprints
```

`validate` checks membership/hashes, reconstructs the complete deterministic export
from the capture and proposals, and uses the existing pack validator. Draft-only
validation is a separate explicit method; normal game loading still requires an
eligible default. Source indexing receives only collection source IDs, titles,
categories and text, not the private pack, keys, offsets, review or authoring inputs.
It covers **all eight selected sources** in the demo, not a clue-specific answer
shortlist. No source request runs during game play. The fixed web asset allowlist
does not expose any artifact/cache/proposal path. Nothing is promoted automatically.

## Demonstration, Review and Verification

See the [fixture record](../drafts/jovian-v1/README.md) for actual capture identity,
source revisions, proposal findings and reconstruction commands. The demo contains
eight usable leads / 19,399 UTF-8 text bytes and five distinct Standard proposals
in five subtopics, over five different title answers. No content quota shortfall
occurred; **playable/evaluable availability remains zero**, not five. This is an
assistant-authored authoring fixture, never an external/held-out retrieval challenge.
No retrieval accuracy is claimed for it. Human reviewed/playtested/calibrated
starter coverage remains 0/60, deferred; the draft also has no human review.

Actual checks and dated evidence are recorded in
`docs/engineering/2026-11-06-source-intake-verification.json`. The prior baseline
was rerun before edits: 133 Java + seven Node tests passed. The expanded suite
passes **169 Java tests (36 new) and seven Node tests**, with no failures/errors/
skips and a packaged jar. The final clean build ran after the admin-only fixes.
Scripted Chromium completed two normal games plus one missing-index game, covering
lost start/answer/advance/completion responses, replay/stable receipts, private
pre-reveal state, evidence separation, keyboard/touch and 320/390px layouts.
Owned preview servers were stopped. This is not human playtesting or a
physical-device/screen-reader/cross-browser audit. SHA-256 checks found zero changes
among 124 preexisting historical/content/frozen/review/legacy reference files.
The expanded suite
tests synthetic destinations/resolvers/transport responses without external calls,
framing/gzip limits, cancellation/deadlines, cache retention/corruption/retries,
redirects/duplicates/thin context, malformed proposals/aliases, exact UTF-16 support,
immutable/interrupted exports and private-source boundaries. Game/privacy/recovery
tests remain part of the complete suite. Live Wikipedia preview/capture, immutable
offline reconstruction, draft indexing/reuse and active pack/index validation ran
separately. No historical retrieval benchmark was remeasured or tuned.

Next: adjudicate the five private editorial records (especially Europa's exposed
designation and Juice's revealing hint), make any supported corrections in a new
pack version, then make an explicit local review/promotion decision with actual
review identity/evidence and accurate availability. Independent human approval and
calibration stay separate. Optional generation remains a later offline integration;
paid providers, new opponents, custom-topic player UI and public deployment are not
authorized. Lead-only context, latest-render uncertainty, heuristic screening,
in-memory game sessions and ongoing dependency/advisory/rights/accessibility review
remain limitations, not public-release clearance.
