// Optional paid smoke test. Input and outputs remain private, under ignored target.
import { chromium } from 'playwright';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
if (process.env.RUN_REAL_FLOW !== 'true' || !process.env.REAL_CAPTURE_FILE)
  throw new Error('Explicitly set RUN_REAL_FLOW=true and REAL_CAPTURE_FILE to your local capture export. This test can call paid APIs.');
const {job} = JSON.parse(await readFile(process.env.REAL_CAPTURE_FILE,'utf8'));
assert(job?.title && job?.description && !job.simulated, 'Provide a real, reviewed capture.');
const out=resolve('../target/real-e2e-evidence'); await mkdir(out,{recursive:true});
const ctx=await chromium.launchPersistentContext(resolve(out,'chrome-'+Date.now()),{channel:'chrome',headless:true,ignoreDefaultArgs:['--disable-extensions'],args:['--enable-unsafe-extension-debugging']});
try {
  const cdp=await ctx.browser().newBrowserCDPSession();
  const {id}=await cdp.send('Extensions.loadUnpacked',{path:resolve('.output/chrome-mv3')});
  const page=await ctx.newPage(); await page.goto(`chrome-extension://${id}/demo.html`);
  const rpc=(path,method='GET',body)=>page.evaluate(async input=>{
    const r=await chrome.runtime.sendMessage({type:'api',base:'http://127.0.0.1:18080/api',...input});
    if(!r.ok) throw new Error(r.code); return r.data;
  },{path,method,body});
  const session=await rpc('/session'), rules=await rpc('/rules/current');
  assert.equal(session.providerMode,'REAL'); assert(session.profile, 'Confirm your own resume first.');
  const snapshot=await rpc('/jobs','POST',job);
  const operation=await rpc('/analyses','POST',{clientRequestId:randomUUID(),jobSnapshotId:snapshot.id,expectedProfileVersion:session.profile.version,expectedRuleVersion:rules.version});
  assert.equal(operation.status,'SUCCESS');assert.equal(operation.stale,false);
  const bound=await rpc(`/analyses/${operation.id}/evidence`);
  const refs=new Set(bound.facts.map(f=>f.factId));
  for(const assessment of operation.execution?.result?.requirementAssessments || [])
    for(const ref of assessment.evidenceRefs) assert(refs.has(ref));
  const questions=['这个岗位适合我吗？请结合简历证据说明优势与缺口。','根据上一轮，哪两项缺口最需要优先补足？','结合前两轮，请给出一周面试准备顺序，并区分已有证据与待确认事项。'];
  const turns=[];
  for(const question of questions){
    const history=await rpc('/chat/history?limit=1');
    const turn=await rpc('/chat/messages','POST',{clientRequestId:randomUUID(),jobSnapshotId:snapshot.id,analysisId:operation.id,question,expectedGeneration:history.generation,comparisonAnalysisIds:[]});
    assert.equal(turn.status,'SUCCEEDED');assert.equal(turn.question.analysisId,operation.id);
    assert.equal(turn.question.jobSnapshotId,snapshot.id);assert.equal(turn.reply.replyToMessageId,turn.question.id);
    for(const ref of turn.reply.evidenceRefs) {assert(ref.startsWith(operation.id+'/'));assert(refs.has(ref.slice(operation.id.length+1)));}
    turns.push(turn);
  }
  await writeFile(resolve(out,'private-results.json'),JSON.stringify({session,rules,operation,bound,turns},null,2));
  console.log('REAL extension background HTTP smoke passed; private evidence saved under ignored target.');
} finally {await ctx.close();}
