# Jovian Source-Intake Demonstration

Captured and assistant-checked 2026-11-06 (America/Phoenix). This is an **authoring
fixture**, not a playable pack, independent question bank, human approval,
calibrated difficulty set or retrieval benchmark. No TriviaQA or old evaluation
questions were used. No paid generator was called.

## Provenance and Rights

The bounded English Wikipedia Action API search for `Jupiter moons` returned 12
candidate page IDs/titles. Eight were explicitly selected before proposal authoring:
four Galilean moons, the Galilean group, Jupiter, Metis and the Juice spacecraft.
Three of the selected sources are not expected answer pages; their full captured
lead context is retained rather than tailored to individual clues. No live source
failed context checks. Eight plaintext leads total **19,399 UTF-8 bytes**.

Text is credited to Wikipedia contributors under **CC BY-SA 4.0**, with article,
history and license links in every exported source attribution. Plaintext lead
extraction removes formatting/pronunciation/media; it is not a complete page
archive or guaranteed old-revision render. Source-specific additional conditions
still need rights review before distribution. No media or linked documents were
imported. See the [permitted-source policy](../../docs/SOURCE-INTAKE.md).

| Page ID | Title | Observed Revision ID |
| --- | --- | --- |
| 12505 | Galilean moons | 1379468135 |
| 32657610 | Jupiter Icy Moons Explorer | 1383451352 |
| 38930 | Jupiter | 1383322969 |
| 54211 | Ganymede (moon) | 1382629845 |
| 1051892 | Io (moon) | 1383471933 |
| 43127 | Europa (moon) | 1381496497 |
| 43126 | Callisto (moon) | 1380203801 |
| 44654 | Metis (moon) | 1382215334 |

Capture receipts retain canonical/original URLs, publisher, license evidence,
UTC capture timestamps (07:15:23-07:15:30), redirect resolution, available revision
metadata, response hashes and text hashes. IDs are observations returned alongside
latest extracts, not proof of identical revision rendering. Future unavailable
IDs must stay null. The original historical unknown revisions remain unchanged.

Frozen file SHA-256 values:

```text
preview/preview.json  9a65ad361d2c2ae6d0c1632a32d54cb95995374ff1638d32a6e056bd6b84dd99
selection/selection.json  385784cbce0466dc8c833b484f1187c63c1a4cb4d6bef1f886a56f5fe510f09d
capture/capture.json  662d1ce8cb9346511c3f657ff36b817a0efd8dd63c51d01f8554afeb2f1eea5c
export/manifest.json  190a3d2c52e23f9878a2de6a4d1f6b5194070fc23c5b67291b09fc1764728991
```

The authored JSON file is copied canonically into `export/private/proposals.json`;
the manifest fingerprints canonical input bytes, not the pretty-print formatting
of the external proposal file. Source snapshots and all export membership/hashes
are frozen separately. Rerunning export reconstructs the same bytes and refuses
changed output under this identity/version.

## Assistant Findings, Not Human Review

Five new proposals use five different canonical article-title answers and five
subtopics: Io/volcanism, Ganymede/magnetism, Europa/designation and relative size,
Callisto/orbital resonance, Juice/mission launch and target. All are provisionally
Standard, not equivalent in known player difficulty. Exact passages were checked
against all complete captured leads and the linked article contexts.

| Clue ID | Actual Assistant Finding and Open Editorial Work |
| --- | --- |
| jovian-io-volcanism-01 | Lead supports tidal heating/volcanoes and the hint's innermost-Galilean qualifier. [NASA context](https://science.nasa.gov/jupiter/jupiter-moons/io/facts/) also describes the tidal mechanism and hundreds of volcanoes. Bare `Io` alias still needs editorial assessment. |
| jovian-ganymede-magnetism-01 | Lead supports internally generated field; [NASA](https://science.nasa.gov/jupiter/jupiter-moons/ganymede/facts/) corroborates the known uniqueness. Retain the internal/known qualifiers; broad readings could confuse induced fields. |
| jovian-europa-identity-01 | Captured opening explicitly supports Jupiter II and smallest Galilean moon. Empty pronunciation parentheses are honestly preserved extraction artifacts. `Jupiter II` occurs in the question and aliases, making the answer partly exposed; hold for wording/alias review. NASA Europa facts context was inaccessible in the additional browser-source check, so no corroboration from that page is claimed. |
| jovian-callisto-resonance-01 | Quoted pronoun requires the complete Callisto lead as context. NASA's [Ganymede orbital section](https://science.nasa.gov/jupiter/jupiter-moons/ganymede/facts/) separately identifies Callisto as outside the three-moon resonance. Specialized vocabulary may feel Hard; no player evidence supports relabeling. |
| jovian-juice-mission-01 | Saved text and [ESA factsheet](https://www.esa.int/Science_Exploration/Space_Science/Juice/Juice_factsheet) support launch/date/target. Wording says intended orbit, not accomplished orbit. Wordplay hint nearly supplies the short alias; hold for review. Future schedule is not guaranteed. |

These external NASA/ESA checks are review references only; their page text was not
imported into the collection/index or used as hidden retrieval support. No extra
redistribution rights are inferred. The export retains the original assistant
notes and conservative flags. This separate README adds review context without
mutating a frozen exported content version.

**Proposed: 5. Distinct answers: 5. Eligible/playable/evaluable: 0. Human-reviewed:
0. Playtested: 0. Calibrated: 0.** The pack status remains `draft`, outside the
active catalog. Schema-1 `reviewedOn` is the assembly date, not an approval date.
No accuracy/Hit/MRR is measured on these proposals; zero eligible evaluation has
null metrics, not a perfect score. Existing benchmark measurements remain intact.

## Reconstruct and Validate

From the repository root:

```sh
mvn -o -q compile exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='export drafts/jovian-v1/capture drafts/jovian-v1/proposals.json drafts/jovian-v1/export'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='validate drafts/jovian-v1/export'
mvn -o -q exec:java -Dexec.mainClass=main.SourceIntake -Dexec.args='index drafts/jovian-v1/export'
```

No network/contact identity is required for these three saved-data operations.
The last command builds or verifies eight source-only documents in ignored
`indexes/source-intake/`; it does not register/promote a pack. For fresh discovery,
capture limits, proposal format, cancellation and failure recovery, see the
[runbook](../../docs/SOURCE-INTAKE.md). Preserve this version; use new IDs/versions
and an ignored `drafts/local/` workspace for further authoring.
