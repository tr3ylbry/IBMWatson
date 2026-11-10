'use strict';
const $ = id => document.getElementById(id);
const el = (tag, text, cls) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; };
let csrf = '', catalog = {artifacts: [], jobs: [], targets: []}, polling = false, working = false, planData = null, captureData = null;
let saved = {}; try { saved = JSON.parse(sessionStorage.getItem('cluelab-workshop') || '{}'); } catch { /* A new tab can start cleanly. */ }
const state = {preview: '', selection: '', capture: '', before: 'jovian-original', draft: 'jovian-revised', review: '', plan: '', target: 'isolated', ...saved};
function remember() { state.proposalJson = $('proposal-json').value; state.decisionJson = $('decision-json').value; try { sessionStorage.setItem('cluelab-workshop', JSON.stringify(state)); } catch { error('This tab could not preserve unsaved edits. Export or copy the private JSON before reloading.'); } }
function error(message) { $('error').textContent = message; $('error').hidden = false; }
function clearError() { $('error').hidden = true; }
function busy(value) { working = value; $('main').setAttribute('aria-busy',String(value)); for (const id of ['select-sources','capture','validate','export','compare','prepare-plan','activate','rollback']) $(id).disabled = value; document.querySelector('#search-form button').disabled = value; $('capture').disabled = value || !state.selection; $('generate').disabled=value||!captureData||!catalog.assistance?.configured; confirmations(); }
async function api(path, data) {
  const response = await fetch(path, {method: data === undefined ? 'GET' : 'POST', credentials: 'same-origin', headers: data === undefined ? {} : {'Content-Type': 'application/json', 'X-ClueLab-CSRF': csrf}, body: data === undefined ? undefined : JSON.stringify(data)});
  const body = await response.json(); if (!response.ok) { if (response.status === 401) { $('unlock').hidden = false; $('workshop').hidden = true; } const e = new Error(body.error || 'Request failed. Reconnect before retrying.'); e.status = response.status; throw e; } return body;
}
function details(title, data) { const d = el('details'), s = el('summary', title); s.append(el('span', '', 'chevron')); d.append(s, el('pre', typeof data === 'string' ? data : JSON.stringify(data, null, 2))); return d; }
function button(text, fn) { const b = el('button', text); b.type = 'button'; b.addEventListener('click', () => safe(fn)); return b; }
function link(text, url) { const a = el('a', text); if (/^https:\/\//.test(url)) { a.href = url; a.target = '_blank'; a.rel = 'noreferrer'; } return a; }
async function safe(fn) { try { clearError(); await fn(); } catch (e) { error(e.message); } }
function chooser(container, label, key, items, change) {
  const host = $(container), d = el('details'), summary = el('summary'), field = el('fieldset'), choices = el('div', null, 'choices');
  const current = items.find(a => a.id === state[key]); summary.append(el('span', label + ': ' + (current?.label || 'Choose Saved Work')), el('span', '', 'chevron'));
  field.append(el('legend', label));
  for (const item of items) { const l = el('label', null, 'choice'), input = document.createElement('input'); input.type = 'radio'; input.name = container; input.value = item.id; input.checked = state[key] === item.id;
    input.addEventListener('change', () => safe(async () => { state[key] = item.id; remember(); d.open = false; summary.focus(); summary.firstChild.textContent = label + ': ' + item.label; if (change) await change(item.id); })); l.append(input, el('span', item.label)); choices.append(l); }
  if (!items.length) choices.append(el('p', 'No completed work of this kind yet.', 'small')); field.append(choices); d.append(summary, field); host.replaceChildren(d);
}
function pickers() {
  const by = kind => catalog.artifacts.filter(a => a.kind === kind);
  chooser('capture-choice', 'Captured Source Set', 'capture', by('capture'), loadCapture);
  chooser('before-choice', 'Previous Draft', 'before', by('export'));
  chooser('draft-choice', 'Revised Draft', 'draft', by('export'));
  chooser('review-choice', 'Comparison / Review', 'review', by('review'));
  chooser('plan-choice', 'Saved Activation Plan', 'plan', by('plan'), loadPlan);
  chooser('target-choice', 'Activation Target', 'target', catalog.targets.map(t => ({...t, label: t.label + ' / Catalog ' + t.catalogVersion})), () => { $('confirm').checked = false; planData = null; $('plan-details').replaceChildren(); confirmations(); });
}
async function refresh() {
  const session = await api('/api/session'); csrf = session.csrf; catalog = session.catalog; $('unlock').hidden = true; $('workshop').hidden = false;
  renderAssistanceSettings();
  pickers(); const artifacts = $('artifacts'), jobs = $('jobs'); artifacts.replaceChildren(); jobs.replaceChildren();
  for (const a of catalog.artifacts) artifacts.append(button(a.label, () => open(a.id)));
  for (const j of [...catalog.jobs].reverse()) jobs.append(button(j.operation + ' / ' + j.state + ' / ' + j.id.slice(0,8), async () => showJob(await api('/api/jobs/' + j.id))));
  const running = catalog.jobs.find(j => ['queued','running','cancelling'].includes(j.state));
  if (running && !polling) watch(running.id);
  if (!running && !polling) busy(false);
  if (state.pending && !running) { const job = catalog.jobs.find(j => j.id === state.pending.requestId); if (job) { await showJob(await api('/api/jobs/' + job.id)); state.pending = null; remember(); } else { $('job-status').textContent = 'A request response was lost. Reconnect retries the same saved request ID, never a new mutation.'; } }
  await refreshInbox();
}
async function run(operation, data) {
  if (working) return;
  if (state.pending) throw new Error('Recover the pending request with Reconnect before starting another job.');
  const request = {requestId: crypto.randomUUID(), operation, ...data};
  if(state.playerRequestId){request.playerRequestId=state.playerRequestId;request.playerTargetId=state.playerTargetId;}
  state.pending = request; remember(); busy(true);
  try { const job = await api('/api/jobs', request); await watch(job.id); } catch (e) { if (e.status) { state.pending = null; remember(); } error(e.message + ' Use Reconnect to inspect server state.'); busy(false); }
}
async function watch(id) {
  if (polling) return; polling = true; busy(true);
  try { for (;;) { const job = await api('/api/jobs/' + id); $('job-status').textContent = job.operation + ': ' + job.state + '. ' + (job.result.message || ''); $('cancel').hidden = !job.cancellable; $('cancel').dataset.job = id;
      const p = job.result.progress, measured = p && Number.isFinite(p.total) && Number.isFinite(p.completed); $('job-progress').hidden = !measured; if (measured) { $('job-progress').max = p.total; $('job-progress').value = p.completed; }
      if (!['queued','running','cancelling'].includes(job.state)) { state.pending = null; remember(); await showJob(job); break; } await new Promise(r => setTimeout(r, 450)); }
  } catch (e) { error(e.message + ' Reconnect recovers server-owned work.'); }
  finally { polling = false; busy(false); $('cancel').hidden = true; $('job-progress').hidden = true; await safe(refresh); }
}
async function showJob(job) {
  $('job-status').textContent = job.operation + ': ' + job.state + '. ' + (job.result.message || 'Saved result available.');
  if (['failed','cancelled','interrupted','uncertain'].includes(job.state)) { error(job.result.message || 'Inspect saved work before starting a new job.'); if(job.operation==='generate'){$('assistance-panel').open=true;const box=$('assistance-results');box.replaceChildren(details('Saved Attempt Outcome (Not a Bill)',job.result));if(job.result.retryEligible)box.append(button('Prepare Explicit Retry',async()=>{state.assistanceInput=job.result.retryInput;state.capture=job.result.retryInput.captureId;await loadCapture(state.capture);restoreAssistanceInput();$('assistance-confirm').checked=false;$('assistance-retry').value=job.id;$('assistance-retry-confirm').checked=false;$('assistance-panel').open=true;$('assistance-retry').closest('details').open=true;remember();pickers();$('assistance-retry').focus();}));else box.append(el('p','No further retry is offered for this outcome. Inspect whether preflight failed or the retry chain is exhausted; no new call is made.','small'));} return; }
  if (job.result.artifactId) { const id = job.result.artifactId; if (job.operation === 'select') { state.selection = id; $('capture').disabled = false; } else await open(id); }
  if (job.result.receipt) { const box = $('receipt'); box.replaceChildren(el('p', 'Historical Receipt: ' + job.result.receipt.action + ' / ' + job.result.receipt.outcome, 'success'), details('Immutable Receipt', job.result.receipt)); await loadPlan(state.plan); }
  remember();
}
async function open(id) {
  const value = await api('/api/artifacts/' + id);
  switch (value.kind) {
    case 'preview': state.preview = id; renderPreview(value.data); break;
    case 'capture': state.capture = id; renderCapture(value.data); break;
    case 'generate': state.generation=id;renderGenerated(id,value);break;
    case 'proposals': $('proposal-json').value = JSON.stringify(value.data, null, 2); break;
    case 'decision': $('decision-json').value = JSON.stringify(value.data, null, 2); break;
    case 'validate': case 'export':
      if (value.kind === 'export') state.draft = id;
      $('validation').replaceChildren(el('p', value.kind === 'export' ? 'Immutable Draft Exported. Still Draft: Zero Eligible Clues.' : 'Structural Validation Passed. Not Factual Approval or Activation.', 'success'), details('Counts and Review Status', value.validation), details('Flags Requiring Review', value.screening));
      $('proposal-json').value = JSON.stringify(value.data, null, 2); break;
    case 'review': state.review = id; renderComparison(value.data); break;
    case 'plan': state.plan = id; renderPlan(value); break;
    default: throw new Error('Unsupported saved artifact');
  }
  remember(); pickers();
}
function renderPreview(data) {
  const box = $('source-results'); box.replaceChildren(el('h3', data.topic));
  for (const source of data.candidates) { const label = el('label', null, 'choice'), input = document.createElement('input'); input.type = 'checkbox'; input.name = 'page'; input.value = source.pageId; label.append(input, el('span', source.title)); box.append(label, link('Inspect ' + source.title, source.url)); }
  $('rights').checked = false; $('selection-controls').hidden = false;
}
async function loadCapture(id) { const v = await api('/api/artifacts/' + id); renderCapture(v.data); }
function renderCapture(data) {
  captureData = data; const box = $('source-context'); box.replaceChildren(el('h3', data.sources.length + ' Captured Sources'), el('p', 'Exact saved leads, not full articles or guaranteed old-revision renders. Unknown revisions stay unknown.', 'small'));
  for (const source of data.sources) { const d = el('details'), summary = el('summary', source.title); summary.append(el('span', '', 'chevron')); d.append(summary, link('Source and Attribution Context', source.canonicalUrl), el('p', 'Observed Revision: ' + (source.revisionId ?? 'Unknown') + ' / Captured: ' + source.capturedAt, 'small'), el('blockquote', source.text), details('Saved Source Metadata', {...source, text: undefined})); box.append(d); }
  if (data.failures.length) box.prepend(el('p', 'Partial Capture: ' + data.failures.length + ' Failed Source(s). No replacements were silently selected.', 'error'), details('Per-Source Failures', data.failures));
  const bytes=data.sources.reduce((n,s)=>n+new TextEncoder().encode(s.text).length,0);$('assistance-scope').textContent='Selected Capture: '+state.capture+' / '+data.topic+' / '+data.sources.length+' Sources / '+bytes+' UTF-8 Bytes. All selected source text is sent; no keys, reviews, player guesses or benchmark material.';
  $('generate').disabled=working||!catalog.assistance?.configured;
}
function renderAssistanceSettings(){const a=catalog.assistance;if(!a)return;$('assistance-status').textContent=a.synthetic?'TEST FIXTURE ONLY. No live model or paid call. '+a.message:a.message;$('assistance-limits').replaceChildren(details('Execution Limits and Configured Token-Price Estimates',a));$('generate').disabled=working||!captureData||!a.configured;}
function renderGenerated(id,value){
  const box=$('assistance-results'),selected=new Set();box.replaceChildren(el('h3','Private Proposed Clues'),el('p','Select proposals to edit. Nothing here is approved or playable. Exact support and model output still need independent source/editorial review.','notice'),details('Attempt, Usage and Screening (Not an Exact Bill)',value.report));
  const findings=value.report.validation.clues;
  for(const clue of value.data.clues){const card=el('article',null,'source'),label=el('label',null,'choice'),check=document.createElement('input');check.type='checkbox';check.addEventListener('change',()=>{if(check.checked)selected.add(clue.id);else selected.delete(clue.id);});label.append(check,el('span','Use '+clue.id));card.append(label);
    const grid=el('div',null,'review-grid'),question=el('div'),context=el('div'),source=value.capture.sources.find(s=>s.pageId===clue.support.pageId);question.append(el('h3','Proposed Clue'),el('p',clue.text),el('p','Answer: '+clue.aliases.join(' / ')),el('p','Hint: '+(clue.hint||'None')),details('Unresolved Screening Flags',findings.find(f=>f.clueId===clue.id)||{}));context.append(el('h3','Exact Supporting Context'),el('blockquote',clue.support.text),el('p',source.title+' / UTF-16 Start '+clue.support.start,'small'),details('Full Saved Source',source.text));grid.append(question,context);card.append(grid);box.append(card);
  }
  box.append(button('Use Selected Proposals in Editor',()=>{const clues=value.data.clues.filter(c=>selected.has(c.id));if(!clues.length)throw Error('Select at least one proposal; no unsupported filler is added.');state.generation=id;state.capture=value.captureId;$('proposal-json').value=JSON.stringify({...value.data,clues},null,2);renderCapture(value.capture);remember();pickers();editProposals();$('proposal-editor').tabIndex=-1;$('proposal-editor').focus();}));
  $('assistance-panel').open=true;
}
function inputField(host, label, value, change, type = 'text', multiline = false) {
  const id = 'field-' + crypto.randomUUID(), l = el('label', label), i = document.createElement(multiline ? 'textarea' : 'input'); l.htmlFor = id; i.id = id; if (!multiline) i.type = type; i.value = value ?? ''; i.addEventListener('input', () => change(i.value)); host.append(l, i); return i;
}
function radios(host, label, values, value, change) {
  const field = el('fieldset'); field.append(el('legend', label)); const name = crypto.randomUUID();
  for (const row of values) { const l = el('label', null, 'choice'), i = document.createElement('input'); i.type = 'radio'; i.name = name; i.checked = row.id === value; i.value = row.id; i.addEventListener('change', () => change(row.id)); l.append(i, el('span', row.label)); field.append(l); } host.append(field);
}
function newProposals() {
  if (!captureData) throw new Error('Choose a captured source set first.');
  delete state.generation;$('proposal-json').value = JSON.stringify({schemaVersion:1,id:'',version:'1.0.0',topicId:'',topic:captureData.topic,label:captureData.topic,description:'',subtopics:[],authoring:{method:'operator-supplied',author:'',createdOn:'',notes:'',humanReviewer:null,humanReviewedOn:null},clues:[]}, null, 2); editProposals();
}
function editProposals() {
  if (!captureData) throw new Error('Choose a captured source set first.');
  const model = JSON.parse($('proposal-json').value), box = $('proposal-editor'); box.replaceChildren();
  const commit = () => { $('proposal-json').value = JSON.stringify(model, null, 2); remember(); };
  for (const [key,label] of [['id','Stable Pack ID'],['version','Draft Version'],['topicId','Stable Topic ID'],['label','Topic Label'],['description','Description']]) inputField(box,label,model[key],v=>{model[key]=v;commit();});
  inputField(box,'Subtopics (One id | Label Per Line)',model.subtopics.map(s=>s.id+' | '+s.label).join('\n'),v=>{model.subtopics=v.split('\n').filter(Boolean).map(line=>{const [id,...label]=line.split('|');return {id:id.trim(),label:label.join('|').trim()};});commit();},'text',true);
  for (const [key,label,type] of [['author','Actual Author','text'],['createdOn','Actual Authoring Date','date'],['notes','Authoring Notes','text']]) inputField(box,label,model.authoring[key],v=>{model.authoring[key]=v;commit();},type);
  for (const [index,clue] of model.clues.entries()) {
    const d = el('details'), s = el('summary', 'Clue ' + (index+1) + ' / ' + (clue.id || 'New')); s.append(el('span','','chevron')); d.append(s);
    for (const [key,label] of [['id','Clue ID'],['factId','Distinct Fact ID'],['subtopicId','Subtopic ID'],['category','Category'],['text','Clue Wording'],['hint','Optional Hint'],['reviewNotes','Factual / Editorial Notes']]) inputField(d,label,clue[key],v=>{if(key==='hint'&&!v)delete clue.hint;else clue[key]=v;commit();},'text',['text','reviewNotes'].includes(key));
    radios(d,'Provisional Difficulty',['easy','standard','hard'].map(id=>({id,label:id[0].toUpperCase()+id.slice(1)})),clue.difficulty,v=>{clue.difficulty=v;commit();});
    const pages=captureData.sources.map(s=>({id:String(s.pageId),label:s.title}));
    radios(d,'Canonical Answer Article',pages,String(clue.answerPageId),v=>{clue.answerPageId=Number(v);commit();});
    inputField(d,'Accepted Aliases (One Per Line; Include Canonical Title)',clue.aliases.join('\n'),v=>{clue.aliases=v.split('\n');commit();},'text',true);
    radios(d,'Supporting Source',pages,String(clue.support.pageId),v=>{clue.support.pageId=Number(v);commit();});
    inputField(d,'Exact Supporting Passage',clue.support.text,v=>{clue.support.text=v;commit();},'text',true);
    inputField(d,'Optional UTF-16 Start (Required for Repeated Passages)',clue.support.start,v=>{if(v==='')delete clue.support.start;else clue.support.start=Number(v);commit();},'number');
    d.append(button('Remove This Proposal',()=>{model.clues.splice(index,1);commit();editProposals();})); box.append(d);
  }
  box.append(button('Add a Clue',()=>{model.clues.push({id:'',factId:'',subtopicId:'',category:'',text:'',difficulty:'standard',answerType:'article-title',answerPageId:0,aliases:[],support:{pageId:0,text:''},reviewNotes:''});commit();editProposals();}));
  remember();
}
async function newReview() {
  const draft = await api('/api/artifacts/' + state.draft); if (!draft.screening) throw new Error('Choose an exported draft first.');
  const rows=draft.screening.clues.map(c=>({clueId:c.clueId,checks:Object.fromEntries(['factualSupport','wording','aliases','hint','distinctFact','attribution','difficulty'].map(k=>[k,''])),flagDecisions:c.flags.map(flag=>({flag,disposition:'unresolved',rationale:''})),blockers:[],advisories:[]}));
  $('decision-json').value=JSON.stringify({schemaVersion:1,id:'',kind:'operator-source-editorial',status:'source-checked',reviewer:'',reviewedOn:'',draftManifestSha256:draft.manifestSha256,candidateVersion:'',limitations:'',humanReviewer:null,humanReviewedOn:null,clues:rows},null,2); editReview();
}
function editReview() {
  const model=JSON.parse($('decision-json').value),box=$('review-editor');box.replaceChildren(el('p','Recorded Review Kind: '+model.kind+'. No human approval is created.','notice'));
  const commit=()=>{$('decision-json').value=JSON.stringify(model,null,2);remember();};
  for(const [key,label,type] of [['id','Decision ID','text'],['reviewer','Actual Reviewer / Role','text'],['reviewedOn','Actual Review Date','date'],['candidateVersion','New Reviewed Candidate Version','text'],['limitations','Remaining Limitations','text']])inputField(box,label,model[key],v=>{model[key]=v;commit();},type,key==='limitations');
  for(const clue of model.clues){const d=el('details'),s=el('summary',clue.clueId);s.append(el('span','','chevron'));d.append(s);
    const labels={factualSupport:'Factual Support',wording:'Wording',aliases:'Accepted Aliases',hint:'Hint',distinctFact:'Distinct Fact',attribution:'Attribution',difficulty:'Provisional Difficulty'};
    for(const key of Object.keys(clue.checks))inputField(d,'Review: '+(labels[key]||key),clue.checks[key],v=>{clue.checks[key]=v;commit();},'text',true);
    for(const flag of clue.flagDecisions){d.append(el('p',flag.flag,'notice'));radios(d,'Flag Disposition',['unresolved','resolved','advisory'].map(id=>({id,label:id[0].toUpperCase()+id.slice(1)})),flag.disposition,v=>{flag.disposition=v;commit();});inputField(d,'Evidence and Decision Rationale',flag.rationale,v=>{flag.rationale=v;commit();},'text',true);}
    for(const key of ['blockers','advisories'])inputField(d,key==='blockers'?'Unresolved Blockers (One Per Line)':'Advisories (One Per Line)',clue[key].join('\n'),v=>{clue[key]=v.split('\n').filter(Boolean);commit();},'text',true);box.append(d);}
  remember();
}
function renderComparison(data){const box=$('comparison');box.replaceChildren(el('p','Draft '+data.beforeVersion+' to '+data.draftVersion+' / Reviewed Candidate: '+data.candidateVersion,'success'),details('Availability and Blockers',{proposed:data.proposedCounts,reviewed:data.reviewedCandidateCounts,blockers:data.blockingFailures,advisories:data.advisoryLimitations}));
  for(const clue of data.clues){const a=el('article');a.append(el('h3',clue.clueId));const grid=el('div',null,'review-grid');for(const [label,key]of[['Before','before'],['After','after']]){const part=el('div'),v=clue[key];part.append(el('h3',label));if(v){part.append(el('p',v.wording),el('p','Hint: '+(v.hint||'None'),'small'),el('p','Aliases: '+v.aliases.join(' | '),'small'),details('Exact Support and Source References',v.sourceEvidence),el('p','UTF-16 Support: '+v.supportStart+'..'+v.supportEnd,'small'));}grid.append(part);}a.append(grid);box.append(a);}box.append(details('Recorded Review Decisions',data.reviewDecision));}
async function loadPlan(id){const value=await api('/api/artifacts/'+id);renderPlan(value);}
function renderPlan(value){planData=value;state.target=value.data.targetId;$('confirm').checked=false;const d=value.data,box=$('plan-details');box.replaceChildren(el('h3','Target: '+(d.targetId==='normal'?'Normal Local Game':'Isolated Workshop')),el('p','Actual Catalog: '+value.current.catalogVersion+' / '+value.current.state,'notice'),el('p','Proposed Catalog: '+d.catalogVersion+' / Affected Topic: '+d.topicId),el('p','Current SHA-256: '+value.current.catalogSha256,'small'),el('p','Exact Plan: '+d.planId,'small'),details('Eligible Topics, Pack Versions and Source Scope',d.availability),details('Required Indexes and Provenance',d.requiredIndexes),el('p',d.limitations,'notice'));confirmations();}
function confirmations(){const ready=!working&&planData&&$('confirm').checked;$('activate').disabled=!ready||planData.current.state!=='previous-active';$('rollback').disabled=!ready||planData.current.state!=='candidate-active';}
for(const [file,area]of[['proposal-file','proposal-json'],['decision-file','decision-json']])$(file).addEventListener('change',()=>safe(async()=>{const f=$(file).files[0];if(!f)return;if(f.size>1024*1024)throw new Error('JSON import exceeds 1 MiB.');let text;try{text=new TextDecoder('utf-8',{fatal:true}).decode(await f.arrayBuffer());}catch{throw new Error('JSON import must be valid UTF-8; existing edits were not replaced.');}$(area).value=text;remember();}));
for(const id of ['proposal-json','decision-json'])$(id).addEventListener('input',remember);
function restoreAssistanceInput(){if(!state.assistanceInput)return;for(const [key,id]of Object.entries(assistanceFields))if(state.assistanceInput[key]!==undefined)$(id).value=state.assistanceInput[key];for(const radio of document.querySelectorAll('input[name=assistance-level]'))radio.checked=radio.value===state.assistanceInput.difficulty;}
async function restore(){await refresh();if(state.capture)await loadCapture(state.capture);if(state.generation&&!state.proposalJson)await open(state.generation);if(state.plan)await loadPlan(state.plan);restoreAssistanceInput();}
$('login').addEventListener('submit',e=>{e.preventDefault();safe(async()=>{const r=await api('/api/login',{code:$('access-code').value});csrf=r.csrf;$('access-code').value='';await restore();$('main').tabIndex=-1;$('main').focus();});});
$('search-form').addEventListener('submit',e=>{e.preventDefault();safe(()=>run('preview',{topic:$('topic').value.trim(),limit:Number($('limit').value)}));});
$('select-sources').addEventListener('click',()=>safe(()=>run('select',{artifactId:state.preview,pageIds:[...document.querySelectorAll('input[name=page]:checked')].map(i=>Number(i.value)),acknowledgeRights:$('rights').checked})));
$('capture').addEventListener('click',()=>safe(()=>run('capture',{artifactId:state.selection})));
$('cancel').addEventListener('click',()=>safe(()=>api('/api/jobs/'+$('cancel').dataset.job+'/cancel',{})));
$('new-proposals').addEventListener('click',()=>safe(newProposals));$('edit-proposals').addEventListener('click',()=>safe(editProposals));
for(const operation of ['validate','export'])$(operation).addEventListener('click',()=>safe(()=>{const model=JSON.parse($('proposal-json').value),generationId=model.authoring?.assistance?.attemptId;return run(operation,{captureId:state.capture,proposalJson:$('proposal-json').value,...(generationId?{generationId}:{})});}));
$('new-review').addEventListener('click',()=>safe(newReview));$('edit-review').addEventListener('click',()=>safe(editReview));
$('compare').addEventListener('click',()=>safe(()=>run('review',{beforeId:state.before,draftId:state.draft,decisionJson:$('decision-json').value})));
$('prepare-plan').addEventListener('click',()=>safe(()=>run('plan',{reviewId:state.review,targetId:state.target,catalogVersion:$('catalog-version').value.trim()})));
$('confirm').addEventListener('change',confirmations);
for(const operation of ['activate','rollback'])$(operation).addEventListener('click',()=>safe(()=>run(operation,{planId:state.plan,confirmPlanId:planData.data.planId,confirmed:$('confirm').checked})));
$('reconnect').addEventListener('click',()=>safe(async()=>{await refresh();if(state.pending&&!catalog.jobs.some(j=>j.id===state.pending.requestId)){busy(true);const j=await api('/api/jobs',state.pending);await watch(j.id);}else if(state.capture)await loadCapture(state.capture);if(state.plan)await loadPlan(state.plan);}));
$('proposal-json').value=state.proposalJson||'';$('decision-json').value=state.decisionJson||'';
const assistanceFields={draftId:'assistance-id',topicId:'assistance-topic',version:'assistance-version',label:'assistance-label',description:'assistance-description',count:'assistance-count'};
$('assistance-form').addEventListener('submit',event=>{event.preventDefault();safe(()=>{if(!catalog.assistance?.configured)throw Error('Live Assistance Unconfigured. Manual authoring/import is available.');const data=Object.fromEntries(Object.entries(assistanceFields).map(([key,id])=>[key,$(id).value.trim()]));data.count=Number(data.count);data.captureId=state.capture;data.difficulty=document.querySelector('input[name=assistance-level]:checked').value;data.confirmed=$('assistance-confirm').checked;const retry=$('assistance-retry').value.trim();if(retry){data.retryOf=retry;data.acknowledgeRetry=$('assistance-retry-confirm').checked;}state.assistanceInput=data;remember();return run('generate',data);});});
safe(restore);

let inboxWorking=false;
const requestLabel=value=>value.split('-').map(word=>word.charAt(0).toUpperCase()+word.slice(1)).join(' ');
async function refreshInbox(){
  const data=await api('/api/player-requests'),box=$('player-inbox');box.replaceChildren();
  if(!data.enabled){box.append(el('p','This workshop has no player-request store.','small'));return;}
  for(const request of data.requests){const card=el('article',null,'source');card.dataset.requestId=request.id;card.append(el('h3',request.topic+' / '+requestLabel(request.difficulty)),el('p',requestLabel(request.state)+' / Player Target: '+request.targetId),el('p',request.readiness.message));
    card.append(button('Use This Request and Target',()=>{if(!request.mounted)throw Error('This target is not mounted. No substitute is selected.');state.playerRequestId=request.id;state.playerTargetId=request.targetId;state.target=request.targetId;$('topic').value=request.topic;remember();pickers();handoffStatus(request);}));
    const message=document.createElement('input'),label=el('label','Message for Player (No Private Review Notes or Paths)');message.id='message-'+request.id;message.maxLength=300;const pending=Object.entries(state).find(([key])=>key.startsWith('transition-'+request.id+'-'));if(pending)message.value=pending[1].message;label.htmlFor=message.id;card.append(label,message);
    for(const [action,title]of[['awaiting-review','Mark Awaiting Review'],['clarification','Ask for Clarification'],['unavailable','Mark Unavailable'],['failed','Mark Failed']])card.append(button(title,()=>operatorTransition(request,action,message.value)));
    card.append(el('p','Request '+request.id,'small'));box.append(card);
  }
  if(!data.requests.length)box.append(el('p','No retained player requests.','small'));
  if(state.playerRequestId){const row=data.requests.find(r=>r.id===state.playerRequestId);if(row)handoffStatus(row);else $('handoff-status').textContent='Linked request expired or unavailable. Detach it before continuing.';}
}
function handoffStatus(request){$('handoff-status').textContent='Linked Request: '+request.topic+' / '+requestLabel(request.difficulty)+' / Exact Target: '+request.targetId+' / '+requestLabel(request.state)+'. No automatic activation or restart.';}
async function operatorTransition(request,action,message){
  if(inboxWorking)return;
  const key='transition-'+request.id+'-'+action;let pending=state[key];if(!pending){pending={requestId:request.id,targetId:request.targetId,retryId:crypto.randomUUID(),action,message};state[key]=pending;remember();}
  if(pending.message!==message)throw Error('A transition response is pending. Retry its original message before changing it.');
  inboxWorking=true;
  try{await api('/api/player-requests/transition',pending);delete state[key];remember();await refreshInbox();}catch(e){if(e.status){delete state[key];remember();}throw e;}finally{inboxWorking=false;}
}
$('refresh-inbox').addEventListener('click',()=>safe(refreshInbox));
$('detach-request').addEventListener('click',()=>{delete state.playerRequestId;delete state.playerTargetId;remember();$('handoff-status').textContent='No player request linked.';});
