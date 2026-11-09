import { createReadStream } from 'node:fs';
import { readFile, writeFile, mkdir, readdir, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { articles, sha256 } from './wiki-shard.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const dataset = path.join(root, 'data/external-challenge/v1');
const local = path.join(root, 'corpus/external-triviaqa-v1');
const ARCHIVE_SHA = '7f0c94207fd0a1eb7bba3d52cdfe0735c98de850b9e5aa4e75f2776ef9a531d0';
// Match String.trim + Locale.ROOT lowercase + Java's default ASCII whitespace collapse.
export const normalize = value => value.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '').toLowerCase().replace(/[ \t\n\x0b\f\r]+/g, ' ');
const valid = (value, size) => typeof value === 'string' && value.trim().length > 0 && value.length <= size && !/[\x00-\x1f\x7f]/u.test(value);
export async function fingerprint(file) {
  const digest = createHash('sha256'); for await (const chunk of createReadStream(file)) digest.update(chunk); return digest.digest('hex');
}
async function publish(file, bytes) {
  await mkdir(path.dirname(file), {recursive:true});
  try { await writeFile(file, bytes, {flag:'wx'}); }
  catch (error) { if (error.code !== 'EEXIST') throw error; if (sha256(await readFile(file)) !== sha256(bytes)) throw Error(`Existing frozen file differs: ${file}. Publish a new version; do not overwrite.`); }
}
const json = value => JSON.stringify(value, null, 2) + '\n';

