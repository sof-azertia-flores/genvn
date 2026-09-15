import type { AssetStatusName, AssetView } from "./types";
import { t, type Lang } from "./i18n";

const TASK_KEYS: Record<string, string> = {
  queued: "taskQueued", generating: "taskGenerating", ready: "taskReady", failed: "taskFailed", cancelled: "taskCancelled",
  PLANNED: "picPlanned", QUEUED: "picQueued", GENERATING: "picGenerating", READY: "picReady", FAILED: "picFailed", PAUSED: "picPaused", MISSING: "picMissing",
};
const KIND_KEYS: Record<string, string> = {
  BACKGROUND: "kindBackground", CHARACTER_CARD: "kindCard", PORTRAIT_VARIANT: "kindPose", PORTRAIT: "kindPortrait",
};
const rank: Record<AssetStatusName, number> = { GENERATING: 0, QUEUED: 1, FAILED: 2, PAUSED: 3, MISSING: 4, PLANNED: 5, READY: 6 };

export function sortedPictures(assets: AssetView[]) { return [...assets].sort((a, b) => rank[a.status] - rank[b.status] || a.priority - b.priority); }

export function taskStatus(lang: Lang, status: string): string {
  const key = TASK_KEYS[status];
  return key ? t(lang, key) : status;
}

export function pictureKind(lang: Lang, asset: AssetView): string {
  const key = KIND_KEYS[asset.kind] ?? "kindPortrait";
  return t(lang, key);
}

export function friendlyPause(lang: Lang, reason: string | null | undefined): string {
  if (!reason) return t(lang, "pauseGeneric");
  if (/[\u4e00-\u9fff]/u.test(reason)) return reason;
  if (/budget|limit|quota/i.test(reason)) return t(lang, "pauseBudget");
  if (/persist|disk|manifest|writ/i.test(reason)) return t(lang, "pauseDisk");
  if (/format|webp/i.test(reason)) return t(lang, "pauseFormat");
  if (/key|credential|disabled|configured/i.test(reason)) return t(lang, "pauseKey");
  return t(lang, "pauseDown");
}
