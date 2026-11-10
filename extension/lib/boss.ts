// Read-only adaptation of boss-helper 0.5.0 jobDetail (MIT, Ocyss).
import type { Job } from "./contracts";

export const detailNodes = "#wrap .page-job-wrapper,.job-recommend-main,.page-jobs-main,.job-detail-box,.job-detail-container,.job-detail,.job-detail-body,#wrap";
export const activeCards = ".job-card-wrapper.active,.job-card-box.active,.job-card-wrap.active,.job-card-wrapper.selected,.job-card-box.selected,[data-job-id][aria-selected=true]";
export const stringValue = (value: unknown, max = 255): string =>
  typeof value === "string" ? value.trim().slice(0, max) : "";

export function jobIdFromUrl(value: string): string {
  try {
    const url = new URL(value, location.href);
    return url.pathname.match(/\/job_detail\/([^/]+)\.html/)?.[1] ??
      url.searchParams.get("jobId") ?? url.searchParams.get("encryptJobId") ?? "";
  } catch { return ""; }
}

export function cardJobId(card: Element | null): string {
  if (!card) return "";
  const link = card.matches("a[href]") ? card : card.querySelector("a[href*='/job_detail/']");
  return stringValue(card.getAttribute("data-job-id")) ||
    stringValue(card.getAttribute("data-encrypt-job-id")) ||
    jobIdFromUrl(link?.getAttribute("href") ?? "");
}

export function selectedJobId(doc: Document = document): string {
  const card = [...doc.querySelectorAll(activeCards)].find(node => node.getClientRects().length);
  return cardJobId(card ?? null) || jobIdFromUrl(location.href);
}

export function internshipLabels(text: string): Pick<Job, "internshipDays" | "internshipMonths" | "duration"> {
  const days = [...new Set([...text.matchAll(/([1-7])天\s*\/\s*周/g)].map(match => Number(match[1])))];
  const months = [...new Set([...text.matchAll(/(\d+)个?月/g)].map(match => Number(match[1])))];
  return {
    internshipDays: days.length === 1 ? days[0] : null,
    internshipMonths: months.length === 1 && months[0]! >= 1 && months[0]! <= 60 ? months[0] : null,
    duration: months.length ? text : "",
  };
}

export function mapDetail(value: unknown, expectedId: string): Job | null {
  if (!value || typeof value !== "object") return null;
  const detail = value as Record<string, any>;
  const info = detail.jobInfo;
  if (!info || typeof info !== "object") return null;
  const id = stringValue(info.encryptId);
  if (expectedId && id !== expectedId) return null;
  const title = stringValue(info.jobName);
  const description = stringValue(info.postDescription, 50000);
  if (!title || !description) return null;
  return {
    sourcePlatform: "BOSS", simulated: false, captureMethod: "VUE_READ_ONLY",
    sourceJobId: id, title, description,
    ...internshipLabels(stringValue(info.experienceName)),
    city: stringValue(info.locationName), location: stringValue(info.address, 1024),
    education: stringValue(info.degreeName, 128), experience: stringValue(info.experienceName, 128),
    salary: stringValue(info.salaryDesc),
    company: stringValue(detail.brandComInfo?.brandName) || stringValue(detail.bossInfo?.brandName),
    skills: Array.isArray(info.showSkills)
      ? info.showSkills.filter((v: unknown): v is string => typeof v === "string").map((v: string) => stringValue(v, 128)).filter(Boolean).slice(0, 50)
      : [],
  };
}

export function readVueDetail(expectedId: string): Job | null {
  for (const node of document.querySelectorAll(detailNodes)) {
    const vm = (node as Element & { __vue__?: { jobDetail?: unknown } }).__vue__;
    const job = mapDetail(vm?.jobDetail, expectedId);
    if (job) return job;
  }
  return null;
}

export function readDomDetail(expectedId: string): Job | null {
  const roots = [...document.querySelectorAll<HTMLElement>(".job-detail-box,.job-detail-container,.job-detail")]
    .filter(node => node.getClientRects().length);
  const root = roots.at(-1);
  if (!root) return null;
  const read = (selectors: string) => stringValue(root.querySelector<HTMLElement>(selectors)?.innerText, 50000);
  const ownId = cardJobId(root) || jobIdFromUrl(location.href);
  // A DOM-only detail must identify itself when a list card is selected.
  if (expectedId && ownId !== expectedId) return null;
  const title = read(".job-name,h1");
  const description = read(".job-sec-text,.job-description,.job-detail-body");
  if (!title || !description) return null;
  const tags = [...root.querySelectorAll<HTMLElement>(".job-limit span,.job-info span,.job-primary .info-primary p span")].map(node => node.innerText.trim());
  return {
    sourcePlatform: "BOSS", simulated: false, captureMethod: "DOM", sourceJobId: ownId,
    title, description, salary: read(".salary"), company: read(".company-name,.company-info a"),
    city: read(".job-area,.job-city,.text-city"), location: read(".job-location .location-address,.location-address,.job-address,.job-location"),
    education: read(".job-degree,.degree") || tags.find(v => /^(博士|硕士|本科|大专|中专|高中|学历不限)/.test(v)) || "",
    experience: read(".job-experience,.experience") || tags.find(v => /^(\d+[-~至]\d+年|\d+年以上|经验不限|应届生|在校生)/.test(v)) || "",
    skills: [...root.querySelectorAll<HTMLElement>(".job-label-list span,.job-tags span,.job-require span")].map(node => node.innerText.trim()).filter(Boolean),
  };
}
