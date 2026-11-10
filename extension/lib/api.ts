import { browser } from "wxt/browser";
import { wireClone } from "./contracts";
let currentBase = "http://127.0.0.1:18085/api";
export function setApiBase(base: string) { currentBase = base; }
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status = 0,
  ) {
    super(message);
  }
}
export async function message<T>(request: unknown): Promise<T> {
  const payload = wireClone(request) as Record<string, unknown>;
  if (payload.type === "api" || String(payload.type).startsWith("upload.")) payload.base = currentBase;
  if (location.protocol === "chrome-extension:")
    payload.viewTabId = (await browser.tabs.getCurrent())?.id;
  const response = await browser.runtime.sendMessage(payload);
  if (!response.ok)
    throw new ApiError(response.code, response.message, response.status);
  return response.data as T;
}
export const api = <T>(path: string, method = "GET", body?: unknown) =>
  message<T>({ type: "api", path, method, body });
export async function upload(file: File) {
  if (file.size > 10 * 1024 * 1024 || !file.name.toLowerCase().endsWith(".pdf"))
    throw new Error("请选择不超过10 MiB的PDF");
  const id = await message<string>({
    type: "upload.begin",
    name: file.name,
    size: file.size,
  });
  for (
    let offset = 0, index = 0;
    offset < file.size;
    offset += 65536, index++
  ) {
    const bytes = new Uint8Array(
      await file.slice(offset, offset + 65536).arrayBuffer(),
    );
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    await message({ type: "upload.chunk", id, index, chunk: btoa(binary) });
  }
  return message<import("./contracts").ImportView>({
    type: "upload.finish",
    id,
  });
}
