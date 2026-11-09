# Stage 2B Human Review and Playtesting Checklist

Status: **Pending human review and player testing.** This is a procedure and blank
record template, not evidence that a study or approval occurred. The four starter
packs retain `source-checked` status; Original Demo retains `legacy-preserved`.
Stage 2A automated/browser checks establish software behavior only.

## Launch and Roles

The organizer needs JDK 21 and Maven 3.9.x (see README for installation). In a
terminal, run these lines from this checkout. The last line stays running:

```sh
cd /Users/Tr3y/Projects/PersonalProjects/IBMWatson
mvn clean verify
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='index data/game.json'
mvn -q exec:java -Dexec.mainClass=main.PackTool -Dexec.args='validate data/game.json --indexes'
mvn -q exec:java -Dexec.mainClass=main.GameServer
```

Open [ClueLab locally](http://127.0.0.1:8765/). Use `-o` after dependencies are
cached. If the port is busy, stop your previous ClueLab server rather than playing
an unknown older instance. Stop the server with Ctrl+C only after saving results.
An index validation failure needs organizer attention; do not rebuild the
coursework archive. [LOCAL-GAME.md](LOCAL-GAME.md) explains recovery and limits.

The **player** needs only the browser and a note sheet, not the source files or
answer key. The **observer** records what really happens without coaching. The
**content reviewer** opens the private ledger and sources, preferably after their
own first-exposure playtest or separately from the player. A person who has read
the key can review facts but must not be counted as an unexposed player.

Use a consenting participant's chosen pseudonym, not their legal name. Explain
that this tests the clues and interface, not the person's intelligence; breaks
and stopping are welcome. No names, contact details or sensitive personal notes
are needed. Keep raw notes/receipts in ignored `reviews/local/` (or another private
location), not browser assets or retrieval documents. Share only consented,
de-identified observations. The committed templates contain no human data.

## Initial Six-Game Pilot

This is a small exploratory pilot, **not statistical difficulty calibration or
approval of all 60 clues**. One complete pilot exposes one player to 30 distinct
clues: 20 Standard clues and ten Easy/Hard clues in one topic. The other 30 still
need player coverage. Factual review separately covers all 60.

Before play, record familiarity with each topic as `none`, `some`, or `high`, in
the player's own judgment, plus any prior exposure. Choose one familiar topic
for both slots 5 and 6; do not pick it from computer scores. If none is familiar,
record that limitation rather than inventing familiarity. The planned order is:

| Slot | Topic | Level | Clues |
| --- | --- | --- | --- |
| 1 | General Knowledge | Standard | 5 |
| 2 | World History | Standard | 5 |
| 3 | Geography | Standard | 5 |
| 4 | Music | Standard | 5 |
| 5 | The chosen familiar topic | Easy | 5 |
| 6 | The same familiar topic | Hard | 5 |

Use the blank [six-game log](review/six-game-pilot.csv). Its planned slots are
not completed games; actual dates, people, versions and receipts remain empty.
Each participant needs a fresh browser profile/private session; tabs sharing
cookies share scores and cycles. Do not count an explicit reset/replay as a new
independent exposure. Six games fit the 12-game limit, but sessions expire two
hours after creation. Take breaks between saved games; if expiry interrupts a
game, record the interruption and prior exposures in the next session.

### Instructions for the Player

1. Choose the planned topic and difficulty, then **Start Five-Clue Game**. Choices
   are locked until the five clues finish. Use Tab to move, Enter/Space to open
   a selector, arrow keys to choose and Enter to confirm. There is no timer.
2. Read the category and clue. Write an initial guess before opening **Show Hint**
   if you choose to use it. Tell the observer whether you used a hint and, before
   submitting, rate the clue `easy`, `standard`, `hard`, or `unsure`. Do not open
   reference pages or look up an answer during first-exposure play.
3. Type the answer directly and choose **Lock My Answer** (or press Enter in the
   answer field). Case and repeated spaces do not matter. Conventional question
   wrappers, punctuation and unlisted spellings are not automatically accepted.
   If you do not know, enter `I don't know`; there is currently no Skip button.
4. Review the awarded points. If the result feels unfair, record your **exact
   submitted text**, intended answer, clue wording, what seems ambiguous and why
   you think it is valid. Use **Save Clue Result** (or **Save Game Results** after
   clue five); the displayed reference gives
   the clue/round/game IDs and pack/source versions. Do not retry to change the
   receipt: the first submitted answer is final, even if a review later agrees.
5. Choose **Next Clue** after each scored result. After clue five, choose
   **Save Game Results** and inspect the missed-clue review. Tell the observer
   whether hints helped, wording felt fair, controls were understandable, or
   anything was hard to read/use. Then select the next planned game.
6. Reload once during a game to check recovery; record when and what happened.
   If a response is interrupted, use **Reconnect**, not a second guessed answer.
   Save each game before closing the browser or stopping the server. Previous
   games can also be saved from **Previous Games** in the same session.

Hints cost no points and are always available to the computer, even when the
player leaves them closed. Capture unassisted initial guesses separately from
hint-assisted submitted guesses. Seeing a solution can change later familiarity;
record this carryover rather than assuming the fixed six-game order is neutral.

## Prepare a Review Set

- [ ] Record the topic, difficulty, pack ID/version/hash, collection ID/version,
  review date and actual reviewer identity. Keep observations tied to that snapshot.
- [ ] Validate the pack and indexes using the authoring guide. Confirm at least
  five eligible, unused clues for the chosen game, or explicitly reset the cycle.
- [ ] Read the [existing content-review limitations](PACK-REVIEW.md), including
  unknown revisions, excerpt-only captures, context fragments and rights review.
- [ ] Separate factual/editorial review from player testing: a reviewer who has
  already read the answer key is not a first-exposure difficulty participant.
- [ ] Decide the test's scope and recruit actual consenting players. Use anonymous
  participant codes where useful; collect no unnecessary personal information.
  Do not fill participant counts, approvals or findings in advance.

## Factual and Editorial Checks (Each Clue)

- [ ] Read the linked source in context, not only the stored excerpt or the
  computer's result. Check that the passage supports the distinguishing fact,
  every meaningful qualifier, and the intended article-title answer.
- [ ] Check dates, scope, superlatives and historical versus current claims.
  Record inaccessible references or weak archival context as unresolved issues.
- [ ] Look for a second reasonable answer, ambiguous pronouns, misleading wording,
  assumed cultural knowledge and accidental answer giveaways.
- [ ] Check that the answer type is supported and that the canonical title exists
  in the declared collection. Do not expect dates/quantities to be extracted.
- [ ] Review accepted aliases: common names, abbreviations, diacritics and genuine
  alternate spellings. Identify missing legitimate aliases and overly broad ones.
- [ ] Check the public hint for factual support, usefulness and answer leakage.
  The opponent receives it even if the human leaves Show Hint closed.
- [ ] Check distinct facts/answers and topic/subtopic coverage across all levels.
  Treat related wording variants and repeated exposure as possible confounders.
- [ ] Verify readable attribution, captured metadata and public rights questions.
  Do not infer an exact historical revision or open license from a current URL.

## Play a Five-Clue Game

- [ ] Use a fresh session or identify prior exposure. Record game ID, configuration
  and whether this is a replay. Do not count repeated attempts as independent
  first-exposure difficulty samples.
- [ ] Before revealing each answer, record the player's guess, optional confidence,
  whether a hint was opened and perceived difficulty. If timing is useful, define
  the start/end points and record it consistently.
- [ ] Keep answer keys out of the player's view and the opponent's retrieval inputs.
  Do not coach from the key. A conventional question wrapper or unlisted spelling
  may fail exact matching; record the issue rather than silently awarding points.
- [ ] After submission, compare awarded points with both the accepted aliases and
  an independent human correctness judgment. Log legitimate answers scored zero,
  incorrect answers scored 100, and ambiguous cases separately.
- [ ] Check the computer's actual guess and retrieved evidence separately from the
  curated answer support. An engine failure is not evidence that a clue is hard
  for humans. Raw scores are not confidence or a difficulty rating.
- [ ] Confirm Clue 1-5 progress, one result per clue, no within-game repeats and a
  final score that sums the five awards. Check that session totals are separate.
- [ ] Ask whether the hint helped, whether wording felt fair, and what label the
  player would assign. Record perceived difficulty before showing all solutions
  when possible; keep hint-assisted and unassisted correctness separate.
- [ ] Use the final missed-clue review and check source support for disputed clues.
  Test keyboard/touch access with real users when appropriate, including focus and
  screen-reader understanding. Record actual devices/assistive tools used.
- [ ] Recover once after reload/reconnection, then explicitly replay or switch
  selection. Confirm previous receipts retain the original guesses and scores.

The prototype does **not** automatically collect hint use, elapsed answer time,
confidence or perceived difficulty. Record those observations manually during
the session. The final receipt/API supplies clue IDs, guesses, awards and source
evidence after scoring; it does not reconstruct whether a player opened a hint.
Capture review notes before stopping the server or the two-hour session expiry.

## Blank Observation Record

Use [observations.csv](review/observations.csv) for one row per actual clue
exposure or content review; it currently has a header and **zero observations**.
The CSV forms can be opened in a spreadsheet application; preserve their header
names and keep filled records private. The JSON ledger is for the content reviewer,
not required for a player to submit feedback: a saved game and brief notes suffice.
Use `kind: playtest` or `content-review`. Assign a unique observation ID only when
an observation occurs. Keep observer/reviewer and participant pseudonyms separate.
Dates are the actual observation dates (`YYYY-MM-DD`), never the preparation date.
Blank means uncollected; record `no` only when someone actually reports no hint
use/concern, and `not applicable` where appropriate. No timing/confidence is
required for this pilot. If collected, define a consistent protocol separately.

Join records by **clue ID + pack ID/version + collection ID/version + supporting
source ID/snapshot SHA-256**. Game/round UUIDs and the saved receipt distinguish
exposures. The saved JSON wrapper is `cluelab-review-receipt`, schema 1; `receipt`
is an unchanged server result, not a review or an importable save game. It contains
guesses/aliases/evidence, but no cookie, future clues, reviewer, hint-use log,
observation date or perceived difficulty. Record those manually; download time is
not the review date. Keep answer-bearing receipts away from unexposed players.

The private [60-clue ledger](review/starter-ledger.json) is a moderator packet:
each row includes wording, public hint, aliases, exact curated support, source
path/link/attribution and pinned versions/hash, followed by separate
`assistantReview`, blank `humanReview`, `playtestObservationRefs` and
`calibrationEvidenceRefs`. Open it in an editor after play; the topic tables in
[PACK-REVIEW.md](PACK-REVIEW.md) provide clickable source navigation. Do not give
the ledger to an unexposed player or add it to searchable sources. Fill human
decisions only from the actual review and link observation IDs/receipt paths.
For multiple reviewers, retain each original observation row and use evidence
references to the adjudicated ledger decision; do not overwrite dissent.

Reviewer / review date: __________

Anonymous participant code / actual device and input method: __________

Topic familiarity / prior exposure / pilot slot: __________

Topic / difficulty / pack ID and version / collection version: __________

Game ID / first exposure or replay / hint and timing protocol: __________

| Clue ID | Player Guess | Awarded Points | Human Correctness Judgment | Hint Opened / Helped | Perceived Difficulty | Ambiguity or Missing Alias | Support / Follow-up |
| --- | --- | --- | --- | --- | --- | --- | --- |
| | | | | | | | |

Optional elapsed time/confidence notes: __________

Game totals and score checks: __________

Open factual/editorial/accessibility issues: __________

## Decide and Version Changes

Classify each issue before changing content: `factual`, `grading/alias`,
`wording/ambiguity`, `difficulty`, or `interface`. Record the supporting observation
IDs, number of unique people and exposures, familiarity and hint use. An assistant
source finding is not a participant observation. Investigate even one supported
factual error; a single preference is not a repeated difficulty problem. Report
conflicting judgments and missing fields, not only favorable responses.

For each clue/version, summarize actual unassisted and hinted responses separately,
with numerator/denominator and exclusions (prior-key exposure, replay, missing
hint data). Do not infer an unassisted answer from a correct final score when
initial guess/hint use was not recorded. Perceived labels are reports, not a
calibrated scale. Computer accuracy is neither human correctness nor difficulty.
This pilot's fixed order, self-selected familiarity, tiny sample and answer review
between games preclude broad calibration claims.

Human coverage counts distinct version-linked clue IDs, not rows or approvals:
`human-reviewed` requires a dated actual factual/editorial decision plus evidence;
`playtested` requires an actual participant exposure; `calibrated` requires a
separately justified study/decision. Report each out of 60 with unresolved counts.
At preparation, those counts are **0/60, 0/60 and 0/60**, respectively. Assistant
inspection covers 60 local records; 49 upstream contexts were readable and 11
were unavailable in this pass. Those are not human-reviewed counts.

- [ ] Summarize actual unique players, exposures, unassisted/hint-assisted
  correctness, ambiguity reports and perceived difficulty. Report missing data
  and limited samples; keep software QA, human findings and retrieval metrics apart.
- [ ] Investigate disputed answers against sources before accepting aliases.
  Preserve the original game receipt; correct content/scoring rules only in a
  documented future version, with appropriate regression checks.
- [ ] Adjust wording, hints, aliases or provisional difficulty only with recorded
  reasons. Bump pack/source versions as required, validate and evaluate the new
  set independently, and retain earlier measurements.
- [ ] Mark a pack `human-reviewed` only after a real reviewer completes factual
  and editorial review and records remaining limitations. Difficulty calibration
  is a separate claim requiring actual player evidence.
- [ ] Leave unresolved content as draft/unavailable or retain an honest review
  status. Do not fabricate filler, approvals or participant results.

Later niche-topic content, answer-wrapper normalization, new opponent algorithms,
automatic authoring and public deployment remain separate roadmap work. This
checklist does not authorize or claim completion of those milestones.
