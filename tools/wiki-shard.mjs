import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export function articles(bytes) {
  const lines = new TextDecoder('utf-8', { fatal: true }).decode(bytes).split('\n'), starts = [];
  lines.forEach((line, index) => {
    const match = line.trim().match(/^\[\[([^\[\]|]+)\]\]$/);
    if (match && match[1].trim() && !match[1].startsWith('File:') && !match[1].startsWith('Image:'))
      starts.push({ title: match[1].trim(), line: index });
  });
  return starts.map((start, i) => {
    const end = starts[i + 1]?.line ?? lines.length, raw = lines.slice(start.line, end).join('\n');
    return { id: 'wiki-b-' + String(i + 1).padStart(4, '0'), title: start.title,
      firstLine: start.line + 1, lastLine: end, rawSha256: sha256(raw), raw };
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL('../', import.meta.url));
  process.chdir(root);
  if (process.argv.length !== 3) throw new Error('Usage: node tools/wiki-shard.mjs /path/to/original/enwiki-20140602-pages-articles.xml-0005.txt');
  const manifest = JSON.parse(await readFile('data/retrieval-lab/v1/collection.json', 'utf8'));
  const bytes = await readFile(process.argv[2]);
  if (sha256(bytes) !== manifest.sourceSha256) throw new Error('Wrong source snapshot: SHA-256 differs from frozen manifest. No corpus was written.');
  const actual = articles(bytes).map(({ raw, ...metadata }) => metadata);
  if (JSON.stringify(actual) !== JSON.stringify(manifest.documents)) throw new Error('Document boundaries/fingerprints differ from frozen manifest.');
  const output = path.resolve('corpus/retrieval-lab-v1');
  await mkdir(output, { recursive: true });
  const target = path.join(output, 'source.txt');
  try {
    if (sha256(await readFile(target)) !== manifest.sourceSha256) throw new Error('Existing generated corpus differs; choose a separate checkout/output.');
  } catch (error) { if (error.code !== 'ENOENT') throw error; else await writeFile(target, bytes, { flag: 'wx' }); }
  console.log(JSON.stringify({ corpus: output, documents: actual.length, sourceSha256: manifest.sourceSha256 }));
}
