'use strict';
import { sourceUrl, evidenceLinks, gameFocusTarget } from './presentation.mjs';
const el = id => document.getElementById(id);
let round = null, catalog = null, game = null, gameLength = 0;
let busy = false, connected = false, poll, needsRecovery = false, epoch = 0;
let sessionTotals = { player: 0, computer: 0 }, completedGames = [], summaryId, historyFocus, pastGame;
const gameMode = () => !!catalog && gameLength === 5;

// Select-only combobox shared by all catalog-driven selectors.
class Picker {
  constructor(id, changed) {
    this.id = id; this.changed = changed; this.options = []; this.value = null; this.active = 0;
    this.button = el(id); this.list = el(id + '-options');
    this.button.addEventListener('click', () => this.list.hidden ? this.open() : this.close());
    this.button.addEventListener('keydown', event => this.key(event));
    this.list.addEventListener('mousedown', event => event.preventDefault());
    this.button.addEventListener('blur', () => { if (!this.list.hidden) this.close(true); });
    document.addEventListener('pointerdown', event => {
      if (!el(id + '-picker').contains(event.target) && !this.list.hidden) this.close(true);
    });
  }
  set(options, preferred) {
    this.close(); this.options = options;
    this.value = options.some(option => option.id === preferred) ? preferred : null;
    el(this.id + '-value').textContent = (options.find(option => option.id === this.value) || {}).label || 'Choose an Option';
    this.list.replaceChildren(...options.map((item, index) => {
      const option = document.createElement('div');
      option.id = this.id + '-option-' + index; option.setAttribute('role', 'option');
      option.setAttribute('aria-selected', String(item.id === this.value)); option.textContent = item.label;
      option.addEventListener('click', () => { this.active = index; this.close(true); this.button.focus(); });
      return option;
    }));
  }
  highlight() {
    [...this.list.children].forEach((option, index) => option.setAttribute('aria-selected', String(index === this.active)));
    const active = this.list.children[this.active];
    if (active) { this.button.setAttribute('aria-activedescendant', active.id); active.scrollIntoView({ block: 'nearest' }); }
  }
  open() {
    if (this.button.disabled || !this.options.length) return;
    this.typed = ''; this.typedAt = 0;
    this.active = Math.max(0, this.options.findIndex(option => option.id === this.value));
    this.list.hidden = false; this.button.setAttribute('aria-expanded', 'true'); this.highlight();
  }
  close(commit = false) {
    this.list.hidden = true; this.button.setAttribute('aria-expanded', 'false'); this.button.removeAttribute('aria-activedescendant');
    if (commit && this.options[this.active]) {
      const item = this.options[this.active]; this.value = item.id;
      el(this.id + '-value').textContent = item.label; this.changed();
    }
  }
  key(event) {
    const open = !this.list.hidden;
    if (event.key === 'Tab') { if (open) this.close(true); return; }
    if (event.key === 'Escape') { if (open) { event.preventDefault(); this.close(); } return; }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault(); if (open) this.close(true); else this.open(); return;
    }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault(); if (!open) this.open();
      if (event.key === 'ArrowUp' && event.altKey && open) { this.close(true); return; }
      if (event.key === 'Home') this.active = 0;
      else if (event.key === 'End') this.active = this.options.length - 1;
      else if (open) this.active = Math.max(0, Math.min(this.options.length - 1, this.active + (event.key === 'ArrowDown' ? 1 : -1)));
      this.highlight();
    } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault(); if (!open) this.open();
      const now = Date.now(); this.typed = (now - this.typedAt < 700 ? this.typed : '') + event.key.toLowerCase(); this.typedAt = now;
      if ([...this.typed].every(letter => letter === this.typed[0])) this.typed = this.typed[0];
      const match = this.options.findIndex(option => option.label.toLowerCase().startsWith(this.typed));
      if (match >= 0) { this.active = match; this.highlight(); }
    }
  }
}
const topicPicker = new Picker('topic', () => { setLevels(difficultyPicker.value); controls(); });
const difficultyPicker = new Picker('difficulty', controls);
const selectedTopic = () => catalog && catalog.topics.find(topic => topic.id === topicPicker.value);
const selectedLevel = () => { const topic = selectedTopic(); return topic && topic.difficulties.find(level => level.id === difficultyPicker.value); };
const levelName = id => id[0].toUpperCase() + id.slice(1);

