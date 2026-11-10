import { chromium } from "playwright";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const out = resolve("../target/ui-redesign");
await mkdir(out, { recursive: true });
const fixture = JSON.parse(await readFile('tests/fixtures/ui-synthetic.json', 'utf8'));
const {operation: source, history: hist, facts, job} = fixture;
const ctx = await chromium.launchPersistentContext(
  resolve(out, "profile-" + Date.now()),
  {
    channel: "chrome",
    headless: true,
    ignoreDefaultArgs: ["--disable-extensions"],
    args: ["--enable-unsafe-extension-debugging"],
    viewport: { width: 1440, height: 1000 },
  },
);
const checks = [];
const pass = (name) => checks.push(name);
try {
  const cdp = await ctx.browser().newBrowserCDPSession();
  const { id } = await cdp.send("Extensions.loadUnpacked", {
    path: resolve(".output/chrome-mv3"),
  });
  const page = await ctx.newPage();
  const initial = { operation: source, history: hist, facts, job };
  await page.addInitScript((initial) => {
    window.testData = structuredClone(initial);
    window.sent = [];
    const native = chrome.runtime.sendMessage.bind(chrome.runtime);
    chrome.runtime.sendMessage = async (req) => {
      const d = window.testData;
      const o = d.operation;
      const snap = { id: o.jobSnapshotId, job: d.job };
      if (req.type === "scope") return { ok: true, data: "ui-test" };
      if (req.type === "configure") return { ok: true, data: req.base };
      if (req.type !== "api") return native(req);
      window.sent.push(req);
      let value;
      if (req.path === "/health") value = { status: "UP" };
      else if (req.path === "/session")
        value = { profile: { version: 3 }, providerMode: "TEST" };
      else if (req.path === "/profile/current")
        value = { profile: { identity: { profileVersion: 3 } }, resume: null };
      else if (req.path === "/rules/current")
        value = {
          version: 2,
          config: {
            candidate: {availableDaysPerWeek:null,availableMonths:null,availableFrom:null},
            criteria: { cities: [], directions: [], excludedKeywords: [],salaryMin:null,salaryMax:null,salaryUnit:null },
            modes: { city: "DISABLED",direction:'DISABLED',attendance:'DISABLED',duration:'DISABLED',salary:'DISABLED',excludedKeywords:'DISABLED' },
            softNotes: [],
          },
        };
      else if (req.path === "/rules/capabilities") value = {};
      else if (req.path === "/analyses") value = [o];
      else if (req.path.endsWith("/evidence")) value = d.facts;
      else if (req.path.startsWith("/analyses/")) value = o;
      else if (req.path.startsWith("/jobs/")) value = snap;
      else if (req.path.startsWith("/chat/history"))
        value =
          req.path.includes("afterSequence=") && d.moreHistory
            ? d.moreHistory
            : d.history;
      else if (req.path === "/chat" && req.method === "DELETE") {
        d.history = {
          generation: d.history.generation + 1,
          turns: [],
          hasMore: false,
        };
        value = d.history;
      } else if (req.path === "/chat/messages") {
        d.completed ??= {};
        if (d.completed[req.body.clientRequestId])
          return {
            ok: true,
            data: structuredClone(d.completed[req.body.clientRequestId]),
          };
        if (req.body.question.startsWith("延迟"))
          await new Promise((resolve) => (window.releaseReply = resolve));
        const question = {
          ...req.body,
          id: "new-question",
          content: req.body.question,
          sequence: 90,
        };
        value = {
          id: req.body.clientRequestId,
          clientRequestId: req.body.clientRequestId,
          generation: req.body.expectedGeneration,
          status: "SUCCEEDED",
          question,
          reply: {
            content:
              "**中文回复**\n\n- 根据当前岗位解释。\n\n`Java`\n\n<script>window.hacked=1</script>\n[危险](javascript:alert(1))",
            evidenceRefs: [],
            replyToMessageId: "new-question",
          },
        };
        if (req.body.expectedGeneration === d.history.generation)
          d.history.turns.push(value);
        d.completed[req.body.clientRequestId] = structuredClone(value);
      } else value = {};
      return { ok: true, data: structuredClone(value) };
    };
  }, initial);
  await page.goto(`chrome-extension://${id}/demo.html`);
  await page.getByText("后端已连接", { exact: true }).waitFor();
  await page.getByRole("button", { name: "分析", exact: true }).click();
  await page.getByText("分析操作与历史记录", { exact: true }).click();
  await page.locator("section select").first().selectOption(source.id);
  await page.getByRole("heading", { name: "可以考虑", exact: true }).waitFor();
  assert.equal(
    await page.getByText("尚未启用筛选规则", { exact: true }).count(),
    1,
  );
  await page.locator('main').evaluate(el => el.scrollTop = 0);
  await page.screenshot({ path: resolve(out, "analysis-wide.png") });
  pass("synthetic analysis in built Chrome UI (mock transport)");
  await page.getByText(/逐项要求与简历证据/).click();
  await page
    .getByText(/查看.*绑定简历证据/)
    .first()
    .click();
  assert(await page.locator("blockquote").first().innerText());
  pass("bound evidence expands");
  // Each scenario returns through the same saved-analysis entry point.
  async function scenario(kind) {
    await page.evaluate((kind) => {
      const o = window.testData.operation;
      if (kind === "failed") o.execution.result = null;
      o.stale = kind === "stale";
      o.status = kind === "failed" ? "FAILED" : "SUCCESS";
      o.errorCode = kind === "failed" ? "LLM_ANALYSIS_FAILED" : null;
      o.execution.resultOrigin =
        kind === "terminal"
          ? "RULE_TERMINAL"
          : kind === "degraded"
            ? "DEGRADED_FALLBACK"
            : "LLM_ANALYSIS";
      o.ruleResult.items = [
        {
          field: "city",
          strength: "HARD",
          verdict: kind === "terminal" ? "UNSATISFIED" : "UNKNOWN",
          candidateValue: "北京",
          jobValue: "上海",
          reason: "岗位城市与接受城市不同",
        },
      ];
    }, kind);
    await page.locator("section select").first().selectOption(source.id);
  }
  await scenario("terminal");
  await page
    .getByText("本次依据规则结束，未进行 AI 分析", { exact: true })
    .waitFor();
  await page.locator('main').evaluate(el => el.scrollTop = 0);
  await page.screenshot({ path: resolve(out, "hard-refusal.png") });
  pass("hard refusal explicitly skips model analysis");
  await scenario("degraded");
  await page
    .getByText(/降级结果/)
    .first()
    .waitFor();
  pass("degraded label");
  await scenario("failed");
  await page.getByText(/未通过校验/).waitFor();
  pass("failed label");
  await page.evaluate(
    (source) => (window.testData.operation = structuredClone(source)),
    source,
  );
  await scenario("stale");
  await page.getByText(/分析已过期，画像或规则/).waitFor();
  pass("stale warning");
  await scenario("unknown");
  assert(await page.getByText(/1 项待确认/).count());
  pass("UNKNOWN remains pending");
  // Use the actual historical analysis binding for the chat view.
  const aid = hist.turns.at(-1).question.analysisId;
  await page.evaluate((aid) => {
    window.testData.operation.id = aid;
    window.testData.operation.jobSnapshotId =
      window.testData.history.turns.at(-1).question.jobSnapshotId;
  }, aid);
  await page
    .getByRole("button", { name: "连接 / 刷新共享状态", exact: true })
    .count();
  // Reload list through the shared-state action then choose this analysis.
  await page.getByRole("button", { name: "岗位", exact: true }).click();
  await page.getByText("连接与恢复", { exact: true }).click();
  await page
    .getByRole("button", { name: "连接 / 刷新共享状态", exact: true })
    .click();
  await page.getByRole("button", { name: "分析", exact: true }).click();
  if (!(await page.locator("section select").first().isVisible()))
    await page.getByText("分析操作与历史记录", { exact: true }).click();
  await page.locator("section select").first().selectOption(aid);
  await page.getByRole("button", { name: "聊天", exact: true }).click();
  await page.getByRole("button", { name: "刷新历史", exact: true }).click();
  assert.equal(await page.locator(".conversation").count(), 3);
  pass("current-job chat filters other jobs");
  await page
    .getByRole("button", { name: "复制回复", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "已复制", exact: true }).waitFor();
  pass("reply clipboard write succeeds in Chrome");
  await page.screenshot({ path: resolve(out, "chat-wide.png") });
  const textbox = page.getByRole("textbox", { name: "问题", exact: true });
  await textbox.fill("中文组词");
  const sentBefore = await page.evaluate(
    () => window.sent.filter((r) => r.path === "/chat/messages").length,
  );
  await textbox.dispatchEvent("keydown", {
    key: "Enter",
    isComposing: true,
    keyCode: 229,
  });
  assert.equal(
    await page.evaluate(
      () => window.sent.filter((r) => r.path === "/chat/messages").length,
    ),
    sentBefore,
  );
  pass("IME does not send");
  await textbox.press("Shift+Enter");
  assert((await textbox.inputValue()).includes("\n"));
  pass("Shift Enter newline");
  await textbox.fill("请解释当前岗位");
  await textbox.press("Enter");
  await page.getByText("中文回复", { exact: true }).waitFor();
  assert.equal(
    await page
      .locator(
        '.markdown script,.markdown img,.markdown a[href^="javascript:"]',
      )
      .count(),
    0,
  );
  pass("safe Markdown and Enter send");
  const originalRequest = await page.evaluate(
    () => window.sent.filter((r) => r.path === "/chat/messages").at(-1).body,
  );
  const turnCount = await page.locator(".conversation").count();
  await page.getByText("更多操作与岗位比较", { exact: true }).click();
  await page
    .getByRole("button", { name: "使用同一请求重试", exact: true })
    .click();
  await page.waitForFunction(
    (n) => window.sent.filter((r) => r.path === "/chat/messages").length > n,
    sentBefore + 1,
  );
  assert.deepEqual(
    await page.evaluate(
      () => window.sent.filter((r) => r.path === "/chat/messages").at(-1).body,
    ),
    originalRequest,
  );
  assert.equal(await page.locator(".conversation").count(), turnCount);
  pass("retry retains original request body and displays one saved reply");
  await page.getByText("更多操作与岗位比较", { exact: true }).click();
  await page.locator(".message-list").evaluate((el) => (el.scrollTop = 0));
  await textbox.fill("阅读时追加回复");
  await textbox.press("Enter");
  await page.getByRole("button", { name: "回到最新消息 ↓" }).waitFor();
  pass("reading position preserved on new messages");
  await page.setViewportSize({ width: 360, height: 800 });
  await page.screenshot({ path: resolve(out, "chat-narrow.png") });
  assert(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  const composer = await page.locator(".composer").boundingBox();
  assert(composer.y + composer.height <= 802);
  pass("narrow viewport and fixed composer");
  await page.getByRole("button", { name: "清空历史", exact: true }).click();
  await page.getByText(/将清空所有岗位的共享聊天历史/).waitFor();
  pass("clear scope confirmation");
  // Clear while a reply is pending. The old generation must not return to view.
  await page.getByRole("button", { name: "取消", exact: true }).click();
  await textbox.fill("延迟回复用于清空竞态");
  await textbox.press("Enter");
  await page.waitForFunction(() => typeof window.releaseReply === "function");
  await page.getByRole("button", { name: "清空历史", exact: true }).click();
  await page
    .getByRole("button", { name: "确认清空所有聊天", exact: true })
    .click();
  await page.getByText("一起看看这个岗位", { exact: true }).waitFor();
  await page.evaluate(() => window.releaseReply());
  await page.waitForTimeout(250);
  assert.equal(await page.locator(".conversation").count(), 0);
  pass("clear generation fence drops delayed reply");
  await page.evaluate(() => {
    const d = window.testData;
    const make = (index) => ({
      id: "long-" + index,
      status: "SUCCEEDED",
      generation: d.history.generation,
      question: {
        id: "q-" + index,
        content: "长历史问题 " + index,
        sequence: index * 2,
        jobSnapshotId: d.operation.jobSnapshotId,
        analysisId: d.operation.id,
      },
      reply: {
        content: "中文长回复。".repeat(60),
        evidenceRefs: [],
        replyToMessageId: "q-" + index,
      },
    });
    d.history = {
      generation: d.history.generation,
      turns: Array.from({ length: 20 }, (_, i) => make(i)),
      hasMore: true,
      nextAfterSequence: 38,
    };
    d.moreHistory = {
      generation: d.history.generation,
      turns: Array.from({ length: 20 }, (_, i) => make(i + 20)),
      hasMore: false,
      nextAfterSequence: null,
    };
  });
  await page.getByRole("button", { name: "刷新历史", exact: true }).click();
  await page.waitForFunction(
    () => document.querySelectorAll(".conversation").length === 20,
  );
  await page
    .getByRole("button", { name: "加载下一页历史", exact: true })
    .click();
  await page.waitForFunction(
    () => document.querySelectorAll(".conversation").length === 40,
  );
  pass("forty long turns load through history pagination");
  await writeFile(
    resolve(out, "browser-results.json"),
    JSON.stringify(
      {
        browser: ctx.browser().version(),
        mockTransport: true,
        syntheticContent: true,
        checks,
      },
      null,
      2,
    ),
  );
  console.log(checks);
} finally {
  await ctx.close();
}
