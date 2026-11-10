import { chromium } from "playwright";
import { createServer } from "node:http";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { strict as assert } from "node:assert";
import { createHash } from "node:crypto";
const root = resolve(".."),
  evidence = resolve(root, process.env.UI_EVIDENCE || "target/phase5-evidence");
await mkdir(evidence, { recursive: true });
const { id } = JSON.parse(await readFile("extension-id.json", "utf8"));
const fixture = createServer((req, res) => {
  res.setHeader("Content-Type", "text/html; charset=utf-8");
  res.end(
    `<!doctype html><html lang="zh"><title>BOSS结构模拟页面</title><body style="background:#eef2f6"><h1>明确标识 · BOSS结构模拟页面</h1><div class="job-detail-box"><h1 class="job-name">Java 页面岗位 ${req.url}</h1><div class="job-sec-text">Java 后端开发，每周至少4天。</div></div><script>document.querySelector('.job-detail-box').__vue__={jobDetail:{jobInfo:{jobName:'Java Vue模拟岗位',postDescription:'Java 后端开发，每周至少4天。',cityName:'上海'}}};</script></body></html>`,
  );
});
await new Promise((r) => fixture.listen(18086, "127.0.0.1", r));
const context = await chromium.launchPersistentContext(
  resolve(evidence, "browser-profile-final-" + Date.now()),
  {
    executablePath:
      process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ??
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
    headless: true,
    ignoreDefaultArgs: ["--disable-extensions"],
    args: ["--enable-unsafe-extension-debugging"],
    viewport: { width: 1280, height: 960 },
  },
);
const protocol = await context.browser().newBrowserCDPSession();
context.setDefaultTimeout(20000);
try {
  await protocol.send("Extensions.uninstall", { id });
} catch {
  /* Fresh isolated profile. */
}
const installed = await protocol.send("Extensions.loadUnpacked", {
  path: resolve(".output/chrome-mv3"),
});
assert.equal(installed.id, id);
const worker =
  context.serviceWorkers().find((item) => item.url().includes(id)) ??
  (await context.waitForEvent("serviceworker"));
await worker.evaluate(() => chrome.storage.local.clear());
const log = [],
  errors = [];
