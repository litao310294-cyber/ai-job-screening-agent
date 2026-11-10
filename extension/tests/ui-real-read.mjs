import { chromium } from "playwright";
import { createServer } from "node:http";
import { resolve } from "node:path";
import { readFile, writeFile } from "node:fs/promises";
import assert from "node:assert/strict";
const out = resolve("../target/ui-redesign");
const ctx = await chromium.launchPersistentContext(
  resolve(out, "real-profile-" + Date.now()),
  {
    channel: "chrome",
    headless: true,
    ignoreDefaultArgs: ["--disable-extensions"],
    args: ["--enable-unsafe-extension-debugging"],
    viewport: { width: 1440, height: 1000 },
  },
);
const server = createServer((req, res) =>
  res.end("<html><body><h1>面板尺寸验收模拟页面</h1></body></html>"),
);
await new Promise((r) => server.listen(0, "127.0.0.1", r));
try {
  const cdp = await ctx.browser().newBrowserCDPSession();
  const { id } = await cdp.send("Extensions.loadUnpacked", {
    path: resolve(".output/chrome-mv3"),
  });
  const worker =
    ctx.serviceWorkers()[0] ?? (await ctx.waitForEvent("serviceworker"));
  await worker.evaluate(() =>
    chrome.storage.local.set({ apiBase: "http://127.0.0.1:18080/api" }),
  );
  const page = await ctx.newPage();
  await page.goto(`chrome-extension://${id}/demo.html`);
  await page.getByText("后端已连接", { exact: true }).waitFor();
  const operations = await fetch('http://127.0.0.1:18080/api/analyses').then(r => r.json());
  const selected = process.env.UI_ANALYSIS_ID ? operations.find(a => a.id === process.env.UI_ANALYSIS_ID) : operations.find(a => a.status === 'SUCCESS');
  assert(selected, 'Import a resume and save an analysis before this optional REAL read-only test.');
  const binding = {newAnalysis:selected.id};
  await page.getByRole("button", { name: "分析", exact: true }).click();
  await page.getByText("分析操作与历史记录", { exact: true }).click();
  await page
    .locator("section select")
    .first()
    .selectOption(binding.newAnalysis);
  await page.locator(".analysis-view").waitFor();
  await page.getByText("分析操作与历史记录", { exact: true }).click();
  await page.locator('main').evaluate(el => el.scrollTop = 0);
  await page.screenshot({ path: resolve(out, "real-analysis-wide.png") });
  await page.reload();
  await page.getByRole("button", { name: "分析", exact: true }).click();
  await page.locator(".analysis-view").waitFor();
  await page.getByText("分析操作与历史记录", { exact: true }).click();
  const history = await fetch('http://127.0.0.1:18080/api/chat/history?limit=20').then(r => r.json());
  const example = history.turns.find(t => t.question);
  assert(example, 'Save at least one chat turn before this optional read-only test.');
  const old = {analysisId:example.question.analysisId};
  const expectedTurns = history.turns.filter(t => t.question?.jobSnapshotId === example.question.jobSnapshotId).length;
  await page.locator("section select").first().selectOption(old.analysisId);
  await page.getByRole("button", { name: "聊天", exact: true }).click();
  await page.getByRole("button", { name: "刷新历史", exact: true }).click();
  await page.waitForFunction(
    (n) => document.querySelectorAll(".conversation").length === n, expectedTurns,
  );
  assert.equal(await page.locator(".conversation").count(), expectedTurns);
  await page.screenshot({ path: resolve(out, "real-chat-wide.png") });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.screenshot({ path: resolve(out, "real-chat-narrow.png") });
  const box = await page.locator(".composer").boundingBox();
  assert(box.y + box.height <= 801);
  const embedded = await ctx.newPage();
  await embedded.goto(`http://127.0.0.1:${server.address().port}`);
  await embedded.getByRole("button", { name: "AI 求职", exact: true }).click();
  const panel = embedded.locator(".panel");
  assert.equal(Math.round((await panel.boundingBox()).width), 520);
  const handle = embedded.getByRole("separator", { name: "调整面板宽度" });
  const point = await handle.boundingBox();
  await embedded.mouse.move(point.x + 3, point.y + 200);
  await embedded.mouse.down();
  await embedded.mouse.move(point.x - 150, point.y + 200);
  await embedded.mouse.up();
  const width = Math.round((await panel.boundingBox()).width);
  assert(width > 650);
  await embedded.screenshot({ path: resolve(out, "panel-resized.png") });
  await embedded.reload();
  await embedded.getByRole("button", { name: "AI 求职", exact: true }).click();
  await embedded.waitForTimeout(600);
  assert.equal(Math.round((await panel.boundingBox()).width), width);
  await embedded.getByRole("button", { name: "放大阅读", exact: true }).click();
  assert(Math.round((await panel.boundingBox()).width) > 900);
  await embedded.setViewportSize({ width: 350, height: 800 });
  assert((await panel.boundingBox()).width <= 350);
  await embedded.screenshot({ path: resolve(out, "panel-narrow.png") });
  const session = await fetch("http://127.0.0.1:18080/api/session").then((r) =>
    r.json(),
  );
  const rules = await fetch("http://127.0.0.1:18080/api/rules/current").then(
    (r) => r.json(),
  );
  await writeFile(
    resolve(out, "real-read-results.json"),
    JSON.stringify(
      {
        browser: ctx.browser().version(),
        realBackend: true,
        readOnly: true,
        httpMethods: ["GET"],
        modelCallsMeasured: false,
        liveSession: session,
        liveRuleVersion: rules.version,
        analysis: binding.newAnalysis,
        historyAnalysis: old.analysisId,
        turns: expectedTurns,
        refreshRestore: true,
        panelDefaultWidth: 520,
        resizedWidth: width,
        widthPersisted: true,
        narrow: 350,
      },
      null,
      2,
    ),
  );
  console.log(
    "REAL backend saved analysis/history, refresh, panel resize and narrow checks passed",
  );
} finally {
  await ctx.close();
  await new Promise((r) => server.close(r));
}
