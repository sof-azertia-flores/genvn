import type { AccessView, AssetsStatus, ChoiceView, ConfigView, CreationJobView, HistoryPage, RollResponse, SessionSummary, SessionTasksView, SessionView, SettingsView } from "./types";

export class ApiError extends Error {
  constructor(message: string, readonly code: string) {
    super(message);
  }
}

/**
 * Backend origin baked in at build time (genvn.config.json `apiBase`). Empty means the same
 * origin that served the page: the dev proxy, or a reverse proxy forwarding /api.
 */
export const API_BASE: string = (typeof __GENVN_API_BASE__ === "string" ? __GENVN_API_BASE__ : "").replace(/\/+$/, "");

/** Absolute form of a backend path, e.g. a picture URL from the assets status. */
export function apiUrl(path: string): string {
  return API_BASE && path.startsWith("/") ? `${API_BASE}${path}` : path;
}

export const ACCESS_KEY_HEADER = "X-Genvn-Key";
const ACCESS_KEY_STORAGE = "genvn.accessKey";
let memoryKey: string | null = null;

function keyStore(): Storage | null {
  try { return typeof localStorage === "undefined" ? null : localStorage; } catch { return null; }
}

/** The key this browser was given, if any. Kept in localStorage so a reload does not ask again. */
export function getAccessKey(): string | null {
  const store = keyStore();
  if (store) {
    try { return store.getItem(ACCESS_KEY_STORAGE); } catch { /* private mode: fall through */ }
  }
  return memoryKey;
}

export function setAccessKey(key: string | null): void {
  memoryKey = key;
  const store = keyStore();
  if (!store) return;
  try {
    if (key) store.setItem(ACCESS_KEY_STORAGE, key);
    else store.removeItem(ACCESS_KEY_STORAGE);
  } catch { /* storage full or blocked: the in-memory copy still serves this page */ }
}

type AccessListener = (code: string) => void;
const accessListeners = new Set<AccessListener>();

/** Called when the server refuses a call for want of a (valid) key; the app then shows the key screen. */
export function onAccessDenied(listener: AccessListener): () => void {
  accessListeners.add(listener);
  return () => { accessListeners.delete(listener); };
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", ...(init?.headers as Record<string, string> | undefined) };
  const key = getAccessKey();
  if (key) headers[ACCESS_KEY_HEADER] = key;
  const response = await fetch(`${API_BASE}/api${path}`, { ...init, headers });
  const text = await response.text();
  let body: { message?: string; error?: string } | null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(
      response.ok ? "服务端返回了无法读取的数据。" : `请求失败 (${response.status})`,
      "invalid_response",
    );
  }
  if (!response.ok) {
    const code = body?.error ?? "http_error";
    if (response.status === 401 && code.startsWith("access_key")) {
      for (const listener of accessListeners) listener(code);
    }
    throw new ApiError(body?.message ?? `请求失败 (${response.status})`, code);
  }
  return body as T;
}

export interface CreateSessionPayload {
  storyOutline: string;
  artStyle?: string;
  player: {
    name: string;
    background: string;
    visualDescription?: string;
    stats: Record<string, number>;
    traits: string[];
    maxHp: number;
  };
}

