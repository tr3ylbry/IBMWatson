import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { records, project, normalize } from './prepare-external-challenge.mjs';

const example={QuestionId:'external-1',Question:'Who wrote this?',QuestionSource:'https://example.org',
  Answer:{Type:'WikipediaEntity',MatchedWikiEntityName:'Example Title',Value:'Example',Aliases:['Example','EXAMPLE TITLE','E. Title']}};
test('stream parser preserves text across every boundary and ignores braces in quoted strings',async()=>{
  const input=JSON.stringify({Data:[{...example,Question:'A "quoted" {title} \\ here?'},example],Version:'1.0'});
  for(const size of [1,2,7,64,65536]) {
    const chunks=[];for(let i=0;i<input.length;i+=size)chunks.push(input.slice(i,i+size));
    const actual=[];for await(const row of records(Readable.from(chunks)))actual.push(row);
    assert.deepEqual(actual,JSON.parse(input).Data);
  }
});
test('stream parser rejects truncated, missing and oversized data',async()=>{
  for(const input of ['{}','{"Data":[{"x":1}','{"Data":[42]}'])
    await assert.rejects(async()=>{for await(const _ of records(Readable.from([input]))){};});
  await assert.rejects(async()=>{for await(const _ of records(Readable.from(['{"Data":[{"x":"'+'a'.repeat(9*1024*1024)]))){};},/exceeds 8 MiB/);
});
test('external question is verbatim and aliases are only upstream labels',()=>{
  const result=project(example,'pictured'); assert.equal(result.clue.question,example.Question);
  assert.equal(result.clue.authorship,'external-verbatim');assert.equal(result.clue.curatedSupport,null);
  assert.deepEqual(new Set(result.clue.aliases.map(normalize)),new Set(['example title','example','e. title']));
  assert.equal(result.clue.questionSource,example.QuestionSource);
});
test('unsupported answers, invalid aliases and media are excluded before ranking',()=>{
  assert.equal(project({...example,Answer:{...example.Answer,Type:'Numeric'}},'pictured').reason,'unavailable-answer-type');
  assert.equal(project({...example,Question:'Who is pictured?'},'pictured').reason,'external-media-required');
  assert.equal(project({...example,Answer:{...example.Answer,Aliases:['A|B']}},'pictured').reason,'invalid-upstream-alias');
  assert.equal(project({...example,Answer:{...example.Answer,Aliases:['bad\nlabel']}},'pictured').reason,'invalid-upstream-alias');
});
test('normalization matches existing Java policy, not TriviaQA fuzzy or article removal',()=>{
  assert.equal(normalize('  The\t\n Moon  '),'the moon');
  assert.notEqual(normalize('Bodymassindex'),normalize('Body mass index'));
  assert.notEqual(normalize('The Moon'),normalize('Moon'));
  assert.notEqual(normalize('\u00a0Moon\u00a0'),normalize('Moon'));
  assert.equal(normalize('İ'),'i\u0307');
});
