import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { AssetsStatus, SessionTasksView } from "../types";
import { friendlyPause, pictureKind, sortedPictures, TASK_STATUS } from "../taskPresentation";

interface Props {
  sessionId: string;
  sceneId: string;
  assets: AssetsStatus | null;
  onClose: () => void;
  onInspect: () => void;
}

const SECOND_ROUND_STATUS: Record<string, string> = {
  waiting_first_round: "等待第一轮完成", estimating: "正在估计选择偏好", generating: "正在准备后续走向",
  ready: "后续走向已就绪", skipped: "本次无需继续预备",
  completed_with_failures: "后续准备已结束，未完成的会在选择时继续准备",
};
function estimatedProbability(value: number): string {
  return Number.isFinite(value) ? `${Math.round(Math.max(0, Math.min(1, value)) * 1000) / 10}%` : "暂无估计";
}

export default function TaskQueue({ sessionId, sceneId, assets, onClose, onInspect }: Props) {
  const [tasks, setTasks] = useState<SessionTasksView | null>(null);
  const [readFailed, setReadFailed] = useState(false);
  const [retrySnapshot, setRetrySnapshot] = useState<AssetsStatus | null>(null);
  const [retrying, setRetrying] = useState<string[]>([]);
  const [retryErrors, setRetryErrors] = useState<Record<string, string>>({});
  const retryRequests = useRef(new Set<string>());
  const lifetime = useRef(0);
  useEffect(() => {
    lifetime.current++;
    setRetrySnapshot(null); setRetrying([]); setRetryErrors({}); retryRequests.current.clear();
    return () => { lifetime.current++; };
  }, [sessionId]);
  const retryPicture = async (assetId: string) => {
    if (retryRequests.current.has(assetId)) return;
    retryRequests.current.add(assetId);
    const request = lifetime.current;
    setRetrying((ids) => [...ids, assetId]);
    setRetryErrors((errors) => ({ ...errors, [assetId]: "" }));
    try {
      const result = await api.retryAsset(sessionId, assetId);
      if (request === lifetime.current) setRetrySnapshot((previous) => previous && previous.version > result.version ? previous : result);
    } catch (error) {
      if (request === lifetime.current) setRetryErrors((errors) => ({ ...errors, [assetId]: error instanceof Error ? error.message : "暂时无法重新生成，请稍后重试。" }));
    } finally {
      if (request === lifetime.current) {
        retryRequests.current.delete(assetId);
        setRetrying((ids) => ids.filter((id) => id !== assetId));
      }
    }
  };
  const pictureStatus = retrySnapshot && (!assets || retrySnapshot.version > assets.version) ? retrySnapshot : assets;
  const [closing, setClosing] = useState(false);
  const [tab, setTab] = useState<"story" | "pictures">("story");
  const panel = useRef<HTMLDivElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const close = () => setClosing(true);
  useEffect(() => {
    if (!closing) return;
    const timer = window.setTimeout(onClose, 160);
    return () => window.clearTimeout(timer);
  }, [closing, onClose]);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButton.current?.focus();
    const keydown = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); setClosing(true); }
      if (event.key !== "Tab") return;
      const items = Array.from(panel.current?.querySelectorAll<HTMLElement>("button:not(:disabled), a[href], summary, [tabindex='0']") ?? []);
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && (document.activeElement === first || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); last?.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); first?.focus();
      }
    };
    window.addEventListener("keydown", keydown);
    return () => { window.removeEventListener("keydown", keydown); previous?.focus(); };
  }, []);
  useEffect(() => {
    setTasks(null); setReadFailed(false);
    let active = true;
    let timer: number;
    const controller = new AbortController();
    const poll = async () => {
      try {
        const result = await api.tasks(sessionId, controller.signal);
        if (!active) return;
        setTasks(result); setReadFailed(false);
      } catch { if (active) setReadFailed(true); }
      if (active) timer = window.setTimeout(poll, 2000);
    };
    void poll();
    return () => { active = false; controller.abort(); window.clearTimeout(timer); };
  }, [sessionId, sceneId]);

  const current = tasks?.sceneId === sceneId ? tasks : null;
  const branches = current?.branches ?? [];
  const choices = [...new Set(branches.map((b) => b.choiceId))];
  const secondRound = current?.secondRound;
  const secondCandidates = secondRound?.candidates.slice(0, 4) ?? [];
  const branchActive = branches.filter((b) => b.status === "generating").length + secondCandidates.filter((b) => b.status === "generating").length;
  const branchReady = branches.filter((b) => b.status === "ready").length + secondCandidates.filter((b) => b.status === "ready").length;
  const pictures = sortedPictures(pictureStatus?.assets ?? []);
  const pictureReady = pictures.filter((a) => a.status === "READY").length;

  return (
    <div className={`task-overlay ${closing ? "is-closing" : ""}`} onClick={close}>
      <div className="task-dialog" ref={panel} role="dialog" aria-modal="true" aria-labelledby="task-title" aria-describedby="task-description" onClick={(e) => e.stopPropagation()}>
        <header className="task-header">
          <div><span className="eyebrow">THE STORY CONTINUES</span><h2 id="task-title">幕后准备</h2></div>
          <button ref={closeButton} className="dialog-close" onClick={close} aria-label="关闭幕后准备">×</button>
        </header>
        <p id="task-description" className="task-intro">你阅读的同时，故事与画面也在继续准备。</p>
        <div className="task-overview" aria-live="polite">
          <div><b>{branchActive}{current?.speculationEnabled && <small> / {current.speculationConcurrency}</small>}</b><span>条走向正在同时准备</span></div>
          <div><b>{pictureStatus?.active ?? 0}<small> / {pictureStatus?.concurrency ?? "—"}</small></b><span>个绘图位置正在使用</span></div>
          <div><b>{branchReady + pictureReady}</b><span>项内容已就绪</span></div>
        </div>
        <div className="queue-tabs" role="tablist" aria-label="生成任务类型">
          <button role="tab" id="story-tab" aria-selected={tab === "story"} aria-controls="story-queue" tabIndex={tab === "story" ? 0 : -1} className={tab === "story" ? "active" : ""} onClick={() => setTab("story")} onKeyDown={(e) => { if (e.key === "ArrowRight" || e.key === "ArrowLeft") { e.preventDefault(); setTab("pictures"); panel.current?.querySelector<HTMLButtonElement>("#pictures-tab")?.focus(); } }}>剧情预推演 <span>{branches.length + secondCandidates.length}</span></button>
          <button role="tab" id="pictures-tab" aria-selected={tab === "pictures"} aria-controls="picture-queue" tabIndex={tab === "pictures" ? 0 : -1} className={tab === "pictures" ? "active" : ""} onClick={() => setTab("pictures")} onKeyDown={(e) => { if (e.key === "ArrowRight" || e.key === "ArrowLeft") { e.preventDefault(); setTab("story"); panel.current?.querySelector<HTMLButtonElement>("#story-tab")?.focus(); } }}>画面素材 <span>{pictures.length}</span></button>
        </div>
        <div className="queue-body" id={tab === "story" ? "story-queue" : "picture-queue"} role="tabpanel" aria-labelledby={tab === "story" ? "story-tab" : "pictures-tab"} tabIndex={0}>
          {tab === "story" ? (
            <>
              <p className="queue-explainer">提前准备几种可能的走向。需要检定的选项，骰子已在幕后掷好并封存；只有你的选择会揭开它，让其中一条成为故事。</p>
              {readFailed && <p className="queue-notice" role="status">暂时无法更新进度，正在尝试重新连接。</p>}
              {current?.resolvingChoiceId && <p className="queue-notice">正在完成你刚刚选择的走向。</p>}
              {current?.continuationPending && <div className="queue-row"><span className="queue-dot generating" /><div className="queue-copy"><strong>下一章的故事框架</strong><small>故事正在延伸</small></div><span className="queue-state generating">正在构思</span></div>}
              {branches.length === 0 && <div className="queue-empty"><span>✧</span><p>{!current ? "正在读取幕后进度…" : !current.speculationEnabled ? "预推演暂未开启，选择后会继续写作。" : "这一幕暂时没有需要预先准备的走向。"}</p></div>}
              {secondRound && branches.length > 0 && <h3 className="queue-round-title"><span>第一轮</span>眼前的选择</h3>}
              {branches.map((branch) => (
                <div className="queue-row" key={branch.id}>
                  <span className={`queue-dot ${branch.status}`} />
                  <div className="queue-copy"><strong>可能的走向 {String(choices.indexOf(branch.choiceId) + 1).padStart(2, "0")}</strong><small>{branch.check ? "含骰子检定 · 结果已封存" : "直接行动"}{branch.error ? ` · ${branch.error}` : ""}</small></div>
                  <span className={`queue-state ${branch.status}`}>{TASK_STATUS[branch.status]}{branch.status === "generating" && branch.elapsedMillis != null && branch.elapsedMillis >= 1000 && <small>{Math.floor(branch.elapsedMillis / 1000)} 秒</small>}</span>
                </div>
              ))}
              {secondRound && <section className="queue-second-round" aria-labelledby="second-round-title">
                <div className="queue-round-heading"><h3 className="queue-round-title" id="second-round-title"><span>第二轮</span>再往前一步</h3><span className="queue-round-status" role="status">{SECOND_ROUND_STATUS[secondRound.status] ?? "正在读取进度"}</span></div>
                <p className="queue-explainer">第一轮完成后，参考你过去的选择习惯，将连续两次选择的估计概率相乘，优先准备排名前 4 条后续走向。这个估计只决定准备顺序，你仍可以自由选择。</p>
                {secondCandidates.map((candidate, index) => <div className="queue-row" key={candidate.key}>
                  <span className={`queue-dot ${candidate.status}`} />
                  <div className="queue-copy"><strong>后续候选 {String(index + 1).padStart(2, "0")}</strong><small>{choices.includes(candidate.parentChoiceId) ? `接在走向 ${String(choices.indexOf(candidate.parentChoiceId) + 1).padStart(2, "0")} 之后 · ` : ""}路径估计 {estimatedProbability(candidate.probability)}</small></div>
                  <span className={`queue-state ${candidate.status}`}>{TASK_STATUS[candidate.status] ?? "等待中"}</span>
                </div>)}
              </section>}
            </>
          ) : (
            <>
              <p className="queue-explainer">先准备场景与人物，再慢慢展开故事。角色卡固定保留，姿势变化只绘制透明立绘。</p>
              {pictureStatus?.budget?.paused && <p className="queue-notice">{friendlyPause(pictureStatus.budget.pauseReason)}</p>}
              {pictureStatus?.manifestError && <p className="queue-notice" role="alert">这局的素材记录文件无法读取，暂时不会生成新图片；故事可以继续。修复或删除该文件后重启后端即可恢复。</p>}
              {pictures.length === 0 && <div className="queue-empty"><span>✧</span><p>{!pictureStatus ? "正在读取素材进度…" : !pictureStatus.enabled ? "图片生成尚未开启，当前使用氛围画面。" : "画面计划正在准备。"}</p></div>}
              {pictures.map((asset) => (
                <div className="queue-row" key={asset.assetId}>
                  <span className={`queue-dot ${asset.status.toLowerCase()}`} />
                  <div className="queue-copy"><strong>{asset.subjectName} <span>· {pictureKind(asset)}</span></strong><small>{asset.dependsOn && asset.status !== "READY" ? "使用同一人物的基础立绘作为参考" : asset.status === "READY" ? "已保存在本地，可随时使用" : asset.status === "PLANNED" ? "已列入画面计划，按需要逐步准备" : asset.status === "FAILED" ? "这张画面暂未完成，不影响继续阅读" : "文字与画面分开准备，互不等待"}</small>{asset.failureReason && <details className="queue-detail"><summary>查看原因</summary>{asset.failureReason}</details>}</div>
                  <div className="queue-actions"><span className={`queue-state ${asset.status.toLowerCase()}`}>{TASK_STATUS[asset.status]}</span>
                    {asset.status === "FAILED" && <button className="regenerate-button" disabled={retrying.includes(asset.assetId)} onClick={() => void retryPicture(asset.assetId)} aria-label={`重新生成${asset.subjectName}的${pictureKind(asset)}`}>{retrying.includes(asset.assetId) ? "正在重新排队…" : "重新生成"}</button>}
                    {retryErrors[asset.assetId] && <small className="retry-error" role="alert">{retryErrors[asset.assetId]}</small>}
                  </div>
                </div>
              ))}
            </>
          )}
        </div>
        <footer className="task-footer"><span>未来的内容会保持隐藏，直到你走到那里。</span><button onClick={onInspect}>调试详情 ↗</button></footer>
      </div>
    </div>
  );
}
