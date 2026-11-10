<script setup lang="ts">
import { ref, computed, watch, nextTick } from "vue";
import type { Analysis, Snapshot, Turn, History, Fact } from "../lib/contracts";
import {
  display,
  errorText,
  shouldSend,
  nearBottom,
} from "../lib/presentation";
import SafeMarkdown from "./SafeMarkdown.vue";
const props = defineProps<{
  analysis: Analysis | null;
  snapshot: Snapshot | null;
  turns: Turn[];
  history: History | null;
  busy: boolean;
  question: string;
  compare: string;
  analyses: Analysis[];
  pending: boolean;
  confirmClear: boolean;
  evidence: Record<string, Fact[]>;
}>();
const emit = defineEmits<{
  send: [];
  retry: [];
  recover: [];
  refresh: [];
  more: [];
  clear: [];
  confirm: [value: boolean];
  question: [value: string];
  compare: [value: string];
  evidence: [analysisId: string];
}>();
const list = ref<HTMLElement>();
const input = ref<HTMLTextAreaElement>();
const pinned = ref(true);
const unread = ref(false);
const all = ref(false);
const copied = ref("");
const shown = computed(() =>
  all.value
    ? props.turns
    : props.turns.filter(
        (t) => t.question?.jobSnapshotId === props.snapshot?.id,
      ),
);
const valid = computed(
  () =>
    props.analysis &&
    !props.analysis.stale &&
    props.analysis.status === "SUCCESS",
);
function latest() {
  if (list.value) list.value.scrollTop = list.value.scrollHeight;
  pinned.value = true;
  unread.value = false;
}
watch(
  () => props.turns.map((t) => t.id + t.status).join(","),
  async () => {
    const stick = pinned.value;
    await nextTick();
    if (stick) latest();
    else unread.value = true;
  },
);
watch(
  () => props.snapshot?.id,
  async () => {
    all.value = false;
    await nextTick();
    latest();
  },
);
function resize() {
  if (input.value) {
    input.value.style.height = "auto";
    input.value.style.height = Math.min(input.value.scrollHeight, 180) + "px";
  }
}
watch(
  () => props.question,
  () => nextTick(resize),
);
function send() {
  if (!props.busy && valid.value && props.question.trim()) emit("send");
}
function keydown(e: KeyboardEvent) {
  if (shouldSend(e)) {
    e.preventDefault();
    send();
  }
}
async function copy(text: string, id: string) {
  try {
    await navigator.clipboard.writeText(text);
    copied.value = id;
  } catch {
    copied.value = "failed";
  }
}
function fact(ref: string) {
  const slash = ref.indexOf("/");
  return props.evidence[ref.slice(0, slash)]?.find(
    (f) => f.factId === ref.slice(slash + 1),
  );
}
</script>
<template>
  <section class="chat-layout">
    <div class="chat-heading">
      <small>{{ snapshot?.job.company || "当前岗位" }}</small>
      <h2>{{ snapshot?.job.title || "先选择一个岗位" }}</h2>
      <span class="badge">{{
        !analysis
          ? "等待分析"
          : analysis.stale
            ? "分析已过期"
            : display(analysis.status)
      }}</span>
    </div>
    <div class="chat-tools">
      <button @click="emit('refresh')">刷新历史</button
      ><button @click="all = !all">
        {{ all ? "返回当前岗位" : "查看共享历史" }}</button
      ><button @click="emit('confirm', true)">清空历史</button>
    </div>
    <p v-if="copied === 'failed'" role="status">
      复制失败，请选择回复文字手动复制。
    </p>
    <div v-if="confirmClear" class="warning">
      <p>将清空所有岗位的共享聊天历史。简历、规则和分析记录保留。</p>
      <button @click="emit('clear')">确认清空所有聊天</button
      ><button @click="emit('confirm', false)">取消</button>
    </div>
    <div
      ref="list"
      class="message-list"
      tabindex="0"
      aria-label="聊天消息"
      @scroll="pinned = list ? nearBottom(list) : true"
    >
      <div v-if="!shown.length" class="chat-empty">
        <h2>一起看看这个岗位</h2>
        <p>从适合程度、主要缺口或面试准备开始。</p>
        <button
          v-for="q in [
            '这个岗位适合我吗？',
            '主要缺口是什么？',
            '如何准备面试？',
          ]"
          @click="emit('question', q)"
        >
          {{ q }}
        </button>
      </div>
      <div v-for="turn in shown" :key="turn.id" class="conversation">
        <small v-if="all">{{
          turn.question?.jobSnapshotId === snapshot?.id
            ? "当前岗位"
            : "其他岗位的历史对话"
        }}</small>
        <div class="user-message">{{ turn.question?.content }}</div>
        <div class="assistant-message">
          <span class="eyebrow">求职助手</span
          ><SafeMarkdown v-if="turn.reply" :text="turn.reply.content" />
          <p v-else>
            {{
              turn.errorCode ? errorText(turn.errorCode) : display(turn.status)
            }}
          </p>
          <button
            v-if="turn.reply"
            class="text-button"
            @click="copy(turn.reply.content, turn.id)"
          >
            {{ copied === turn.id ? "已复制" : "复制回复" }}
          </button>
          <details
            v-for="ref in turn.reply?.evidenceRefs"
            class="evidence-chip"
            @toggle="emit('evidence', ref.slice(0, ref.indexOf('/')))"
          >
            <summary>简历证据</summary>
            <p>{{ fact(ref)?.normalizedText || "读取绑定版本的证据…" }}</p>
            <blockquote>{{ fact(ref)?.source.sourceText }}</blockquote>
          </details>
          <details class="technical">
            <summary>技术详情</summary>
            <pre>{{ JSON.stringify(turn, null, 2) }}</pre>
          </details>
        </div>
      </div>
      <button v-if="history?.hasMore" @click="emit('more')">
        加载下一页历史
      </button>
    </div>
    <button v-if="unread" class="latest" @click="latest">回到最新消息 ↓</button>
    <div class="composer">
      <p v-if="!valid" class="muted">
        {{
          analysis?.stale
            ? "分析已过期，请重新分析后继续提问。"
            : "请先完成当前岗位分析，再开始提问。"
        }}
      </p>
      <label class="sr-only" for="chat-question">问题</label
      ><textarea
        id="chat-question"
        ref="input"
        :value="question"
        @input="emit('question', ($event.target as HTMLTextAreaElement).value)"
        @keydown="keydown"
        rows="2"
        maxlength="2000"
        placeholder="继续问问这个岗位…"
      />
      <div class="composer-actions">
        <small>Enter 发送 · Shift+Enter 换行</small
        ><button
          class="primary"
          @click="send"
          :disabled="busy || !valid || !question.trim()"
        >
          {{ busy ? "正在处理…" : "发送问题" }}
        </button>
      </div>
      <details>
        <summary>更多操作与岗位比较</summary>
        <label
          >显式跨岗位比较<select
            :value="compare"
            @change="
              emit('compare', ($event.target as HTMLSelectElement).value)
            "
          >
            <option value="">仅围绕当前岗位</option>
            <option
              v-for="(item, index) in analyses.filter(
                (a) =>
                  a.id !== analysis?.id && !a.stale && a.status === 'SUCCESS',
              )"
              :value="item.id"
            >
              已保存分析 {{ index + 1 }} · 画像版本
              {{ item.dependencies.profileArtifactIdentity.profileVersion }}
            </option>
          </select></label
        ><button v-if="pending" @click="emit('recover')">
          查询或恢复原请求</button
        ><button v-if="pending" @click="emit('retry')">使用同一请求重试</button>
        <details>
          <summary>技术详情</summary>
          <p>聊天代次 {{ history?.generation }}</p>
          <p>当前分析 {{ analysis?.id }}</p>
        </details>
      </details>
    </div>
  </section>
</template>
