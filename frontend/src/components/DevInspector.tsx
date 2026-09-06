import { useEffect, useState } from "react";
import { api } from "../api";
import type { AssetsStatus, GameState, SceneBundle } from "../types";

type Tab = "state" | "branches" | "assets" | "llm" | "scene";

interface Props {
  sessionId: string;
  state: GameState;
  scene: SceneBundle;
  assets: AssetsStatus | null;
  onClose: () => void;
}

interface Branch {
  key: string;
  choiceId: string;
  outcome: string;
  status: string;
  baseStateVersion: number;
  ageMillis: number;
  provisional: string;
}

interface LlmCall {
  at: string;
  purpose: string;
  model: string;
  durationMillis: number;
  ok: boolean;
  note: string;
  responseChars: number;
}

/**
 * The window into the runtime. Speculative branches are polled live so you can watch them
 * appear while you read, and watch them get discarded the moment you commit to a choice.
 */
export default function DevInspector({ sessionId, state, scene, assets, onClose }: Props) {
  const [tab, setTab] = useState<Tab>("state");
  const [debug, setDebug] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    let alive = true;
    const poll = () => {
      api.debug(sessionId).then((d) => alive && setDebug(d)).catch(() => undefined);
    };
    poll();
    const timer = setInterval(poll, 1200);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [sessionId, state.stateVersion]);

  const branches = (debug?.speculativeBranches as Branch[] | undefined) ?? [];
  const calls = (debug?.llmCalls as LlmCall[] | undefined) ?? [];
  const pendingArc = debug?.pendingArc as { arcTitle?: string } | null | undefined;

  return (
    <div className="inspector">
      <div className="bar">
        <strong>DEV INSPECTOR</strong>
        <span style={{ color: "var(--ink-faint)" }}>
          session {sessionId} · state v{state.stateVersion}
        </span>
        <span style={{ marginLeft: "auto" }}>
          <button className="icon-btn" onClick={onClose}>收起</button>
        </span>
      </div>
      <div className="body">
        <div className="tabs">
          {(["state", "branches", "assets", "llm", "scene"] as Tab[]).map((t) => (
            <button key={t} className={tab === t ? "on" : ""} onClick={() => setTab(t)}>
              {t === "branches" ? `branches (${branches.length})`
                : t === "assets" ? `assets (${assets?.assets.length ?? 0})` : t}
            </button>
          ))}
        </div>

        {tab === "state" && (
          <>
            <div className="kv"><span>currentBeat</span><span>{state.currentBeatId ?? "— (spine exhausted)"}</span></div>
            <div className="kv"><span>scenesInCurrentBeat</span><span>{state.scenesInCurrentBeat}</span></div>
            <div className="kv"><span>completedBeats</span><span>{state.completedBeats.join(", ") || "—"}</span></div>
            <div className="kv"><span>storyProgress</span><span>
              arc {state.storyProgress.arcNumber} · {state.storyProgress.beatsCompleted}/{state.storyProgress.totalBeats} beats · {state.storyProgress.scenesPlayed} scenes
            </span></div>
            <div className="kv"><span>location</span><span>{state.currentLocationId}</span></div>
            <div className="kv"><span>inventory</span><span>{state.inventory.map((i) => i.name).join(", ") || "—"}</span></div>
            <div className="kv"><span>flags</span><span>
              {Object.entries(state.flags).map(([k, v]) => `${k}=${v}`).join(", ") || "—"}
            </span></div>
            <div className="kv"><span>hp / conditions</span><span>
              {state.player.hp}/{state.player.maxHp} · {state.player.conditions.join(", ") || "none"}
            </span></div>
            <div className="kv"><span>continuityLedger</span><span>
              {state.continuityLedger.map((t) => `${t.id}:${t.status}`).join(" ") || "—"}
            </span></div>
            <div className="kv"><span>pendingArc</span><span>{pendingArc?.arcTitle ?? "— (not planned yet)"}</span></div>
            <div className="kv"><span>rejected ops</span><span>
              {state.rejectedOpsLog.length === 0 ? "none" : state.rejectedOpsLog.slice(-4).join(" | ")}
            </span></div>
            <details style={{ marginTop: 12 }}>
              <summary style={{ cursor: "pointer", color: "var(--ink-faint)" }}>canonical GameState JSON</summary>
              <pre style={{ marginTop: 8 }}>{JSON.stringify(state, null, 2)}</pre>
            </details>
          </>
        )}

        {tab === "branches" && (
          <>
            <div style={{ color: "var(--ink-faint)", marginBottom: 8, lineHeight: 1.6 }}>
              一层预生成：每个 Choice 一条候选，都从同一个 canonical snapshot fork。骰子在场景提交时已经掷好
              （见 state 页的 sceneDice），有检定的只写实际会发生的那个结果。未被选中的分支全部丢弃。
            </div>
            {branches.length === 0 ? (
              <div style={{ color: "var(--ink-faint)" }}>（没有活跃分支 —— 已全部丢弃或该场景没有选项）</div>
            ) : (
              branches.map((b) => (
                <div className="branch" key={b.key}>
                  <span className={`st ${b.status}`}>{b.status}</span>
                  <span className="kk">{b.choiceId}/{b.outcome}</span>
                  <span className="pv">v{b.baseStateVersion} · {b.provisional}</span>
                </div>
              ))
            )}
          </>
        )}

        {tab === "assets" && (
          <>
            {!assets ? (
              <div style={{ color: "var(--ink-faint)" }}>（尚未读取素材状态）</div>
            ) : (
              <>
                <div style={{ color: "var(--ink-faint)", marginBottom: 8, lineHeight: 1.6 }}>
                  素材在故事框架成形时就已规划并入队，与正文并行生成。图片只更新自己的版本（v{assets.version}），
                  从不推进 GameState、不提交剧情、不重掷骰子。这里显示的是<b>预测</b>会用到的画面，不代表玩家已到达。
                </div>
                <div className="kv"><span>provider</span><span>{assets.provider} · {assets.enabled ? "enabled" : "disabled"}</span></div>
                <div className="kv"><span>workers</span><span>
                  {assets.active} active / {assets.concurrency} max · high-water {assets.highWaterConcurrency} · queue {assets.queueDepth}/{assets.queueCapacity}
                </span></div>
                {assets.budget && (
                  <div className="kv"><span>budget</span><span>
                    arc {assets.budget.arcNumber}: {assets.budget.arcAttempts}{assets.budget.arcBudget > 0 ? `/${assets.budget.arcBudget}` : " (不限)"} attempts ·
                    first batch {assets.budget.firstBatchQueued}/{assets.budget.firstBatchBudget} ·
                    total {assets.budget.attemptsTotal}
                    {assets.budget.paused && <b style={{ color: "var(--failure)" }}>　PAUSED: {assets.budget.pauseReason}</b>}
                  </span></div>
                )}
                <div className="kv"><span>counts</span><span>
                  {Object.entries(assets.counts).filter(([, n]) => n > 0).map(([k, n]) => `${k} ${n}`).join(" · ") || "—"}
                </span></div>
                {assets.assets.length === 0 ? (
                  <div style={{ color: "var(--ink-faint)", marginTop: 8 }}>（没有规划的素材 —— 图片功能可能已关闭）</div>
                ) : (
                  assets.assets.map((a) => (
                    <div className="branch" key={a.assetId} title={a.failureReason ?? undefined}>
                      <span className={`st ${a.status === "READY" ? "ready" : a.status === "FAILED" ? "failed" : a.status === "GENERATING" ? "generating" : "queued"}`}>
                        {a.status.toLowerCase()}
                      </span>
                      <span className="kk">{a.assetId}</span>
                      <span className="pv">
                        {a.kind === "BACKGROUND" ? "bg" : a.kind === "PORTRAIT" ? "portrait" : `variant:${a.variant}`} · {a.subjectName}
                        {a.beatId ? ` · beat ${a.beatId}` : ""} · p{a.priority} · try {a.attempts}
                        {a.reuseCount > 0 ? ` · used ×${a.reuseCount}` : ""}
                        {a.readyBeforeNeeded === true ? " · ✓ ready before needed" : a.readyBeforeNeeded === false ? " · ✗ needed before ready" : ""}
                        {a.startedAt && a.readyAt ? ` · ${Math.round((Date.parse(a.readyAt) - Date.parse(a.startedAt)) / 1000)}s` : ""}
                        {a.failureReason ? ` · ${a.failureReason}` : ""}
                      </span>
                    </div>
                  ))
                )}
              </>
            )}
          </>
        )}

        {tab === "llm" && (
          <>
            <div className="kv"><span>client</span><span>{String(debug?.llmClient ?? "?")}</span></div>
            {calls.length === 0 ? (
              <div style={{ color: "var(--ink-faint)", marginTop: 8 }}>（暂无调用）</div>
            ) : (
              calls.slice(0, 14).map((c, i) => (
                <div className="branch" key={i}>
                  <span className={`st ${c.ok ? "ready" : "failed"}`}>{c.ok ? "ok" : "fail"}</span>
                  <span className="kk">{c.purpose}</span>
                  <span className="pv">{c.durationMillis}ms · {c.responseChars}ch · {c.note}</span>
                </div>
              ))
            )}
          </>
        )}

        {tab === "scene" && (
          <>
            <div className="kv"><span>sceneId</span><span>{scene.sceneId}</span></div>
            <div className="kv"><span>from cache</span><span>{String(scene.meta.fromSpeculativeCache)}</span></div>
            <div className="kv"><span>generation</span><span>{scene.meta.generationMillis}ms · repairs {scene.meta.repairAttempts}</span></div>
            <div className="kv"><span>outcomeContext</span><span>{scene.meta.outcomeContext ?? "—"}</span></div>
            <div className="kv"><span>backgroundPrompt</span><span>{scene.location.backgroundPrompt ?? "—"}</span></div>
            <div className="kv"><span>proposed delta</span><span>
              {scene.proposedStateDelta.ops.length === 0
                ? "—"
                : scene.proposedStateDelta.ops.map((o) => `${o.op}(${o.target ?? o.value ?? ""})`).join(", ")}
            </span></div>
            <details style={{ marginTop: 12 }}>
              <summary style={{ cursor: "pointer", color: "var(--ink-faint)" }}>SceneBundle JSON</summary>
              <pre style={{ marginTop: 8 }}>{JSON.stringify(scene, null, 2)}</pre>
            </details>
          </>
        )}
      </div>
    </div>
  );
}
