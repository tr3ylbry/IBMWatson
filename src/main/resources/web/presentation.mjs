// Presentation only: never change a stored receipt or infer a source from an answer title.
export function sourceUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol === 'https:' && !url.username && !url.password) return url.href;
  } catch { /* Missing or unsafe references stay unavailable. */ }
  return null;
}

export function evidenceLinks(evidence) {
  const links = { article: sourceUrl(evidence.articleUrl), history: null, revision: null, license: null };
  if (links.article) {
    const article = new URL(links.article);
    if (article.hostname === 'en.wikipedia.org' && !article.port) {
      let title;
      try {
        if (article.pathname.startsWith('/wiki/')) title = decodeURIComponent(article.pathname.slice(6)).replaceAll('_', ' ');
        else if (article.pathname === '/w/index.php') title = article.searchParams.get('title');
      } catch { /* Do not guess a title from malformed encoding. */ }
      if (title) {
        const history = new URL('https://en.wikipedia.org/w/index.php');
        history.searchParams.set('title', title); history.searchParams.set('action', 'history');
        links.history = history.href;
        // Observed metadata is not a guarantee of an immutable snapshot render.
        if (/^[1-9]\d{0,19}$/.test(String(evidence.revisionId ?? ''))) {
          const revision = new URL('https://en.wikipedia.org/w/index.php');
          revision.searchParams.set('title', title); revision.searchParams.set('oldid', evidence.revisionId);
          links.revision = revision.href;
        }
      }
    }
  }
  // Older standalone receipts already have an explicit history reference.
  links.history = sourceUrl(evidence.historyUrl) || links.history;
  const license = String(evidence.attribution || '').match(/https:\/\/creativecommons\.org\/(?:licenses\/[a-z-]+\/[0-9.]+(?:\/igo)?|publicdomain\/(?:zero|mark)\/[0-9.]+)\//i);
  if (license) links.license = sourceUrl(license[0]);
  else if (/\bCC BY-SA 3\.0\b/.test(evidence.attribution || '')) links.license = 'https://creativecommons.org/licenses/by-sa/3.0/';
  return links;
}

export function gameFocusTarget({ game, round, recoveryRequired = false, expired = false }) {
  if (recoveryRequired) return expired ? 'new-session' : 'retry';
  if (game?.state === 'COMPLETED') return 'game-outcome';
  if (round?.state === 'FINALIZED') return 'outcome';
  if (round?.state === 'READY') return 'answer';
  if (round?.state === 'THINKING') return 'clue';
  return 'start';
}
