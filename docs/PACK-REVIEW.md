# Starter Pack Content Review

Reviewed 2026-11-04 by Codex (AI assistant). **No independent human sign-off or
player difficulty study has occurred.** The four new packs are marked
`source-checked`, not `human-reviewed`. Structural validation and retrieval scores
are separate evidence; neither certifies factual or editorial quality.

## Scope and Method

The 60-clue target is met: General Knowledge, World History, Geography and Music
each have 15 distinct canonical answers, five per provisional level. The review
checked the distinguishing facts against text returned from the institutional
sources linked below, then checked title-answer compatibility, aliases, subtopic
variety and repeated facts. The clues are newly authored; the sources are not a
ready-made question bank. No automatic question-generation pipeline was built.

Each row identifies a private clue and its source. Exact excerpts, attribution,
capture dates, available publication/update notes and SHA-256 fingerprints are
in `data/packs/collections` and `data/packs/snapshots`; support ranges are in the
private packs. Excerpts were selected before retrieval evaluation, not adjusted
to hide misses. New snapshots are brief text excerpts, not full HTML/PDF archives.
Whitespace may be normalized. Some sources were accessed through search-index
extracts, including the Library of Congress Gutenberg Bible page and the British
Museum annual report; this is not a claim of complete-page archival inspection.

All immutable page revision IDs are currently unknown and stored as `null`.
Known publication/update dates are notes, not invented revision IDs. A local hash
pins the saved excerpt only. Some excerpts are sentence fragments or catalog
headings whose subject is supplied by the linked source/reference title; reviewers
must check their surrounding context, not treat an isolated fragment as proof.

## Editorial Limitations and Follow-up

- Difficulty is a provisional author judgment. Familiarity varies by player;
  astronomy and obvious definition clues can be easier than their assigned label.
- General Knowledge intentionally spans science, communication and technology,
  but is not balanced across all general-knowledge disciplines. World History
  spans Africa, Europe, Asia and North America but cannot represent world history
  comprehensively. Music includes non-Western instruments/ensembles but remains
  weighted toward Western performers and repertoire.
- Each new pack has 15 unique answers across its three levels. Related subjects
  are allowed, but none is a paraphrase of another clue's distinguishing fact.
  The original demo intentionally retains paired Easy/Standard variants.
- Specific context checks for human review: the Braille clue describes the
  six-dot system rather than all modern variants; Everest specifies height above
  mean sea level; the Amazon clue avoids the disputed longest-river superlative;
  the Dead Sea excerpt's elevation is historical, and the clue asks no number.
- Source fragments needing fuller archival context before public release include
  the Suez Canal, Great Wall, Silk Roads, Great Zimbabwe, Galapagos, Four Seasons
  and Hildegard entries. The Armstrong FAQ heading supplies the nickname
  association. Supporting passages do not necessarily repeat every contextual
  qualifier in a clue; the cited source page must also be reviewed.
- The NOAA Everest reference currently uses its institutional UAT hostname;
  replace it with a verified stable public reference in a future content version.
- Aliases are deliberately explicit, not exhaustive. Question wrappers, arbitrary
  spellings and broad fuzzy acceptance are not implemented. Players need a way to
  flag ambiguity or a missing legitimate alias in the next milestone.
- The collections are tiny and answer-aligned. High scores are development-fixture
  results, not evidence of general retrieval quality, factual review or human
  difficulty. Geography's Baikal clue ranks Tanganyika first and Baikal second;
  that miss is retained in the evaluation record.
- Attribution follows the source record: UNESCO World Heritage description
  excerpts identify CC BY-SA IGO 3.0 where shown; other sources retain their
  holders' rights with no blanket open-content license asserted. These short
  excerpts are local research material. Public redistribution requires a separate
  rights/licensing review, not an assumption that every institutional page is
  public domain. No third-party images or full articles are bundled.

No numerical content shortfall remains for the four starter topics, but
independent human factual/editorial approval is **0 of 60 new clues**. Human
review, stronger source capture where needed, alias feedback and player testing
are prerequisites for treating these as release-quality calibrated packs.

