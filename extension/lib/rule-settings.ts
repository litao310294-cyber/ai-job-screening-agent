import type { Rules } from "./contracts";
import { display } from "./presentation";
// v0 is synthesized by RuleConfigService only when the user has no saved rows.
// Empty configs at v1+ are user saves and must never be replaced.
export const neverSaved = (rules: Rules) => rules.version === 0;
export function suggestedConfig(): Rules["config"] {
  return {
    candidate: {
      availableDaysPerWeek: 5,
      availableMonths: 6,
      availableFrom: null,
    },
    criteria: {
      cities: [],
      directions: ["JAVA_BACKEND", "AI_APPLICATION"],
      salaryMin: null,
      salaryMax: null,
      salaryUnit: null,
      excludedKeywords: [],
    },
    modes: {
      city: "DISABLED",
      direction: "SOFT",
      attendance: "SOFT",
      duration: "SOFT",
      salary: "DISABLED",
      excludedKeywords: "DISABLED",
    },
    softNotes: [],
  };
}
export function normalizedConfig(config: Rules["config"]) {
  return JSON.parse(
    JSON.stringify(config, (_key, value) => (value === "" ? null : value)),
  ) as Rules["config"];
}
export const sameConfig = (a: Rules["config"], b: Rules["config"]) =>
  JSON.stringify(normalizedConfig(a)) === JSON.stringify(normalizedConfig(b));
export function settingsSummary(config: Rules["config"]) {
  const { candidate: c, criteria: r, modes: m } = config;
  const parts = [
    m.city === "DISABLED"
      ? "城市不限"
      : `城市${display(m.city)}：${r.cities.join("、") || "待填写"}`,
  ];
  if (m.direction !== "DISABLED")
    parts.push(
      `${r.directions.map(display).join("／") || "方向待填写"} · ${display(m.direction)}`,
    );
  if (m.attendance !== "DISABLED")
    parts.push(
      `每周${c.availableDaysPerWeek ?? "待填写"}天 · ${display(m.attendance)}`,
    );
  if (m.duration !== "DISABLED")
    parts.push(
      `可实习${c.availableMonths ?? "待填写"}个月 · ${display(m.duration)}`,
    );
  if (m.salary === "DISABLED") parts.push("薪资不限");
  else
    parts.push(
      `薪资${r.salaryMin ?? "不限"}～${r.salaryMax ?? "不限"}${display(r.salaryUnit)} · ${display(m.salary)}`,
    );
  if (m.excludedKeywords !== "DISABLED")
    parts.push(
      `排除词${display(m.excludedKeywords)}：${r.excludedKeywords.join("、")}`,
    );
  return parts.join(" · ");
}
