<script setup lang="ts">
import { computed, ref } from "vue";
import type { Analysis, Fact, Job } from "../lib/contracts";
import {
  display,
  ruleLabel,
  ruleSummary,
  errorText,
  ruleValue,
} from "../lib/presentation";
const props = defineProps<{ analysis: Analysis; facts: Fact[]; job?: Job }>();
defineEmits<{ ask: []; rules: []; reanalyze: [] }>();
const result = computed(() => props.analysis.execution?.result);
const terminal = computed(
  () => props.analysis.execution?.resultOrigin === "RULE_TERMINAL",
);
const rejected = computed(
  () =>
    props.analysis.ruleResult?.items.filter(
      (i) => i.strength === "HARD" && i.verdict === "UNSATISFIED",
    ) ?? [],
);
const gaps = computed(
  () =>
    result.value?.requirementAssessments
      .filter((i) => i.gap)
      .map((i) => i.gap) ?? [],
);
const copyStatus = ref("");
async function copy() {
  try {
    await navigator.clipboard.writeText(
      JSON.stringify(props.analysis, null, 2),
    );
    copyStatus.value = "已复制";
  } catch {
    copyStatus.value = "复制失败，请选择下方文字手动复制";
  }
}
</script>
<template>
  <div class="analysis-view">
    <div class="job-heading">
      <small
        >{{ job?.company || "公司待确认" }} ·
        {{ job?.city || "城市待确认" }}</small
      >
      <h2>{{ job?.title || "已保存岗位" }}</h2>
      <small
        >{{ job?.salary || "薪资待确认" }} · 画像版本
        {{
          analysis.dependencies.profileArtifactIdentity.profileVersion
        }}</small
      >
    </div>
    <p v-if="analysis.stale" class="warning">
      分析已过期，画像或规则已更新。请重新分析。<button
        @click="$emit('reanalyze')"
      >
        重新分析
      </button>
    </p>
    <div v-if="terminal" class="decision-box warning">
      <h2>岗位不符合已启用的硬性条件</h2>
      <p>本次依据规则结束，未进行 AI 分析</p>
      <p v-for="item in rejected">
        {{ display(item.field) }}：你的条件
        {{ ruleValue(item.candidateValue) }}，岗位要求
        {{ ruleValue(item.jobValue) }}。{{ item.reason }}
      </p>
      <button @click="$emit('rules')">修改规则</button
      ><button @click="$emit('reanalyze')">重新分析</button>
    </div>
    <div v-else-if="result" class="decision-box">
      <span class="eyebrow"
        >{{ display(analysis.execution?.resultOrigin) }} ·
        {{ display(analysis.reuseSource) }}</span
      >
      <h1>{{ display(result.decision) }}</h1>
      <p>{{ result.overallAssessment }}</p>
    </div>
    <p v-else class="notice">
      {{ display(analysis.status)
      }}<span v-if="analysis.errorCode">
        · {{ errorText(analysis.errorCode) }}</span
      >
    </p>
    <template v-if="result && !terminal"
      ><div class="insight-grid">
        <section>
          <h3>主要优势</h3>
          <ul v-if="result.strengths.length">
            <li v-for="item in result.strengths">{{ item }}</li>
          </ul>
          <p v-else>当前结果未列出明确优势。</p>
        </section>
        <section>
          <h3>主要缺口与风险</h3>
          <ul v-if="gaps.length || result.risks.length">
            <li v-for="item in [...new Set([...gaps, ...result.risks])]">
              {{ item }}
            </li>
          </ul>
          <p v-else>当前结果未列出明确缺口。</p>
        </section>
      </div>
      <h3>下一步建议</h3>
      <p>{{ result.recommendation }}</p></template
    >
    <button class="primary" @click="$emit('ask')">围绕这个岗位提问 →</button>
    <details v-if="result && !terminal">
      <summary>
        逐项要求与简历证据 · {{ result.requirementAssessments.length }} 项
      </summary>
      <div
        v-for="(item, index) in result.requirementAssessments"
        class="requirement"
      >
        <h3>
          岗位要求 {{ index + 1 }}
          <span class="badge">{{ display(item.status) }}</span>
        </h3>
        <p>{{ item.reasoning }}</p>
        <p v-if="item.gap" class="muted">待补充：{{ item.gap }}</p>
        <details v-if="item.evidenceRefs.length">
          <summary>查看 {{ item.evidenceRefs.length }} 条绑定简历证据</summary>
          <div v-for="id in item.evidenceRefs">
            <p>
              {{
                facts.find((f) => f.factId === id)?.normalizedText ||
                "该证据暂不可用"
              }}
            </p>
            <blockquote>
              {{ facts.find((f) => f.factId === id)?.source.sourceText }}
            </blockquote>
          </div>
        </details>
        <small v-else>暂无可引用的简历证据</small>
      </div>
    </details>
    <details>
      <summary>{{ ruleSummary(analysis.ruleResult?.items) }}</summary>
      <div v-for="item in analysis.ruleResult?.items" class="rule">
        <b
          >{{ display(item.field) }} · {{ ruleLabel(item) }} ·
          {{ display(item.strength) }}</b
        ><template v-if="item.strength !== 'DISABLED'"
          ><p>
            你的条件 {{ ruleValue(item.candidateValue) }} ／ 岗位要求
            {{ ruleValue(item.jobValue) }}
          </p>
          <p>{{ item.reason }}</p></template
        >
      </div>
      <button @click="$emit('rules')">设置筛选规则</button>
    </details>
    <details>
      <summary>技术详情</summary>
      <button @click="copy">复制技术详情</button>
      <span role="status">{{ copyStatus }}</span>
      <pre>{{ JSON.stringify(analysis, null, 2) }}</pre>
    </details>
  </div>
</template>
