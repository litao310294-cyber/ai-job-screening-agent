import { browser } from "wxt/browser";
import { allowedBase, allowedRequest } from "../lib/contracts";
interface Upload {
  base: string;
  owner: string;
  name: string;
  size: number;
  received: number;
  chunks: Uint8Array[];
  created: number;
}
export default defineBackground(() => {
  const uploads = new Map<string, Upload>();
  browser.tabs.onRemoved.addListener((id) => {
    void browser.storage.local.remove(`tab:${id}`);
  });
  browser.action.onClicked.addListener(() => {
    void browser.tabs.create({ url: browser.runtime.getURL("/demo.html") });
  });
  browser.runtime.onMessage.addListener((raw, sender, respond) => {
    if (sender.id !== browser.runtime.id) return false;
    const viewTabId = (raw as { viewTabId?: number }).viewTabId;
    const owner = String(
      sender.tab?.id ??
        (sender.url?.startsWith(browser.runtime.getURL("/demo.html")) &&
        Number.isSafeInteger(viewTabId)
          ? viewTabId
          : "demo"),
    );
    const run = async () => {
      const request = raw as {
        type: string;
        path: string;
        method: string;
        body?: unknown;
        base?: string;
        name: string;
        size: number;
        id: string;
        index: number;
        chunk: string;
      };
      if (request.type === "scope") return owner;
      if (request.type === "configure") {
        if (!request.base || !allowedBase(request.base))
          throw new Error("仅支持可信本机端口18080或18085");
        await browser.storage.local.set({ apiBase: request.base });
        return request.base;
      }
      for (const [id, file] of uploads)
        if (Date.now() - file.created > 120000) uploads.delete(id);
      if (request.type === "upload.begin") {
        if (
          !Number.isInteger(request.size) ||
          request.size <= 0 ||
          request.size > 10 * 1024 * 1024 ||
          !request.name.toLowerCase().endsWith(".pdf")
        )
          throw new Error("上传文件大小或类型不合法");
        for (const [id, file] of uploads)
          if (file.owner === owner) uploads.delete(id);
        const id = crypto.randomUUID();
        if (!request.base || !allowedBase(request.base)) throw new Error("本机API配置不可信");
        uploads.set(id, {
          base: request.base,
          owner,
          name: request.name.slice(0, 255),
          size: request.size,
          received: 0,
          chunks: [],
          created: Date.now(),
        });
        return id;
      }
      if (request.type.startsWith("upload.")) {
        const file = uploads.get(request.id);
        if (!file || file.owner !== owner)
          throw new Error("上传会话已失效，请重新选择文件");
        if (request.type === "upload.chunk") {
          if (
            request.index !== file.chunks.length ||
            typeof request.chunk !== "string" ||
            request.chunk.length > 90000
          )
            throw new Error("上传分块顺序错误");
          const bytes = Uint8Array.from(atob(request.chunk), (c) =>
            c.charCodeAt(0),
          );
          file.received += bytes.length;
          if (file.received > file.size) {
            uploads.delete(request.id);
            throw new Error("上传大小不符");
          }
          file.chunks.push(bytes);
          return file.received;
        }
        if (request.type !== "upload.finish" || file.received !== file.size)
          throw new Error("上传尚未完整");
        uploads.delete(request.id);
        const form = new FormData();
        form.append(
          "file",
          new Blob(file.chunks as BlobPart[], { type: "application/pdf" }),
          file.name,
        );
        return fetchApi("/profile/resume/upload", "POST", form, file.base);
      }
      if (
        request.type !== "api" ||
        !allowedRequest(request.method, request.path)
      )
        throw new Error(
          `请求不在后台白名单内 (${String(request.method)} ${String(request.path).slice(0, 160)})`,
        );
      return fetchApi(request.path, request.method, request.body, request.base);
    };
    run()
      .then((data) => respond({ ok: true, data }))
      .catch((error) =>
        respond({
          ok: false,
          code: error.code ?? "COMMUNICATION_FAILED",
          message: error.message,
          status: error.status ?? 0,
        }),
      );
    return true;
  });
  async function fetchApi(path: string, method: string, body?: unknown, requestedBase?: string) {
    const saved = await browser.storage.local.get("apiBase");
    const base = requestedBase ?? String(saved.apiBase ?? "http://127.0.0.1:18085/api");
    if (!allowedBase(base)) throw new Error("本机API配置不可信");
    const form = body instanceof FormData;
    const response = await fetch(base + path, {
      method,
      headers:
        form || body === undefined
          ? {}
          : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(75000),
      redirect: "error",
      credentials: "omit",
    });
    const data = await response.json();
    if (!response.ok)
      throw Object.assign(new Error(data.message ?? "后端请求失败"), {
        code: data.code,
        status: response.status,
      });
    return data;
  }
});
