import { describe, it, expect } from "vitest";
import { Fence, allowedBase, allowedRequest } from "../lib/contracts";
import { wireClone } from "../lib/contracts";
import { reactive } from "vue";
import { whitelist } from "../lib/capture";
describe("request fences and trusted boundaries", () => {
  it("removes Vue proxies before Chrome messaging and persistence", () => {
    const state = reactive({
      skills: ["Java", "Spring"],
      comparisonAnalysisIds: [],
    });
    const wire = wireClone(state);
    expect(Array.isArray(wire.skills)).toBe(true);
    expect(Array.isArray(wire.comparisonAnalysisIds)).toBe(true);
    expect(wire).toEqual({
      skills: ["Java", "Spring"],
      comparisonAnalysisIds: [],
    });
  });
  it("discards a late response after job change or clear", () => {
    const fence = new Fence();
    const a = fence.token();
    fence.advance();
    expect(fence.valid(a)).toBe(false);
    const b = fence.token();
    fence.advance();
    expect(fence.valid(b)).toBe(false);
  });
  it("allows only exact local API bases", () => {
    expect(allowedBase("http://127.0.0.1:18085/api")).toBe(true);
    for (const base of [
      "https://evil.test/api",
      "http://127.0.0.1:18085/api/..",
      "http://localhost:18085/api",
    ])
      expect(allowedBase(base)).toBe(false);
  });
  it("restricts API paths and verbs", () => {
    expect(allowedRequest("GET", "/analyses/test-id/evidence")).toBe(true);
    expect(allowedRequest("POST", "/chat/messages")).toBe(true);
    expect(allowedRequest("DELETE", "/chat")).toBe(true);
    expect(allowedRequest("PUT", "/rules")).toBe(true);
    for (const path of ["/../admin", "//evil.test", "/chat#evil", "/unknown"])
      expect(allowedRequest("GET", path)).toBe(false);
    expect(allowedRequest("DELETE", "/profile/current")).toBe(false);
  });
  it("page messages cannot introduce commands or unknown properties", () => {
    const job = whitelist({
      title: "Java",
      description: "JD",
      apiKey: "secret",
      command: "DELETE",
      simulated: true,
      city: "上海",
    });
    expect(job?.simulated).toBe(false);
    expect(job).not.toHaveProperty("command");
    expect(job).not.toHaveProperty("apiKey");
    expect(whitelist({ title: "", description: "x" })).toBe(null);
  });
});
