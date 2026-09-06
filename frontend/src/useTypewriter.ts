import { useCallback, useEffect, useRef, useState } from "react";

/** Split visible characters, keeping emoji sequences and combining marks together. */
export function graphemes(text: string): string[] {
  return Array.from(new Intl.Segmenter("zh", { granularity: "grapheme" }).segment(text), (part) => part.segment);
}

/** A scene/block identity owns its reveal; unrelated picture and queue updates cannot restart it. */
export function useTypewriter(key: string, text: string, paused = false) {
  const segments = useRef({ key: "", text: "", units: [] as string[] });
  if (segments.current.key !== key || segments.current.text !== text) {
    segments.current = { key, text, units: graphemes(text) };
  }
  const [reveal, setReveal] = useState({ key, text, count: 0 });
  const [reducedMotion, setReducedMotion] = useState(() => typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => setReducedMotion(media.matches);
    update(); media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  const units = segments.current.units;
  const count = reveal.key === key && reveal.text === text ? reveal.count : 0;
  const complete = reducedMotion || count >= units.length;
  useEffect(() => {
    if (complete || paused) return;
    const last = units[count - 1] ?? "";
    const delay = /[。！？!?；;…]/u.test(last) ? 210 : /[，、,:：]/u.test(last) ? 110 : 32;
    const timer = window.setTimeout(() => setReveal({ key, text, count: count + 1 }), delay);
    return () => window.clearTimeout(timer);
  }, [complete, count, key, paused, text, units]);
  const finish = useCallback(() => setReveal({ key, text, count: units.length }), [key, text, units]);
  return { visible: complete ? text : units.slice(0, count).join(""), complete, finish };
}