/** Stream the upstream Data array, bounded to one record. The train JSON exceeds V8's string limit. */
export async function* records(stream) {
  let buffer = '', cursor = 0, start = -1, depth = 0, quoted = false, escaped = false, entered = false, ended = false;
  for await (const chunk of stream) {
    if (ended) continue;
    buffer += chunk;
    if (!entered) {
      const marker = /"Data"\s*:\s*\[/.exec(buffer);
      if (!marker) { if (buffer.length > 65536) throw Error('Missing upstream Data array'); continue; }
      buffer = buffer.slice(marker.index + marker[0].length); entered = true; cursor = 0;
    }
    for (; cursor < buffer.length; cursor++) {
      const char = buffer[cursor];
      if (start < 0) {
        if (/\s|,/.test(char)) continue;
        if (char === ']') { ended = true; break; }
        if (char !== '{') throw Error('Expected a JSON question object');
        start = cursor; depth = 1; continue;
      }
      if (quoted) {
        if (escaped) escaped = false;
        else if (char === '\\') escaped = true;
        else if (char === '"') quoted = false;
      } else if (char === '"') quoted = true;
      else if (char === '{') depth++;
      else if (char === '}' && --depth === 0) {
        yield JSON.parse(buffer.slice(start, cursor + 1)); start = -1;
      }
    }
    if (start < 0) { buffer = ''; cursor = 0; }
    else { buffer = buffer.slice(start); cursor = buffer.length; start = 0; }
    if (buffer.length > 8 * 1024 * 1024) throw Error('Upstream record exceeds 8 MiB');
  }
  if (!entered || !ended || start >= 0) throw Error('Truncated upstream Data array');
}

export function project(record, mediaPattern) {
  const answer = record.Answer;
  if (!valid(record.QuestionId, 200) || !valid(record.Question, 4096)) return {reason:'invalid-id-or-question'};
  if (new RegExp(mediaPattern, 'i').test(record.Question)) return {reason:'external-media-required'};
  if (answer?.Type !== 'WikipediaEntity' || !valid(answer.MatchedWikiEntityName, 200)) return {reason:'unavailable-answer-type'};
  const aliases = [...new Set([answer.MatchedWikiEntityName, answer.Value, ...(answer.Aliases ?? [])])];
  if (aliases.some(a => !valid(a, 200) || a.includes('|'))) return {reason:'invalid-upstream-alias'};
  const unique = new Map(aliases.map(a=>[normalize(a), a]));
  return {clue:{id:record.QuestionId, question:record.Question, questionSha256:sha256(record.Question),
    questionSource:record.QuestionSource ?? null, canonicalTitle:answer.MatchedWikiEntityName,
    answerType:'article-title', aliases:[...unique.values()], upstreamAnswer:answer,
    authorship:'external-verbatim', curatedSupport:null}};
}

async function collection(sourceDirectory, plan) {
  const files = (await readdir(sourceDirectory, {withFileTypes:true})).filter(f=>f.isFile() && f.name.endsWith('.txt')).map(f=>f.name).sort();
  if (files.length !== plan.corpusExpectedFiles) throw Error('Expected exactly the predeclared 80 source shards');
  await mkdir(path.join(local,'wiki'), {recursive:true});
  const rows = [], sources = [], titles = new Set(); let documents = 0;
  for (const name of files) {
    const source = path.join(sourceDirectory,name), bytes = await readFile(source), digest = sha256(bytes);
    const docs = articles(bytes); if (!docs.length) throw Error(`No articles: ${name}`);
    sources.push({file:name, bytes:bytes.length, sha256:digest, documents:docs.length, revisionId:null});
    for (const d of docs) {
      rows.push(JSON.stringify({id:name+'#'+d.firstLine, title:d.title, file:name, firstLine:d.firstLine,lastLine:d.lastLine,rawSha256:d.rawSha256})+'\n');
      titles.add(normalize(d.title)); documents++;
    }
    const target = path.join(local,'wiki',name);
    try { if (await fingerprint(target) !== digest) throw Error('Existing corpus snapshot differs: '+target); }
    catch (error) { if (error.code !== 'ENOENT') throw error; await copyFile(source,target,1); }
  }
  const membership = rows.join('');
  await publish(path.join(local,'members.jsonl'),membership);
  await publish(path.join(dataset,'collection.json'),json({schemaVersion:1,id:'coursework-all-shards',version:'1.0.0',language:'en',
    description:plan.corpus, sources, documents, distinctNormalizedTitles:titles.size, membershipSha256:sha256(membership),
    boundaryParser:'tools/wiki-shard.mjs articles; same strict title rule as Indexer',
    attribution:'Wikipedia contributors, historical processed export attributed under CC BY-SA 3.0. Exact article revisions and processing history unknown. Third-party material may retain separate rights. Full text not redistributed in Git.',
    sourceDateHint:'2014-06-02 in filenames is not an immutable revision',revisionId:null}));
  console.log(json({prepared:'source-only-collection',files:files.length,documents,distinctNormalizedTitles:titles.size}));
}
async function priorQuestions() {
  const known = new Set();
  for (const name of ['questions.txt','data/demo/questions.txt','data/demo/easy-questions.txt']) {
    const lines = (await readFile(path.join(root,name),'utf8')).split(/\r?\n/); for (let i=1;i<lines.length;i+=4) known.add(normalize(lines[i]));
  }
  for (const name of await readdir(path.join(root,'data/packs/private'))) {
    const pack = JSON.parse(await readFile(path.join(root,'data/packs/private',name),'utf8'));
    for (const clue of pack.clues) known.add(normalize(clue.text));
  }
  for (const split of ['development','held-out']) for (const clue of JSON.parse(await readFile(path.join(root,`data/retrieval-lab/v1/private/${split}.json`),'utf8')).cases) known.add(normalize(clue.clue));
  return known;
}
async function questions(upstreamDirectory, plan) {
  const archiveSha256=await fingerprint(path.join(local,'triviaqa-unfiltered.tar.gz'));
  if(archiveSha256!==ARCHIVE_SHA)throw Error('Unexpected official archive bytes; verify before extraction/import');
  const collectionBytes = await readFile(path.join(dataset,'collection.json')), source = JSON.parse(collectionBytes);
  const membershipBytes = await readFile(path.join(local,'members.jsonl')); if(sha256(membershipBytes)!==source.membershipSha256) throw Error('Membership differs');
  const titleMap = new Map();
  for (const line of membershipBytes.toString('utf8').trim().split('\n')) {const doc=JSON.parse(line), key=normalize(doc.title);if(!titleMap.has(key))titleMap.set(key,[]);titleMap.get(key).push(doc);}
  const seenIds=new Set(), seenQuestions=await priorQuestions(), seenAnswers=new Set(), exclusions=[], sources=[], output={};
  for(const [split,filename,target] of [['development','unfiltered-web-train.json',plan.developmentSize],['test','unfiltered-web-dev.json',plan.testSize]]) {
    const file=path.join(upstreamDirectory,filename), pool=[]; let total=0;
    for await(const record of records(createReadStream(file,{encoding:'utf8'}))) {
      total++; const hash=sha256('cluelab-external-v1\n'+record.QuestionId);
      if(pool.length===plan.candidatePoolPerSplit && hash>=pool.at(-1).hash) continue;
      const entry={hash,record}; let i=pool.findIndex(v=>v.hash>hash); if(i<0)i=pool.length;pool.splice(i,0,entry);if(pool.length>plan.candidatePoolPerSplit)pool.pop();
    }
    sources.push({file:filename,sha256:await fingerprint(file),total,pool:pool.length,outsideDeclaredPool:total-pool.length});
    const selected=[];
    for(const {record} of pool) {
      let {clue,reason}=project(record,plan.mediaPattern);
      if(!reason && seenIds.has(clue.id))reason='duplicate-id';
      if(!reason && seenQuestions.has(normalize(clue.question)))reason='duplicate-or-prior-question';
      if(!reason && clue.aliases.some(a=>seenAnswers.has(normalize(a))))reason='duplicate-or-cross-split-answer';
      if(!reason && selected.length>=target)reason='eligible-outside-predeclared-quota';
      if(!reason) {
        seenIds.add(clue.id);seenQuestions.add(normalize(clue.question));clue.aliases.forEach(a=>seenAnswers.add(normalize(a)));
        const canonical=titleMap.get(normalize(clue.canonicalTitle))??[], aliases=clue.aliases.flatMap(a=>titleMap.get(normalize(a))??[]);
        clue.coverage={kind:canonical.length?'canonical-title':aliases.length?'alias-only':'missing',documents:[...new Map(aliases.map(d=>[d.id,d])).values()]};
        selected.push(clue);
      }
      exclusions.push({id:record.QuestionId,upstreamFile:filename,questionSha256:sha256(record.Question??''),disposition:reason?'excluded':'selected',reason:reason??split});
    }
    output[split]={schemaVersion:1,id:plan.id+'-'+split,version:plan.version,split,upstreamFile:filename,cases:selected};
    await publish(path.join(dataset,`private/${split}.json`),json(output[split]));
  }
  await publish(path.join(dataset,'exclusions.json'),json({schemaVersion:1,sources,candidates:exclusions}));
  await publish(path.join(dataset,'selection.json'),json({schemaVersion:1,splits:Object.fromEntries(Object.entries(output).map(([name,set])=>[name,set.cases.map(c=>({id:c.id,questionSha256:c.questionSha256,upstreamFile:set.upstreamFile}))]))}));
  const hashes={};for(const name of ['plan.json','collection.json','private/development.json','private/test.json','exclusions.json','selection.json','LICENSE-TriviaQA.txt','UPSTREAM-README.txt'])hashes[name]=sha256(await readFile(path.join(dataset,name)));
  await publish(path.join(dataset,'freeze.json'),json({schemaVersion:1,id:plan.id,version:plan.version,archiveSha256,hashes,
    indexType:1,scorer:4,profile:'baseline',analysisPolicy:plan.analysisPolicy,repetitions:plan.repetitions,normalization:plan.normalization,
    sourceScope:'coursework-all-shards@1.0.0',runtime:{lucene:'10.6.0',corenlp:'4.6.0',indexSchema:'3'},humanApproved:false}));
  console.log(json({prepared:'frozen-external-challenge',sizes:Object.fromEntries(Object.entries(output).map(([k,v])=>[k,v.cases.length])),coverage:Object.fromEntries(Object.entries(output).map(([k,v])=>[k,v.cases.reduce((a,c)=>(a[c.coverage.kind]=(a[c.coverage.kind]??0)+1,a),{})]))}));
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  if(process.argv.length!==4)throw Error('Usage: node tools/prepare-external-challenge.mjs archive <downloaded-archive> | collection <original-wikiPages> | questions <extracted-triviaqa-directory>');
  const plan=JSON.parse(await readFile(path.join(dataset,'plan.json'),'utf8'));
  if(process.argv[2]==='collection')await collection(path.resolve(process.argv[3]),plan);
  else if(process.argv[2]==='questions')await questions(path.resolve(process.argv[3]),plan);
  else if(process.argv[2]==='archive') {
    if(await fingerprint(path.resolve(process.argv[3]))!==ARCHIVE_SHA)throw Error('Archive fingerprint differs. Do not extract/import it.');
    console.log('Official archive fingerprint matches the frozen identity.');
  }
  else throw Error('Unknown preparation command');
}
