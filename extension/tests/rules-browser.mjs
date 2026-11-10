// Actual built Chrome extension + dedicated MySQL schema + test-only providers.
// Requires docker MySQL ai-job-assistant-mysql, Redis on6383 and phase5 test classpath.
import { chromium } from "playwright";
import { spawn, execFileSync } from "node:child_process";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import { once } from "node:events";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const root = resolve(".."),
  out = resolve(root, "target/ui-redesign");
await mkdir(out, { recursive: true });
const db = "ai_job_ui_" + Date.now();
const settings = Object.fromEntries((await readFile(resolve(root, '.env.demo.local'),'utf8')).split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => [l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1).trim()]));
const mysqlPort = settings.DEMO_MYSQL_PORT || '3307';
const redisPort = settings.DEMO_TEST_REDIS_PORT || '6383';
const sql = (statement) =>
  execFileSync(
    "docker",
    [
      "exec",
      "-i",
      "ai-job-assistant-mysql",
      "sh",
      "-c",
      'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N',
    ],
    { input: statement, encoding: "utf8" },
  ).trim();
sql(
  `CREATE DATABASE ${db} CHARACTER SET utf8mb4; GRANT ALL ON ${db}.* TO 'ai_job_demo'@'%';`,
);
const secret = (await readFile(resolve(root, ".env.demo.local"), "utf8"))
  .split(/\r?\n/)
  .find((x) => x.startsWith("DEMO_MYSQL_PASSWORD="))
  .slice("DEMO_MYSQL_PASSWORD=".length);
const env = {
  ...process.env,
  MYSQL_URL: `jdbc:mysql://127.0.0.1:${mysqlPort}/${db}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC`,
  MYSQL_USERNAME: "ai_job_demo",
  MYSQL_PASSWORD: secret,
};
for (const k of Object.keys(env))
  if (
    k.startsWith("AI_CHAT_") ||
    k.startsWith("AI_EMBEDDING_") ||
    k.startsWith("SPRING_DATASOURCE_")
  )
    delete env[k];
const cp = [
  resolve(root, "target/classes"),
  resolve(root, "target/test-classes"),
  (await readFile(resolve(root, "target/phase5-classpath.txt"), "utf8")).trim(),
].join(";");
const origin = JSON.parse(await readFile("extension-id.json", "utf8")).origin;
const args = [
  "-cp",
  cp,
  "com.aijob.analyzer.demo.ExtensionDemoTestServer",
  "--spring.profiles.active=phase2a-test,phase5-extension-test",
  "--server.port=18085",
  "--app.demo.provider-mode=TEST",
  `--app.demo.extension-origin=${origin}`,
  "--spring.data.redis.host=127.0.0.1",
  `--spring.data.redis.port=${redisPort}`,
  "--spring.data.redis.password=",
  `--app.retrieval.lucene-root=${resolve(out, db + "-index")}`,
  "--app.embedding.model=TEST-EMBEDDING",
  "--app.versions.resume-parser.model=TEST-CHAT",
  "--app.versions.requirement-parser.model=TEST-CHAT",
  "--app.versions.final-analysis.model=TEST-CHAT",
  "--app.versions.chat.model=TEST-CHAT",
];
let backend, ctx;
const checks = [],
  pids = [];
