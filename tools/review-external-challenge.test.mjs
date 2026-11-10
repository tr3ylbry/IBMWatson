import test from 'node:test';
import assert from 'node:assert/strict';
import { isRedirect, selections } from './review-external-challenge.mjs';
test('redirect detection is case insensitive and not a mention in prose',()=>{
  assert.equal(isRedirect('[[Books]]\n\n#REDIRECT Book\n'),true);
  assert.equal(isRedirect('Prose about a #redirect example.'),false);
});
test('review selection follows frozen outcome order rather than selecting convenient failures',()=>{
  const rows=[{id:'first',coverage:'canonical-title',acceptedRank:0,question:'Pony example',candidates:[{title:'Pony Album'}]},
    {id:'missing',coverage:'missing',acceptedRank:0,question:'Elsewhere?',candidates:[]},
    {id:'alias',coverage:'alias-only',acceptedRank:1,question:'A title',candidates:[{title:'Answer'}]}];
  assert.deepEqual(selections(rows,new Set(['first'])),{canonicalTitleFailures:['first'],missingTitle:['missing'],aliasOnly:'alias',redirect:'first',sharedTerm:'first'});
});
