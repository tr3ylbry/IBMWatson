import { readFile, writeFile, mkdir, mkdtemp, readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
process.chdir(root);
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const classpath = async legacy => (legacy ? 'legacy-baseline/target/classes:' : 'target/classes:') +
  (await readFile(legacy ? 'legacy-baseline/target/classpath.txt' : 'target/classpath.txt', 'utf8')).trim();
const run = (cp, main, args, expect = 0) => {
  const result = spawnSync('java', ['-Xmx1g', '-cp', cp, main, ...args], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  if (result.error || (expect === 0 ? result.status !== 0 : result.status === 0))
    throw new Error(`Unexpected result for ${main} ${args.join(' ')}: ${result.error || result.stderr}`);
  return { status: result.status, stdout: result.stdout.trim(), stderr: result.stderr.trim() };
};
async function treeDigest(dir) {
  const rows = [];
  for (const name of (await readdir(dir)).sort()) rows.push([name, sha(await readFile(path.join(dir, name)))]);
  return sha(JSON.stringify(rows));
}

await mkdir('indexes', { recursive: true });
await mkdir('target', { recursive: true });
const work = await mkdtemp('indexes/compatibility-');
const old = await classpath(true), modern = await classpath(false);
const reports = {};
for (const [name, cp] of [['legacy', old], ['modern', modern]]) {
  const base = `${work}/${name}`;
  for (const type of [1, 2, 3]) run(cp, 'main.Watson', ['index', 'data/demo/corpus', base, String(type)]);
  reports[name] = {
    matrix: JSON.parse(run(cp, 'main.Benchmark', [base, 'data/demo/questions.txt']).stdout),
    guided: JSON.parse(run(cp, 'main.Watson', ['evaluate', base, '1', '4', 'data/demo/easy-questions.txt', '--json']).stdout)
  };
}
const legacyIndex = `${work}/legacy/1_idxTypeNone`, before = await treeDigest(legacyIndex);
const read = run(modern, 'main.Watson', ['ask', `${work}/legacy`, '1', '4', 'gargoyles turrets'], 1);
const rebuild = run(modern, 'main.Watson', ['index', 'data/demo/corpus', `${work}/legacy`, '1', '--rebuild'], 1);
if (before !== await treeDigest(legacyIndex)) throw new Error('Legacy index changed during modern compatibility check');
const legacyRead = run(old, 'main.Watson', ['ask', `${work}/legacy`, '1', '4', 'Henry Sproatt gargoyles turrets']);
const report = {
  schemaVersion: 1, id: 'cluelab-runtime-compatibility-v1', measuredAt: new Date().toISOString(),
  classification: 'identical-small-fixture-runtime-comparison-not-general-accuracy',
  corpusSha256: sha(await readFile('data/demo/corpus/articles.txt')),
  questionsSha256: sha(await readFile('data/demo/questions.txt')),
  guidedSha256: sha(await readFile('data/demo/easy-questions.txt')),
  reports, compatibility: { modernRead: read, modernRebuild: rebuild, legacyIndexUnchanged: true, legacyRead: JSON.parse(legacyRead.stdout) }
};
await writeFile('target/compatibility-results.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ result: 'target/compatibility-results.json', work, legacyGuided: reports.legacy.guided.correctFirst, modernGuided: reports.modern.guided.correctFirst }));
