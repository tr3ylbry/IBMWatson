import test from 'node:test';
import assert from 'node:assert/strict';
import { evidenceLinks, sourceUrl, gameFocusTarget } from '../src/main/resources/web/presentation.mjs';

test('deliberate recovery selects the active clue, scored result, summary or recovery action', () => {
  for (const [state, target] of [['THINKING', 'clue'], ['READY', 'answer'], ['FINALIZED', 'outcome']]) {
    assert.equal(gameFocusTarget({ game: { state: 'ACTIVE' }, round: { state } }), target);
  }
  assert.equal(gameFocusTarget({ game: { state: 'COMPLETED' }, round: null }), 'game-outcome');
  assert.equal(gameFocusTarget({ round: { state: 'READY' }, recoveryRequired: true }), 'retry');
  assert.equal(gameFocusTarget({ recoveryRequired: true, expired: true }), 'new-session');
  assert.equal(gameFocusTarget({ game: null, round: null }), 'start');
});

test('source references reject executable, relative and credential-bearing URLs', () => {
  for (const url of [null, '', '/private/key', 'javascript:alert(1)', 'data:text/html,test', 'http://example.org/', 'https://user:secret@example.org/']) assert.equal(sourceUrl(url), null);
  assert.equal(sourceUrl('https://example.org/a?q=one#two'), 'https://example.org/a?q=one#two');
});

test('Wikipedia history and observed revision use the saved URL with correctly encoded titles', () => {
  const links = evidenceLinks({ title: 'An unrelated game title', articleUrl: 'https://en.wikipedia.org/wiki/Graduation_%28album%29', revisionId: '1384000473', attribution: 'CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/.' });
  const history = new URL(links.history), revision = new URL(links.revision);
  assert.equal(history.searchParams.get('title'), 'Graduation (album)');
  assert.equal(history.searchParams.get('action'), 'history');
  assert.equal(revision.searchParams.get('oldid'), '1384000473');
  assert.equal(revision.searchParams.get('title'), 'Graduation (album)');
  assert.equal(links.license, 'https://creativecommons.org/licenses/by-sa/4.0/');
  assert.equal(new URL(evidenceLinks({ articleUrl: 'https://en.wikipedia.org/wiki/L%27Anse_aux_Meadows' }).history).searchParams.get('title'), "L'Anse aux Meadows");
});

test('unknown and malformed revisions never acquire invented revision links', () => {
  for (const revisionId of [null, undefined, 'unknown', '-1', '0', '1&action=edit', 'https://example.org/']) assert.equal(evidenceLinks({ articleUrl: 'https://en.wikipedia.org/wiki/Jupiter', revisionId }).revision, null);
  assert.equal(evidenceLinks({ articleUrl: 'https://en.wikipedia.org/wiki/%ZZ' }).history, null);
});

test('institutional sources keep their saved reference; content associations are not silently repaired', () => {
  const articleUrl = 'https://www.britishmuseum.org/sites/default/files/2019-10/BM-report-and-accounts-2015-2016.pdf';
  assert.deepEqual(evidenceLinks({ title: 'Rosetta Stone', articleUrl, revisionId: '123', attribution: 'Rights review pending.' }), { article: articleUrl, history: null, revision: null, license: null });
  assert.equal(evidenceLinks({ title: 'Missing Reference' }).article, null);
});

test('old standalone receipts preserve explicit safe references and declared historical license', () => {
  const historyUrl = 'https://en.wikipedia.org/w/index.php?title=Body_mass_index&action=history';
  const links = evidenceLinks({ articleUrl: 'https://en.wikipedia.org/wiki/Body_mass_index', historyUrl, attribution: 'Historical coursework sample. CC BY-SA 3.0. Exact revision unavailable.' });
  assert.equal(links.history, historyUrl);
  assert.equal(links.revision, null);
  assert.equal(links.license, 'https://creativecommons.org/licenses/by-sa/3.0/');
  assert.notEqual(evidenceLinks({ articleUrl: links.article, historyUrl: 'javascript:bad' }).history, 'javascript:bad');
});

test('license navigation preserves IGO declarations without granting licenses to other sources', () => {
  assert.equal(evidenceLinks({ attribution: 'CC BY-SA IGO 3.0 (https://creativecommons.org/licenses/by-sa/3.0/igo/).' }).license, 'https://creativecommons.org/licenses/by-sa/3.0/igo/');
  assert.equal(evidenceLinks({ attribution: 'No blanket license; https://creativecommons.org.evil.test/licenses/by-sa/4.0/' }).license, null);
  assert.equal(evidenceLinks({ articleUrl: 'https://en.wikipedia.org.evil.test/wiki/Jupiter', revisionId: '123' }).history, null);
});
