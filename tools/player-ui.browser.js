// Browser-tool regression function. Run only against an owned player with fresh QA saves.
// It creates its own browser session and uses deliberately incorrect guesses, not private keys.
async (page) => {
  const context = await page.context().browser().newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const p = await context.newPage(), base = 'http://127.0.0.1:8793';
  const check = (condition, message) => { if (!condition) throw Error(message); };
  const focus = () => p.evaluate(() => document.activeElement.id);
  const session = () => p.evaluate(async () => await (await fetch('/api/session')).json());
  const ready = () => p.waitForFunction(() => !document.getElementById('submit').disabled);
  const nextReady = () => p.waitForFunction(() => !document.getElementById('next-clue').hidden && !document.getElementById('next-clue').disabled);
  const lose = async path => {
    const holder = {};
    await p.route(path, async route => { const result = await route.fetch(); holder.committed = await result.json(); await route.abort(); }, { times: 1 });
    return holder;
  };
  const recover = async () => {
    await p.locator('#error-box').waitFor({ state: 'visible' });
    check(await focus() === 'retry', 'Lost response must focus Reconnect, not BODY');
    await p.keyboard.press('Enter');
    await p.waitForFunction(() => document.getElementById('error-box').hidden && !document.getElementById('round').getAttribute('aria-busy').includes('true'));
  };
  try {
    await p.goto(base); await p.waitForFunction(() => !document.getElementById('topic').disabled);
    await p.locator('#topic').focus(); await p.keyboard.press('Enter'); await p.keyboard.press('End'); await p.keyboard.press('Enter');
    check((await p.locator('#topic-value').innerText()).includes('Kanye West'), 'Run against the isolated showcase catalog');
    await p.locator('#difficulty').focus(); await p.keyboard.press('Enter'); await p.keyboard.press('ArrowDown'); await p.keyboard.press('Enter');
    const start = await lose('**/api/games');
    await p.locator('#start').click(); await recover(); await ready();
    let s = await session(); check(s.game.id === start.committed.id, 'Lost start created a replacement game');
    check(await focus() === 'answer' || await focus() === 'clue', 'Recovered active clue focus');
    const firstRound = s.game.round.id;
    check(!JSON.stringify(s.game).match(/acceptedAnswers|computerAnswer|curatedEvidence|answerSourceId|candidate/), 'Pre-reveal disclosure');
    await p.locator('#answer').focus(); await p.keyboard.type('Typing stays here'); await p.waitForTimeout(650);
    check(await focus() === 'answer' && await p.locator('#answer').inputValue() === 'Typing stays here', 'Typing lost focus/value');
    const answer = await lose('**/api/games/*/answer');
    await p.locator('#answer').press('Enter'); await recover(); await nextReady();
    s = await session(); check(await focus() === 'outcome', 'Recovered scored round must focus outcome');
    check(JSON.stringify(s.game.round) === JSON.stringify(answer.committed.round), 'Recovered score/guess changed');
    for (const id of ['article-link', 'history-link', 'revision-link', 'license-link', 'curated-link', 'curated-history-link', 'curated-revision-link', 'curated-license-link']) {
      check(await p.locator('#' + id).isVisible(), id + ' missing');
      check((await p.locator('#' + id).getAttribute('href')).startsWith('https://'), id + ' unsafe');
    }
    const advance = await lose('**/api/games/*/advance');
    await p.locator('#next-clue').focus(); await p.keyboard.press('Enter'); await recover(); await ready();
    s = await session(); check(s.game.round.id === advance.committed.round.id && s.game.clueNumber === 2, 'Lost advancement skipped/replaced clue');
    const duplicate = await p.evaluate(async x => await (await fetch('/api/games/' + x.game + '/advance', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-ClueLab-Request': '1' }, body: JSON.stringify({ roundId: x.round }) })).json(), { game: s.game.id, round: firstRound });
    check(duplicate.clueNumber === 2, 'Duplicate advance skipped clue');
    const polling = [];
    for (let number = 2; number <= 5; number++) {
      if (number > 2) {
        await nextReady(); await p.locator('#next-clue').focus(); await p.keyboard.press('Enter');
        await p.locator('#clue').focus(); const before = await focus(); await ready();
        polling.push({ before, after: await focus() }); check(await focus() === before, 'Routine READY polling stole focus');
      }
      await ready(); const completion = number === 5 ? await lose('**/api/games/*/answer') : null;
      await p.locator('#answer').fill('UI regression guess'); await p.locator('#answer').press('Enter');
      if (completion) {
        await recover(); await p.locator('#game-summary').waitFor({ state: 'visible' });
        s = await session(); check(JSON.stringify(s.game) === JSON.stringify(completion.committed), 'Completed receipt changed');
      } else await nextReady();
    }
    check(await focus() === 'game-outcome', 'Recovered completion must focus summary');
    const completed = (await session()).game;
    check(await p.locator('#game-review .source-links a').count() === 40, 'Review does not use the same evidence navigation');
    const replay = await lose('**/api/games'); await p.locator('#start').focus(); await p.keyboard.press('Enter'); await recover(); await ready();
    s = await session(); check(s.game.id === replay.committed.id && s.game.id !== completed.id, 'Replay recovery identity changed');
    const old = await (await p.request.get(base + '/api/games/' + completed.id)).json();
    check(JSON.stringify(old) === JSON.stringify(completed), 'Replay changed old receipt');
    await p.setViewportSize({ width: 320, height: 844 });
    check(!await p.evaluate(() => document.documentElement.scrollWidth > innerWidth), 'Narrow layout overflow');
    return { passed: true, gameId: completed.id, score: [completed.playerScore, completed.computerScore], polling, links: 40, replayId: s.game.id, privateBeforeReveal: true, oldReceiptUnchanged: true, mobileWidth: 320 };
  } finally { await context.close(); }
}
