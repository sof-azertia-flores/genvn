import type { AssetStatusName, AssetView } from "./types";

export const TASK_STATUS: Record<string, string> = {
  queued: "等待中", generating: "正在准备", ready: "已就绪", failed: "暂未完成", cancelled: "已取消",
  PLANNED: "已规划", QUEUED: "等待中", GENERATING: "正在绘制", READY: "已就绪", FAILED: "生成失败", PAUSED: "已暂停", MISSING: "文件缺失",
};
const rank: Record<AssetStatusName, number> = { GENERATING: 0, QUEUED: 1, FAILED: 2, PAUSED: 3, MISSING: 4, PLANNED: 5, READY: 6 };
export function sortedPictures(assets: AssetView[]) { return [...assets].sort((a, b) => rank[a.status] - rank[b.status] || a.priority - b.priority); }
export function pictureKind(asset: AssetView): string {
  return asset.kind === "BACKGROUND" ? "场景画面" : asset.kind === "CHARACTER_CARD" ? "角色卡" : asset.kind === "PORTRAIT_VARIANT" ? "透明姿势立绘" : "透明基础立绘";
}
export function friendlyPause(reason: string | null | undefined): string {
  if (!reason) return "图片准备已暂停，故事仍可继续。";
  if (/[\u4e00-\u9fff]/u.test(reason)) return reason;
  if (/budget|limit|quota/i.test(reason)) return "本章的图片生成额度已用完，已有素材仍可使用。";
  if (/persist|disk|manifest|writ/i.test(reason)) return "素材记录暂时无法保存，已暂停新的图片请求。";
  if (/format|webp/i.test(reason)) return "当前图片格式不可用，请使用 PNG 或 JPEG。";
  if (/key|credential|disabled|configured/i.test(reason)) return "图片服务尚未就绪，请检查图片生成配置。";
  return "图片服务暂时不可用，已有素材仍可使用。";
}