context.on("weberror", (e) => errors.push(String(e.error())));
context.on("response", async (response) => {
  if (response.url().startsWith("http://127.0.0.1:18085/api")) {
    try {
      const req = response.request();
      log.push({
        url: response.url(),
        method: req.method(),
        request: req.headers()["content-type"]?.includes("application/json")
          ? req.postDataJSON()
          : null,
        status: response.status(),
        response: await response.json(),
      });
    } catch {}
  }
});
const page = await context.newPage();
async function text(value) {
  await page
    .getByText(value, { exact: false })
    .first()
    .waitFor({ timeout: 60000 });
}
async function click(value) {
  await page.getByRole("button", { name: value, exact: true }).click();
}
async function section(value) {
  await click(value);
}
try {
  await page.goto("http://127.0.0.1:18086/a");
  await click("AI 求职");
  await text("后端已连接");
  await text("测试模式");
  assert((await page.locator("ai-job-panel").count()) === 1);
  await click("读取当前页面");
  await text("只读采集完成");
  await page.getByLabel("岗位名称", { exact: true }).waitFor();
  assert.equal(
    await page.getByLabel("岗位名称", { exact: true }).inputValue(),
    "Java Vue模拟岗位",
  );
  await section("简历");
  const pdf =
    process.env.DEMO_RESUME_PDF || "../target/synthetic-resume.pdf";
  await page.getByTestId("pdf").setInputFiles(pdf);
  await text("导入状态 文本已提取");
  const pdfBytes = await readFile(pdf);
  const pdfSha256 = createHash("sha256").update(pdfBytes).digest("hex");
  assert.equal(
    log.find((item) => item.url.endsWith("/profile/resume/upload"))?.response
      .contentSha256,
    pdfSha256,
  );
  await click("结构化事实");
  await text("等待确认事实");
  await page
    .getByLabel("事实内容", { exact: true })
    .fill("Java TEST PROVIDER · 已检查修正");
  await click("保存事实修正");
  await text("事实修正已保存");
  await click("确认事实并发布画像");
  await text("导入状态 已就绪");
  await page.getByTestId("pdf").setInputFiles([]);
  await page.getByTestId("pdf").setInputFiles(pdf);
  await text("重复文件");
  await section("规则");
  await click("保存设置");
  await text("已保存，下次自动使用");
  await section("岗位");
  await page.getByText("固定模拟岗位", { exact: true }).click();
  await click("载入模拟岗位");
  await click("保存岗位快照");
  await text("不可变岗位快照已保存");
  await section("分析");
  await page.getByText("分析操作与历史记录", { exact: true }).click();
  await click("提交当前岗位分析");
  await text("AI 分析");
  await page.screenshot({ path: resolve(evidence, "analysis.png") });
  await section("聊天");
  for (let i = 1; i <= 3; i++) {
    await page
      .getByLabel("问题", { exact: true })
      .fill(
        `浏览器测试第${i}轮` +
          (i === 3
            ? " <img src=x onerror=alert(1)> " + "长文本测试".repeat(80)
            : ""),
      );
    await click("发送问题");
    await page.waitForFunction(
      (n) =>
        document
          .querySelector("ai-job-panel")
          ?.shadowRoot?.querySelectorAll(".conversation").length === n,
      i,
      { timeout: 30000 },
    );
  }
  assert.equal(await page.locator(".conversation img").count(), 0);
  await page.screenshot({ path: resolve(evidence, "chat.png") });
  await page.reload();
  await click("AI 求职");
  await text("后端已连接");
  await section("聊天");
  await page.waitForFunction(
    () =>
      document
        .querySelector("ai-job-panel")
        ?.shadowRoot?.querySelectorAll(".conversation").length === 3,
  );
  assert.equal(await page.locator(".conversation").count(), 3);
  await click("清空历史");
  await text("将清空所有岗位的共享聊天历史");
  await click("确认清空所有聊天");
  await text("所有岗位的聊天已清空");
  assert.equal(await page.locator(".conversation").count(), 0);
  await page.setViewportSize({ width: 350, height: 800 });
  await page.screenshot({ path: resolve(evidence, "narrow.png") });
  assert(await page.locator("aside").evaluate((el) => el.scrollWidth <= 350));
  const second = await context.newPage();
  await second.goto("http://127.0.0.1:18086/b");
  await second.getByRole("button", { name: "AI 求职", exact: true }).click();
  await second.getByText("后端已连接", { exact: false }).waitFor();
  await second.getByRole("button", { name: "规则", exact: true }).click();
  await second.getByRole("button", { name: "保存设置", exact: true }).waitFor();
  await second.getByLabel("每周可出勤天数", { exact: true }).fill("6");
  await section("规则");
  await click("保存设置");
  await text("已保存，下次自动使用");
  await second.getByRole("button", { name: "保存设置", exact: true }).click();
  await second
    .getByRole("alert")
    .filter({ hasText: "本地输入已保留" })
    .waitFor();
  await page.setViewportSize({ width: 1280, height: 960 });
  await section("岗位");
  await page
    .getByLabel("岗位名称", { exact: true })
    .fill("Java LATE_TEST 后端");
  await click("保存岗位快照");
  await text("不可变岗位快照已保存");
  await section("分析");
  await page.getByText("分析操作与历史记录", { exact: true }).click();
  await click("提交当前岗位分析");
  await section("岗位");
  if (
    !(await page
      .getByRole("button", { name: "载入模拟岗位", exact: true })
      .isVisible())
  )
    await page.getByText("固定模拟岗位", { exact: true }).click();
  await click("载入模拟岗位");
  await page.waitForTimeout(6500);
  assert.equal(
    await page.getByLabel("岗位名称", { exact: true }).inputValue(),
    "Java 后端实习 A",
  );
  await page.evaluate(() => history.pushState({}, "", "/route-change"));
  await page.waitForTimeout(700);
  assert.equal(
    await page.getByLabel("岗位名称", { exact: true }).inputValue(),
    "",
  );
  const late = log
    .filter((item) => item.url.endsWith("/analyses") && item.method === "POST")
    .at(-1)?.response;
  await section("分析");
  await page.getByText("分析操作与历史记录", { exact: true }).click();
  await page.locator("main section select").first().selectOption(late.id);
  await text("AI 分析");
  await section("聊天");
  await page.getByLabel("问题", { exact: true }).fill("清空竞态测试");
  const outgoing = context.waitForEvent("request", {
    predicate: (r) =>
      r.url().endsWith("/chat/messages") && r.method() === "POST",
  });
  await click("发送问题");
  await outgoing;
  await page.waitForTimeout(500);
  await click("清空历史");
  await click("确认清空所有聊天");
  await text("所有岗位的聊天已清空");
  await page.waitForTimeout(3500);
  assert.equal(await page.locator(".conversation").count(), 0);
  await second.getByRole("button", { name: "聊天", exact: true }).click();
  await second.getByRole("button", { name: "清空历史", exact: true }).click();
  await second
    .getByRole("button", { name: "确认清空所有聊天", exact: true })
    .click();
  await second
    .getByRole("alert")
    .filter({ hasText: "共享聊天已更新" })
    .waitFor();
  const demo = await context.newPage();
  await demo.goto(`chrome-extension://${id}/demo.html`);
  await demo.getByText("后端已连接", { exact: false }).waitFor();
  const rejected = await demo.evaluate(() =>
    chrome.runtime.sendMessage({
      type: "api",
      method: "GET",
      path: "/../admin",
    }),
  );
  assert.equal(rejected.ok, false);
  const preflight = await fetch("http://127.0.0.1:18085/api/chat", {
    method: "OPTIONS",
    headers: {
      Origin: `chrome-extension://${id}`,
      "Access-Control-Request-Method": "DELETE",
      "Access-Control-Request-Headers": "Content-Type",
    },
  });
  assert.equal(preflight.status, 204);
  assert(
    preflight.headers.get("access-control-allow-methods").includes("DELETE"),
  );
  const denied = await fetch("http://127.0.0.1:18085/api/health", {
    headers: { Origin: "https://www.zhipin.com" },
  });
  assert.equal(denied.status, 403);
  const results = {
    browser: context.browser()?.version(),
    extensionId: id,
    actualExtensionUpload: true,
    pdfBytes: pdfBytes.length,
    pdfSha256,
    shadowDom: true,
    vueFixtureCapture: true,
    threeChatRounds: true,
    refreshRestore: true,
    clear: true,
    clearLateReplyFence: true,
    ruleConflict: true,
    lateResultFence: true,
    routeChange: true,
    escapedUntrustedHtml: true,
    narrowWidth: 350,
    preflightStatus: preflight.status,
    deniedOriginStatus: denied.status,
    paidModelCalls: 0,
    errors,
  };
  await writeFile(
    resolve(evidence, "browser-results.json"),
    JSON.stringify(results, null, 2),
  );
  console.log(JSON.stringify(results));
} catch (error) {
  await page.screenshot({ path: resolve(evidence, "browser-failure.png") });
  await writeFile(
    resolve(evidence, "browser-failure.txt"),
    String(error.stack) + "\n" + errors.join("\n"),
  );
  throw error;
} finally {
  await writeFile(
    resolve(evidence, "browser-http.json"),
    JSON.stringify(log, null, 2),
  );
  await context.close();
  await new Promise((r) => fixture.close(r));
}
