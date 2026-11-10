import { chromium } from "playwright";
import { createServer } from "node:http";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const evidence = resolve("../target/capture-fix-evidence");
await mkdir(evidence, { recursive: true });
const detail = id => ({ jobInfo: { encryptId: id, jobName: `Java岗位${id}`, locationName: id === "A" ? "北京" : "上海",
  address: id === "A" ? "北京市示例区演示路" : "上海市浦东新区测试路", degreeName: "本科", experienceName: "3-5年",
  showSkills: ["Java", "MySQL", "Spring"], salaryDesc: "14-15K", postDescription: `岗位${id}完整JD\n岗位职责\nJava系统开发与优化\n任职要求\n熟悉示例业务` },
  brandComInfo: { brandName: "模拟公司" } });
const server = createServer((req, res) => {
  res.setHeader("Content-Type", "text/html; charset=utf-8");
  res.end(`<!doctype html><html lang="zh"><body><h2>明确标识 · 参考字段结构回归页面</h2>
    <div class="job-card-wrapper active" data-job-id="A"><a href="/job_detail/A.html" onclick="return false">Java岗位A</a></div>
    <div class="job-card-wrapper" data-job-id="B"><a href="/job_detail/B.html" onclick="return false">Java岗位B</a></div>
    <div id="wrap"><div class="page-job-wrapper"></div></div>
    <div class="job-detail-box" data-job-id="A"><h1 class="job-name">Java岗位A</h1><span class="job-area">北京</span>
    <span class="salary">14-15K</span><div class="company-name">模拟公司</div><span class="job-degree">本科</span>
    <span class="job-experience">3-5年</span><div class="job-location"><span class="location-address">北京市示例区演示路</span></div>
    <div class="job-label-list"><span>Java</span><span>MySQL</span></div><div class="job-sec-text">DOM完整JD</div></div>
    <script>window.details=${JSON.stringify({ A: detail("A"), B: detail("B") })};
    window.vm={jobDetail:window.details.A};document.querySelector('.page-job-wrapper').__vue__=window.vm;
    window.select=id=>{document.querySelectorAll('.job-card-wrapper').forEach(n=>n.classList.toggle('active',n.dataset.jobId===id));};</script>
    </body></html>`);
});
await new Promise(r => server.listen(18086, "127.0.0.1", r));
const ctx = await chromium.launchPersistentContext(resolve(evidence, "chrome-" + Date.now()), {
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe", headless: true,
  ignoreDefaultArgs: ["--disable-extensions"], args: ["--enable-unsafe-extension-debugging"], viewport: { width: 1480, height: 1100 }
});
const results = { simulatedPage: true, paidModelCalls: 0, cases: [] };
const record = name => results.cases.push({ name, passed: true });
try {
  const cdp = await ctx.browser().newBrowserCDPSession();
  await cdp.send("Extensions.loadUnpacked", { path: resolve(".output/chrome-mv3") });
  const page = await ctx.newPage();
  await page.goto("http://127.0.0.1:18086/jobs");
  await page.getByRole("button", { name: "AI 求职", exact: true }).click();
  await page.getByRole("button", { name: "岗位", exact: true }).click();
  await page.getByRole("button", { name: "读取当前页面", exact: true }).click();
  await page.getByRole("status").filter({ hasText: "只读采集完成" }).waitFor();
  for (const [field, value] of Object.entries({ "岗位名称": "Java岗位A", "公司": "模拟公司", "城市": "北京", "详细地点": "北京市示例区演示路", "学历": "本科", "经验": "3-5年", "薪资原文": "14-15K", "原始 JD": detail("A").jobInfo.postDescription }))
    assert.equal(await page.getByLabel(field, { exact: true }).inputValue(), value);
  await page.getByText("技能 Java、MySQL、Spring", { exact: false }).waitFor();
  await page.screenshot({ path: resolve(evidence, "capture-preview.png"), fullPage: true });
  record("all reference fields survive MAIN bridge, whitelist and Vue panel");
  async function bridge() {
    return page.evaluate(() => new Promise(resolve => {
      const nonce=crypto.randomUUID();
      const listener=e=>{if(e.data?.type==='AI_JOB_READ_RESULT' && e.data.nonce===nonce){window.removeEventListener('message',listener);resolve(e.data);}};
      window.addEventListener('message',listener);
      window.postMessage({type:'AI_JOB_READ_REQUEST',nonce},location.origin);
    }));
  }
  await page.evaluate(() => { window.select('B'); setTimeout(()=>window.vm.jobDetail=window.details.B,600); });
  await page.waitForTimeout(160);
  let value = await bridge();
  assert.equal(value.job.sourceJobId, "B"); assert.equal(value.job.city, "上海");
  record("same URL B selection waits for asynchronous B detail, rejects A");
  await page.evaluate(() => {window.vm.jobDetail=window.details.A;setTimeout(()=>window.vm.jobDetail=window.details.B,500);});
  value=await bridge(); assert.equal(value.job.sourceJobId,"B");
  record("late A detail cannot be captured as B");
  await page.evaluate(() => {window.vm.jobDetail=null;setTimeout(()=>{window.select('A');window.vm.jobDetail=window.details.A;},200);});
  value=await bridge(); assert.equal(value.changed,true); assert.equal(value.job,null);
  record("selection changes invalidate in-flight bridge request");
  await page.waitForTimeout(250);
  await page.evaluate(() => {document.querySelector('.page-job-wrapper').__vue__=undefined;setTimeout(()=>document.querySelector('.page-job-wrapper').__vue__=window.vm,400);});
  value=await bridge(); assert.equal(value.job.sourceJobId,"A");
  record("bounded wait includes delayed Vue mounting");
  await page.evaluate(() => {document.querySelector('.page-job-wrapper').__vue__=undefined;});
  await page.getByRole("button", { name: "读取当前页面", exact: true }).click();
  await page.waitForFunction(()=>document.querySelector('ai-job-panel')?.shadowRoot?.textContent?.includes('页面文本'),null,{timeout:8000});
  assert.equal(await page.getByLabel("详细地点",{exact:true}).inputValue(),"北京市示例区演示路");
  assert.equal(await page.getByLabel("学历",{exact:true}).inputValue(),"本科");
  record("DOM fallback preserves supported explicit fields and checks identity");
  await page.evaluate(() => {window.select('B');});
  await page.waitForTimeout(200);
  await page.getByRole("button", { name: "读取当前页面", exact: true }).click();
  await page.getByRole('alert').filter({hasText:'无法确认与当前岗位一致'}).waitFor({timeout:8000});
  assert.equal(await page.getByLabel('岗位名称',{exact:true}).inputValue(),'');
  record("mismatching DOM fallback is rejected instead of mixing old detail");
  if (process.env.RUN_BACKEND_SWITCH === 'true') {
  // Two live loopback backends have separate data. Switching must drop TEST IDs.
  await page.getByRole("button", { name: "分析", exact: true }).click();
  await page.getByText('分析操作与历史记录', {exact:true}).click();
  const savedAnalyses = page.locator("section select").first();
  const options = await savedAnalyses.locator("option").all();
  if (options.length > 1) {
    const analysisId = await options[1].getAttribute("value");
    await savedAnalyses.selectOption(analysisId);
    await page.waitForTimeout(500);
  }
  await page.getByRole("button", {name:"岗位",exact:true}).click();
  await page.getByText("连接与恢复", { exact: true }).click();
  await page.getByRole("combobox", { name: /^可信本机API/ }).selectOption("http://127.0.0.1:18080/api");
  await page.getByRole("button", { name: "连接 / 刷新共享状态", exact: true }).click();
  await page.waitForFunction(()=>document.querySelector('ai-job-panel')?.shadowRoot?.textContent?.includes('真实服务'));
  assert.equal(await page.getByText("测试模式", { exact: false }).count(), 0);
  assert.equal(await page.getByRole('alert').count(), 0);
  record("switching TEST to REAL drops previous backend IDs and loads confirmed REAL profile");
  const worker = ctx.serviceWorkers()[0];
  await worker.evaluate(() => chrome.storage.local.set({apiBase:'http://127.0.0.1:18085/api'}));
  await page.waitForTimeout(16000);
  await page.waitForFunction(()=>document.querySelector('ai-job-panel')?.shadowRoot?.textContent?.includes('真实服务'));
  assert.equal(await page.getByText("测试模式", { exact: false }).count(), 0);
  record("request-pinned backend survives a different tab changing the default API");
  } else results.backendSwitchSkipped = 'Opt-in requires two separately configured local backends.';
  results.browser = ctx.browser().version();
  await writeFile(resolve(evidence,"browser-results.json"),JSON.stringify(results,null,2));
  console.log(JSON.stringify(results,null,2));
} finally { await ctx.close(); await new Promise(r=>server.close(r)); }
