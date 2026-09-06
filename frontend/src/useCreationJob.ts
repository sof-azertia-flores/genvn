import { useCallback, useEffect, useRef, useState } from "react";
import { api, type CreateSessionPayload } from "./api";
import type { CreationJobView, SessionView } from "./types";

function missingTask(error: unknown) {
  return error !== null && typeof error === "object" && "code" in error
    && (error.code === "not_found" || error.code === "session_not_found");
}

const STORAGE_KEY = "genvn.pending-creation";
function requestId() {
  if (window.crypto.randomUUID) return window.crypto.randomUUID();
  const bytes = window.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
function savedJob(id?: string | null): string | null {
  try {
    if (id === null) window.sessionStorage.removeItem(STORAGE_KEY);
    else if (id !== undefined) window.sessionStorage.setItem(STORAGE_KEY, id);
    return window.sessionStorage.getItem(STORAGE_KEY);
  } catch { return null; }
}

function bounded<T>(work: (signal: AbortSignal) => Promise<T>, parent: AbortSignal, ms = 15000): Promise<T> {
  const local = new AbortController();
  return new Promise<T>((resolve, reject) => {
    const cleanup = () => { window.clearTimeout(timer); parent.removeEventListener("abort", abort); };
    const abort = () => { cleanup(); local.abort(); reject(new Error("cancelled")); };
    const timer = window.setTimeout(() => {
      cleanup(); local.abort(); reject(new Error("读取进度超时，可以重试读取同一个任务。"));
    }, ms);
    parent.addEventListener("abort", abort, { once: true });
    if (parent.aborted) { abort(); return; }
    Promise.resolve().then(() => work(local.signal)).then((value) => { cleanup(); resolve(value); }, (e) => { cleanup(); reject(e); });
  });
}

function pause(signal: AbortSignal, ms: number) {
  return new Promise<void>((resolve, reject) => {
    const abort = () => { window.clearTimeout(timer); reject(new Error("cancelled")); };
    const timer = window.setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, ms);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  });
}

async function readJob(id: string, signal: AbortSignal) {
  // A reload can race the POST reaching the server. Give the same id a short grace period;
  // never conclude it vanished from one GET, and never compensate with another POST.
  for (let attempt = 0; ; attempt++) {
    try { return await bounded((local) => api.creationJob(id, local), signal); }
    catch (e) {
      if (signal.aborted || !missingTask(e) || attempt >= 2) throw e;
      await pause(signal, 1000 * (attempt + 1));
    }
  }
}

/** Follow one server job. A read failure can only retry that job, never submit another story. */
export default function useCreationJob() {
  const [job, setJob] = useState<CreationJobView | null>(null);
  const [readySession, setReadySession] = useState<SessionView | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  const currentJob = useRef<CreationJobView | null>(null);
  const watching = useRef(false);
  const resumeId = useRef(savedJob());

  const follow = useCallback(async (initial: CreationJobView, signal: AbortSignal) => {
    let next = initial;
    let failures = 0;
    let polls = 0;
    while (!signal.aborted) {
      currentJob.current = next;
      setJob(next);
      if (next.status === "FAILED") { savedJob(null); setError(next.error || "故事准备未能完成，请调整后重试。"); return; }
      if (next.status === "READY") {
        if (!next.sessionId) throw new Error("存档编号暂不可用，请重新读取进度。");
        const session = await bounded((local) => api.getSession(next.sessionId!, local), signal);
        if (!signal.aborted) { savedJob(null); setReadySession(session); }
        return;
      }
      if (++polls > 900) throw new Error("已暂停长时间的进度查询。可以继续读取同一个任务，或稍后从存档列表进入。");
      await pause(signal, 1200);
      try {
        next = await readJob(initial.id, signal);
        failures = 0;
      } catch (e) {
        if (signal.aborted) return;
        if (missingTask(e)) throw e;
        if (++failures >= 3) throw new Error("暂时无法读取进度。故事可能仍在准备，请重试读取进度。");
        await pause(signal, 1500 * failures);
      }
    }
  }, []);

  const run = useCallback(async (payload?: CreateSessionPayload) => {
    if (watching.current) return;
    watching.current = true;
    controller.current?.abort();
    const active = new AbortController();
    controller.current = active;
    setError(null);
    setPending(true);
    try {
      if (payload) {
        setReadySession(null);
        const id = requestId();
        currentJob.current = { id, status: "QUEUED", stage: "连接故事服务", progress: 0, logs: [], sessionId: null, error: null };
        savedJob(id);
        setJob(currentJob.current);
      }
      const id = currentJob.current?.id ?? resumeId.current;
      if (!id) return;
      if (!currentJob.current) {
        currentJob.current = { id, status: "QUEUED", stage: "恢复开局进度", progress: 0, logs: [], sessionId: null, error: null };
        setJob(currentJob.current);
      }
      const initial = payload ? await bounded((local) => api.createSessionJob(payload, local, id), active.signal, 30000)
        : await readJob(id, active.signal);
      savedJob(initial.id);
      if (!active.signal.aborted) await follow(initial, active.signal);
    } catch (e) {
      if (!active.signal.aborted) {
        const message = missingTask(e) ? "未找到这次开局任务，可能尚未提交完成或服务已重启。请返回查看存档列表。"
          : e instanceof Error ? e.message : "暂时无法准备故事，请稍后重试。";
        const rejected = e !== null && typeof e === "object" && "code" in e
          && ["invalid_request", "creation_queue_full"].includes(String(e.code));
        if (missingTask(e) || rejected) {
          savedJob(null);
          const existing = currentJob.current ?? { id: resumeId.current ?? "", stage: "准备中断", progress: 0, logs: [], sessionId: null };
          currentJob.current = { ...existing, status: "FAILED", error: message };
          setJob(currentJob.current);
        }
        setError(message);
      }
    } finally {
      if (!active.signal.aborted) { watching.current = false; setPending(false); }
    }
  }, [follow]);

  useEffect(() => {
    if (resumeId.current) void run();
    return () => { controller.current?.abort(); watching.current = false; };
  }, [run]);

  const reset = useCallback(() => {
    controller.current?.abort();
    watching.current = false;
    currentJob.current = null;
    resumeId.current = null;
    savedJob(null);
    setJob(null);
    setReadySession(null);
    setError(null);
    setPending(false);
  }, []);

  return { job, readySession, pending, error, begin: run,
    retry: () => { if (currentJob.current || resumeId.current) void run(); }, reset };
}
