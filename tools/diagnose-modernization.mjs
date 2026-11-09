import { readFile, mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const sha = value => createHash('sha256').update(value).digest('hex');
const run = (command, args) => {
  const result = spawnSync(command, args, {encoding: 'utf8', maxBuffer: 32 * 1024 * 1024});
  if (result.error || result.status !== 0) throw Error(result.error || result.stderr);
  return result.stdout.trim();
};
await mkdir('indexes', {recursive:true}); await mkdir('target', {recursive:true});
const work = await mkdtemp('indexes/diagnosis-');
const rows = (await readFile('data/demo/easy-questions.txt','utf8')).trim().split(/\r?\n\r?\n/)[1].split(/\r?\n/);
const query = rows[0] + ' ' + rows[1], reports = {};
for (const name of ['legacy','modern']) {
  const prefix = name === 'legacy' ? 'legacy-baseline/' : '';
  const cp = prefix + 'target/classes:' + (await readFile(prefix + 'target/classpath.txt','utf8')).trim();
  const classes = `${work}/${name}-probe`; await mkdir(classes);
  run('javac', ['-cp',cp,'-d',classes,'tools/diagnostics/ModernizationProbe.java']);
  const base = `${work}/${name}`;
  run('java',['-cp',cp,'main.Watson','index','data/demo/corpus',base,'1']);
  const records = run('java',['-cp',classes+':'+cp,'main.ModernizationProbe',base,Buffer.from(query).toString('base64')])
    .split('\n').map(line=>line.split('\t').map(x=>Buffer.from(x,'base64').toString('utf8')));
  reports[name] = {records};
}
const value = (runtime,kind) => reports[runtime].records.filter(r=>r[0]===kind);
const bodies = runtime => value(runtime,'document').map(r=>r.slice(1,5));
const result = {
  schemaVersion:1,id:'guided-analysis-diagnosis',measuredAt:new Date().toISOString(),
  corpusSha256:sha(await readFile('data/demo/corpus/articles.txt')),
  questionsSha256:sha(await readFile('data/demo/easy-questions.txt')),
  query,acceptedAnswers:rows[2],
  processedQueryIdentical:JSON.stringify(value('legacy','processed'))===JSON.stringify(value('modern','processed')),
  storedFieldsIdentical:JSON.stringify(bodies('legacy'))===JSON.stringify(bodies('modern')),
  controlledQueryTokensIdentical:JSON.stringify(value('legacy','controlled-query-tokens'))===JSON.stringify(value('modern','controlled-query-tokens')),
  controlledDocumentTokensIdentical:JSON.stringify(value('legacy','controlled-document-tokens'))===JSON.stringify(value('modern','controlled-document-tokens')),
  classification:'native-codec-controlled-diagnostic-not-an-opponent-trial',reports
};
if(!result.controlledQueryTokensIdentical || !result.controlledDocumentTokensIdentical) throw Error('Analysis-policy replay has additional token differences; do not infer an isolated cause.');
for(const mode of ['indexStop=false,queryStop=false','indexStop=false,queryStop=true','indexStop=true,queryStop=false','indexStop=true,queryStop=true']) {
  const hits=name=>value(name,'hit').filter(r=>r[1]===mode);
  const legacy=hits('legacy'),modern=hits('modern');
  if(JSON.stringify(legacy.map(r=>r.slice(2,4)))!==JSON.stringify(modern.map(r=>r.slice(2,4)))
      || legacy.some((r,i)=>Math.abs(Number(r[4])-Number(modern[i][4]))>1e-5))throw Error('Controlled ranks/scores differ beyond float tolerance: '+mode);
}
await writeFile('target/modernization-diagnosis.json',JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify({report:'target/modernization-diagnosis.json',work,processedQueryIdentical:result.processedQueryIdentical,storedFieldsIdentical:result.storedFieldsIdentical,
  rankings:Object.fromEntries(Object.keys(reports).map(name=>[name,value(name,'hit').map(r=>r.slice(1,5))]))}));
