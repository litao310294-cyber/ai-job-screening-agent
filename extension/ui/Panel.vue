<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import { browser } from "wxt/browser";
import { api, message, upload, ApiError, setApiBase } from "../lib/api";
import {
  Fence,
  wireClone,
  type Rules,
  type Job,
  type Snapshot,
  type Analysis,
  type CurrentProfile,
  type ImportView,
  type Draft,
  type Fact,
  type History,
  type Turn,
  type ChatInput,
  type AnalysisInput,
} from "../lib/contracts";
import { capture } from "../lib/capture";
import AnalysisView from "./AnalysisView.vue";
import ChatView from "./ChatView.vue";
import { display, errorText } from "../lib/presentation";
import {
  neverSaved,
  suggestedConfig,
  sameConfig,
  normalizedConfig,
  settingsSummary,
} from "../lib/rule-settings";
import fixtures from "../lib/jobs.json";
const props = defineProps<{ embedded: boolean }>();
const open = ref(!props.embedded),
  section = ref("岗位"),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  connected = ref(false);
const base = ref("http://127.0.0.1:18085/api"),
  profile = ref<CurrentProfile | null>(null),
  rules = ref<Rules | null>(null),
  serverRuleVersion = ref<number | null>(null),
  capabilities = ref<unknown>(null);
const imported = ref<ImportView | null>(null),
  draft = ref<Draft | null>(null),
  structured = ref("[]"),
  remediation = ref("");
const savedRules = ref<Rules | null>(null);
const rulesDirty = computed(
  () =>
    !!rules.value &&
    !!savedRules.value &&
    !sameConfig(rules.value.config, savedRules.value.config),
);
function acceptRules(latest: Rules, initial = false) {
  const keepEdits = rulesDirty.value;
  savedRules.value = structuredClone(wireClone(latest));
  serverRuleVersion.value = latest.version;
  if (!keepEdits || !rules.value) {
    rules.value = structuredClone(wireClone(latest));
    if (initial && neverSaved(latest)) rules.value.config = suggestedConfig();
  }
}
function suggestRules() {
  if (rules.value) rules.value.config = suggestedConfig();
  notice.value =
    "根据简历预填，可随时修改。这是未保存的建议，不会修改简历事实；确认后请保存设置。";
}
const emptyJob = (): Job => ({
  sourcePlatform: "MANUAL",
  title: "",
  city: "",
  company: "",
  salary: "",
  education: "",
  experience: "",
  description: "",
  simulated: false,
  captureMethod: "MANUAL",
});
const editJob = ref<Job>(emptyJob()),
  snapshot = ref<Snapshot | null>(null),
  analysis = ref<Analysis | null>(null),
  analyses = ref<Analysis[]>([]);
const history = ref<History | null>(null),
  turns = ref<Turn[]>([]),
  question = ref(""),
  compare = ref(""),
  confirmClear = ref(false);
const pendingAnalysis = ref<AnalysisInput | null>(null),
  pendingChat = ref<ChatInput | null>(null),
  lastTurn = ref<Turn | null>(null);
const sampleId = ref("SIM-JAVA-A"),
  scope = ref("demo");