const base = "http://127.0.0.1:18085/api";
async function request(path, method = "GET", body) {
  const r = await fetch(base + path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, body: await r.json() };
}
async function start() {
  const log = createWriteStream(
    resolve(out, `rules-jvm-${pids.length + 1}.log`),
  );
  backend = spawn("java", args, { cwd: root, env, windowsHide: true });
  pids.push(backend.pid);
  backend.stdout.pipe(log);
  backend.stderr.pipe(log);
  for (let i = 0; i < 120; i++) {
    try {
      if ((await request("/health")).status === 200) return;
    } catch {}
    if (backend.exitCode !== null)
      throw new Error("Isolated backend exited; see JVM log");
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("Backend startup timed out");
}
async function stop() {
  if (backend && backend.exitCode === null) {
    backend.kill();
    await once(backend, "exit");
  }
}
const profile = resolve(out, db + "-chrome");
let id;
async function browser(first = false) {
  ctx = await chromium.launchPersistentContext(profile, {
    channel: "chrome",
    headless: true,
    ignoreDefaultArgs: ["--disable-extensions"],
    args: ["--enable-unsafe-extension-debugging"],
    viewport: { width: 1200, height: 1000 },
  });
  // CDP-loaded extensions are temporary; load the same build again after restart.
  const cdp = await ctx.browser().newBrowserCDPSession();
  id = (
    await cdp.send("Extensions.loadUnpacked", {
      path: resolve(".output/chrome-mv3"),
    })
  ).id;
  return ctx;
}
async function page() {
  const p = await ctx.newPage();
  await p.goto(`chrome-extension://${id}/demo.html`);
  await p.getByText("后端已连接", { exact: true }).waitFor();
  await p.getByRole("button", { name: "规则", exact: true }).click();
  return p;
}
async function save(p) {
  await p.getByRole("button", { name: "保存设置", exact: true }).click();
  await p.getByText("已保存，下次自动使用", { exact: true }).waitFor();
}
async function sync(p) {
  await p.getByText("连接与恢复", { exact: true }).click();
  await p
    .getByRole("button", { name: "连接 / 刷新共享状态", exact: true })
    .click();
}
try {
  await start();
  assert.equal((await request("/rules/current")).body.version, 0);
  await browser(true);
  let a = await page();
  assert.equal(
    await a.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "5",
  );
  assert.equal(
    await a.getByLabel("可承诺周期（月）", { exact: true }).inputValue(),
    "6",
  );
  assert.equal((await request("/rules/current")).body.version, 0);
  checks.push("never-saved v0 suggests 5 days/6 months without writing");
  await save(a);
  assert.equal((await request("/rules/current")).body.version, 1);
  await a.getByLabel("每周可出勤天数", { exact: true }).fill("4");
  await save(a);
  await a.close();
  a = await page();
  assert.equal(
    await a.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "4",
  );
  await a.reload();
  await a.getByRole("button", { name: "规则", exact: true }).click();
  await a.getByText("已保存，下次自动使用", { exact: true }).waitFor();
  checks.push("saved edits restore after close/reopen and refresh");
  let b = await page();
  await a.getByLabel("每周可出勤天数", { exact: true }).fill("3");
  await save(a);
  await sync(b);
  assert.equal(
    await b.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "3",
  );
  checks.push("pristine tab refreshes from same-user backend");
  await b.getByLabel("每周可出勤天数", { exact: true }).fill("2");
  await a.getByLabel("每周可出勤天数", { exact: true }).fill("5");
  await save(a);
  await b.getByRole("button", { name: "保存设置", exact: true }).click();
  await b.getByRole("alert").filter({ hasText: "本地输入已保留" }).waitFor();
  assert.equal(
    await b.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "2",
  );
  assert.equal(
    (await request("/rules/current")).body.config.candidate
      .availableDaysPerWeek,
    5,
  );
  checks.push("stale expectedVersion rejects save and preserves local input");
  await b
    .getByRole("button", { name: "加载最新配置（放弃本地修改）", exact: true })
    .click();
  await b.getByText("已加载后端最新配置。", { exact: true }).waitFor();
  await b.getByLabel("每周可出勤天数", { exact: true }).fill("8");
  await b.getByRole("button", { name: "保存设置", exact: true }).click();
  await b.getByRole("alert").filter({ hasText: "设置未保存" }).waitFor();
  assert.equal(
    await b.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "8",
  );
  assert.equal(
    (await request("/rules/current")).body.config.candidate
      .availableDaysPerWeek,
    5,
  );
  checks.push("validation failure retains draft and leaves saved rules intact");
  await b.getByRole("button", { name: "岗位", exact: true }).click();
  await b.getByLabel("岗位名称", { exact: true }).fill("隔离验收岗位");
  await b.getByLabel("原始 JD", { exact: true }).fill("Java 工作");
  await b.getByRole("button", { name: "保存岗位快照", exact: true }).click();
  await b.getByRole("button", { name: "分析", exact: true }).click();
  await b.getByText("分析操作与历史记录", { exact: true }).click();
  await b
    .getByRole("button", { name: "提交当前岗位分析", exact: true })
    .click();
  await b
    .getByRole("alert")
    .filter({ hasText: "有未保存的规则修改" })
    .waitFor();
  assert.equal((await request("/analyses")).body.length, 0);
  checks.push("unsaved edits cannot silently enter an analysis");
  await ctx.close();
  await browser();
  a = await page();
  assert.equal(
    await a.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "5",
  );
  checks.push(
    "browser process restart restores backend settings, discards unsaved draft",
  );
  const before = (await request("/rules/current")).body;
  await stop();
  await start();
  await a.reload();
  await a.getByRole("button", { name: "规则", exact: true }).click();
  await a.getByText("已保存，下次自动使用", { exact: true }).waitFor();
  assert.deepEqual((await request("/rules/current")).body, before);
  assert.equal(
    await a.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "5",
  );
  checks.push("independent JVM restart restores identical saved rules");
  await a.getByLabel("每周可出勤天数", { exact: true }).fill("");
  await a.getByLabel("可承诺周期（月）", { exact: true }).fill("");
  await a.getByLabel("目标方向", { exact: true }).selectOption([]);
  for (const label of [
    "城市",
    "目标方向",
    "每周出勤",
    "实习周期",
    "薪资范围",
    "排除关键词",
  ])
    await a
      .getByLabel(label + "属性", { exact: true })
      .selectOption("DISABLED");
  await save(a);
  await a.reload();
  await a.getByRole("button", { name: "规则", exact: true }).click();
  await a.getByText("已保存，下次自动使用", { exact: true }).waitFor();
  assert.equal(
    await a.getByLabel("每周可出勤天数", { exact: true }).inputValue(),
    "",
  );
  assert.deepEqual(
    (await request("/rules/current")).body.config.criteria.directions,
    [],
  );
  checks.push(
    "explicit empty/all-disabled saved config survives reload without suggestions",
  );
  await a.getByRole("button", { name: "使用建议配置", exact: true }).click();
  await a.getByText(/这是未保存的建议/).waitFor();
  assert.equal(
    (await request("/rules/current")).body.config.candidate
      .availableDaysPerWeek,
    null,
  );
  checks.push("manual suggestions are a draft until explicit save");
  await a.screenshot({ path: resolve(out, "rules-settings.png") });
  const calls = Number(sql(`SELECT COUNT(*) FROM ${db}.demo_model_call;`));
  assert.equal(calls, 0);
  await writeFile(
    resolve(out, "rules-results.json"),
    JSON.stringify(
      {
        database: db,
        user: "demo-li-tao",
        mysql: `127.0.0.1:${mysqlPort}`,
        redis: `127.0.0.1:${redisPort}`,
        browser: ctx.browser().version(),
        pids,
        checks,
        modelAuditRows: calls,
        finalSavedRules: (await request("/rules/current")).body,
      },
      null,
      2,
    ),
  );
  console.log(checks);
  if (process.env.UI_FULL_FLOW === "1") {
    const log = createWriteStream(resolve(out, "full-flow.log"));
    const flow = spawn(process.execPath, ["tests/browser.mjs"], {
      env: { ...process.env, UI_EVIDENCE: "target/ui-redesign/full-flow" },
      windowsHide: true,
    });
    flow.stdout.pipe(log);
    flow.stderr.pipe(log);
    const [code] = await once(flow, "exit");
    assert.equal(
      code,
      0,
      "Original browser business regression failed; see full-flow.log",
    );
  }
} finally {
  if (ctx) await ctx.close();
  await stop();
}
