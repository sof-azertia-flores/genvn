import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { api } from "../api";
import type { HistoryPage } from "../types";

interface Props {
  sessionId: string;
  throughSceneId: string;
  /** Last completely read block; -1 means no part of this scene has been read yet. */
  throughBlockIndex: number;
  onClose: () => void;
  onRewind?: (sceneId: string) => void;
}

/** Where the reader should land once the next page has rendered. */
type ScrollPlan =
  | { mode: "bottom" }
  | { mode: "keep"; atBottom: boolean }
  | { mode: "anchor"; height: number; top: number };

/** Within this many pixels of the end counts as "reading the latest", so new text keeps you there. */
const BOTTOM_TOLERANCE = 12;

/** Only asks for canonical history through the reader's current position. */
export default function HistoryDialog({ sessionId, throughSceneId, throughBlockIndex, onClose, onRewind }: Props) {
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
      if (operation === generation.current) setError(failure instanceof Error ? failure.message : "暂时无法读取剧情回顾。");
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
      <header className="task-header"><div><span className="eyebrow">THE PATH YOU HAVE TAKEN</span><h2 id="history-title">剧情回顾</h2></div><button className="dialog-close" ref={closeButton} onClick={close} aria-label="关闭剧情回顾">×</button></header>
      <p className="task-intro" id="history-description">重读已经走过的情节。点「从这里重新选择」会回到那一幕，骰子保持原样，你可以改选另一条路。</p>
      <div className="history-body" ref={body} tabIndex={0} aria-label="已读剧情">
        {page?.nextBeforeSceneId && <button className="history-earlier" disabled={loading} onClick={() => void load(page.nextBeforeSceneId ?? undefined)}>{loading ? "正在翻阅…" : "更早的剧情 ↑"}</button>}
        {error && <div className="queue-notice" role="alert">{error}<button className="icon-btn" disabled={loading} onClick={() => void load(page?.nextBeforeSceneId ?? undefined)}>重试读取</button></div>}
        {loading && !page && <p className="queue-empty" role="status">正在翻开故事记录…</p>}
        {!loading && !error && entries.length === 0 && <div className="queue-empty"><span>☷</span><p>读完第一段文字后，它就会留在这里。</p></div>}
        {entries.map((entry) => <article className="history-scene" key={entry.sceneId}>
          <div className="history-divider"><i /><span>故事片段</span><i /></div>
          {entry.choiceText && <div className="history-choice"><span>你的选择</span><p>{entry.choiceText}</p>{entry.rollSummary && <small>🎲 {entry.rollSummary}</small>}</div>}
          {entry.blocks.map((block, index) => <div key={`${entry.sceneId}:${index}`} className={`history-line ${block.type}`}>
            {block.type === "dialogue" && <strong>{block.speakerName ?? (block.speakerId === "player" ? "你" : "人物")}</strong>}
            <p>{block.text}</p>
          </div>)}
          {entry.restorable && onRewind && (
            <button className="history-rewind" onClick={(event) => { event.stopPropagation(); onRewind(entry.sceneId); }}>
              从这里重新选择
            </button>
          )}
        </article>)}
      </div>
      <footer className="task-footer"><span>只记录已经读过的故事，未来仍然留白。</span><button onClick={close}>返回此刻 ↗</button></footer>
    </div>
  </div>;
}