function setLevels(preferred) {
  const topic = selectedTopic();
  difficultyPicker.set(topic ? topic.difficulties.map(level => ({ id: level.id, label: level.label + ' / ' + level.eligible + ' Clues' })) : [], preferred);
  el('topic-hint').textContent = topic ? topic.description + ' The computer can search ' + topic.documents + ' source documents.' : '';
}
function sessionData(session) {
  el('new-session').hidden = true;
  connected = true; needsRecovery = false; catalog = session.catalog || null; gameLength = session.gameLength || 0;
  sessionTotals = session.sessionTotals || { player: session.round ? session.round.playerTotal : 0, computer: session.round ? session.round.computerTotal : 0 };
  completedGames = session.completedGames || [];
  const active = session.game && session.game.state === 'ACTIVE' ? session.game
    : session.round && session.round.state !== 'FINALIZED' ? session.round : null;
  if (catalog) {
    const recent = session.game || session.round;
    topicPicker.set(catalog.topics, (active && active.topicId) || topicPicker.value || (recent && recent.topicId) || catalog.defaultTopic);
    setLevels((active && active.difficulty) || difficultyPicker.value || (recent && recent.difficulty) || catalog.defaultDifficulty);
  } else {
    difficultyPicker.set(session.difficulties.map(id => ({ id, label: id === 'easy' ? 'Easy - Guided Clues' : 'Standard - Original Clues' })),
      (active && active.difficulty) || difficultyPicker.value || session.difficulties[0]);
  }
  el('topic-label').textContent = gameMode() ? 'Topic for Your Next Game' : 'Topic for Your Next Round';
  el('difficulty-label').textContent = gameMode() ? 'Difficulty for Your Next Game' : 'Difficulty for Your Next Round';
  el('game-lede').textContent = gameMode() ? 'Five clues. Two independent answers. See what you know, then see what it found.' : 'One clue. Two independent answers. See what you know, then see what it found.';
  el('game-instructions').textContent = gameMode() ? 'Choose a topic and level for five clues. No timer. Each correct answer earns 100 points.' : 'No timer. No trick phrasing required. Just the answer.';
  if (gameMode() && session.game) renderGame(session.game);
  else { game = null; render(session.round); }
  renderHistory();
  document.dispatchEvent(new CustomEvent('cluelab-session', {detail:session}));
}
el('clue-hint').addEventListener('toggle', () => {
  el('hint-toggle-label').textContent = el('clue-hint').open ? 'Hide Hint' : 'Show Hint';
});

function sourceLink(id, url) {
  const safe = sourceUrl(url);
  el(id).hidden = !safe;
  if (safe) el(id).href = safe; else el(id).removeAttribute('href');
}
const sourceDetails = evidence => evidence.title + ' / ' + evidence.source + ' / ' + (evidence.publisher || 'Wikipedia contributors') + '. '
  + (evidence.capturedOn ? 'Captured: ' + evidence.capturedOn + '. ' : '')
  + (evidence.revisionId ? 'Observed Revision: ' + evidence.revisionId + '. ' : 'Exact Revision Unavailable. ')
  + (evidence.revisionNote || 'Historical adapted source; exact revision unavailable.');
function renderEvidence(evidence, curated = false) {
  el(curated ? 'curated-evidence' : 'evidence').hidden = !evidence;
  if (!evidence) return;
  el(curated ? 'curated-text' : 'evidence-text').textContent = evidence.text || 'This article has no stored excerpt.';
  el(curated ? 'curated-details' : 'evidence-details').textContent = sourceDetails(evidence);
  el(curated ? 'curated-attribution' : 'attribution').textContent = evidence.attribution;
  const links = evidenceLinks(evidence);
  sourceLink(curated ? 'curated-link' : 'article-link', links.article);
  for (const kind of ['history', 'revision', 'license']) sourceLink((curated ? 'curated-' : '') + kind + '-link', links[kind]);
}

