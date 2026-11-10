import { describe, it, expect } from "vitest";
import {
  suggestedConfig,
  neverSaved,
  sameConfig,
  settingsSummary,
} from "../lib/rule-settings";
import type { Rules } from "../lib/contracts";
describe("user rule settings", () => {
  it("suggests editable capacity with soft modes and no salary/city/date assumption", () => {
    const c = suggestedConfig();
    expect(c.candidate).toEqual({
      availableDaysPerWeek: 5,
      availableMonths: 6,
      availableFrom: null,
    });
    expect(c.modes).toEqual({
      city: "DISABLED",
      direction: "SOFT",
      attendance: "SOFT",
      duration: "SOFT",
      salary: "DISABLED",
      excludedKeywords: "DISABLED",
    });
    expect(c.criteria.salaryMin).toBeNull();
    expect(c.criteria.cities).toEqual([]);
    expect(settingsSummary(c)).toContain("城市不限");
    expect(settingsSummary(c)).not.toContain("北京");
  });
  it("uses provenance version instead of empty content to determine first use", () => {
    expect(neverSaved({ version: 0 } as Rules)).toBe(true);
    expect(neverSaved({ version: 1 } as Rules)).toBe(false);
    expect(neverSaved({ version: 9 } as Rules)).toBe(false);
  });
  it("normalizes cleared HTML fields without overwriting explicit emptiness", () => {
    const c = suggestedConfig();
    const blank = structuredClone(c);
    (blank.candidate as unknown as Record<string, unknown>).availableFrom = "";
    expect(sameConfig(blank, c)).toBe(true);
    blank.criteria.directions = [];
    expect(sameConfig(blank, c)).toBe(false);
    expect(suggestedConfig().criteria.directions).toHaveLength(2);
  });
});