const fence = new Fence();
let poll: ReturnType<typeof setInterval> | undefined;
const technicalError = ref("");
const panelWidth = ref(520);
const expanded = ref(false);
function resizePanel(event: PointerEvent) {
  const start = event.clientX,
    width = panelWidth.value;
  const move = (e: PointerEvent) =>
    (panelWidth.value = Math.max(
      320,
      Math.min(window.innerWidth, width + start - e.clientX),
    ));
  const finish = () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", finish);
    void browser.storage.local.set({ panelWidth: panelWidth.value });
  };
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", finish);
}
function workspace() {
  void browser.tabs.create({ url: browser.runtime.getURL("/demo.html") });
}
const backendMode = ref("UNKNOWN");
const testMode = computed(() => backendMode.value === "TEST");
const boundEvidence = ref<Record<string, Fact[]>>({});
const analysisFacts = computed(() =>
  analysis.value ? (boundEvidence.value[analysis.value.id] ?? []) : [],
);
const strengthOptions = ["DISABLED", "SOFT", "HARD"];
const labels: Record<string, string> = {
  city: "城市",
  direction: "目标方向",
  attendance: "每周出勤",
  duration: "实习周期",
  salary: "薪资范围",
  excludedKeywords: "排除关键词",
};
async function loadEvidence(id: string) {
  if (!boundEvidence.value[id])
    boundEvidence.value[id] = (
      await api<{ facts: Fact[] }>(`/analyses/${id}/evidence`)
    ).facts;
}
async function saveTab() {
  if (analysis.value) await loadEvidence(analysis.value.id);
  await browser.storage.local.set(
    wireClone({
      [`tab:${scope.value}`]: {
        apiBase: base.value,
        snapshot: snapshot.value,
        analysis: analysis.value,
        pendingAnalysis: pendingAnalysis.value,
        pendingChat: pendingChat.value,
        url: location.href,
        importId: imported.value?.id,
      },
    }),
  );
}
async function work(action: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  technicalError.value = "";
  notice.value = "";
  try {
    await action();
  } catch (e) {
    technicalError.value =
      e instanceof ApiError ? `${e.code} · ${e.message}` : String(e);
    error.value =
      e instanceof ApiError
        ? errorText(e.code)
        : String((e as Error).message ?? e);
  } finally {
    busy.value = false;
  }
}
async function sync() {
  await api("/health");
  connected.value = true;
  const session = await api<{
    profile: { version: number } | null;
    providerMode: string;
  }>("/session");
  backendMode.value = session.providerMode;
  if (session.profile)
    profile.value = await api<CurrentProfile>("/profile/current");
  else profile.value = null;
  const latest = await api<Rules>("/rules/current");
  acceptRules(latest, !rules.value);
  capabilities.value = await api("/rules/capabilities");
  analyses.value = await api<Analysis[]>("/analyses");
  if (analysis.value) {
    const token = fence.token(),
      id = analysis.value.id;
    const result = await api<Analysis>(`/analyses/${id}`);
    if (fence.valid(token) && analysis.value?.id === id) {
      analysis.value = result;
      await loadEvidence(id);
    }
  }
}
async function reloadRules() {
  const latest = await api<Rules>("/rules/current");
  rules.value = null;
  acceptRules(latest);
  notice.value = "已加载后端最新配置。";
}
async function historyPage(more = false) {
  const token = fence.token();
  const cursor = more ? history.value?.nextAfterSequence : null;
  const query = new URLSearchParams({ limit: "20" });
  if (cursor) query.set("afterSequence", String(cursor));
  if (more && history.value)
    query.set("expectedGeneration", String(history.value.generation));
  const result = await api<History>(`/chat/history?${query}`);
  if (!fence.valid(token)) return;
  if (more && history.value?.generation !== result.generation) return;
  history.value = result;
  turns.value = more ? [...turns.value, ...result.turns] : result.turns;
}
async function restore() {
  scope.value = await message<string>({ type: "scope" });
  const saved = await browser.storage.local.get([
    "apiBase",
    "panelWidth",
    `tab:${scope.value}`,
  ]);
  panelWidth.value = Number(saved.panelWidth) || 520;
  base.value = String(saved.apiBase ?? base.value);
  setApiBase(base.value);
  const tab = saved[`tab:${scope.value}`] as
    | {
        apiBase?: string;
        snapshot?: Snapshot;
        analysis?: Analysis;
        pendingAnalysis?: AnalysisInput;
        pendingChat?: ChatInput;
        importId?: string;
        url?: string;
      }
    | undefined;
  if (tab && tab.apiBase === base.value) {
    const samePage = !props.embedded || tab.url === location.href;
    snapshot.value = samePage ? (tab.snapshot ?? null) : null;
    analysis.value = samePage ? (tab.analysis ?? null) : null;
    editJob.value = snapshot.value
      ? JSON.parse(JSON.stringify(snapshot.value.job))
      : emptyJob();
    pendingAnalysis.value = tab.pendingAnalysis ?? null;
    pendingChat.value = tab.pendingChat ?? null;
    if (tab.importId)
      imported.value = await api<ImportView>(
        `/profile/imports/${tab.importId}`,
      );
  }
  await sync();
  if (!imported.value && profile.value?.resume)
    imported.value = profile.value.resume;
  await historyPage();
}
async function connect() {
  const old = await browser.storage.local.get("apiBase");
  if (old.apiBase !== base.value) {
    fence.advance();
    snapshot.value = null;
    analysis.value = null;
    pendingAnalysis.value = null;
    pendingChat.value = null;
    imported.value = null;
    draft.value = null;
    profile.value = null;
    boundEvidence.value = {};
    lastTurn.value = null;
    history.value = null;
    turns.value = [];
  }
  setApiBase(base.value);
  await message({ type: "configure", base: base.value });
  if (old.apiBase !== base.value) rules.value = null;
  await sync();
  await historyPage();
  if (!imported.value && profile.value?.resume)
    imported.value = profile.value.resume;
  await saveTab();
}
async function loadFacts() {
  if (!imported.value) return;
  const facts = await api<{ operation: ImportView; facts: Draft | null }>(
    `/profile/imports/${imported.value.id}/facts`,
  );
  imported.value = facts.operation;
  draft.value = facts.facts;
  structured.value = JSON.stringify(
    facts.facts?.structuredFacts ?? [],
    null,
    2,
  );
  await saveTab();
}
async function fileChosen(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  await work(async () => {
    const old = imported.value?.id;
    imported.value = await upload(file);
    await saveTab();
    notice.value =
      old === imported.value.id || imported.value.status === "READY"
        ? "重复文件，复用已有导入记录"
        : "已通过扩展后台上传并完成文本提取";
    await loadFacts();
  });
}
async function importAction(action: string) {
  if (!imported.value) return;
  imported.value = await api<ImportView>(
    `/profile/imports/${imported.value.id}/${action}`,
    "POST",
    action === "confirm"
      ? { expectedRevision: imported.value.revision, confirmed: true }
      : undefined,
  );
  await loadFacts();
  await sync();
}
async function saveFacts() {
  if (!imported.value || !draft.value) return;
  const value = JSON.parse(structured.value);
  if (!Array.isArray(value)) throw new Error("结构化事实必须为数组");
  draft.value.structuredFacts = value;
  imported.value = await api<ImportView>(
    `/profile/imports/${imported.value.id}/facts`,
    "PUT",
    { expectedRevision: imported.value.revision, facts: draft.value },
  );
  await loadFacts();
  notice.value = "事实修正已保存，请检查后确认发布";
}
async function saveRules() {
  if (!rules.value) return;
  const config = normalizedConfig(rules.value.config);
  await api("/rules/validate", "POST", {
    expectedVersion: rules.value.version,
    config,
  });
  const saved = await api<Rules>("/rules", "PUT", {
    expectedVersion: rules.value.version,
    config,
  });
  rules.value = null;
  acceptRules(saved);
  await sync();
  notice.value = "已保存，下次自动使用。旧分析会按原有版本检查过期状态。";
}
function changeJob(job: Job) {
  fence.advance();
  editJob.value = structuredClone(job);
  snapshot.value = null;
  analysis.value = null;
  lastTurn.value = null;
  question.value = "";
  compare.value = "";
  void saveTab();
}
async function readPage() {
  const token = fence.token();
  const job = await capture();
  if (!fence.valid(token)) return;
  changeJob(job);
  notice.value = "只读采集完成，请预览并修正后保存";
}
function exportCapture() {
  const blob = new Blob(
    [
      JSON.stringify(
        {
          capturedAt: new Date().toISOString(),
          pageUrl: location.href,
          job: editJob.value,
        },
        null,
        2,
      ),
    ],
    { type: "application/json" },
  );
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "boss-capture-preview.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function sample() {
  const job = fixtures.cases.find(
    (item) => item.caseId === sampleId.value,
  )?.job;
  if (job) changeJob({ ...job, simulated: true } as Job);
}
async function saveJob() {
  const token = fence.advance();
  const payload = JSON.parse(JSON.stringify(editJob.value));
  for (const key of ["internshipDays", "internshipMonths"])
    if (payload[key] === "") payload[key] = null;
  const result = await api<Snapshot>("/jobs", "POST", payload);
  if (fence.valid(token)) {
    snapshot.value = result;
    analysis.value = null;
    await saveTab();
    notice.value = "不可变岗位快照已保存";
  }
}
async function analyze(retry = false) {
  if (!retry) {
    if (rulesDirty.value)
      throw new Error(
        "有未保存的规则修改，请先保存设置，或加载后端最新配置后再分析。",
      );
    if (!snapshot.value || !profile.value || serverRuleVersion.value === null)
      throw new Error("请先确认画像、保存岗位并读取规则");
    pendingAnalysis.value = {
      clientRequestId: crypto.randomUUID(),
      jobSnapshotId: snapshot.value.id,
      expectedProfileVersion: profile.value.profile.identity.profileVersion,
      expectedRuleVersion: serverRuleVersion.value,
    };
    await saveTab();
  }
  const input = pendingAnalysis.value;
  if (!input) return;
  const token = fence.token();
  const result = await api<Analysis>("/analyses", "POST", input);
  if (fence.valid(token) && snapshot.value?.id === input.jobSnapshotId) {
    analysis.value = result;
    await saveTab();
  }
  analyses.value = await api<Analysis[]>("/analyses");
}
async function recoverAnalysis() {
  if (!pendingAnalysis.value) return;
  const all = await api<Analysis[]>("/analyses");
  const result = all.find(
    (item) => item.clientRequestId === pendingAnalysis.value?.clientRequestId,
  );
  if (result && snapshot.value?.id === result.jobSnapshotId) {
    analysis.value = result;
    await saveTab();
  } else notice.value = "尚未查到该请求，可用原标识重试";
}
async function selectAnalysis(event: Event) {
  const id = (event.target as HTMLSelectElement).value;
  if (!id) return;
  const token = fence.advance();
  const result = await api<Analysis>(`/analyses/${id}`);
  const job = await api<Snapshot>(`/jobs/${result.jobSnapshotId}`);
  if (fence.valid(token)) {
    analysis.value = result;
    snapshot.value = job;
    editJob.value = JSON.parse(JSON.stringify(job.job));
    lastTurn.value = null;
    question.value = "";
    compare.value = "";
    await saveTab();
  }
}
async function chat(retry = false) {
  if (!retry) {
    if (!analysis.value || analysis.value.stale)
      throw new Error("请先选择未过期的成功分析");
    const current = await api<History>("/chat/history?limit=1");
    history.value = current;
    pendingChat.value = {
      clientRequestId: crypto.randomUUID(),
      jobSnapshotId: analysis.value.jobSnapshotId,
      analysisId: analysis.value.id,
      question: question.value,
      expectedGeneration: current.generation,
      comparisonAnalysisIds: compare.value ? [compare.value] : [],
    };
    await saveTab();
  }
  const input = pendingChat.value;
  if (!input) return;
  const token = fence.token();
  const result = await api<Turn>("/chat/messages", "POST", input);
  if (fence.valid(token) && analysis.value?.id === input.analysisId) {
    lastTurn.value = result;
    if (result.generation === history.value?.generation) {
      await historyPage();
      if (result.status === "SUCCEEDED") question.value = "";
    }
  }
}
async function queryChat() {
  if (lastTurn.value) {
    const token = fence.token();
    const result = await api<Turn>(`/chat/messages/${lastTurn.value.id}`);
    if (fence.valid(token)) {
      lastTurn.value = result;
      if (result.generation === history.value?.generation) await historyPage();
    }
  } else await chat(true);
}
async function clearChat() {
  if (!history.value) throw new Error("请先刷新聊天历史并核对代次");
  const expectedGeneration = history.value.generation;
  fence.advance();
  history.value = await api<History>("/chat", "DELETE", { expectedGeneration });
  turns.value = [];
  lastTurn.value = null;
  pendingChat.value = null;
  confirmClear.value = false;
  await saveTab();
  notice.value = "所有岗位的聊天已清空，画像、规则、岗位和分析保留";
}
function routeChanged() {
  fence.advance();
  snapshot.value = null;
  analysis.value = null;
  editJob.value = emptyJob();
  lastTurn.value = null;
  notice.value = "页面已变化，请重新采集当前岗位。原请求可通过历史恢复。";
  void saveTab();
}
onMounted(() => {
  void work(restore);
  window.addEventListener("ai-job-route", routeChanged);
  poll = setInterval(() => {
    void sync().catch(() => {
      connected.value = false;
    });
  }, 15000);
});
onUnmounted(() => {
  if (poll) clearInterval(poll);
  window.removeEventListener("ai-job-route", routeChanged);
});
</script>