export const api = {
  /** Reachable without a key: whether one is needed, and whether ours is right. */
  access: () => request<AccessView>("/access"),
  config: () => request<ConfigView>("/config"),
  createSession: (payload: CreateSessionPayload) =>
    request<SessionView>("/sessions", { method: "POST", body: JSON.stringify(payload) }),
  createSessionJob: (payload: CreateSessionPayload, signal?: AbortSignal, requestId?: string) =>
    request<CreationJobView>("/session-creations", { method: "POST", body: JSON.stringify(payload), signal,
      headers: { "Content-Type": "application/json", ...(requestId ? { "Idempotency-Key": requestId } : {}) } }),
  creationJob: (id: string, signal?: AbortSignal) =>
    request<CreationJobView>(`/session-creations/${encodeURIComponent(id)}`, { signal }),
  getSession: (id: string, signal?: AbortSignal) =>
    request<SessionView>(`/sessions/${encodeURIComponent(id)}`, { signal }),
  listSessions: () => request<SessionSummary[]>("/sessions"),
  /** Cast the die now and get it back at once; the scene comes from a separate choose(). */
  roll: (id: string, choiceId: string, expectedSceneId: string, expectedStateVersion: number) =>
    request<RollResponse>(`/sessions/${encodeURIComponent(id)}/choices/${encodeURIComponent(choiceId)}/roll`, {
      method: "POST",
      body: JSON.stringify({ expectedSceneId, expectedStateVersion }),
    }),
  choose: (id: string, choiceId: string, expectedSceneId: string, expectedStateVersion: number) =>
    request<ChoiceView>(`/sessions/${encodeURIComponent(id)}/choices/${encodeURIComponent(choiceId)}`, {
      method: "POST",
      body: JSON.stringify({ expectedSceneId, expectedStateVersion }),
    }),
  rewind: (id: string, nodeId: string, expectedSceneId: string, expectedStateVersion: number) =>
    request<SessionView>(`/sessions/${encodeURIComponent(id)}/nodes/${encodeURIComponent(nodeId)}/rewind`, {
      method: "POST",
      body: JSON.stringify({ expectedSceneId, expectedStateVersion }),
    }),
  /**
   * Rewrite the story from one scene onward. Two model calls, so it answers with a job to poll
   * rather than a session; the instruction is sent once and kept nowhere.
   */
  restructure: (id: string, nodeId: string, instruction: string, expectedSceneId: string,
                expectedStateVersion: number, requestId?: string, signal?: AbortSignal) =>
    request<CreationJobView>(`/sessions/${encodeURIComponent(id)}/nodes/${encodeURIComponent(nodeId)}/restructure`, {
      method: "POST",
      body: JSON.stringify({ expectedSceneId, expectedStateVersion, instruction }),
      signal,
      headers: { "Content-Type": "application/json", ...(requestId ? { "Idempotency-Key": requestId } : {}) },
    }),
  restructureJob: (jobId: string, signal?: AbortSignal) =>
    request<CreationJobView>(`/session-restructures/${encodeURIComponent(jobId)}`, { signal }),
  debug: (id: string) => request<Record<string, unknown>>(`/sessions/${encodeURIComponent(id)}/debug`),
  tasks: (id: string, signal?: AbortSignal) =>
    request<SessionTasksView>(`/sessions/${encodeURIComponent(id)}/tasks`, { signal }),
  history: (id: string, options: { throughSceneId: string; throughBlockIndex: number; beforeSceneId?: string; limit?: number }, signal?: AbortSignal) => {
    const query = new URLSearchParams({ throughSceneId: options.throughSceneId, throughBlockIndex: String(options.throughBlockIndex) });
    if (options.beforeSceneId) query.set("beforeSceneId", options.beforeSceneId);
    if (options.limit != null) query.set("limit", String(options.limit));
    return request<HistoryPage>(`/sessions/${encodeURIComponent(id)}/history?${query}`, { signal });
  },
  retryAsset: (id: string, assetId: string) =>
    request<AssetsStatus>(`/sessions/${encodeURIComponent(id)}/assets/${encodeURIComponent(assetId)}/retry`, { method: "POST" }),
  /** Picture status. Lock-free on the server, cheap to poll while anything is still generating. */
  assets: (id: string, signal?: AbortSignal) =>
    request<AssetsStatus>(`/sessions/${encodeURIComponent(id)}/assets`, { signal }),
  settings: () => request<SettingsView>("/settings"),
  saveSettings: (values: Record<string, unknown>) =>
    request<SettingsView>("/settings", { method: "PUT", body: JSON.stringify({ values }) }),
};
