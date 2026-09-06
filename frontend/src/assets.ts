import { useEffect, useState } from "react";
import { api, apiUrl } from "./api";
import type { AssetsStatus } from "./types";
import { preloadUrls } from "./assetView";

export { assetById, displayUrl } from "./assetView";

/**
 * Picture state for the current session, kept apart from scene state on purpose: a picture
 * finishing must never reset the dialogue line, replay a die, or resubmit a choice. It only
 * ever swaps what is painted behind and around the text.
 *
 * Polls faster while something is still generating, slowly once the library is quiet, and
 * ignores any response that arrives for a session we have since left.
 */
export function useAssets(sessionId: string | null): AssetsStatus | null {
  const [status, setStatus] = useState<AssetsStatus | null>(null);

  useEffect(() => {
    setStatus(null);
    if (!sessionId) return;
    let alive = true;
    let timer: number | undefined;
    const controller = new AbortController();

    const tick = async () => {
      try {
        const next = await api.assets(sessionId, controller.signal);
        if (!alive) return;
        // Picture URLs come back relative to the backend; point them at it when it is elsewhere.
        setStatus({ ...next, assets: next.assets.map((a) => (a.url ? { ...a, url: apiUrl(a.url) } : a)) });
        // Pictures switched off need no attention; a quiet library is checked now and then.
        timer = window.setTimeout(tick, !next.enabled ? 30000 : next.pending > 0 || next.active > 0 ? 1500 : 8000);
      } catch {
        if (!alive) return;
        timer = window.setTimeout(tick, 5000);
      }
    };
    void tick();
    return () => {
      alive = false;
      controller.abort();
      if (timer) window.clearTimeout(timer);
    };
  }, [sessionId]);

  return status;
}

const warmed = new Set<string>();
const warming = new Set<string>();
const retryAfter = new Map<string, number>();

/** Warm the browser cache for what is on screen and what is likely next; never the whole library. */
export function preload(status: AssetsStatus | null, wanted: (string | null | undefined)[], extra = 4,
  upcomingBeatIds: readonly string[] = []): void {
  const excluded = new Set([...warmed, ...warming]);
  for (const [u, until] of retryAfter) {
    if (until > Date.now()) excluded.add(u);
    else retryAfter.delete(u);
  }
  for (const u of preloadUrls(status, wanted, extra, excluded, upcomingBeatIds)) {
    warming.add(u);
    const img = new Image();
    const finish = (ok: boolean) => {
      window.clearTimeout(timer);
      img.onload = null;
      img.onerror = null;
      warming.delete(u);
      if (ok) { warmed.add(u); retryAfter.delete(u); }
      else retryAfter.set(u, Date.now() + 2000);
    };
    const timer = window.setTimeout(() => finish(false), 15000);
    img.onload = () => finish(true);
    img.onerror = () => finish(false);
    img.src = u;
  }
}
