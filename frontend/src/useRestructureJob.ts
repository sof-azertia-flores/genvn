import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";
import type { CreationJobView, SessionView } from "./types";

/**
 * Follows one restructure job, the way {@link useCreationJob} follows a creation job: two model
 * calls take far too long for a request, so the player watches a progress bar instead.
 *
 * Two differences. A restructure belongs to a save, so the resume id is stored per save -- two
 * open saves cannot inherit each other's job. And the player's instruction is submitted once and
 * never retained here: a retry re-reads the job the server already has rather than re-sending the
 * text, which is also what stops a retry from rewriting the story twice.
 */

/**
 * Read an error's own message structurally rather than through `instanceof Error`: the thing
 * thrown may come from another realm (a test harness, a worker), and losing the server's own
 * wording there would replace a precise answer -- "the scene moved on" -- with a vague one.
 */
function messageOf(error: unknown, fallback: string) {
  const own = (error as { message?: unknown } | null)?.message;
  return typeof own === "string" && own.trim() ? own : fallback;
}

function missingTask(error: unknown) {
  return error !== null && typeof error === "object" && "code" in error
    && (error.code === "not_found" || error.code === "session_not_found");
}

const STORAGE_PREFIX = "genvn.pending-restructure:";

function requestId() {
  if (window.crypto.randomUUID) return window.crypto.randomUUID();
  const bytes = window.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function savedJob(sessionId: string | null, id?: string | null): string | null {
  if (!sessionId) return null;
  const key = STORAGE_PREFIX + sessionId;
  try {
    if (id === null) window.sessionStorage.removeItem(key);
    else if (id !== undefined) window.sessionStorage.setItem(key, id);
    return window.sessionStorage.getItem(key);
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
    try { return await bounded((local) => api.restructureJob(id, local), signal); }
    catch (e) {
      if (signal.aborted || !missingTask(e) || attempt >= 2) throw e;
      await pause(signal, 1000 * (attempt + 1));
    }
  }
}

export interface RestructureRequest {
  nodeId: string;
  instruction: string;
  expectedSceneId: string;
  expectedStateVersion: number;
}

export default function useRestructureJob(sessionId: string | null) {
  const [job, setJob] = useState<CreationJobView | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  const currentJob = useRef<CreationJobView | null>(null);
  const watching = useRef(false);
  const readyHandler = useRef<((session: SessionView) => void) | null>(null);

  /** Called with the rewritten save the moment the job reports READY. */
  const onReady = useCallback((handler: (session: SessionView) => void) => {
    readyHandler.current = handler;
  }, []);

  const follow = useCallback(async (initial: CreationJobView, save: string, signal: AbortSignal) => {
    let next = initial;
    let failures = 0;
    let polls = 0;
    while (!signal.aborted) {
      currentJob.current = next;
      setJob(next);
      if (next.status === "FAILED") {
        savedJob(save, null);
        setError(next.error || "重塑未能完成，剧情没有改动。");
        return;
      }
      if (next.status === "READY") {
        const session = await bounded((local) => api.getSession(save, local), signal);
        if (!signal.aborted) {
          savedJob(save, null);
          readyHandler.current?.(session);
        }
        return;
      }
      if (++polls > 900) throw new Error("已暂停长时间的进度查询。可以继续读取同一个任务，或稍后重新打开这个存档。");
      await pause(signal, 1200);
      try {
        next = await readJob(initial.id, signal);
        failures = 0;
      } catch (e) {
        if (signal.aborted) return;
        if (missingTask(e)) throw e;
        if (++failures >= 3) throw new Error("暂时无法读取进度。剧情可能仍在重塑，请重试读取进度。");
        await pause(signal, 1500 * failures);
      }
    }
  }, []);

  const run = useCallback(async (save: string, request?: RestructureRequest) => {
    if (watching.current) return;
    watching.current = true;
    controller.current?.abort();
    const active = new AbortController();
    controller.current = active;
    setError(null);
    setPending(true);
    try {
      if (request) {
        const id = requestId();
        currentJob.current = { id, status: "QUEUED", stage: "连接故事服务", progress: 0, logs: [], sessionId: save, error: null };
        savedJob(save, id);
        setJob(currentJob.current);
      }
      const id = currentJob.current?.id ?? savedJob(save);
      if (!id) return;
      if (!currentJob.current) {
        currentJob.current = { id, status: "QUEUED", stage: "恢复重塑进度", progress: 0, logs: [], sessionId: save, error: null };
        setJob(currentJob.current);
      }
      const initial = request
        ? await bounded((local) => api.restructure(save, request.nodeId, request.instruction,
            request.expectedSceneId, request.expectedStateVersion, id, local), active.signal, 30000)
        : await readJob(id, active.signal);
      savedJob(save, initial.id);
      if (!active.signal.aborted) await follow(initial, save, active.signal);
    } catch (e) {
      if (!active.signal.aborted) {
        const message = missingTask(e) ? "未找到这次重塑任务，可能尚未提交完成或服务已重启。剧情没有改动。"
          : messageOf(e, "暂时无法重塑剧情，请稍后重试。");
        // These are settled answers from the server, not a connection that might come back.
        const rejected = e !== null && typeof e === "object" && "code" in e
          && ["invalid_request", "creation_queue_full", "scene_conflict", "choice_resolving",
            "restructure_in_progress"].includes(String(e.code));
        if (missingTask(e) || rejected) {
          savedJob(save, null);
          const existing = currentJob.current
            ?? { id: "", stage: "重塑中断", progress: 0, logs: [], sessionId: save };
          currentJob.current = { ...existing, status: "FAILED", error: message };
          setJob(currentJob.current);
        }
        setError(message);
      }
    } finally {
      if (!active.signal.aborted) { watching.current = false; setPending(false); }
    }
  }, [follow]);

  const reset = useCallback(() => {
    controller.current?.abort();
    watching.current = false;
    currentJob.current = null;
    setJob(null);
    setError(null);
    setPending(false);
  }, []);

  // A reload while the story was being rewritten resumes the same job rather than starting one.
  useEffect(() => {
    if (sessionId && savedJob(sessionId)) void run(sessionId);
    return () => { controller.current?.abort(); watching.current = false; };
  }, [run, sessionId]);

  return {
    job,
    pending,
    error,
    running: pending || (job !== null && job.status !== "FAILED" && job.status !== "READY"),
    begin: (request: RestructureRequest) => { if (sessionId) void run(sessionId, request); },
    retry: () => { if (sessionId && (currentJob.current || savedJob(sessionId))) void run(sessionId); },
    onReady,
    reset,
  };
}
