import { useEffect, useRef, useState } from "react";
import CompilationProgress from "./CompilationProgress";
import { useT } from "../i18n";
import type { CreationJobView } from "../types";

interface Props {
  /** The scene the rewrite starts from: the one on screen, or one chosen from the history. */
  anchorLabel: string | null;
  /** False on an old save whose opening was written before pre-states were stored. */
  canRestructure: boolean;
  job: CreationJobView | null;
  running: boolean;
  error: string | null;
  onSubmit: (instruction: string) => void;
  onRetry: () => void;
  onClose: () => void;
}

const MAX_CHARS = 2000;

/**
 * Where the player says what they want the story to become. While the job runs the same dialog
 * becomes its progress panel, so the words they typed and the work they caused stay in one place.
 */
export default function RestructureDialog({ anchorLabel, canRestructure, job, running, error, onSubmit, onRetry, onClose }: Props) {
  const tr = useT();
  const [instruction, setInstruction] = useState("");
  const [closing, setClosing] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const logWindow = useRef<HTMLOListElement>(null);
  const failed = job?.status === "FAILED";
  // Closing mid-rewrite would only hide it: the job keeps running on the server either way.
  const close = () => { if (!running) setClosing(true); };

  useEffect(() => {
    if (!closing) return;
    const timer = window.setTimeout(onClose, 160);
    return () => window.clearTimeout(timer);
  }, [closing, onClose]);

  useEffect(() => {
    if (logWindow.current) logWindow.current.scrollTop = logWindow.current.scrollHeight;
  }, [job?.logs.length]);

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    (field.current ?? closeButton.current)?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close(); }
      if (event.key !== "Tab") return;
      // Unlike the history dialog this one holds a text field, so the trap has to include it.
      const items = Array.from(panel.current?.querySelectorAll<HTMLElement>(
        "button:not(:disabled), textarea:not(:disabled), input:not(:disabled), [tabindex='0']") ?? []);
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && (document.activeElement === first || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); last?.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); first?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => { window.removeEventListener("keydown", onKey); previous?.focus(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const ready = canRestructure && !running && instruction.trim().length >= 4;
  const submit = () => { if (ready) onSubmit(instruction.trim()); };

  return <div className={`task-overlay restructure-overlay ${closing ? "is-closing" : ""}`} onClick={close}>
    <div className="task-dialog restructure-dialog" ref={panel} role="dialog" aria-modal="true"
      aria-labelledby="restructure-title" aria-describedby="restructure-description"
      onClick={(event) => event.stopPropagation()}>
      <header className="task-header">
        <div><span className="eyebrow">REWRITE WHAT COMES NEXT</span><h2 id="restructure-title">{tr("restructure")}</h2></div>
        <button className="dialog-close" ref={closeButton} onClick={close} disabled={running}
          aria-label={tr("restructureClose")}>×</button>
      </header>
      <p className="task-intro" id="restructure-description">
        {anchorLabel ? tr("restructureIntroAt", { scene: anchorLabel }) : tr("restructureIntro")}
      </p>

      {running || job ? (
        <div className="restructure-progress">
          <ol className="compile-log" ref={logWindow} role="log" aria-live="polite" aria-relevant="additions"
            aria-label={tr("restructureLogAria")}>
            <li><time>···</time><span>{tr("restructurePreface")}</span></li>
            {job?.logs.map((entry) => <li key={entry.id}>
              <time dateTime={entry.time}>{new Date(entry.time).toLocaleTimeString("zh-CN", { hour12: false })}</time>
              <span>{entry.message}</span>
            </li>)}
          </ol>
          <CompilationProgress job={job} pending={running && !job} heading={tr("restructureProgress")}
            readyHeading={tr("restructureDone")} />
          <p className="compile-footnote">{tr("restructureFoot")}</p>
        </div>
      ) : (
        <div className="restructure-compose">
          {!canRestructure && <div className="queue-notice" role="status">{tr("restructureUnavailable")}</div>}
          <label className="restructure-label" htmlFor="restructure-input">{tr("restructurePrompt")}</label>
          <textarea id="restructure-input" ref={field} value={instruction} disabled={!canRestructure}
            maxLength={MAX_CHARS} placeholder={tr("restructurePlaceholder")}
            onChange={(event) => setInstruction(event.target.value.slice(0, MAX_CHARS))}
            onKeyDown={(event) => {
              // Enter is a newline here: this is prose, not a search box. Ctrl/Cmd+Enter sends.
              if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) { event.preventDefault(); submit(); }
            }} />
          <div className="restructure-meta">
            <small>{tr("restructureHint")}</small>
            <small aria-hidden="true">{instruction.length}/{MAX_CHARS}</small>
          </div>
        </div>
      )}

      {error && <div className="queue-notice" role="alert">{error}</div>}

      <footer className="task-footer">
        <span>{running ? tr("restructureWorking") : tr("restructureWarning")}</span>
        <div className="restructure-actions">
          {failed && <button className="btn ghost" onClick={onRetry}>{tr("restructureRetry")}</button>}
          {!running && !job && <button className="btn" disabled={!ready} onClick={submit}>{tr("restructureSubmit")}</button>}
          <button onClick={close} disabled={running}>{running ? tr("restructureWorking") : tr("backToNow")}</button>
        </div>
      </footer>
    </div>
  </div>;
}
