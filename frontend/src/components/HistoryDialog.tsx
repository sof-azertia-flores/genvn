import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { api } from "../api";
import { useT } from "../i18n";
import type { HistoryPage } from "../types";

interface Props {
  sessionId: string;
  throughSceneId: string;
  /** Last completely read block; -1 means no part of this scene has been read yet. */
  throughBlockIndex: number;
  onClose: () => void;
  onRewind?: (sceneId: string) => void;
  /** Rewrite the story from this scene onward. Shares rewindBlocked: both mutate the save. */
  onRestructure?: (sceneId: string, label: string) => void;
  rewindBlocked?: boolean;
}

/** Where the reader should land once the next page has rendered. */
type ScrollPlan =
  | { mode: "bottom" }
  | { mode: "keep"; atBottom: boolean }
  | { mode: "anchor"; height: number; top: number };

/** Within this many pixels of the end counts as "reading the latest", so new text keeps you there. */
const BOTTOM_TOLERANCE = 12;

/** Only asks for canonical history through the reader's current position. */
export default function HistoryDialog({ sessionId, throughSceneId, throughBlockIndex, onClose, onRewind, onRestructure, rewindBlocked }: Props) {
  const tr = useT();
  const [page, setPage] = useState<HistoryPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [closing, setClosing] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const inFlight = useRef(false);
  const generation = useRef(0);
  const request = useRef<AbortController | null>(null);
  const loadedSession = useRef<string | null>(null);
  const scrollPlan = useRef<ScrollPlan | null>(null);
  const close = () => setClosing(true);

  const load = async (beforeSceneId?: string) => {
    if (inFlight.current) return;
    inFlight.current = true;
    const operation = generation.current;
    const controller = new AbortController();
    request.current = controller;
    // Decide now, from the reader's position, where they land after this page renders:
    // an earlier page is pinned so the text under their eyes does not move; a first page
    // opens at the latest text; a refreshed page keeps them where they were, or at the
    // end if that is where they were.
    const el = body.current;
    if (beforeSceneId) scrollPlan.current = el ? { mode: "anchor", height: el.scrollHeight, top: el.scrollTop } : null;
    else if (!page) scrollPlan.current = { mode: "bottom" };
    else scrollPlan.current = { mode: "keep", atBottom: !el || el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_TOLERANCE };
    setLoading(true); setError(null);
    try {
      const result = await api.history(sessionId, { throughSceneId, throughBlockIndex, beforeSceneId, limit: 20 }, controller.signal);
      if (operation !== generation.current) return;
      setPage((previous) => beforeSceneId && previous ? {
        ...result,
        entries: [...result.entries.filter((entry) => !previous.entries.some((seen) => seen.sceneId === entry.sceneId)), ...previous.entries],
      } : result);
    } catch (failure) {
      if (operation === generation.current) setError(failure instanceof Error ? failure.message : tr("historyFailed"));
    } finally {
      if (operation === generation.current) { inFlight.current = false; setLoading(false); }
    }
  };

  useEffect(() => {
    generation.current++;
    inFlight.current = false;
    // A new bound (a scene that arrived while reading) refreshes in place: the text that is
    // already on screen stays put until the replacement page has arrived. Only a different
    // save starts from nothing.
    if (loadedSession.current !== sessionId) { loadedSession.current = sessionId; setPage(null); }
    void load();
    return () => { generation.current++; request.current?.abort(); };
    // The bound is frozen by the paused reader while this dialog is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, throughSceneId, throughBlockIndex]);
  // Runs before paint, so the reader never sees the intermediate scroll position.
  useLayoutEffect(() => {
    const el = body.current;
    const plan = scrollPlan.current;
    if (!el || !plan) return;
    scrollPlan.current = null;
    if (plan.mode === "bottom" || (plan.mode === "keep" && plan.atBottom)) el.scrollTop = el.scrollHeight;
    else if (plan.mode === "anchor") el.scrollTop = el.scrollHeight - plan.height + plan.top;
  }, [page]);
  useEffect(() => {
    if (!closing) return;
    const timer = window.setTimeout(onClose, 160);
    return () => window.clearTimeout(timer);
  }, [closing, onClose]);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButton.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); setClosing(true); }
      if (event.key !== "Tab") return;
      const items = Array.from(panel.current?.querySelectorAll<HTMLElement>("button:not(:disabled), [tabindex='0']") ?? []);
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && (document.activeElement === first || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); last?.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); first?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => { window.removeEventListener("keydown", onKey); previous?.focus(); };
  }, []);

  const entries = page?.entries.filter((entry) => entry.blocks.length > 0 || entry.choiceText || entry.rollSummary) ?? [];
  return <div className={`task-overlay history-overlay ${closing ? "is-closing" : ""}`} onClick={close}>
    <div className="task-dialog history-dialog" ref={panel} role="dialog" aria-modal="true" aria-labelledby="history-title" aria-describedby="history-description" onClick={(event) => event.stopPropagation()}>
      <header className="task-header"><div><span className="eyebrow">THE PATH YOU HAVE TAKEN</span><h2 id="history-title">{tr("history")}</h2></div><button className="dialog-close" ref={closeButton} onClick={close} aria-label={tr("historyClose")}>×</button></header>
      <p className="task-intro" id="history-description">{tr("historyIntro")}</p>
      <div className="history-body" ref={body} tabIndex={0} aria-label={tr("historyAria")}>
        {page?.nextBeforeSceneId && <button className="history-earlier" disabled={loading} onClick={() => void load(page.nextBeforeSceneId ?? undefined)}>{loading ? tr("historyPaging") : tr("historyEarlier")}</button>}
        {error && <div className="queue-notice" role="alert">{error}<button className="icon-btn" disabled={loading} onClick={() => void load(page?.nextBeforeSceneId ?? undefined)}>{tr("historyRetry")}</button></div>}
        {loading && !page && <p className="queue-empty" role="status">{tr("historyOpening")}</p>}
        {!loading && !error && entries.length === 0 && <div className="queue-empty"><span>☷</span><p>{tr("historyEmpty")}</p></div>}
        {entries.map((entry) => <article className="history-scene" key={entry.sceneId}>
          <div className="history-divider"><i /><span>{tr("historyDivider")}</span><i /></div>
          {entry.choiceText && <div className="history-choice"><span>{tr("yourChoice")}</span><p>{entry.choiceText}</p>{entry.rollSummary && <small>🎲 {entry.rollSummary}</small>}</div>}
          {entry.blocks.map((block, index) => <div key={`${entry.sceneId}:${index}`} className={`history-line ${block.type}`}>
            {block.type === "dialogue" && <strong>{block.speakerName ?? (block.speakerId === "player" ? tr("you") : tr("someone"))}</strong>}
            <p>{block.text}</p>
          </div>)}
          {entry.restorable && (onRewind || onRestructure) && (
            <div className="history-actions">
              {onRewind && <button className="history-rewind" disabled={rewindBlocked} title={rewindBlocked ? tr("rewindBlocked") : undefined}
                onClick={(event) => { event.stopPropagation(); if (!rewindBlocked) onRewind(entry.sceneId); }}>
                {tr("rewindHere")}
              </button>}
              {onRestructure && <button className="history-restructure" disabled={rewindBlocked}
                title={rewindBlocked ? tr("restructureBlocked") : undefined}
                onClick={(event) => {
                  event.stopPropagation();
                  if (rewindBlocked) return;
                  // The opening line is the clearest label the recap has for a scene.
                  const label = entry.choiceText || entry.blocks[0]?.text || entry.sceneId;
                  onRestructure(entry.sceneId, label.length > 28 ? `${label.slice(0, 28)}…` : label);
                }}>
                {tr("restructureHere")}
              </button>}
            </div>
          )}
        </article>)}
      </div>
      <footer className="task-footer"><span>{tr("historyFoot")}</span><button onClick={close}>{tr("backToNow")}</button></footer>
    </div>
  </div>;
}
