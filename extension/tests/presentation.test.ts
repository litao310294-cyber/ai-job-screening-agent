import { describe, it, expect } from "vitest";
import {
  display,
  ruleLabel,
  ruleSummary,
  shouldSend,
  nearBottom,
  ruleValue,
} from "../lib/presentation";
import type { RuleItem } from "../lib/contracts";
const item = (strength: string, verdict: string) =>
  ({ strength, verdict }) as RuleItem;
describe("Chinese presentation semantics", () => {
  it("never infers unknown enums or implies disabled rules passed", () => {
    expect(display("unexpected")).toBe("待确认");
    expect(display("UNSUPPORTED")).toBe("证据显示不符合");
    expect(ruleValue('["JAVA_BACKEND","AI_APPLICATION"]')).toBe(
      "Java 后端、AI 应用",
    );
    expect(ruleValue("null")).toBe("未提供");
    expect(ruleLabel(item("DISABLED", "UNKNOWN"))).toBe("未启用");
    expect(ruleSummary([item("DISABLED", "SATISFIED")])).toBe(
      "尚未启用筛选规则",
    );
  });
  it("distinguishes unknown, hard refusal and soft mismatch", () => {
    expect(ruleSummary([item("HARD", "UNKNOWN")])).toContain("待确认");
    expect(ruleSummary([item("HARD", "UNSATISFIED")])).toContain(
      "硬性条件不满足",
    );
    expect(ruleSummary([item("SOFT", "UNSATISFIED")])).toBe("未触发硬拒绝");
  });
  it("does not submit IME composition or Shift Enter", () => {
    const key = {
      key: "Enter",
      shiftKey: false,
      isComposing: false,
      keyCode: 13,
    };
    expect(shouldSend(key)).toBe(true);
    expect(shouldSend({ ...key, isComposing: true })).toBe(false);
    expect(shouldSend({ ...key, keyCode: 229 })).toBe(false);
    expect(shouldSend({ ...key, shiftKey: true })).toBe(false);
  });
  it("only sticks to latest near the bottom", () => {
    expect(
      nearBottom({ scrollHeight: 1000, scrollTop: 200, clientHeight: 400 }),
    ).toBe(false);
    expect(
      nearBottom({ scrollHeight: 1000, scrollTop: 550, clientHeight: 400 }),
    ).toBe(true);
  });
});