## General Knowledge

Pack `general-knowledge-clues@1.0.0`; scope `general-knowledge-sources@1.0.0` (15 documents).
Status: assistant source-checked. All 15 rows also received the editorial checks above.

| Clue ID | Level | Answer / Subtopic | Authoritative Reference |
| --- | --- | --- | --- |
| `general-knowledge-sun-01` | Easy | Sun / Space | [NASA: The Sun ](https://science.nasa.gov/sun/) |
| `general-knowledge-moon-01` | Easy | Moon / Space | [NASA: Moon Facts ](https://science.nasa.gov/moon/facts/) |
| `general-knowledge-mars-01` | Easy | Mars / Space | [NASA: Mars Facts ](https://science.nasa.gov/mars/facts/) |
| `general-knowledge-dna-01` | Easy | DNA / Biology | [National Human Genome Research Institute: Deoxyribonucleic Acid (DNA) ](https://www.genome.gov/genetics-glossary/Deoxyribonucleic-Acid-DNA) |
| `general-knowledge-photosynthesis-01` | Easy | Photosynthesis / Biology | [NASA: ECOSTRESS Sees Plants Waking Up ](https://science.nasa.gov/earth/earth-observatory/ecostress-sees-plants-waking-up-146348/) |
| `general-knowledge-braille-01` | Standard | Braille / Communication | [Library of Congress, National Library Service: About Braille ](https://www.loc.gov/nls/services-and-resources/informational-publications/about-braille/) |
| `general-knowledge-gutenberg-bible-01` | Standard | Gutenberg Bible / Printing | [Library of Congress: The Gutenberg Bible ](https://www.loc.gov/exhibits/bibles/the-gutenberg-bible.html?loclr=blog.gov) |
| `general-knowledge-iss-01` | Standard | International Space Station / Spaceflight | [NASA: International Space Station ](https://science.nasa.gov/resource/international-space-station/) |
| `general-knowledge-seismograph-01` | Standard | Seismograph / Instruments | [U.S. Geological Survey: Seismometers, seismographs, seismograms - what's the difference? How do they work? ](https://www.usgs.gov/faqs/seismometers-seismographs-seismograms-whats-difference-how-do-they-work?items_per_page=6&page=1&qt-news_science_products=4) |
| `general-knowledge-penicillin-01` | Standard | Penicillin / Medicine | [Science History Institute: Alexander Fleming ](https://www.sciencehistory.org/education/scientific-biographies/alexander-fleming/) |
| `general-knowledge-mitochondria-01` | Hard | Mitochondria / Cell Biology | [National Human Genome Research Institute: Mitochondria ](https://www.genome.gov/genetics-glossary/Mitochondria) |
| `general-knowledge-periodic-table-01` | Hard | Periodic Table / Chemistry | [Royal Society of Chemistry: Development of the periodic table ](https://periodic-table.rsc.org/about) |
| `general-knowledge-hubble-01` | Hard | Hubble Space Telescope / Spaceflight | [NASA: Hubble History Timeline: Non-Interactive, Full Text ](https://science.nasa.gov/mission/hubble/overview/hubble-timeline/hubble-history-timeline-full-text/) |
| `general-knowledge-suez-canal-01` | Hard | Suez Canal / Engineering | [Suez Canal Authority: About Suez Canal ](https://www.suezcanal.gov.eg/English/About/SuezCanal/Pages/AboutSuezCanal.aspx) |
| `general-knowledge-antikythera-01` | Hard | Antikythera Mechanism / Ancient Technology | [American Society of Mechanical Engineers: Quiz: What Do You Know About the Antikythera Mechanism? ](https://www.asme.org/topics-resources/content/quiz-what-do-you-know-about-the-antikythera-mechanism) |

## World History

Pack `world-history-clues@1.0.0`; scope `world-history-sources@1.0.0` (15 documents).
Status: assistant source-checked. All 15 rows also received the editorial checks above.

| Clue ID | Level | Answer / Subtopic | Authoritative Reference |
| --- | --- | --- | --- |
| `world-history-mandela-01` | Easy | Nelson Mandela / African History | [Nelson Mandela Foundation: On this day - 10 May 1994 ](https://www.nelsonmandela.org/on-this-day/entry/nelson-mandela-is-inaugurated-in-pretoria-as-south-africas-first-democratic) |
| `world-history-apollo-eleven-01` | Easy | Apollo 11 / Space History | [NASA: What Was the Apollo Program? (Grades 5-8) ](https://www.nasa.gov/learning-resources/for-kids-and-students/what-was-the-apollo-program-grades-5-8/) |
| `world-history-magna-carta-01` | Easy | Magna Carta / Legal History | [U.S. National Archives: Magna Carta ](https://www.archives.gov/exhibits/featured-documents/magna-carta) |
| `world-history-great-wall-01` | Easy | Great Wall of China / Chinese History | [UNESCO World Heritage Centre: The Great Wall ](https://whc.unesco.org/en/list/438) |
| `world-history-great-pyramid-01` | Easy | Great Pyramid of Giza / Ancient Egypt | [UNESCO World Heritage Centre: Memphis and its Necropolis - the Pyramid Fields from Giza to Dahshur ](https://whc.unesco.org/en/list/86) |
| `world-history-hatshepsut-01` | Standard | Hatshepsut / Ancient Egypt | [The Metropolitan Museum of Art: Hatshepsut: From Queen to Pharaoh ](https://www.metmuseum.org/exhibitions/listings/2006/hatshepsut) |
| `world-history-rosetta-stone-01` | Standard | Rosetta Stone / Writing Systems | [The Trustees of the British Museum: The British Museum Report and Accounts 2015-2016 ](https://www.britishmuseum.org/sites/default/files/2019-10/BM-report-and-accounts-2015-2016.pdf) |
| `world-history-silk-roads-01` | Standard | Silk Roads / Trade Networks | [UNESCO World Heritage Centre: The Chinese Section of the Silk Roads ](https://whc.unesco.org/en/tentativelists/6093/) |
| `world-history-versailles-01` | Standard | Treaty of Versailles / Diplomacy | [Palace of Versailles: Versailles, from the French Revolution to the interwar period ](https://en.chateauversailles.fr/discover/resources/versailles-french-revolution-interwar-period) |
| `world-history-mali-empire-01` | Standard | Mali Empire / West African History | [The Metropolitan Museum of Art: The Trans-Saharan Gold Trade (7th-14th Century) ](https://www.metmuseum.org/fr/essays/the-trans-saharan-gold-trade-7th-14th-century) |
| `world-history-hammurabi-01` | Hard | Code of Hammurabi / Legal History | [Musee du Louvre: The Code of Hammurabi ](https://www.louvre.fr/en/the-code-of-hammurabi) |
| `world-history-abbasid-01` | Hard | Abbasid Caliphate / Islamic History | [The Metropolitan Museum of Art: The Art of the Abbasid Period (750-1258) ](https://www.metmuseum.org/zh/essays/the-art-of-the-abbasid-period-750-1258) |
| `world-history-tokugawa-yoshinobu-01` | Hard | Tokugawa Yoshinobu / Japanese History | [National Diet Library, Japan: Chronological Table - Modern Japan in Archives ](https://www.ndl.go.jp/modern/e/utility/chronology.html) |
| `world-history-great-zimbabwe-01` | Hard | Great Zimbabwe / Southern African History | [UNESCO World Heritage Centre: Great Zimbabwe National Monument ](https://whc.unesco.org/en/list/364/) |
| `world-history-lanse-aux-meadows-01` | Hard | L'Anse aux Meadows / Norse Archaeology | [UNESCO World Heritage Centre: L'Anse aux Meadows National Historic Site ](https://whc.unesco.org/en/list/4/) |

## Geography

Pack `geography-clues@1.0.0`; scope `geography-sources@1.0.0` (15 documents).
Status: assistant source-checked. All 15 rows also received the editorial checks above.

| Clue ID | Level | Answer / Subtopic | Authoritative Reference |
| --- | --- | --- | --- |
| `geography-amazon-01` | Easy | Amazon River / Rivers | [NASA: Mouth of the Amazon ](https://science.nasa.gov/earth/earth-observatory/mouth-of-the-amazon-1161/) |
| `geography-sahara-01` | Easy | Sahara / Deserts | [European Space Agency: Earth from Space: Land of Terror ](https://www.esa.int/Applications/Observing_the_Earth/Earth_from_Space_Land_of_Terror) |
| `geography-everest-01` | Easy | Mount Everest / Mountains | [NOAA: What is the highest point on Earth as measured from Earth's center? ](https://uat.oceanservice.noaa.gov/facts/highestpoint.html) |
| `geography-antarctica-01` | Easy | Antarctica / Continents | [Australian Antarctic Program: Antarctic Weather ](https://www.antarctica.gov.au/about-antarctica/weather-and-climate/weather/) |
| `geography-pacific-01` | Easy | Pacific Ocean / Oceans | [NOAA: Exploring the Pacific on NOAA Ship Rainier ](https://oceanservice.noaa.gov/news/mar24/richard-missions.html) |
| `geography-baikal-01` | Standard | Lake Baikal / Lakes | [UNESCO World Heritage Centre: Lake Baikal ](https://whc.unesco.org/en/list/754) |
| `geography-galapagos-01` | Standard | Galapagos Islands / Islands | [UNESCO World Heritage Centre: Galapagos Islands ](https://whc.unesco.org/en/list/001/) |
| `geography-gibraltar-01` | Standard | Strait of Gibraltar / Straits | [NASA: The Strait of Gibraltar in 3D ](https://science.nasa.gov/earth/earth-observatory/the-strait-of-gibraltar-in-3d-3926/) |
| `geography-andes-01` | Standard | Andes / Mountains | [European Space Agency: Peruvian Andes ](https://www.esa.int/ESA_Multimedia/Images/2020/06/Peruvian_Andes?TB_iframe=true&height=921.6&width=921.6) |
| `geography-dead-sea-01` | Standard | Dead Sea / Rift Valleys | [NASA: Dead Sea ](https://science.nasa.gov/earth/earth-observatory/dead-sea-7115/) |
| `geography-tanganyika-01` | Hard | Lake Tanganyika / Rift Lakes | [NASA: A Tale of Contrasting Rift Valley Lakes ](https://science.nasa.gov/earth/earth-observatory/a-tale-of-contrasting-rift-valley-lakes-145194/) |
| `geography-okavango-01` | Hard | Okavango Delta / Inland Wetlands | [UNESCO World Heritage Centre: Okavango Delta ](https://whc.unesco.org/en/list/1432/) |
| `geography-danube-delta-01` | Hard | Danube Delta / European Wetlands | [UNESCO World Heritage Centre: Danube Delta ](https://whc.unesco.org/en/list/588) |
| `geography-socotra-01` | Hard | Socotra Archipelago / Island Biodiversity | [UNESCO World Heritage Centre: Socotra Archipelago ](https://whc.unesco.org/en/list/1263) |
| `geography-wadden-sea-01` | Hard | Wadden Sea / Coastal Wetlands | [UNESCO World Heritage Centre: Wadden Sea ](https://whc.unesco.org/en/list/1314/) |

## Music

Pack `music-clues@1.0.0`; scope `music-sources@1.0.0` (15 documents).
Status: assistant source-checked. All 15 rows also received the editorial checks above.

| Clue ID | Level | Answer / Subtopic | Authoritative Reference |
| --- | --- | --- | --- |
| `music-piano-01` | Easy | Piano / Keyboard Instruments | [The Metropolitan Museum of Art: Grand Piano - MetKids ](https://archived.metmuseum.org/art/online-features/metkids/explore/501788/Bartolomeo-Cristofori-Grand-Piano) |
| `music-beethoven-01` | Easy | Ludwig van Beethoven / Composers | [Carnegie Hall: Interactive Guide to Beethoven's Ninth Symphony ](https://www.carnegiehall.org/Education/For-Educators/All-Together-A-Global-Ode-to-Joy) |
| `music-armstrong-01` | Easy | Louis Armstrong / Jazz Performers | [Louis Armstrong House Museum: How did Louis Armstrong get the nickname Satchmo? ](https://www.louisarmstronghouse.org/faq/pellentesque-leo-risus-cursus-a-varius-vitae-venenatis-ut-arcu-3/) |
| `music-beatles-01` | Easy | The Beatles / Pop Groups | [Apple Corps Ltd.: Beatles '64 ](https://www.thebeatles.com/beatles-64) |
| `music-violin-01` | Easy | Violin / String Instruments | [The Metropolitan Museum of Art: Renaissance Violins ](https://www.metmuseum.org/pt/essays/renaissance-violins) |
| `music-jazz-01` | Standard | Jazz / Musical Traditions | [U.S. National Park Service: Louisiana: New Orleans Jazz National Historic Park ](https://www.nps.gov/articles/jazz.htm) |
| `music-ellington-01` | Standard | Duke Ellington / Jazz Composers | [Smithsonian Institution: Duke Ellington: Jazz Legend and Cultural Icon ](https://www.si.edu/spotlight/duke-ellington) |
| `music-simone-01` | Standard | Nina Simone / Singers and Pianists | [Nina Simone Official Website: The Official Home of Nina Simone ](https://www.ninasimone.com/) |
| `music-sitar-01` | Standard | Sitar / Indian Instruments | [The Metropolitan Museum of Art: Musical Instruments of the Indian Subcontinent ](https://www.metmuseum.org/it/essays/musical-instruments-of-the-indian-subcontinent) |
| `music-four-seasons-01` | Standard | The Four Seasons / Baroque Works | [Los Angeles Philharmonic: The Four Seasons ](https://www.laphil.com/works/the-four-seasons) |
| `music-clara-schumann-01` | Hard | Clara Schumann / Romantic Composers | [Los Angeles Philharmonic: Piano Concerto in A minor, Op. 7 ](https://www.laphil.com/works/piano-concerto-in-a-minor-op-7) |
| `music-gamelan-01` | Hard | Gamelan / Indonesian Ensembles | [UNESCO Intangible Cultural Heritage: Gamelan ](https://ich.unesco.org/en/RL/gamelan-01607) |
| `music-oud-01` | Hard | Oud / Middle Eastern Instruments | [UNESCO Intangible Cultural Heritage: Crafting and playing the Oud ](https://ich.unesco.org/en/RL/crafting-and-playing-the-oud-01867) |
| `music-hildegard-01` | Hard | Hildegard of Bingen / Medieval Music | [British Library: Add MS 15102 ](https://searcharchives.bl.uk/catalog/032-002086795) |
| `music-joplin-01` | Hard | Scott Joplin / Ragtime | [Library of Congress: American Composers and Musicians from A to Z: J (Part 1 - Joplin, Scott) ](https://blogs.loc.gov/nls-music-notes/2019/03/american-composers-and-musicians-from-a-to-z-j-part-1-joplin-scott/) |

## Preserved Original Demo

`coursework-demo-clues@1.0.0` contains the same eight Standard clues and eight
Easy variants as `data/demo`, including aliases and public-hint retrieval input.
It shares eight source adaptations in `coursework-demo-sources@1.0.0`; Hard has
zero eligible clues. Tests compare migrated clue/category/answer strings and
source text to the unchanged originals. Source credit and unknown Wikipedia
revision history are preserved; see [original attribution](../data/demo/ATTRIBUTION.md).
Status `legacy-preserved` records migration review, not a new factual audit of
historical assertions. It is separate from the unchanged 100-question benchmark.

## Stage 2B Preparation

Prepared 2026-11-04 by Codex (AI assistant). **No human feedback was received in
this pass. Human factual/editorial coverage: 0/60. Human playtested coverage:
0/60. Calibrated coverage: 0/60.** The existing `source-checked` statuses record
assistant work only. Original Demo's 16 clues remain outside this 60-clue pilot
ledger and retain `legacy-preserved` status.

The private [structured ledger](review/starter-ledger.json) has exactly one row
per starter clue/version, including wording, hint, accepted aliases, exact curated
support and its source metadata/hash/path. Human fields and observation references
are empty; the separate `assistantReview` section records this pass's findings.
The [existing checklist](PLAYTEST-CHECKLIST.md) now supplies a novice-friendly
six-game protocol and [blank observation](review/observations.csv) and
[planned-game](review/six-game-pilot.csv) forms. No planned slot is a participant
or completed game. Raw notes/receipts belong in ignored `reviews/local/`.

### Assistant Source Recheck (Not Human Review)

All 60 local clues, hints, aliases, curated support passages and attribution
records were inspected. Every upstream reference was attempted through the web
reader. **49 returned readable surrounding institutional text; 11 did not.**
This is a fresh access/context check, not a replacement snapshot, exhaustive
fact audit, human endorsement or rights clearance. The per-clue ledger records
the access result, context locator, concerns and limitations. Known revision IDs
remain null; local hashes still pin only the original brief excerpts.

| Follow-up | Clues / Evidence | Status |
| --- | --- | --- |
| Blocked or missing context | USGS Seismograph (verification); Great Pyramid (503/timeout); Rosetta Stone report (403); Silk Roads (timeout); Great Zimbabwe (503/unavailable); Okavango and Wadden Sea (timeouts); MetKids Piano (only privacy link); Smithsonian Ellington (404); UNESCO Gamelan and Oud (verification challenges) | 11 fresh context checks unresolved, not factual-error determinations. Human reviewers need accessible authoritative context. |
| Context recovered for fragments | Suez Canal (opening/sea-level discussion), Great Wall (English description after slash-URL retry), Galapagos (description/synthesis), Four Seasons (title/composer/program notes), Hildegard (manuscript contents/legacy catalogue note), Armstrong (FAQ explanation) | Six contexts now read; saved v1.0.0 excerpts remain fragments/headings. No archival completeness claimed. |
| Wording precision | `general-knowledge-braille-01`: the [LOC source](https://www.loc.gov/nls/services-and-resources/informational-publications/about-braille/) describes dot positions/patterns, not six raised dots in every character | Candidate clarification for review, not a reported human misunderstanding. |
| Explicit alias candidate | `general-knowledge-periodic-table-01`: [RSC](https://periodic-table.rsc.org/about) uses the alternative name Periodic System, absent from the current key | Supported alias candidate for a versioned editorial decision; zero human rejection observations. No fuzzy grading added. |
| Hint support gap | `music-beatles-01`: the [official page](https://www.thebeatles.com/beatles-64) names the four members but its returned text does not establish the Liverpool/insect-name hint | Obtain additional authoritative context before approving that hint. No hint-use reports collected. |
| Stable source candidate | `geography-everest-01`: the [public NOAA page](https://oceanservice.noaa.gov/facts/highestpoint.html) supports the sea-level qualifier | Verified replacement candidate for the UAT URL; changing source metadata requires a new collection/pack/catalog version, not a silent v1.0.0 edit. |

No content, alias, hint, scorer or difficulty change was made on the basis of
uncollected feedback. The limited UI change makes scored clue IDs/versions visible
and lets reviewers save revealed receipts before session expiry. It does not
collect observations automatically or expose keys before scoring.

### Evidence and Coverage Rules

Count unique clue/version records with actual dated human review and traceable
evidence, not the number of notes, source fetches, automated tests or computer
correct answers. Track human-reviewed, playtested and calibrated independently.
The initial six-game pilot covers at most 30 unique clues for one player and has
fixed-order/familiarity/hint confounders. The remaining 30 need additional player
coverage; all 60 need factual/editorial decisions before full human approval.
Record actual feedback before proposing label changes. Preserve contrary views
and original receipts, and use the authoring rules for any supported correction.

Stage 2B preparation is ready for a real participant; **Stage 2B human review and
calibration are not complete**. Next: run the six games, save receipts and return
version-linked observations, then adjudicate factual/alias/wording/UI issues with
counts and source evidence. New opponents, niche-topic packs, generation and
public deployment remain later work.

### Preparation Verification

Checks actually run in this pass on 2026-11-04 (not inherited test claims):

- Before editing, `mvn -o clean verify` passed the existing **105 tests**. After
  the UI/material changes, the same full command passed **108 tests**, zero
  failures/errors/skips, and packaged the jar. Three new checks pin all 60 ledger
  records to their real pack/source content, verify empty human fields and source
  access accounting, and validate the six planned slots/blank observation form.
  HTTP privacy coverage now also rejects review-ledger/feedback/private-note paths.
- `main.PackTool validate data/game.json --indexes` passed: five collections,
  68 source documents and 76 clues including Original Demo. Existing indexes were
  checked, not rebuilt. JavaScript syntax and `git diff --check` passed.
- Scripted Chromium QA completed Music/Standard (test guesses 300 vs computer
  500) and General Knowledge/Easy (test guesses 500 vs 500). These are functional
  QA scores, **not participant observations or new pack evaluation measurements**.
  Correct/incorrect/explicit alias grading, plain-text rendering of a markup-like
  guess, round/game exports, previous-game export, duplicate submission, scored
  reload and stable JSON snapshots were checked. Exported JSON matched server
  receipts; active-game export was blocked and references stayed empty pre-score.
- Keyboard selectors, hint disclosure, save controls and focus were exercised.
  Three General Knowledge hints were opened by the script, not a human. A scored
  answer response was deliberately lost; Reconnect recovered it without changing
  points or the submitted guess. 390px/320px layouts had no horizontal overflow;
  a separate 390px touch-emulated session exercised selection, submission and
  saving. An injected local file-save failure showed a readable error and did not
  block Next Clue; retrying the save worked. This is not a physical-device,
  screen-reader, cross-browser or usability study.
- Console inspection showed the intentionally dropped response and two blocked
  blob-fetch attempts from the first export test harness; those attempts were
  corrected to inspect the generated Blob without weakening CSP. No application
  exception was observed in the successful flows. Older logged failure-injection
  messages from Stage 2A were not counted as this pass's checks.
- SHA-256 comparisons confirmed unchanged catalog, all private packs and source
  metadata, original questions/sample/PDFs, `RESULTS.md` and `PACK-RESULTS.json`.
  Snapshot fingerprints passed validation. No coursework-archive writes, content
  version changes, scorer tuning, new dependency or benchmark reruns occurred.

Only preparation and software QA are complete. Human observations remain empty;
all human coverage counts above remain zero. Downloaded QA receipts are test
artifacts, not review evidence. The owned preview server was stopped after checks.

## Human Review Handoff

Separate 2026-11-06 local outcome: [Jovian v2](../drafts/jovian-v2/README.md)
records assistant source/editorial decisions for five revised clues. A newer
`source-checked` candidate was activated, played and rolled back only in an
isolated catalog; normal activation was not performed. This adds no human
approvals/observations: starter review/playtest/calibration remain each 0/60,
Jovian each 0/5. See [PACK-ACTIVATION.md](PACK-ACTIVATION.md) for the private
preview, review schema and operator gate. Prior ledgers/versions/results remain.

Stage 2A now provides complete five-clue games and missed-clue/source review.
Use the [Stage 2B review and playtesting checklist](PLAYTEST-CHECKLIST.md) to
collect actual factual, ambiguity, alias, correctness, hint-use and difficulty
observations. No human approvals, participants or study findings were added by
the software milestone; the review statuses and limitations above remain intact.

Use [PACK-AUTHORING.md](PACK-AUTHORING.md) to record any corrections as new content
versions. Review every linked source in context, verify rights and missing aliases,
and play all levels before promoting to `human-reviewed`. Keep evidence review
independent from opponent evaluation. See [RESULTS.md](RESULTS.md) and the
machine-readable [PACK-RESULTS.json](PACK-RESULTS.json) for the actual measurements.
