import { createApp } from "vue";
import Panel from "../ui/Panel.vue";
import "../ui/style.css";
export default defineContentScript({
  matches: ["https://www.zhipin.com/*", "http://127.0.0.1/*"],
  cssInjectionMode: "ui",
  async main(ctx) {
    if (document.querySelector("ai-job-panel")) return;
    await injectScript("/boss-reader.js", { keepInDom: false });
    const ui = await createShadowRootUi(ctx, {
      name: "ai-job-panel",
      position: "inline",
      anchor: "body",
      onMount(container) {
        const app = createApp(Panel, { embedded: true });
        app.mount(container);
        return app;
      },
      onRemove(app) {
        app?.unmount();
      },
    });
    ui.mount();
    window.addEventListener("message", event => {
      if (event.source === window && event.origin === location.origin &&
        event.data?.type === "AI_JOB_SELECTION_CHANGED")
        window.dispatchEvent(new Event("ai-job-route"));
    });
    let url = location.href;
    ctx.setInterval(() => {
      if (location.href !== url) {
        url = location.href;
        window.dispatchEvent(new Event("ai-job-route"));
      }
    }, 400);
  },
});
