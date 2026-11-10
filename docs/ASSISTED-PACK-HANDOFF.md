# Assisted Topic-Pack Handoff

Follow-on local outcome, 2026-11-06: the bounded offline/admin intake and draft
export slice described below is implemented. See [commands, policy, verification
and limitations](SOURCE-INTAKE.md) and the [Jovian draft fixture](../drafts/jovian-v1/README.md).
It exports five proposals over eight sources, with zero eligibility and no active
catalog registration. Next is targeted editorial adjudication and a separately
versioned, explicitly reviewed promotion decision, not automatic publication or
a paid generator. The original acceptance scope below is retained as reference.

The engineering diagnosis and [external challenge](EXTERNAL-CHALLENGE.md) are
complete locally. Human review/calibration remain deferred at 0/60. No generation
provider, custom-topic UI, multiplayer or public deployment is implemented here.

## Next Concrete Slice

Build an **offline/admin source-intake and draft-pack exporter**, using the current
[pack contract](PACK-AUTHORING.md), not a replacement game API or database. Its
first acceptance target is one reproducible, structurally valid **draft** from a
small rights-permitted English source collection. A draft has zero eligible clues;
this is not human approval or permission to publish.

1. Resolve an operator-chosen topic and preview a bounded source list. Prefer
   authoritative/rights-permitted sources; record original URLs, publisher,
   attribution, retrieved date and available revision IDs. Unknown revisions stay
   null. Bound source count/bytes/time, retries and cancellation; cache immutable
   snapshots by hash. Remote fetching needs allowlisted schemes/hosts and separate
   network/redirect/address validation, not unrestricted user-controlled URL fetches.
2. Preserve adequate source context, including redirect resolution or explicit
   unresolved-target status. A matching title alone is insufficient: this challenge
   contains accepted aliases with only redirect stubs. Keep full archival context
   locally; export accurate `excerpt` snapshots/support offsets under schema 1,
   rather than inventing a new snapshot kind or page revision.
3. An optional authoring component may propose **article-title** clues, aliases,
   hints and exact supporting passages from those sources. Dates/quantities/facts
   without a supported title answer remain unavailable. Record generator/model/
   prompt/version provenance privately. No provider call is needed during play;
   credentials never enter web assets. Do not feed authoring metadata, labels or
   selected support into opponent indexing/ranking.
4. Export new stable collection/pack IDs and versions, topic/subtopic variety,
   explicit provisional difficulties, source references and `review.status: draft`.
   Reuse collections/indexes where appropriate. Adding validated content remains
   data/catalog work, not Java/UI edits. Require at least five eligible distinct
   clues per advertised game selection; report shortages instead of padding.
5. Run source/hash/title/support/alias/schema checks, then inspect every factual
   claim, ambiguity, hint and alias in context. Generation and structural checks
   do not establish truth. Any local promotion to `source-checked` records actual
   assistant/operator findings, not invented human approval. `human-reviewed`
   requires real traceable review; calibrated difficulty requires real evidence.
   Reuse the existing checklist/ledger when human work resumes.

## Boundaries and Acceptance Checks

- TriviaQA's benchmark material stays local non-commercial research data. Do not
  use it as source text, generation exemplars or playable questions. Source/content
  rights and public distribution need their own review. Historical corpus and
  benchmark snapshots/results remain read-only references.
- Keep drafts out of the active game catalog until the documented content gates
  pass. Pin new content versions; never mutate published wording, aliases, hints,
  source snapshots or existing receipts in place. Difficulty and opponent strength
  remain separate. Neither a correct retrieval nor a high score approves a clue.
- Test duplicate/unsupported/insufficient sources, unresolved redirects, zero-token
  symbols, malformed generation output, invented support, ambiguous aliases, repeated
  facts, timeout/cancellation and retry-safe output. Use synthetic fixtures, not the
  inspected external test set, to drive implementation.
- Verify source-only indexing, private draft/key/generator metadata boundaries,
  zero draft availability, five-clue scoring/replay/recovery and separately labeled
  curated support versus actual opponent evidence. Preserve the existing validator,
  safe rendering, keyboard/mobile controls and [bounded durable session limits](DURABLE-GAMES.md).
- Keep a source-first authoring fixture separate from retrieval evaluation. This
  milestone's test is now inspected; any future tuning/unseen claim needs a fresh
  sealed comparison set, including adequate context coverage. No new opponent was
  promoted by the diagnosis or external measurement.

Before implementation, choose the source license/attribution policy and bounded
intake defaults. Do not silently choose a paid provider or publish a generated
pack. The existing Java engine, lightweight client and file-based catalog remain
the architecture until measured needs justify changing them.
