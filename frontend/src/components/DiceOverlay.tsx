import { useEffect, useState } from "react";
import type { Check, RollView } from "../types";

interface Props {
  check: Check;
  roll: RollView | null;
  onDone: () => void;
}

/**
 * The roll has already happened on the server before this ever renders -- this is a reveal,
 * not a simulation. The tumbling numbers are cosmetic; `roll.d20` is the authoritative value.
 */
export default function DiceOverlay({ check, roll, onDone }: Props) {
  const [face, setFace] = useState(1);
  const [settled, setSettled] = useState(false);
  /** The roll itself is a millisecond call; if it has not arrived in a while, say so. */
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    if (roll) {
      setSlow(false);
      return;
    }
    const t = setTimeout(() => setSlow(true), 6000);
    return () => clearTimeout(t);
  }, [roll]);

  useEffect(() => {
    if (!roll) {
      const spin = setInterval(() => setFace(1 + Math.floor(Math.random() * 20)), 70);
      return () => clearInterval(spin);
    }
    const spin = setInterval(() => setFace(1 + Math.floor(Math.random() * 20)), 60);
    const stop = setTimeout(() => {
      clearInterval(spin);
      setFace(roll.d20);
      setSettled(true);
    }, 620);
    return () => {
      clearInterval(spin);
      clearTimeout(stop);
    };
  }, [roll]);

  useEffect(() => {
    if (!settled) return;
    const t = setTimeout(onDone, 2600);
    return () => clearTimeout(t);
  }, [settled, onDone]);

  return (
    <div className="dice-overlay" onClick={settled ? onDone : undefined}>
      <div className="dice-card">
        <div className="what">
          {check.description || "属性检定"}
          <br />
          {check.stat} · DC {check.dc}
        </div>
        <div className={`die ${settled ? "" : "rolling"}`}>{face}</div>
        {settled && roll ? (
          <>
            <div className="dice-math">
              🎲 <b>{roll.d20}</b> + {roll.stat} <b>{roll.modifier}</b> = <b>{roll.total}</b>
            </div>
            <div className="dice-dc">DC {roll.dc}</div>
            <div className={`verdict ${roll.success ? "success" : "failure"}`}>
              {roll.success ? "SUCCESS" : "FAILURE"}
            </div>
            <div className="verdict-note" style={{ marginBottom: 10 }}>
              {roll.critical && "自然 20 —— "}
              {roll.fumble && "自然 1 —— "}
              {roll.success ? "故事按你的意图推进。" : "失败不是死路：故事继续，但你要付出代价。"}
            </div>
            <div style={{ fontSize: 11, color: "var(--ink-faint)", fontFamily: "var(--mono)" }}>
              点击继续
            </div>
          </>
        ) : (
          <div className="dice-math">{slow ? "服务器响应较慢，仍在等待骰子…" : "掷骰中…"}</div>
        )}
      </div>
    </div>
  );
}
