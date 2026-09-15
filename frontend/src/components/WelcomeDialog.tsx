import { useEffect, useRef, useState } from "react";
import { useT } from "../i18n";

interface Props {
  /** Hide the art-direction page when the deployment has pictures turned off. */
  imageEnabled: boolean;
  /** Called on finish and on skip alike: either way this browser has been offered the guide. */
  onDone: () => void;
}

interface Page { title: string; body: string }

/**
 * The first thing a new browser sees. Five short pages that answer the questions the setup
 * screen cannot answer by itself: what this is, what an outline is for, what the talent points
 * do, who rolls the dice, and where the art style comes from.
 *
 * Skipping counts as seeing it -- a player who does not want the guide should not be asked twice.
 */
export default function WelcomeDialog({ imageEnabled, onDone }: Props) {
  const tr = useT();
  const [step, setStep] = useState(0);
  const [closing, setClosing] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  const primary = useRef<HTMLButtonElement>(null);

  const pages: Page[] = [
    { title: tr("guideW1Title"), body: tr("guideW1Body") },
    { title: tr("guideW2Title"), body: tr("guideW2Body") },
    { title: tr("guideW3Title"), body: tr("guideW3Body") },
    { title: tr("guideW4Title"), body: tr("guideW4Body") },
    ...(imageEnabled ? [{ title: tr("guideW5Title"), body: tr("guideW5Body") }] : []),
  ];
  const last = step >= pages.length - 1;
  const finish = () => setClosing(true);

  useEffect(() => {
    if (!closing) return;
    const timer = window.setTimeout(onDone, 160);
    return () => window.clearTimeout(timer);
  }, [closing, onDone]);

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    primary.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); finish(); }
      if (event.key !== "Tab") return;
      const items = Array.from(panel.current?.querySelectorAll<HTMLElement>("button:not(:disabled)") ?? []);
      const first = items[0], end = items[items.length - 1];
      if (event.shiftKey && (document.activeElement === first || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); end?.focus();
      } else if (!event.shiftKey && (document.activeElement === end || !panel.current?.contains(document.activeElement))) {
        event.preventDefault(); first?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => { window.removeEventListener("keydown", onKey); previous?.focus(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const page = pages[Math.min(step, pages.length - 1)];
  return <div className={`task-overlay guide-overlay ${closing ? "is-closing" : ""}`}>
    <div className="task-dialog guide-dialog" ref={panel} role="dialog" aria-modal="true"
      aria-labelledby="guide-title" aria-describedby="guide-body">
      <header className="task-header">
        <div>
          <span className="eyebrow">{tr("guideWelcomeEyebrow")}</span>
          <h2 id="guide-title">{page.title}</h2>
        </div>
        <button className="dialog-close" onClick={finish} aria-label={tr("guideSkip")}>×</button>
      </header>
      {step === 0 && <p className="guide-first" role="status">{tr("guideNewBrowser")}</p>}
      <p className="guide-body" id="guide-body">{page.body}</p>
      <div className="guide-dots" role="status" aria-label={tr("guideStep", { n: step + 1, total: pages.length })}>
        {pages.map((_, index) => <i key={index} className={index === step ? "on" : ""} aria-hidden="true" />)}
        <small>{tr("guideStep", { n: step + 1, total: pages.length })}</small>
      </div>
      <footer className="task-footer">
        <button className="text-button guide-skip" onClick={finish}>{tr("guideSkip")}</button>
        <div className="guide-actions">
          {step > 0 && <button onClick={() => setStep((n) => Math.max(0, n - 1))}>{tr("guidePrev")}</button>}
          <button className="btn" ref={primary}
            onClick={() => (last ? finish() : setStep((n) => n + 1))}>
            {last ? tr("guideDone") : tr("guideNext")}
          </button>
        </div>
      </footer>
    </div>
  </div>;
}