<template>
  <button v-if="embedded && !open" class="launcher" @click="open = true">
    AI 求职
  </button>
  <aside
    v-show="open"
    class="panel"
    :class="{ embedded, expanded, chatting: section === '聊天' }"
    :style="
      embedded
        ? {
            width: expanded
              ? 'min(100vw, 1000px)'
              : `min(100vw, ${panelWidth}px)`,
          }
        : {}
    "
  >
    <div
      v-if="embedded"
      class="resize-handle"
      @pointerdown.prevent="resizePanel"
      role="separator"
      aria-label="调整面板宽度"
      tabindex="0"
      @keydown.left.prevent="panelWidth = Math.min(1600, panelWidth + 20)"
      @keydown.right.prevent="panelWidth = Math.max(320, panelWidth - 20)"
    />
    <header>
      <div><b>求职助手</b><span class="subtitle">本机求职工作区</span></div>
      <button @click="workspace" title="打开独立工作区">工作区 ↗</button>
      <button
        v-if="embedded"
        @click="expanded = !expanded"
        :aria-label="expanded ? '恢复面板宽度' : '放大阅读'"
      >
        {{ expanded ? "还原" : "放大" }}
      </button>
      <button v-if="embedded" @click="open = false" aria-label="折叠面板">
        收起
      </button>
    </header>
    <div class="status">
      <span :class="connected ? 'good' : 'bad'">{{
        connected ? "后端已连接" : "后端未连接"
      }}</span
      ><span
        >画像 v{{ profile?.profile.identity.profileVersion ?? "未就绪" }}</span
      ><span>规则 v{{ serverRuleVersion ?? "—" }}</span>
    </div>
    <div class="context">
      当前岗位 {{ snapshot?.job.title ?? editJob.title ?? "未选择" }}
      <small>{{ snapshot?.job.company || editJob.company }}</small>
      <small v-if="savedRules"
        >已保存设置：{{ settingsSummary(savedRules.config) }}</small
      >
      <small v-if="rulesDirty" class="warning"
        >有未保存的修改，请前往规则设置保存后再分析。</small
      >
    </div>
    <div v-if="testMode" class="banner">
      测试模式 · 使用测试回复，结果仅供流程体验
    </div>
    <div v-if="backendMode === 'REAL'" class="real-mode">
      真实服务 · 使用已确认简历
    </div>
    <nav>
      <button
        v-for="name in ['岗位', '分析', '聊天', '简历', '规则']"
        :key="name"
        :class="{ active: section === name }"
        @click="section = name"
      >
        {{ name }}
      </button>
    </nav>
    <main>
      <p v-if="error" class="error" role="alert">
        {{ error }}
      </p>
      <details v-if="technicalError">
        <summary>技术详情</summary>
        <pre>{{ technicalError }}</pre>
      </details>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <details
        v-if="section === '岗位' || section === '简历' || section === '规则'"
      >
        <summary>连接与恢复</summary>
        <label
          >可信本机API<select v-model="base">
            <option>http://127.0.0.1:18085/api</option>
            <option>http://127.0.0.1:18080/api</option>
          </select></label
        ><button @click="work(connect)">连接 / 刷新共享状态</button
        ><small>加载扩展后，后端需精确允许 chrome-extension://扩展ID。</small>
      </details>
      <section v-if="section === '简历'">
        <h2>候选人画像</h2>
        <p>
          当前简历 {{ profile?.resume?.fileName ?? "尚未导入" }} · 有效版本
          {{ profile?.profile.identity.profileVersion ?? "无" }}
        </p>
        <label
          >上传 PDF（最大10 MiB）<input
            data-testid="pdf"
            type="file"
            accept=".pdf,application/pdf"
            :disabled="busy"
            @change="fileChosen"
        /></label>
        <article v-if="imported">
          <b>{{ imported.fileName }}</b>
          <p>
            导入状态 {{ display(imported.status) }} · 修订
            {{ imported.revision }} ·
            {{ imported.providerMode === "TEST" ? "测试生成画像" : "真实处理" }}
          </p>
          <p class="error" v-if="imported.errorCode">
            {{ errorText(imported.errorCode) }}
          </p>
          <div class="actions">
            <button @click="work(loadFacts)">查询状态 / 检查事实</button
            ><button
              v-if="
                ['TEXT_READY', 'FAILED_STRUCTURE'].includes(imported.status)
              "
              @click="work(() => importAction('structure'))"
              :disabled="busy"
            >
              结构化事实</button
            ><button
              v-if="imported.status.startsWith('FAILED')"
              @click="work(() => importAction('retry'))"
            >
              重试导入
            </button>
          </div>
        </article>
        <template v-if="draft"
          ><h3>检查并修正事实</h3>
          <fieldset v-for="(fact, index) in draft.retrievalFacts" :key="index">
            <legend>{{ display(fact.factType) }}</legend>
            <label
              >事实内容<textarea
                v-model="fact.normalizedText"
                rows="3"
              /></label
            ><label
              >简历原文证据<textarea
                v-model="fact.source.sourceText"
                rows="2"
              />
            </label>
          </fieldset>
          <details>
            <summary>结构化事实 JSON</summary>
            <textarea v-model="structured" rows="8" />
          </details>
          <button
            @click="work(saveFacts)"
            :disabled="busy || imported?.status === 'READY'"
          >
            保存事实修正
          </button>
          <p>确认将构建必要索引并切换当前画像，已有画像在成功前仍可用。</p>
          <button
            v-if="
              ['AWAITING_CONFIRMATION', 'FAILED_INDEX'].includes(
                imported?.status ?? '',
              )
            "
            @click="work(() => importAction('confirm'))"
            :disabled="busy"
          >
            确认事实并发布画像
          </button></template
        >
        <details>
          <summary>扫描件或提取失败的文本补救</summary>
          <textarea
            v-model="remediation"
            placeholder="粘贴本人核对后的简历文本"
            rows="5"
          /><button
            @click="
              work(async () => {
                imported = await api('/profile/imports/text', 'POST', {
                  fileName: imported?.fileName ?? '补救简历',
                  originalImportId: imported?.id,
                  text: remediation,
                });
                await loadFacts();
              })
            "
          >
            保存为用户补救文本</button
          ><small>来源会标记为用户核对后的补救文本。</small>
        </details>
      </section>
      <section v-if="section === '规则' && rules">
        <h2>筛选设置</h2>
        <p v-if="savedRules">
          已保存：{{ settingsSummary(savedRules.config) }}
        </p>
        <p v-if="rulesDirty" class="warning" role="status">
          有未保存的修改。分析使用最后成功保存的规则，请先保存设置。
        </p>
        <p v-else class="good" role="status">
          {{ rules.version > 0 ? "已保存，下次自动使用" : "尚未保存个人配置" }}
        </p>
        <p v-if="rules.version === 0">
          根据简历预填，可随时修改。建议尚未保存，不是新增的已确认简历事实。
        </p>
        <p v-if="rules.version !== serverRuleVersion" class="warning">
          其他页面已更新规则，本地编辑已保留。请先备份需要的内容，再加载最新配置处理。
        </p>
        <button @click="suggestRules">使用建议配置</button>
        <details open>
          <summary>完整规则设置</summary>
          <p>
            表单基于 v{{ rules.version }} · 服务器当前 v{{ serverRuleVersion }}
          </p>
          <h3>候选人可提供条件</h3>
          <label
            >每周可出勤天数<input
              type="number"
              min="1"
              max="7"
              v-model.number="
                rules.config.candidate.availableDaysPerWeek
              " /></label
          ><label
            >可承诺周期（月）<input
              type="number"
              min="1"
              max="60"
              v-model.number="rules.config.candidate.availableMonths" /></label
          ><label
            >可到岗日期（仅保存，比较需人工确认）<input
              type="date"
              v-model="rules.config.candidate.availableFrom"
          /></label>
          <label
            >接受城市（逗号分隔）<input
              :value="rules.config.criteria.cities.join(',')"
              @input="
                rules.config.criteria.cities = (
                  $event.target as HTMLInputElement
                ).value
                  .split(/[,，]/)
                  .map((s) => s.trim())
                  .filter(Boolean)
              "
          /></label>
          <label
            >目标方向<select
              aria-label="目标方向"
              multiple
              v-model="rules.config.criteria.directions"
            >
              <option value="JAVA_BACKEND">Java后端</option>
              <option value="AI_APPLICATION">AI应用</option>
              <option value="OTHER">其他明确方向</option>
            </select></label
          >
          <div class="columns">
            <label
              >最低薪资<input
                type="number"
                min="0"
                v-model.number="rules.config.criteria.salaryMin" /></label
            ><label
              >最高薪资<input
                type="number"
                min="0"
                v-model.number="rules.config.criteria.salaryMax"
            /></label>
          </div>
          <label
            >人民币单位<select v-model="rules.config.criteria.salaryUnit">
              <option :value="null">未配置</option>
              <option value="DAY">元/天</option>
              <option value="MONTH">元/月</option>
            </select></label
          ><small>日薪与月薪不换算，条件不明时待确认。</small>
          <label
            >排除词（逗号分隔）<input
              :value="rules.config.criteria.excludedKeywords.join(',')"
              @input="
                rules.config.criteria.excludedKeywords = (
                  $event.target as HTMLInputElement
                ).value
                  .split(/[,，]/)
                  .map((s) => s.trim())
                  .filter(Boolean)
              "
          /></label>
          <label v-for="(_, key) in rules.config.modes" :key="key"
            >{{ labels[key] ?? key }}属性<select
              :aria-label="`${labels[key] ?? key}属性`"
              v-model="rules.config.modes[key]"
            >
              <option v-for="strength in strengthOptions" :value="strength">
                {{
                  strength === "HARD"
                    ? "硬约束"
                    : strength === "SOFT"
                      ? "软偏好"
                      : "未启用"
                }}
              </option>
            </select></label
          >
          <label
            >软偏好（每行一项）<textarea
              :value="rules.config.softNotes.join('\n')"
              @input="
                rules.config.softNotes = (
                  $event.target as HTMLTextAreaElement
                ).value
                  .split('\n')
                  .filter(Boolean)
              "
            />
          </label>
          <div class="actions">
            <button class="primary" @click="work(saveRules)" :disabled="busy">
              保存设置</button
            ><button @click="work(reloadRules)">
              加载最新配置（放弃本地修改）
            </button>
          </div>
        </details>
        <details>
          <summary>技术详情 · 规则支持边界</summary>
          <pre>{{ JSON.stringify(capabilities, null, 2) }}</pre>
        </details>
      </section>
      <section v-if="section === '岗位'">
        <h2>岗位采集与预览</h2>
        <button @click="work(readPage)" :disabled="busy">读取当前页面</button>
        <p>打开岗位详情后读取，核对内容再保存。也可以手动粘贴完整岗位描述。</p>
        <details>
          <summary>固定模拟岗位</summary>
          <select v-model="sampleId">
            <option v-for="sample in fixtures.cases" :value="sample.caseId">
              模拟 · {{ sample.caseId }}
            </option></select
          ><button @click="sample">载入模拟岗位</button>
        </details>
        <p v-if="editJob.simulated" class="banner">
          模拟数据 · {{ editJob.sourceJobId }}
        </p>
        <button @click="exportCapture" :disabled="!editJob.title">
          导出采集预览
        </button>
        <details>
          <summary>技术详情 · 采集来源</summary>
          <p>
            采集方式
            {{
              editJob.captureMethod === "VUE_READ_ONLY"
                ? "页面详情"
                : editJob.captureMethod === "DOM"
                  ? "页面文本"
                  : "手动输入"
            }}
            · 来源岗位 ID {{ editJob.sourceJobId || "未知" }}
          </p>
        </details>
        <p>技能 {{ editJob.skills?.join("、") || "未知" }}</p>
        <small>空白字段表示未知，请对照页面核对。切换岗位后需重新采集。</small>
        <label>岗位名称<input v-model="editJob.title" maxlength="255" /></label
        ><label>公司<input v-model="editJob.company" /></label
        ><label>城市<input v-model="editJob.city" /></label
        ><label>详细地点<input v-model="editJob.location" /></label
        ><label>薪资原文<input v-model="editJob.salary" /></label>
        <div class="columns">
          <label>学历<input v-model="editJob.education" /></label
          ><label>经验<input v-model="editJob.experience" /></label>
        </div>
        <label
          >明确方向<select v-model="editJob.direction">
            <option value="">未确认</option>
            <option value="JAVA_BACKEND">Java后端</option>
            <option value="AI_APPLICATION">AI应用</option>
            <option value="OTHER">其他</option>
          </select></label
        >
        <div class="columns">
          <label
            >每周最低天数<input
              type="number"
              min="1"
              max="7"
              v-model.number="editJob.internshipDays" /></label
          ><label
            >最低月数<input
              type="number"
              min="1"
              max="60"
              v-model.number="editJob.internshipMonths"
          /></label>
        </div>
        <label>周期原文<input v-model="editJob.duration" /></label
        ><label
          >原始 JD<textarea
            v-model="editJob.description"
            rows="9"
            maxlength="50000"
            placeholder="手动粘贴完整JD"
          /></label
        ><button
          @click="work(saveJob)"
          :disabled="busy || !editJob.title || !editJob.description"
        >
          保存岗位快照
        </button>
      </section>
      <section v-if="section === '分析'" class="analysis-section">
        <details class="analysis-actions">
          <summary>分析操作与历史记录</summary>
          <select @change="work(() => selectAnalysis($event))">
            <option value="">选择已保存的分析</option>
            <option v-for="item in analyses" :value="item.id">
              {{ display(item.status) }} · 画像版本
              {{ item.dependencies.profileArtifactIdentity.profileVersion
              }}{{ item.stale ? " · 已过期" : "" }}
            </option>
          </select>
          <button @click="work(() => analyze())" :disabled="busy || !snapshot">
            提交当前岗位分析
          </button>
          <div v-if="pendingAnalysis" class="actions">
            <button @click="work(recoverAnalysis)">查询原分析请求</button
            ><button @click="work(() => analyze(true))">
              使用同一请求重试
            </button>
          </div>
        </details>
        <div v-if="!analysis">
          <p>保存岗位后即可分析，也可以展开下方历史记录恢复已有结果。</p>
          <button
            class="primary"
            @click="work(() => analyze())"
            :disabled="busy || !snapshot"
          >
            开始分析当前岗位
          </button>
        </div>
        <article v-if="analysis">
          <AnalysisView
            :analysis="analysis"
            :facts="analysisFacts"
            :job="snapshot?.job"
            @ask="section = '聊天'"
            @rules="section = '规则'"
            @reanalyze="work(() => analyze())"
          />
        </article>
      </section>
      <ChatView
        v-if="section === '聊天'"
        :analysis="analysis"
        :snapshot="snapshot"
        :turns="turns"
        :history="history"
        :busy="busy"
        :question="question"
        :compare="compare"
        :analyses="analyses"
        :pending="!!pendingChat"
        :confirm-clear="confirmClear"
        :evidence="boundEvidence"
        @send="work(() => chat())"
        @retry="work(() => chat(true))"
        @recover="work(queryChat)"
        @refresh="work(() => historyPage())"
        @more="work(() => historyPage(true))"
        @clear="work(clearChat)"
        @confirm="confirmClear = $event"
        @question="question = $event"
        @compare="compare = $event"
        @evidence="
          loadEvidence($event).catch(() => {
            error = '暂时无法读取该分析绑定的证据，请稍后重试。';
          })
        "
      />
      <p v-if="busy" class="notice">正在处理，请等待；不要重复创建请求。</p>
      <footer v-if="section !== '聊天'">
        <a
          :href="browser.runtime.getURL('/boss-helper-LICENSE.txt')"
          target="_blank"
          rel="noreferrer"
          >岗位读取参考许可 · MIT</a
        >
      </footer>
    </main>
  </aside>
</template>