function engineOutcome(receipt) {
  const outcomes = {
    answered: `The computer committed its top-ranked article before your submission. Search: ${receipt.responseTimeMs.toFixed(1)} ms.`,
    'no-match': 'The engine found no matching article. The computer receives 0 points.',
    unavailable: 'The search engine was unavailable. The computer could not answer and receives 0 points. Your answer was still scored.',
    timeout: 'The search engine timed out. The computer could not answer and receives 0 points. Your answer was still scored.',
    interrupted: 'The server stopped before the computer saved an answer. Its search was not rerun. The computer receives 0 points; your answer was still scored.'
  };
  return outcomes[receipt.computerStatus] || 'The computer could not answer.';
}

function textNode(tag, text, className) {
  const node = document.createElement(tag); node.textContent = text;
  if (className) node.className = className;
  return node;
}
function reviewReference(receipt) {
  const identity = receipt.clueId ? `Clue ID: ${receipt.clueId}. Pack: ${receipt.packId}@${receipt.packVersion}. Sources: ${receipt.collectionId}@${receipt.collectionVersion}. ` : '';
  return identity + `Round: ${receipt.id}.` + (receipt.gameId ? ` Game: ${receipt.gameId}.` : '');
}
function saveReceipt(receipt) {
  // Export only a server-finalized snapshot already revealed to this session.
  if (!receipt || !['FINALIZED', 'COMPLETED'].includes(receipt.state)) return;
  let url;
  try {
    const file = new Blob([JSON.stringify({ format: 'cluelab-review-receipt', schemaVersion: 1, receipt }, null, 2) + '\n'], { type: 'application/json' });
    url = URL.createObjectURL(file);
    const link = document.createElement('a'); link.href = url;
    link.download = `cluelab-${receipt.state === 'COMPLETED' ? 'game' : 'clue'}-${receipt.id}.json`;
    document.body.append(link); link.click(); link.remove();
  } catch {
    error('Your browser could not save the result. Keep this page open and record the review references before the session expires.');
  } finally {
    if (url) setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
el('save-clue').addEventListener('click', () => saveReceipt(round));
el('save-game').addEventListener('click', () => saveReceipt(game));
el('save-past-game').addEventListener('click', () => saveReceipt(pastGame));
function reviewEvidence(body, evidence, title, description) {
  body.append(textNode('h4', title), textNode('p', description, 'small'));
  if (!evidence) { body.append(textNode('p', 'No retrieved evidence is available.', 'small')); return; }
  body.append(textNode('blockquote', evidence.text), textNode('p', sourceDetails(evidence), 'small'), textNode('p', evidence.attribution, 'small'));
  const links = document.createElement('p'); links.className = 'source-links';
  const labels = { article: 'Saved Source', history: 'Contributor History', revision: 'Observed Revision', license: 'Declared License' };
  for (const [kind, url] of Object.entries(evidenceLinks(evidence))) if (url) {
    const link = textNode('a', labels[kind]); link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer';
    link.setAttribute('aria-label', labels[kind] + ': ' + evidence.title); links.append(link);
  }
  body.append(links);
}
function renderReview(match, target) {
  const items = match.rounds.map(receipt => {
    const item = document.createElement('li'), disclosure = document.createElement('details');
    item.dataset.missed = String(receipt.playerPoints === 0);
    const correct = receipt.playerPoints === 100 ? 'Correct' : 'Missed';
    disclosure.append(textNode('summary', `Clue ${receipt.clueNumber}: ${correct} / You ${receipt.playerPoints} / Computer ${receipt.computerPoints}`));
    const body = document.createElement('div'); body.className = 'receipt-body';
    body.append(textNode('p', reviewReference(receipt), 'small review-reference'));
    body.append(textNode('p', receipt.category, 'eyebrow'), textNode('p', receipt.clue, 'review-clue'));
    if (receipt.hint) body.append(textNode('p', 'Public Hint: ' + receipt.hint, 'small'));
    body.append(textNode('p', 'Your Answer: ' + receipt.playerAnswer, 'review-answer'),
      textNode('p', "Computer's Answer: " + (receipt.computerAnswer || 'No Answer'), 'review-answer'),
      textNode('p', 'Accepted Answers: ' + receipt.acceptedAnswers.join(' / '), 'review-answer'),
      textNode('p', engineOutcome(receipt), 'small'));
    reviewEvidence(body, receipt.evidence, 'What the Computer Found', 'The actual passage returned by the opponent search.');
    reviewEvidence(body, receipt.curatedEvidence, 'Accepted Answer: Source Check', 'Support selected by the pack author for the accepted answer.');
    disclosure.append(body); disclosure.open = receipt.clueNumber === match.length;
    item.append(disclosure); return item;
  });
  target.replaceChildren(...items);
}
function filterMisses() {
  const only = el('missed-only').checked;
  [...el('game-review').children].forEach(item => { item.hidden = only && item.dataset.missed !== 'true'; });
  el('no-missed-clues').hidden = !only || [...el('game-review').children].some(item => item.dataset.missed === 'true');
}
el('missed-only').addEventListener('change', filterMisses);

function renderGame(next) {
  if (game && (next.number < game.number || (next.id === game.id && next.revision < game.revision))) return;
  const completedBefore = game && game.id === next.id && game.state === 'COMPLETED';
  game = next;
  sessionTotals = next.round ? { player: next.round.playerTotal, computer: next.round.computerTotal } : sessionTotals;
  if (!completedBefore && game.state === 'COMPLETED') {
    const last = game.rounds[game.rounds.length - 1];
    sessionTotals = { player: last.playerTotal, computer: last.computerTotal };
  }
  render(game.round);
  if (game.state === 'COMPLETED') {
    if (summaryId !== game.id) {
      el('game-outcome').textContent = game.outcome === 'tie' ? 'An Even Game.' : game.outcome === 'player' ? 'This Game Is Yours.' : 'A Game for the Computer.';
      el('game-summary-score').textContent = `You ${game.playerScore} / Computer ${game.computerScore}. Five of five clues scored.`;
      renderReview(game, el('game-review')); el('missed-only').checked = false; summaryId = game.id; filterMisses();
    }
    el('status').textContent = 'Game complete. All five results are final. Review your clues or choose another game.';
  }
  controls();
}

function renderHistory() {
  const previous = completedGames.filter(played => !game || played.id !== game.id);
  el('game-history').replaceChildren(...previous.map(played => {
    const item = document.createElement('li');
    const button = textNode('button', `Review Game ${played.number}: ${played.topicLabel} / ${levelName(played.difficulty)} / You ${played.playerScore}, Computer ${played.computerScore}`);
    button.type = 'button'; button.addEventListener('click', () => action(async () => {
      const receipt = await api(`/api/games/${played.id}`);
      pastGame = receipt;
      el('past-game-title').textContent = `Game ${receipt.number} / ${receipt.topicLabel}`;
      el('past-game-score').textContent = `You ${receipt.playerScore} / Computer ${receipt.computerScore}`;
      renderReview(receipt, el('past-game-review')); el('past-game').hidden = false; historyFocus = button;
      el('past-game-title').focus();
    }));
    item.append(button); return item;
  }));
  controls();
}
el('close-past-game').addEventListener('click', () => {
  el('past-game').hidden = true; if (historyFocus && historyFocus.isConnected) historyFocus.focus();
});

async function api(path, data) {
  const options = { credentials: 'same-origin', cache: 'no-store' };
  if (data !== undefined) {
    options.method = 'POST';
    options.headers = { 'Content-Type': 'application/json', 'X-ClueLab-Request': '1' };
    options.body = JSON.stringify(data);
  }
  let response;
  try { response = await fetch(path, { ...options, signal: AbortSignal.timeout(20000) }); }
  catch { throw new Error('Connection interrupted. Reconnect to recover your game before trying again.'); }
  let body;
  try { body = await response.json(); }
  catch { throw new Error('The server response could not be read. Reconnect to recover your game.'); }
  if (!response.ok) { const failure=new Error(body.error || 'The local server could not complete the request.');failure.status=response.status;throw failure; }
  return body;
}

function error(message) {
  el('error').textContent = message;
  el('error-box').hidden = !message;
}

function controls() {
  const activeGame = gameMode() && game && game.state === 'ACTIVE';
  const active = activeGame || !!round && round.state !== 'FINALIZED', level = selectedLevel();
  const unavailable = catalog && (!level || level.eligible < (gameMode() ? gameLength : 1));
  const reset = level && (gameMode() ? level.remaining < gameLength : level.exhausted);
  const blocked = busy || needsRecovery || !connected;
  el('start').hidden = !!active;
  el('start').disabled = blocked || !!active || !!unavailable;
  el('start-label').textContent = unavailable ? 'Selection Unavailable' : gameMode()
    ? reset ? 'Reset and Replay Five Clues' : game ? 'Start a New Game' : 'Start Five-Clue Game'
    : reset ? 'Replay This Selection' : round ? 'Start Another Round' : 'Start a Round';
  el('submit').disabled = blocked || !round || round.state !== 'READY';
  el('answer').disabled = blocked || !round || round.state !== 'READY'; el('retry').disabled = busy;
  el('new-session').disabled = busy;
  el('next-clue').hidden = !activeGame || !round || round.state !== 'FINALIZED';
  el('next-clue').disabled = blocked;
  for (const picker of [topicPicker, difficultyPicker]) {
    picker.button.disabled = blocked || !!active;
    if (picker.button.disabled) picker.close();
  }
  el('topic-control').hidden = !catalog || !!active; el('difficulty-control').hidden = !!active;
  el('availability').hidden = !catalog || !!active;
  el('availability').textContent = unavailable ? gameMode()
    ? 'A game needs five eligible clues. This selection has ' + (level ? level.eligible : 0) + '. Choose another topic or difficulty.'
    : 'No eligible clues for this selection. Choose another topic or difficulty; nothing will be substituted.'
    : gameMode() && reset ? level.remaining + ' unused clues remain. Reset starts a new shuffled cycle and may repeat earlier clues. Your previous game receipts stay available.'
    : level && level.exhausted ? 'All ' + level.eligible + ' clues completed. Replay starts a new shuffled cycle with the same clues.'
    : level ? level.remaining + ' unused clues available. ' + (gameMode() ? 'Your game reserves five, with no repeats.' : 'No repeats until you choose to replay.') : '';
  el('welcome').hidden = !!round || !!game;
  el('game-summary').hidden = !game || game.state !== 'COMPLETED';
  el('game-progress').hidden = !game;
  if (game) {
    el('game-progress').value = game.completedRounds; el('game-progress').max = game.length;
    el('round-number').textContent = `GAME ${game.number} / CLUE ${game.clueNumber} OF ${game.length} / ${game.difficulty.toUpperCase()}`;
  } else if (!round) el('round-number').textContent = gameMode() ? 'YOUR FIRST GAME' : 'YOUR FIRST ROUND';
  el('score-label').textContent = game ? 'CURRENT GAME' : gameMode() && !round ? 'YOUR NEXT GAME' : 'SESSION TOTALS';
  el('scoreboard').setAttribute('aria-label', game || gameMode() && !round ? 'Current Game Scores' : 'Session Totals');
  el('player-total').textContent = game ? game.playerScore : round ? round.playerTotal : '0';
  el('computer-total').textContent = game ? game.computerScore : round ? round.computerTotal : '0';
  el('session-totals').hidden = !gameMode();
  el('session-totals').textContent = `Session Totals: You ${sessionTotals.player} / Computer ${sessionTotals.computer}`;
  el('history').hidden = !gameMode() || !!active || !el('game-history').children.length;
  el('game-history').querySelectorAll('button').forEach(button => { button.disabled = blocked; });
  el('round').setAttribute('aria-busy', String(busy || (round && round.state === 'THINKING') || false));
}

function render(next) {
  clearTimeout(poll);
  const previousId = round && round.id;
  round = next;
  el('welcome').hidden = !!round;
  el('question').hidden = !round;
  el('result').hidden = !round || round.state !== 'FINALIZED';
  el('review-reference').textContent = '';
  el('save-clue').disabled = !round || round.state !== 'FINALIZED';
  el('answer-form').hidden = !round || round.state === 'FINALIZED';
  if (!round) {
    el('status').textContent = 'Ready when you are.';
    el('round-number').textContent = 'YOUR FIRST ROUND';
    el('player-total').textContent = '0';
    el('computer-total').textContent = '0';
    controls();
    return;
  }
  if (previousId !== round.id) {
    el('answer').value = '';
    el('clue-hint').open = false;
  }
  el('round-number').textContent = `ROUND ${String(round.number).padStart(2, '0')} / ${round.difficulty.toUpperCase()}`;
  el('category').textContent = round.category;
  el('round-scope').textContent = round.topicLabel ? round.topicLabel + ' / ' + levelName(round.difficulty) + ' / Selection locked for this ' + (game ? 'game.' : 'round.') : '';
  const hintAt = round.clue.indexOf(' Hint: '), structured = typeof round.hint === 'string';
  el('clue').textContent = structured || hintAt < 0 ? round.clue : round.clue.slice(0, hintAt);
  const hint = structured ? round.hint : hintAt < 0 ? '' : round.clue.slice(hintAt + 7);
  el('clue-hint').hidden = !hint;
  el('clue-hint-text').textContent = hint ? hint[0].toUpperCase() + hint.slice(1) : '';
  el('player-total').textContent = round.playerTotal;
  el('computer-total').textContent = round.computerTotal;
  if (round.state === 'THINKING') {
    el('status').textContent = 'The computer is searching the documents. Its answer stays sealed.';
    poll = setTimeout(refresh, 350);
  } else if (round.state === 'READY') {
    el('status').textContent = 'The computer has finished. Your turn to lock an answer.';
  } else {
    el('status').textContent = 'Round complete. Both guesses are final.';
    el('outcome').textContent = round.outcome === 'tie' ? 'An Even Round.' : round.outcome === 'player' ? 'This Round Is Yours.' : 'A Round for the Computer.';
    el('player-answer').textContent = round.playerAnswer;
    el('computer-answer').textContent = round.computerAnswer || 'No Answer';
    el('player-points').textContent = `+${round.playerPoints} Points`;
    el('computer-points').textContent = `+${round.computerPoints} Points`;
    el('accepted').textContent = round.acceptedAnswers.join(' / ');
    el('engine-outcome').textContent = engineOutcome(round);
    el('review-reference').textContent = reviewReference(round);
    renderEvidence(round.evidence);
    renderEvidence(round.curatedEvidence, true);
  }
  controls();
}

function focusGame(recoveryRequired = false) {
  const target = el(gameFocusTarget({ game, round, recoveryRequired, expired: !el('new-session').hidden }));
  if (target && !target.disabled && target.getClientRects().length) target.focus();
}

async function refresh() {
  if (!round) return;
  if (busy) { poll = setTimeout(refresh, 350); return; }
  const expectedEpoch = epoch, id = game ? game.id : round.id;
  try {
    const next = await api(game ? `/api/games/${id}` : `/api/rounds/${id}`);
    if (epoch !== expectedEpoch || id !== (game ? game.id : round && round.id)) return;
    if (game) renderGame(next); else render(next);
  } catch (failure) {
    if (epoch !== expectedEpoch) return;
    needsRecovery = true; error(failure.message); el('new-session').hidden = failure.status !== 410; controls();
  }
}

async function action(work, { focusGameAfter = false } = {}) {
  if (busy) return;
  busy = true; epoch++;
  clearTimeout(poll);
  error('');
  controls();
  try { await work(); }
  catch (failure) { needsRecovery = true; error(failure.message); el('new-session').hidden = failure.status !== 410; el('status').textContent = 'Reconnect to recover the server-held game before continuing.'; }
  finally {
    busy = false;
    controls();
    // Only deliberate actions move focus. Polling/background refresh never does.
    if (needsRecovery) focusGame(true);
    else if (focusGameAfter) focusGame();
  }
}

async function reconnect() {
  await action(async () => {
    sessionData(await api('/api/session'));
    if (game || round) el('status').textContent = 'Game recovered. ' + el('status').textContent;
  }, { focusGameAfter: true });
}

el('start').addEventListener('click', () => action(async () => {
  el('status').textContent = gameMode() ? 'Preparing your five-clue game...' : 'Preparing your next clue...';
  el('past-game').hidden = true;
  const topic = selectedTopic(), level = selectedLevel();
  const choice = catalog ? { topicId: topic.id, difficulty: difficultyPicker.value, packVersion: topic.packVersion, replay: gameMode() ? level.remaining < gameLength : level.exhausted }
    : { difficulty: difficultyPicker.value };
  if (gameMode()) {
    renderGame(await api('/api/games', { ...choice, requestId: crypto.randomUUID() }));
    sessionData(await api('/api/session'));
  } else render(await api('/api/rounds', choice));
}, { focusGameAfter: true }));
el('next-clue').addEventListener('click', () => action(async () => {
  el('status').textContent = 'Opening the next clue...';
  renderGame(await api(`/api/games/${game.id}/advance`, { roundId: round.id }));
}, { focusGameAfter: true }));
el('answer-form').addEventListener('submit', event => {
  event.preventDefault();
  const answer = el('answer').value;
  if (!answer.trim()) { error('Enter an answer before submitting.'); el('answer').focus(); return; }
  action(async () => {
    el('status').textContent = 'Locking your answer and scoring the round...';
    if (game) renderGame(await api(`/api/games/${game.id}/answer`, { roundId: round.id, answer }));
    else render(await api(`/api/rounds/${round.id}/answer`, { answer }));
    sessionData(await api('/api/session'));
  }, { focusGameAfter: true });
});
el('retry').addEventListener('click', reconnect);
el('new-session').addEventListener('click', () => {
  if (!window.confirm('Start a new two-hour game session? This does not recover old scores or transfer old game ownership. Topic requests keep their separate identity.')) return;
  action(async () => {
    game = null; round = null;
    sessionData(await api('/api/session/identity', { confirmed: true }));
  }, { focusGameAfter: true });
});
reconnect();

// Request recovery is separate from the two-hour game session. No recovery credential enters JS storage.
let requestWorking=false, requestEpoch=0, requestTimer, requestEnabled=false, requestRenderSignature='';
let requestPending=null;try{requestPending=JSON.parse(sessionStorage.getItem('cluelab-topic-intention')||'null');}catch{}
function requestNotice(message){el('request-error').textContent=message;el('request-error').hidden=!message;}
function rememberRequest(){try{if(requestPending)sessionStorage.setItem('cluelab-topic-intention',JSON.stringify(requestPending));else sessionStorage.removeItem('cluelab-topic-intention');}catch{requestNotice('This tab cannot preserve a pending intention. Keep it open and use Reconnect Requests after an interrupted response.');}}
const requestStateName=state=>({'awaiting-review':'Awaiting Review',requested:'Requested',preparing:'Preparing',ready:'Ready',failed:'Failed',cancelled:'Cancelled',clarification:'Needs Clarification',unavailable:'Unavailable'}[state]||state);
async function loadRequests(){
  const epoch=++requestEpoch;clearTimeout(requestTimer);
  try{const data=await api('/api/topic-requests');if(epoch!==requestEpoch)return;renderRequests(data);el('request-new-identity').hidden=true;}
  catch(e){requestNotice(e.message);el('request-new-identity').hidden=e.status!==401;}
  if(requestEnabled&&el('topic-requests').open)requestTimer=setTimeout(loadRequests,5000);
}
function renderRequests(data){
  const signature=JSON.stringify([data,requestWorking,requestPending,!!(game&&game.state==='ACTIVE')]);
  if(signature===requestRenderSignature)return;
  requestRenderSignature=signature;
  const focused=document.activeElement, focusedCard=focused.closest('.request-card'), focusedId=focusedCard?.dataset.requestId, focusedLabel=focused.textContent;
  el('request-list').replaceChildren();el('request-status').textContent=requestPending?'A response was lost. Reconnect Requests retries the same intention.':data.requests.length+' Saved Request(s). Preparing means actual operator work began.';
  for(const request of data.requests){const card=textNode('article',null,'request-card');card.dataset.requestId=request.id;card.append(textNode('h4',request.topic+' / '+levelName(request.difficulty)),textNode('p',requestStateName(request.state)+' / Five Clues'),textNode('p',request.message),textNode('p',request.readiness.message));
    const closed=['cancelled','failed','unavailable','clarification'].includes(request.state);
    if(request.offered)card.append(textNode('p','Offered: '+request.offered.label+' / Pack '+request.offered.packId+'@'+request.offered.packVersion+' / Sources '+request.offered.collectionId+'@'+request.offered.collectionVersion+' / '+request.offered.eligible+' Eligible / Review: '+request.offered.reviewStatus+'. Not inferred human approval.'));
    for(const choice of request.choices){const b=textNode('button','Choose '+choice.label+' / '+choice.packVersion+' / '+choice.eligible+' '+levelName(request.difficulty)+' Clues');b.type='button';b.disabled=closed||requestWorking||!!requestPending;b.addEventListener('click',()=>requestMutation('/api/topic-requests/'+request.id+'/choose',{selectionId:choice.selectionId}));card.append(b);}
    if(request.state==='ready'){
      for(const replay of [false,true]){const b=textNode('button',replay?'Reset and Play Requested Topic (May Repeat)':'Play Requested Topic');b.type='button';b.disabled=requestWorking||!!requestPending||!!(game&&game.state==='ACTIVE');b.addEventListener('click',()=>requestMutation('/api/topic-requests/'+request.id+'/play',{selectionId:request.offered.selectionId,replay},true));card.append(b);}
      card.append(textNode('p','Reset is explicit and only allowed after fewer than five unused clues remain. An active game keeps its original versions.','small'));
    }
    if(!closed){const b=textNode('button','Cancel This Request');b.type='button';b.disabled=requestWorking||!!requestPending;b.addEventListener('click',()=>requestMutation('/api/topic-requests/'+request.id+'/cancel',{}));card.append(b);}
    card.append(textNode('p','Request '+request.id+' / Expires '+new Date(request.expiresAt).toLocaleDateString(),'small'));el('request-list').append(card);
  }
  el('request-submit').disabled=requestWorking||!!requestPending;
  if(focusedId){const card=[...el('request-list').children].find(c=>c.dataset.requestId===focusedId);if(card){const replacement=[...card.querySelectorAll('button')].find(b=>b.textContent===focusedLabel&&!b.disabled)||card.querySelector('h4');replacement.tabIndex=replacement.tagName==='H4'?-1:replacement.tabIndex;replacement.focus();}}
}
async function performRequestPending(){
  if(!requestPending||requestWorking)return;requestWorking=true;requestRenderSignature='';requestEpoch++;clearTimeout(requestTimer);el('request-submit').disabled=true;el('request-list').querySelectorAll('button').forEach(b=>{b.disabled=true;});requestNotice('');
  try{const result=await api(requestPending.path,requestPending.body);const play=requestPending.play;requestPending=null;rememberRequest();
    if(play){renderGame(result);sessionData(await api('/api/session'));focusGame();}else el('request-status').textContent='Request saved.';
  }catch(e){if(e.status){requestPending=null;rememberRequest();}requestNotice(e.message+' Use Reconnect Requests to recover, or a new Play intention after game-session expiry.');}
  finally{requestWorking=false;await loadRequests();}
}
async function requestMutation(path,data,play=false){if(requestWorking)return;if(requestPending){requestNotice('Recover the pending request first.');return;}requestPending={path,body:{retryId:crypto.randomUUID(),...data},play};rememberRequest();await performRequestPending();}
el('topic-request-form').addEventListener('submit',event=>{event.preventDefault();requestMutation('/api/topic-requests',{topic:el('request-topic').value.trim(),difficulty:document.querySelector('input[name=request-level]:checked').value});});
el('request-reconnect').addEventListener('click',async()=>{requestNotice('');if(requestPending)await performRequestPending();else await loadRequests();});
el('request-new-identity').addEventListener('click',async()=>{try{await api('/api/topic-requests/identity',{confirmed:true});requestPending=null;rememberRequest();requestNotice('');await loadRequests();}catch(e){requestNotice(e.message);}});
el('topic-requests').addEventListener('toggle',()=>{if(el('topic-requests').open&&requestEnabled)loadRequests();else clearTimeout(requestTimer);});
document.addEventListener('cluelab-session',event=>{requestEnabled=!!event.detail.topicRequestsEnabled;el('topic-requests').hidden=!requestEnabled;if(requestEnabled&&el('topic-requests').open&&!requestWorking)loadRequests();});
