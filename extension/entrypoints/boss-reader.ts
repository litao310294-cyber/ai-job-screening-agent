import { cardJobId, readVueDetail, selectedJobId, stringValue } from "../lib/boss";

// Read-only page bridge. Never replaces a Vue setter or calls page actions.
export default defineUnlistedScript(() => {
  let clickedId = "";
  let current = selectedJobId();
  let url = location.href;
  let generation = 0;
  document.addEventListener("click", event => {
    const target = event.target instanceof Element ? event.target : null;
    const card = target?.closest(".job-card-wrapper,.job-card-box,.job-card-wrap,[data-job-id],a[href*='/job_detail/']");
    const id = cardJobId(card ?? null);
    if (id) { clickedId = id; refresh(); }
  }, true);
  function refresh() {
    if (location.href !== url) { url = location.href; clickedId = ""; }
    const selected = selectedJobId();
    if (selected === clickedId) clickedId = "";
    const next = clickedId || selected;
    if (next !== current) {
      current = next;
      generation++;
      window.postMessage({ type: "AI_JOB_SELECTION_CHANGED", jobId: current, generation }, location.origin);
    }
  }
  setInterval(refresh, 100);
  window.addEventListener("message", async event => {
    if (event.source !== window || event.origin !== location.origin || event.data?.type !== "AI_JOB_READ_REQUEST") return;
    const nonce = stringValue(event.data.nonce, 80);
    if (!nonce) return;
    refresh();
    const start = generation;
    const deadline = Date.now() + 4000;
    let job = null;
    while (Date.now() < deadline) {
      refresh();
      if (generation !== start) break;
      job = readVueDetail(current);
      if (job) break;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    refresh();
    const changed = generation !== start;
    window.postMessage({ type: "AI_JOB_READ_RESULT", nonce, job: changed ? null : job,
      expectedId: current, changed }, location.origin);
  });
});
