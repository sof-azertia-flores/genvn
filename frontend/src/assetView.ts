import type { AssetView, AssetsStatus } from "./types";

/**
 * Pure picture-resolution rules, kept import-free so they can be unit tested outside a browser.
 *
 * The one rule that matters: a reference resolves to its own picture, or to the SAME subject's
 * base portrait while a variant is still generating, or to nothing. Never to another character,
 * never to another place, never to a state the story has not reached.
 */
export function assetById(status: AssetsStatus | null, id: string | null | undefined): AssetView | null {
  if (!status || !id) return null;
  return status.assets.find((a) => a.assetId === id) ?? null;
}

export function displayUrl(status: AssetsStatus | null, id: string | null | undefined): string | null {
  const a = assetById(status, id);
  if (!a) return null;
  if (a.status === "READY" && a.url) return a.url;
  if (a.kind === "PORTRAIT_VARIANT" && a.dependsOn) {
    const base = assetById(status, a.dependsOn);
    if (base && base.subjectId === a.subjectId && base.status === "READY" && base.url) return base.url;
  }
  return null;
}

/** A dialogue expression may use a ready sprite, but never another face or dice outcome. */
export function portraitUrl(status: AssetsStatus | null, subjectId: string,
  expression: string | null | undefined, sceneAssetId: string | null | undefined,
  outcome: string | null | undefined): string | null {
  const applies = (asset: AssetView | null | undefined): asset is AssetView => Boolean(asset
    && asset.subjectId === subjectId && (asset.kind === "PORTRAIT" || asset.kind === "PORTRAIT_VARIANT")
    && (asset.applicability == null || asset.applicability === "GENERAL"
      || asset.applicability.toUpperCase() === `OUTCOME:${(outcome ?? "NONE").toUpperCase()}`));
  const ready = (asset: AssetView | null | undefined) => applies(asset) && asset.status === "READY" ? asset.url : null;
  const requestedExpression = expression?.trim().toLowerCase();
  const variant = requestedExpression === "neutral" ? "base" : requestedExpression;
  if (variant) {
    const matched = status?.assets.find((asset) => applies(asset)
      && asset.variant.toLowerCase() === variant && asset.status === "READY" && asset.url);
    if (matched) return matched.url;
  }
  const assigned = assetById(status, sceneAssetId);
  const assignedUrl = ready(assigned);
  if (assignedUrl) return assignedUrl;
  if (applies(assigned) && assigned.kind === "PORTRAIT_VARIANT") {
    const referenceUrl = ready(assetById(status, assigned.dependsOn));
    if (referenceUrl) return referenceUrl;
  }
  const base = status?.assets.find((asset) => applies(asset) && asset.variant === "base"
    && asset.status === "READY" && asset.url);
  return base?.url ?? null;
}

/** What to warm: what is on screen first, then a few READY pictures by priority. Deduplicated. */
export function preloadUrls(
  status: AssetsStatus | null,
  wanted: (string | null | undefined)[],
  extra = 4,
  excluded: ReadonlySet<string> = new Set(),
  upcomingBeatIds: readonly string[] = [],
): string[] {
  if (!status) return [];
  const urls: string[] = [];
  const seen = new Set<string>();
  const add = (u: string | null) => {
    if (u && !seen.has(u) && !excluded.has(u)) {
      seen.add(u);
      urls.push(u);
    }
  };
  for (const id of wanted) add(displayUrl(status, id));
  // Then a few ADDITIONAL ready pictures by priority -- ones not already on screen.
  let added = 0;
  const beatRank = (a: AssetView) => {
    const index = a.beatId ? upcomingBeatIds.indexOf(a.beatId) : -1;
    return index < 0 ? upcomingBeatIds.length : index;
  };
  for (const a of [...status.assets].filter((x) => x.status === "READY" && x.url)
    .sort((x, y) => beatRank(x) - beatRank(y) || x.priority - y.priority)) {
    if (added >= extra) break;
    if (a.url && !seen.has(a.url) && !excluded.has(a.url)) {
      add(a.url);
      added++;
    }
  }
  return urls;
}
