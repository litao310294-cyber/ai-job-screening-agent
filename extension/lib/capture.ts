import type { Job } from "./contracts";
import { readDomDetail, selectedJobId } from "./boss";
export function whitelist(value: unknown): Job | null {
  if (!value || typeof value !== "object") return null;
  const data = value as Record<string, unknown>;
  if (
    typeof data.title !== "string" ||
    typeof data.description !== "string" ||
    !data.title.trim() ||
    !data.description.trim()
  )
    return null;
  const result: Job = {
    sourcePlatform: "BOSS",
    title: data.title.slice(0, 255),
    description: data.description.slice(0, 50000),
    simulated: false,
    captureMethod: "VUE_READ_ONLY",
  };
  for (const key of [
    "company",
    "city",
    "salary",
    "location",
    "education",
    "experience",
    "sourceJobId",
    "duration",
  ] as const)
    if (typeof data[key] === "string")
      result[key] = (data[key] as string).slice(0, key === "location" ? 1024 : key === "education" || key === "experience" ? 128 : 255);
  if (Array.isArray(data.skills))
    result.skills = data.skills.filter((v): v is string => typeof v === "string").map(v => v.trim().slice(0, 128)).filter(Boolean).slice(0, 50);
  for (const key of ["internshipDays", "internshipMonths"] as const) {
    const number = data[key];
    if (typeof number === "number" && Number.isInteger(number) && number >= 1 && number <= (key === "internshipDays" ? 7 : 60)) result[key] = number;
  }
  return result;
}
export async function capture(): Promise<Job> {
  const nonce = crypto.randomUUID();
  const fromVue = await new Promise<{ job: Job | null; expectedId: string }>((resolve, reject) => {
    const listener = (event: MessageEvent) => {
      if (event.source !== window || event.origin !== location.origin ||
        event.data?.type !== "AI_JOB_READ_RESULT" || event.data.nonce !== nonce) return;
      cleanup();
      if (event.data.changed) {
        reject(new Error("采集期间岗位已切换，请等待新详情加载后重新采集。"));
        return;
      }
      resolve({ job: whitelist(event.data.job), expectedId: typeof event.data.expectedId === "string" ? event.data.expectedId : "" });
    };
    const cleanup = () => { window.removeEventListener("message", listener); clearTimeout(timer); };
    const timer = setTimeout(() => {
      cleanup();
      resolve({ job: null, expectedId: selectedJobId() });
    }, 4500);
    window.addEventListener("message", listener);
    window.postMessage({ type: "AI_JOB_READ_REQUEST", nonce }, location.origin);
  });
  if (fromVue.job) return { ...fromVue.job, sourceUrl: location.href };
  const job = readDomDetail(fromVue.expectedId);
  if (!job) throw new Error("详情尚未就绪或无法确认与当前岗位一致。请等待详情加载后重新采集，也可手动粘贴完整JD并核对字段。");
  return { ...job, sourceUrl: location.href };
}
