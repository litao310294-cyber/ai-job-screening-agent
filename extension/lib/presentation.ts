import type { RuleItem } from "./contracts";
const words: Record<string, string> = {
  RECOMMEND_APPLY: "建议投递",
  CONSIDER: "可以考虑",
  NOT_RECOMMEND: "不建议投递",
  INSUFFICIENT_EVIDENCE: "信息不足",
  SUPPORTED: "有证据支持",
  PARTIALLY_SUPPORTED: "部分支持",
  UNSUPPORTED: "证据显示不符合",
  HARD: "硬性条件",
  SOFT: "偏好条件",
  DISABLED: "未启用",
  SATISFIED: "满足条件",
  UNSATISFIED: "不满足条件",
  UNKNOWN: "待确认",
  RULE_TERMINAL: "依据规则结束",
  LLM_ANALYSIS: "AI 分析",
  DEGRADED_FALLBACK: "降级结果",
  COMPUTED: "本次生成",
  REDIS: "已保存结果",
  DATABASE: "已保存结果",
  SHARED_COMPUTE: "共享计算结果",
  SUCCESS: "已完成",
  SUCCEEDED: "已回复",
  RUNNING: "处理中",
  FAILED: "处理失败",
  DEGRADED: "降级结果",
  INTERRUPTED: "服务中断",
  CLEARED: "已清空",
  TEXT_READY: "文本已提取",
  AWAITING_CONFIRMATION: "等待确认事实",
  READY: "已就绪",
  FAILED_STRUCTURE: "简历整理失败",
  FAILED_INDEX: "索引构建失败",
  FAILED_EXTRACT: "文本提取失败",
  city: "城市",
  direction: "目标方向",
  attendance: "每周出勤",
  duration: "实习周期",
  salary: "薪资范围",
  excludedKeywords: "排除关键词",
  availableFrom: "到岗日期",
  CITY: "城市",
  DAYS_PER_WEEK: "天／周",
  MONTHS: "个月",
  DATE: "日期",
  LITERAL_TEXT: "原文",
  DIRECTION: "方向",
  DAY: "元／天",
  MONTH: "元／月",
  JAVA_BACKEND: "Java 后端",
  AI_APPLICATION: "AI 应用",
  OTHER: "其他方向",
  EDUCATION: "学历",
  EXPERIENCE: "经历",
  SKILL: "技能",
  PROJECT: "项目",
};
export const display = (value: unknown) =>
  typeof value === "string" && words[value] ? words[value] : "待确认";
export function ruleValue(value: unknown): string {
  if (value == null || value === "" || value === "null") return "未提供";
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed))
        return parsed.length
          ? parsed.map((v) => words[String(v)] ?? String(v)).join("、")
          : "未提供";
    } catch {
      /* Source text remains unchanged. */
    }
    return words[value] ?? value;
  }
  return String(value);
}
export const ruleLabel = (item: RuleItem) =>
  item.strength === "DISABLED" ? "未启用" : display(item.verdict);
export function ruleSummary(items: RuleItem[] = []) {
  const enabled = items.filter((i) => i.strength !== "DISABLED");
  if (!enabled.length) return "尚未启用筛选规则";
  const rejected = enabled.filter(
    (i) => i.strength === "HARD" && i.verdict === "UNSATISFIED",
  );
  if (rejected.length) return `${rejected.length} 项硬性条件不满足`;
  const unknown = enabled.filter((i) => i.verdict === "UNKNOWN").length;
  return unknown ? `未触发硬拒绝 · ${unknown} 项待确认` : "未触发硬拒绝";
}
const errors: Record<string, string> = {
  CHAT_BUSY: "上一条问题仍在处理中，请稍后查询回复。",
  CHAT_GENERATION_CONFLICT: "共享聊天已更新，请刷新历史后再试。",
  CHAT_GENERATION_CHANGED: "共享聊天已更新，请刷新历史后再试。",
  CHAT_ANALYSIS_STALE: "分析已过期，请重新分析后继续提问。",
  RULE_VERSION_CONFLICT: "规则已在其他页面更新，请刷新规则后保存。",
  RULE_VERSION_CHANGED:
    "规则已在其他页面更新，本地输入已保留。请加载最新配置后处理，避免覆盖他人的更新。",
  RULES_INVALID:
    "设置未保存：请检查启用条件的字段、单位及范围。本地输入已保留。",
  COMMUNICATION_FAILED: "暂时无法连接后端，请检查连接地址和服务。",
  CHAT_OUTPUT_INVALID: "回复格式校验未通过，可查询状态或用原请求重试。",
  CHAT_MODEL_TIMEOUT: "回复等待超时，可查询原操作或用原请求重试。",
  LLM_ANALYSIS_FAILED: "AI 分析未通过校验，请查看状态后重试。",
};
export const errorText = (code: string) =>
  errors[code] ?? "操作未完成，请检查输入或查询原操作状态。";
export const shouldSend = (
  event: Pick<KeyboardEvent, "key" | "shiftKey" | "isComposing" | "keyCode">,
) =>
  event.key === "Enter" &&
  !event.shiftKey &&
  !event.isComposing &&
  event.keyCode !== 229;
export const nearBottom = (
  node: Pick<HTMLElement, "scrollHeight" | "scrollTop" | "clientHeight">,
) => node.scrollHeight - node.scrollTop - node.clientHeight < 90;
