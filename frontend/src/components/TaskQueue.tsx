import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { AssetsStatus, SessionTasksView } from "../types";
import { friendlyPause, pictureKind, sortedPictures, taskStatus } from "../taskPresentation";
import { useLocale, useT } from "../i18n";

interface Props {
  sessionId: string;
  sceneId: string;
  assets: AssetsStatus | null;
  onClose: () => void;
  onInspect: () => void;
}

const SECOND_ROUND_KEYS: Record<string, string> = {
  waiting_first_round: "secondWaiting", estimating: "secondEstimating", generating: "secondGenerating",
  ready: "secondReady", skipped: "secondSkipped",
  completed_with_failures: "secondPartial",
};

function pad2(n: number): string {
  return String(n).padStart(2, "0");
}

export default function TaskQueue({ sessionId, sceneId, assets, onClose, onInspect }: Props) {
  const { lang } = useLocale();
  const tr = useT();
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
      if (request === lifetime.current) setRetryErrors((errors) => ({ ...errors, [assetId]: error instanceof Error ? error.message : tr("retryFailed") }));
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
  const estimatedProbability = (value: number) => Number.isFinite(value)
    ? `${Math.round(Math.max(0, Math.min(1, value)) * 1000) / 10}%`
    : tr("noEstimate");

  return (
    <div className={`task-overlay ${closing ? "is-closing" : ""}`} onClick={close}>
      <div className="task-dialog" ref={panel} role="dialog" aria-modal="true" aria-labelledby="task-title" aria-describedby="task-description" onClick={(e) => e.stopPropagation()}>
        <header className="task-header">
          <div><span className="eyebrow">THE STORY CONTINUES</span><h2 id="task-title">{tr("queue")}</h2></div>
          <button ref={closeButton} className="dialog-close" onClick={close} aria-label={tr("queueClose")}>×</button>
        </header>
        <p id="task-description" className="task-intro">{tr("queueIntro")}</p>
        <div className="task-overview" aria-live="polite">
          <div><b>{branchActive}{current?.speculationEnabled && <small> / {current.speculationConcurrency}</small>}</b><span>{tr("queueBranchesBusy")}</span></div>
          <div><b>{pictureStatus?.active ?? 0}<small> / {pictureStatus?.concurrency ?? "—"}</small></b><span>{tr("queuePicturesBusy")}</span></div>
          <div><b>{branchReady + pictureReady}</b><span>{tr("queueReadyCount")}</span></div>
        </div>
        <div className="queue-tabs" role="tablist" aria-label={tr("queueTabsAria")}>
          <button role="tab" id="story-tab" aria-selected={tab === "story"} aria-controls="story-queue" tabIndex={tab === "story" ? 0 : -1} className={tab === "story" ? "active" : ""} onClick={() => setTab("story")} onKeyDown={(e) => { if (e.key === "ArrowRight" || e.key === "ArrowLeft") { e.preventDefault(); setTab("pictures"); panel.current?.querySelector<HTMLButtonElement>("#pictures-tab")?.focus(); } }}>{tr("queueStoryTab")} <span>{branches.length + secondCandidates.length}</span></button>
          <button role="tab" id="pictures-tab" aria-selected={tab === "pictures"} aria-controls="picture-queue" tabIndex={tab === "pictures" ? 0 : -1} className={tab === "pictures" ? "active" : ""} onClick={() => setTab("pictures")} onKeyDown={(e) => { if (e.key === "ArrowRight" || e.key === "ArrowLeft") { e.preventDefault(); setTab("story"); panel.current?.querySelector<HTMLButtonElement>("#story-tab")?.focus(); } }}>{tr("queuePictureTab")} <span>{pictures.length}</span></button>
        </div>
        <div className="queue-body" id={tab === "story" ? "story-queue" : "picture-queue"} role="tabpanel" aria-labelledby={tab === "story" ? "story-tab" : "pictures-tab"} tabIndex={0}>
          {tab === "story" ? (
            <>
              <p className="queue-explainer">{tr("queueStoryExplainer")}</p>
              {readFailed && <p className="queue-notice" role="status">{tr("queueReconnect")}</p>}
              {current?.resolvingChoiceId && <p className="queue-notice">{tr("queueResolving")}</p>}
              {current?.continuationPending && <div className="queue-row"><span className="queue-dot generating" /><div className="queue-copy"><strong>{tr("nextArc")}</strong><small>{tr("nextArcSmall")}</small></div><span className="queue-state generating">{tr("nextArcState")}</span></div>}
              {branches.length === 0 && <div className="queue-empty"><span>✧</span><p>{!current ? tr("queueEmptyLoading") : !current.speculationEnabled ? tr("queueSpecOff") : tr("queueNoBranches")}</p></div>}
              {secondRound && branches.length > 0 && <h3 className="queue-round-title"><span>{tr("roundOne")}</span>{tr("roundOneSub")}</h3>}
              {branches.map((branch) => (
                <div className="queue-row" key={branch.id}>
                  <span className={`queue-dot ${branch.status}`} />
                  <div className="queue-copy"><strong>{tr("possiblePath", { n: pad2(choices.indexOf(branch.choiceId) + 1) })}</strong><small>{branch.check ? tr("withCheck") : tr("directAction")}{branch.error ? ` · ${branch.error}` : ""}</small></div>
                  <span className={`queue-state ${branch.status}`}>{taskStatus(lang, branch.status)}{branch.status === "generating" && branch.elapsedMillis != null && branch.elapsedMillis >= 1000 && <small>{tr("seconds", { n: Math.floor(branch.elapsedMillis / 1000) })}</small>}</span>
                </div>
              ))}
              {secondRound && <section className="queue-second-round" aria-labelledby="second-round-title">
                <div className="queue-round-heading"><h3 className="queue-round-title" id="second-round-title"><span>{tr("roundTwo")}</span>{tr("roundTwoSub")}</h3><span className="queue-round-status" role="status">{tr(SECOND_ROUND_KEYS[secondRound.status] ?? "secondReading")}</span></div>
                <p className="queue-explainer">{tr("roundTwoExplainer")}</p>
                {secondCandidates.map((candidate, index) => <div className="queue-row" key={candidate.key}>
                  <span className={`queue-dot ${candidate.status}`} />
                  <div className="queue-copy"><strong>{tr("nextCandidate", { n: pad2(index + 1) })}</strong><small>{choices.includes(candidate.parentChoiceId) ? tr("afterPath", { n: pad2(choices.indexOf(candidate.parentChoiceId) + 1) }) : ""}{tr("pathEstimate", { p: estimatedProbability(candidate.probability) })}</small></div>
                  <span className={`queue-state ${candidate.status}`}>{taskStatus(lang, candidate.status)}</span>
                </div>)}
              </section>}
            </>
          ) : (
            <>
              <p className="queue-explainer">{tr("pictureExplainer")}</p>
              {pictureStatus?.budget?.paused && <p className="queue-notice">{friendlyPause(lang, pictureStatus.budget.pauseReason)}</p>}
              {pictureStatus?.manifestError && <p className="queue-notice" role="alert">{tr("pictureManifest")}</p>}
              {pictures.length === 0 && <div className="queue-empty"><span>✧</span><p>{!pictureStatus ? tr("picturesLoading") : !pictureStatus.enabled ? tr("picturesOff") : tr("picturesPlanning")}</p></div>}
              {pictures.map((asset) => (
                <div className="queue-row" key={asset.assetId}>
                  <span className={`queue-dot ${asset.status.toLowerCase()}`} />
                  <div className="queue-copy"><strong>{asset.subjectName} <span>· {pictureKind(lang, asset)}</span></strong><small>{asset.dependsOn && asset.status !== "READY" ? tr("usingBase") : asset.status === "READY" ? tr("savedLocal") : asset.status === "PLANNED" ? tr("inPlan") : asset.status === "FAILED" ? tr("pictureFailed") : tr("pictureSeparate")}</small>{asset.failureReason && <details className="queue-detail"><summary>{tr("seeWhy")}</summary>{asset.failureReason}</details>}</div>
                  <div className="queue-actions"><span className={`queue-state ${asset.status.toLowerCase()}`}>{taskStatus(lang, asset.status)}</span>
                    {asset.status === "FAILED" && <button className="regenerate-button" disabled={retrying.includes(asset.assetId)} onClick={() => void retryPicture(asset.assetId)} aria-label={tr("regenerateAria", { name: asset.subjectName, kind: pictureKind(lang, asset) })}>{retrying.includes(asset.assetId) ? tr("regenerating") : tr("regenerate")}</button>}
                    {retryErrors[asset.assetId] && <small className="retry-error" role="alert">{retryErrors[asset.assetId]}</small>}
                  </div>
                </div>
              ))}
            </>
          )}
        </div>
        <footer className="task-footer"><span>{tr("queueFoot")}</span><button onClick={onInspect}>{tr("inspect")}</button></footer>
      </div>
    </div>
  );
}
