import { useEffect, useRef, useState } from "react";

/** Animate only work the server has reported; waiting never manufactures more progress. */
export default function useSmoothedProgress(id: string, reported: number, status: string) {
  const target = Math.max(0, Math.min(status === "READY" ? 100 : 99, Number.isFinite(reported) ? reported : 0));
  const [display, setDisplay] = useState({ id, value: 0 });
  const current = useRef({ id, value: 0 });
  const [reducedMotion, setReducedMotion] = useState(() => typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setReducedMotion(media.matches);
    update(); media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  useEffect(() => {
    if (current.current.id !== id) current.current = { id, value: 0 };
    const from = Math.min(current.current.value, target);
    if (reducedMotion || from === target) {
      current.current = { id, value: target };
      setDisplay(current.current);
      return;
    }
    const started = Date.now();
    const duration = Math.min(2400, 700 + (target - from) * 20);
    let timer: number;
    const tick = () => {
      const elapsed = Math.min(1, (Date.now() - started) / duration);
      const eased = 1 - Math.pow(1 - elapsed, 2);
      const value = elapsed === 1 ? target : Math.min(target, from + (target - from) * eased);
      current.current = { id, value };
      setDisplay(current.current);
      if (elapsed < 1) timer = window.setTimeout(tick, 32);
    };
    tick();
    return () => window.clearTimeout(timer);
  }, [id, target, reducedMotion]);

  if (reducedMotion) return target;
  return display.id === id ? Math.min(display.value, target) : 0;
}
