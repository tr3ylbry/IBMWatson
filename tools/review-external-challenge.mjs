import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { fingerprint } from './prepare-external-challenge.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
export const isRedirect = raw => /^\s*#redirect\b/im.test(raw);
export function selections(predictions,redirectIds) {
  const stop=new Set('which what where when who whose this that with from into does did have been were would could'.split(' '));
  return {
    canonicalTitleFailures:predictions.filter(p=>p.coverage==='canonical-title'&&p.acceptedRank!==1).slice(0,3).map(p=>p.id),
    missingTitle:predictions.filter(p=>p.coverage==='missing').slice(0,2).map(p=>p.id),
    aliasOnly:predictions.find(p=>p.coverage==='alias-only')?.id??null,
    redirect:predictions.find(p=>redirectIds.has(p.id))?.id??null,
    sharedTerm:predictions.find(p=>p.acceptedRank!==1&&p.candidates[0]?.title.toLowerCase().match(/[a-z]{4,}/g)?.some(w=>!stop.has(w)&&p.question.toLowerCase().includes(w)))?.id??null
  };
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  if(process.argv.length!==5)throw Error('Usage: node tools/review-external-challenge.mjs <dataset> <local-details-report> <local-corpus>');
  const [dataset,details,corpus]=process.argv.slice(2),frozenBytes=await readFile(path.join(dataset,'freeze.json')),frozen=JSON.parse(frozenBytes);
  const reportBytes=await readFile(details),report=JSON.parse(reportBytes);
  if(report.freezeSha256!==sha(frozenBytes)||report.split!=='test')throw Error('Not the measured frozen test');
  const privateBytes=await readFile(path.join(dataset,'private/test.json')),collectionBytes=await readFile(path.join(dataset,'collection.json'));
  if(sha(privateBytes)!==frozen.hashes['private/test.json']||sha(collectionBytes)!==frozen.hashes['collection.json'])throw Error('Frozen files differ');
  const cases=JSON.parse(privateBytes).cases,source=JSON.parse(collectionBytes),shards=new Map(source.sources.map(s=>[s.file,s]));
  if(cases.length!==report.predictions.length||cases.some((c,i)=>c.id!==report.predictions[i].id))throw Error('Measured case order differs');
  let cachedName,cachedLines;const checked=new Set(),redirectIds=new Set(),groups={},rows=[];
  for(let i=0;i<cases.length;i++) {
    const c=cases[i],p=report.predictions[i];let onlyRedirect=true;
    for(const d of c.coverage.documents) {
      const file=path.join(corpus,'wiki',d.file);
      if(!checked.has(d.file)) {if(await fingerprint(file)!==shards.get(d.file)?.sha256)throw Error('Source snapshot changed');checked.add(d.file);}
      if(cachedName!==d.file){cachedLines=(await readFile(file,'utf8')).split('\n');cachedName=d.file;}
      const raw=cachedLines.slice(d.firstLine-1,d.lastLine).join('\n');if(sha(raw)!==d.rawSha256)throw Error('Source boundary changed');
      const redirect=isRedirect(raw);if(redirect)redirectIds.add(c.id);else onlyRedirect=false;
    }
    const shape=p.coverage==='missing'?'missing-accepted-title':onlyRedirect?'redirect-only-title':'nonredirect-title';
    const g=groups[shape]??={size:0,correctFirst:0,hitAt10:0};g.size++;if(p.acceptedRank===1)g.correctFirst++;if(p.acceptedRank>0)g.hitAt10++;
    rows.push({id:c.id,shape,acceptedRank:p.acceptedRank});
  }
  console.log(JSON.stringify({schemaVersion:1,classification:'assistant-post-evaluation-source-shape-diagnostics-not-factual-or-human-approval',
    freezeSha256:sha(frozenBytes),detailsSha256:sha(reportBytes),groups,plannedReview:selections(report.predictions,redirectIds),rows,
    caveat:'Redirect detection is a post-evaluation source-shape check, not support validation. Original title coverage and all 150 denominators remain unchanged; no filtered metric replaces the declared result.',
    exposure:'Test outcomes and representative question/source material have now been inspected. This is a regression set, not a permanently unseen test.'},null,2));
}
