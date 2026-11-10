import { defineConfig } from "wxt";
import key from "./public-key.json";
export default defineConfig({
  modules: ["@wxt-dev/module-vue"],
  manifest: {
    name: "AI 求职岗位分析 · 本机演示",
    description: "固定用户画像、规则、岗位分析与连续问答",
    version: "0.5.0",
    permissions: ["storage", "tabs"],
    host_permissions: ["http://127.0.0.1/*"],
    key: key.publicKey,
    web_accessible_resources: [
      {
        resources: ["boss-reader.js"],
        matches: ["https://www.zhipin.com/*", "http://127.0.0.1/*"],
      },
    ],
    action: { default_title: "打开演示面板" },
  },
});
