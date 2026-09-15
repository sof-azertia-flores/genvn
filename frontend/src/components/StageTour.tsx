import { useEffect, useRef, useState } from "react";
import { useT } from "../i18n";

interface Props {
  /** Called on finish and on skip alike: the browser has been offered the tour either way. */
  onDone: () => void;
}

/**
 * The in-game half of the guide: five bubbles pointing at the parts of the stage a first-time
 * player has no way to guess -- that the text box advances, that choices appear at the end of a
 * scene, that the die is the engine's, and that the two buttons in the corners can move the story
 * backwards or rewrite it.
 *
 * Bubbles are anchored by CSS class rather than by measuring the elements they describe. The stage
 * layout is fixed (heading top-left, controls top-right, text box along the bottom), so an anchor
 * class lands correctly at every width and survives a scene changing underneath it -- which a
 * measured rectangle would not.
 */
const STEPS = [
  { anchor: "textbox", title: "guideT1Title", body: "guideT1Body" },
  { anchor: "textbox", title: "guideT2Title", body: "guideT2Body" },
  { anchor: "textbox", title: "guideT3Title", body: "guideT3Body" },
  { anchor: "history", title: "guideT4Title", body: "guideT4Body" },
  { anchor: "controls", title: "guideT5Title", body: "guideT5Body" },
] as const;

export default function StageTour({ onDone }: Props) {
  const tr = useT();
  const [step, setStep] = useState(0);
  const [closing, setClosing] = useState(false);
  const primary = useRef<HTMLButtonElement>(null);
  const current = STEPS[Math.min(step, STEPS.length - 1)];
  const last = step >= STEPS.length - 1;
  const finish = () => setClosing(true);

  useEffect(() => {
    if (!closing) return;
    const timer = window.setTimeout(onDone, 160);
    return () => window.clearTimeout(timer);
  }, [closing, onDone]);

  useEffect(() => {
    primary.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      // The tour sits over a stage that also listens for space and digits; swallow everything
      // it uses so a player stepping through the guide never advances the story by accident.
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); finish(); return; }
      if (event.key === " " || event.code === "Space" || event.key === "Enter"
        || event.key === "ArrowRight") {
        event.preventDefault(); event.stopPropagation();
        setStep((n) => (n >= STEPS.length - 1 ? (finish(), n) : n + 1));
        return;
      }
      if (event.key === "ArrowLeft") {
        event.preventDefault(); event.stopPropagation();
        setStep((n) => Math.max(0, n - 1));
      }
    };
    // Capture, so the stage's own keydown listener never sees these.
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <div className={`tour-layer ${closing ? "is-closing" : ""}`} role="dialog" aria-modal="true"
    aria-labelledby="tour-title" aria-describedby="tour-body">
    <div className="tour-scrim" onClick={finish} />
    <div className={`tour-spot at-${current.anchor}`} aria-hidden="true" />
    <div className={`tour-bubble at-${current.anchor}`}>
      <span className="eyebrow">{tr("guideTourEyebrow")}</span>
      <h3 id="tour-title">{tr(current.title)}</h3>
      <p id="tour-body">{tr(current.body)}</p>
      <div className="tour-foot">
        <div className="tour-dots" aria-hidden="true">
          {STEPS.map((_, index) => <i key={index} className={index === step ? "on" : ""} />)}
        </div>
        <span className="sr-only" role="status">{tr("guideStep", { n: step + 1, total: STEPS.length })}</span>
        <div className="tour-actions">
          <button className="text-button" onClick={finish}>{tr("guideSkip")}</button>
          {step > 0 && <button onClick={() => setStep((n) => Math.max(0, n - 1))}>{tr("guidePrev")}</button>}
          <button className="btn" ref={primary} onClick={() => (last ? finish() : setStep((n) => n + 1))}>
            {last ? tr("guideTourDone") : tr("guideNext")}
          </button>
        </div>
      </div>
    </div>
  </div>;
}
