# Jovian Revision and Local Review

Actual assistant source/editorial review: **2026-11-06**. A small assistant-authored
local fixture, not independent benchmark authorship, human approval or calibrated
difficulty. The complete v1 directory remains unchanged.

## Separate States

| Artifact | Version | Status | Eligible Easy / Standard / Hard |
| --- | --- | --- | --- |
| Preserved `jovian-v1/export` | 1.0.0 | draft | 0 / 0 / 0 |
| Revised `jovian-v2/export` | 1.0.1 | draft | 0 / 0 / 0 |
| `preview/candidate` and planned release | 1.0.2 | assistant source-checked | 0 / 5 / 0 |

Frozen proposals/exports never become source-checked in place. The separate
decision creates a newer candidate. Its collection version also changes because
snapshot path metadata becomes content-addressed; the eight captured text bytes,
attribution and observed revisions do not change. Candidate creation is not
activation. The normal catalog was **not activated**.

## Editorial Decisions

All five stable clue IDs and original alias arrays are retained. No fuzzy grading,
answer wrappers, new aliases, difficulty relabeling or scorer/ranking changes.

| Clue ID Suffix | Before | Decision and Evidence |
| --- | --- | --- |
| `io-volcanism-01` | Tidal heating/volcanoes; innermost Galilean hint | Retained. Saved lead supports both. The hint says Galilean, not all moons. NASA Io corroborates the mechanism. `Io` is the astronomical short name in this explicit context. |
| `ganymede-magnetism-01` | Known internally generated magnetic field | Retained qualifiers, not all induced fields. Saved lead and NASA Ganymede support the distinction and short-name alias. |
| `europa-size-01` | Question exposed accepted alias `Jupiter II` | Removed the designation: "Which is the smallest and least massive of Jupiter's four Galilean moons?" Saved lead explicitly supports both claims. Supported `Jupiter II` alias retained but not given away. |
| `callisto-resonance-01` | Unexplained orbital resonance; isolated support | Added "or repeating orbital timing pattern" and named the three participants. Saved lead/NASA Ganymede support the contrast. Support expanded from UTF-16 2070..2212 to 0..2212. |
| `juice-launch-01` | Revealing fruit-drink hint; isolated support | Hint removed without replacement. Launch/place/target wording retained, supported by saved lead/ESA factsheet. Support expanded from 448..954 to 0..1064. |

Ranges are inclusive-start/exclusive-end UTF-16 positions; export recomputes them
from exact source text. [review.json](review.json) explicitly adjudicates every
exporter flag with seven separate source/editorial checks. There are no remaining
local blockers; advisories retain provisional/uneven Standard difficulty. This
does not mean automated screening detected every ambiguity.

Additional contexts inspected this pass:
[NASA Io facts](https://science.nasa.gov/jupiter/jupiter-moons/io/facts/),
[NASA Ganymede facts](https://science.nasa.gov/jupiter/jupiter-moons/ganymede/facts/),
[NASA Europa overview](https://science.nasa.gov/jupiter/jupiter-moons/europa/),
[ESA Juice factsheet](https://www.esa.int/articles/about-us/directorates/science/juice/juice-factsheet).
The accessible Europa overview did not expose the exact superlative; that claim
is supported by the saved Wikipedia lead, not claimed independently corroborated
by NASA. Callisto's captured lead supplies detailed resonance context and NASA
Ganymede corroborates the pattern contrast. No new capture or revision claim.

## Reconstruct and Inspect Privately

Repository root; JDK 21/Maven 3.9.x, cached dependencies, no network:

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='export drafts/jovian-v1/capture drafts/jovian-v2/proposals.json drafts/jovian-v2/export'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='validate drafts/jovian-v2/export'
mvn -o -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='review-preview drafts/jovian-v1/export drafts/jovian-v2/export drafts/jovian-v2/review.json drafts/jovian-v2/preview'
```

Inspect `preview/preview.html` locally or its JSON. These private files contain
answers, before/after text, evidence and decisions. The game serves none of them;
never copy them into web assets. Identical retries preserve bytes; changed input
needs a new version/destination. Human fields remain null: coverage each 0/5 here,
each 0/60 for starter packs, all deferred.

## Identities and Actual Activation

- Revised export manifest SHA-256:
  `b13687b1804799d7a223177974b492ea4a40d762f53377b4d05611730fe84606`.
- Pack `jovian-intake-demo-clues@1.0.2`, SHA-256:
  `4f9c58babceccabd0a868937998e2d751e23d0733a1c6041f7490fbcd517723e`.
- Scope `jovian-intake-demo-sources@1.0.2`, eight captured leads, digest
  `99d758e775ffcbb0718e72290b7ece4bede3801a65ed8e4739bc259fe63e955f`.
- Candidate file-set digest:
  `7b6ee47571f4172b1809bedb316e73e070e6573461a7ffabe022207310aaf8a5`.

The actual isolated lifecycle used `drafts/local/jovian-activation-final/game.json`,
`indexes/jovian-activation-final`, port 8785 and `drafts/local/jovian-final-plan`.
Catalog 1.0.0 -> 1.0.1 activation occurred at 2026-11-06T08:25:04.511798Z;
rollback to 1.0.0 at 2026-11-06T08:27:44.830131Z. Six activated indexes were
verified; rollback verified five old scopes and retained all new artifacts.
Two browser games exercised scoring/recovery/replay, plus a missing-index game.
These are scripted QA, not human playtesting or new accuracy measurements. The
isolated workspace is now rolled back; temporary servers are stopped.

Normal catalog remains v1.0.0, five topics / 68 documents / 76 clues. Only a
dry-run plan exists at `drafts/local/jovian-normal-plan`; no normal activation,
new normal index build or server restart occurred. See the
[runbook](../../docs/PACK-ACTIVATION.md) for exact activation/rollback commands,
manifests, stale-plan protection and restart/session-loss requirements.

## Limitations and Next Step

Leads are bounded context, not full articles or guaranteed old-revision renders.
Original observed IDs and null/unknown revisions remain; exact saved bytes are
reproducible. CC BY-SA attribution/change notices remain; third-party material
and public rights clearance need separate review. Scientific knowledge/mission
plans can change. No paid provider, TriviaQA game reuse or public deployment.

Next: an operator inspects the private preview/plan and deliberately chooses
local activation. Collect actual factual/editorial and difficulty feedback before
broader level coverage or optional offline assisted authoring. Five assistant
checks are not human approval of any content.
