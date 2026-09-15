import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { api, ApiError, onAccessDenied } from "./api";
import AccessGate from "./components/AccessGate";
import Backdrop from "./components/Backdrop";
import DevInspector from "./components/DevInspector";
import DiceOverlay from "./components/DiceOverlay";
import SetupView from "./components/SetupView";
import SidePanel from "./components/SidePanel";
import SettingsDialog from "./components/SettingsDialog";
import Sprite from "./components/Sprite";
import CharacterCard from "./components/CharacterCard";
import TaskQueue from "./components/TaskQueue";
import HistoryDialog from "./components/HistoryDialog";
import RestructureDialog from "./components/RestructureDialog";
import WelcomeDialog from "./components/WelcomeDialog";
import StageTour from "./components/StageTour";
import { useTypewriter } from "./useTypewriter";
import useRestructureJob from "./useRestructureJob";
import { TOUR_KEY, WELCOME_KEY, markSeen, seen } from "./onboarding";
import { displayUrl, preload, useAssets } from "./assets";
import { portraitUrl } from "./assetView";
import type {
  Block, Check, Choice, ChoiceView, CompiledStory, ConfigView, GameState, RollView, SceneBundle, SessionView,
} from "./types";
import { backdropFor } from "./visual";
import { LocaleProvider, approachLabel, expressionLabel, parseLang, t, type Lang } from "./i18n";

type Access = "checking" | "required" | "denied" | "open";

export default function App() {
  /** Nothing else talks to the backend until the key question is settled. */
  const [access, setAccess] = useState<Access>("checking");
  const [lang, setLang] = useState<Lang>("zh");
  const [config, setConfig] = useState<ConfigView | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [story, setStory] = useState<CompiledStory | null>(null);
  const [state, setState] = useState<GameState | null>(null);
  const [scene, setScene] = useState<SceneBundle | null>(null);
  const [finished, setFinished] = useState(false);
  const [saveHealthy, setSaveHealthy] = useState(true);
  const [continuationPending, setContinuationPending] = useState(false);
  const [continuationRefreshFailed, setContinuationRefreshFailed] = useState(false);

  const [blockIndex, setBlockIndex] = useState(0);
  const [readingRevision, setReadingRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loadingMessage, setLoadingMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // React state updates are asynchronous. The ref also blocks two events received
  // before the next render; the sequence keeps late responses out of a different game.
  const requestPending = useRef(false);
  const operationId = useRef(0);

  const [rollCheck, setRollCheck] = useState<Check | null>(null);
  const [roll, setRoll] = useState<RollView | null>(null);
  const [queued, setQueued] = useState<ChoiceView | null>(null);
  /** The die is cast and on screen; the scene it leads to is still on its way. */
  const [awaitingScene, setAwaitingScene] = useState(false);
  /** A save reloaded with a cast die on it: the number was seen, so play resumes from it. */
  const [resumeChoiceId, setResumeChoiceId] = useState<string | null>(null);
  const [resolvingChoiceId, setResolvingChoiceId] = useState<string | null>(null);
  const [resolutionRefreshFailed, setResolutionRefreshFailed] = useState(false);
  const lastAutoResume = useRef<string | null>(null);
  /** Kept on screen for the whole scene the roll produced, so the result is never a flash. */
  const [lastRoll, setLastRoll] = useState<RollView | null>(null);

  const [showSheet, setShowSheet] = useState(false);
  const [showInspector, setShowInspector] = useState(false);
  const [showQueue, setShowQueue] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showRestructure, setShowRestructure] = useState(false);
  // Read once, from this browser's own record. A new browser is genuinely a first visit.
  const [welcomeSeen, setWelcomeSeen] = useState(() => seen(WELCOME_KEY));
  const [tourSeen, setTourSeen] = useState(() => seen(TOUR_KEY));
  /** Which scene a rewrite would start from: the current one, or one picked from the recap. */
  const [restructureAnchor, setRestructureAnchor] = useState<{ nodeId: string; label: string | null } | null>(null);
  // The selected line has its own reading turn. A cached next scene must not erase it.
  const [playerTurn, setPlayerTurn] = useState<{ choice: Choice; confirmed: boolean } | null>(null);
  const [readPlayerSceneId, setReadPlayerSceneId] = useState<string | null>(null);

  const applyConfig = useCallback((view: ConfigView | null) => {
    setConfig(view);
    if (view?.language) setLang(parseLang(view.language));
  }, []);

  const changeLang = useCallback(async (next: Lang) => {
    setLang(next);
    try {
      await api.saveSettings({ "genvn.language": next });
      const view = await api.config();
      applyConfig(view);
    } catch { /* UI already switched; persist when the backend accepts it */ }
  }, [applyConfig]);

  const shell = (node: ReactNode) => <LocaleProvider lang={lang} onChange={changeLang}>{node}</LocaleProvider>;
  const tr = (key: string, vars?: Record<string, string | number>) => t(lang, key, vars);

  useEffect(() => {
    let alive = true;
    api.access()
      .then((view) => {
        if (!alive) return;
        if (view.language) setLang(parseLang(view.language));
        setAccess(view.required && !view.granted ? "required" : "open");
      })
      // An older backend without the probe, or one that is down: behave as before and let the
      // setup screen report the connection problem.
      .catch(() => { if (alive) setAccess("open"); });
    return () => { alive = false; };
  }, []);
  // The key was changed on the server while we were playing: ask again, keep the game on screen.
  useEffect(() => onAccessDenied(() => setAccess("denied")), []);
  useEffect(() => {
    if (access !== "open") return;
    api.config().then(applyConfig).catch(() => undefined);
  }, [access, applyConfig]);

  // Pictures live beside the scene, not inside it: their arrival repaints, never replays.
  const assets = useAssets(sessionId);
  useEffect(() => {
    if (!scene) return;
    const beats = story?.spine.beats ?? [];
    const index = beats.findIndex((b) => b.id === state?.currentBeatId);
    const upcoming = index < 0 ? [] : beats.slice(index, index + 3).map((b) => b.id);
    preload(assets, ["pt.player.base", "pt.player.talking", "pt.player.action", "card.player.default", scene.location.backgroundAssetId, ...scene.characters.flatMap((c) => [c.assetId, assets?.assets.find((a) => a.kind === "CHARACTER_CARD" && a.subjectId === c.characterId)?.assetId])], 4, upcoming);
  }, [assets, scene, state?.currentBeatId, story]);

  const adopt = useCallback((next: {
    story: CompiledStory; state: GameState; scene: SceneBundle; finished: boolean;
    saveHealthy: boolean; continuationPending: boolean;
  }, preserveReading = false) => {
    setStory(next.story);
    setState(next.state);
    setScene(next.scene);
    setFinished(next.finished);
    setSaveHealthy(next.saveHealthy);
    setContinuationPending(next.continuationPending);
    setContinuationRefreshFailed(false);
    if (!preserveReading) {
      setBlockIndex(0);
      setReadingRevision((revision) => revision + 1);
    }
    requestPending.current = false;
    setBusy(false);
    setLoadingMessage(null);
  }, []);

  const resetTransient = useCallback(() => {
    setRollCheck(null);
    setRoll(null);
    setQueued(null);
    setAwaitingScene(false);
    setLastRoll(null);
    setShowQueue(false);
    setShowHistory(false);
    setShowRestructure(false);
    setRestructureAnchor(null);
    setPlayerTurn(null);
    setReadPlayerSceneId(null);
    setShowSheet(false);
    setShowInspector(false);
    setResumeChoiceId(null);
    setResolvingChoiceId(null);
    setResolutionRefreshFailed(false);
  }, []);

  const showSession = useCallback((session: SessionView) => {
    operationId.current += 1;
    resetTransient();
    setError(null);
    setSessionId(session.sessionId);
    adopt(session);
    if (session.resolvingChoiceId) {
      setResolvingChoiceId(session.resolvingChoiceId);
      setLastRoll(session.pendingRoll?.roll ?? null);
      requestPending.current = true;
      setBusy(true);
      setLoadingMessage(tr("loadingChoice"));
    } else setResumeChoiceId(session.pendingRoll?.choiceId ?? null);
  }, [adopt, resetTransient, lang]);

  const returnToSetup = useCallback(() => {
    operationId.current += 1;
    requestPending.current = false;
    resetTransient();
    setSessionId(null);
    setScene(null);
    setStory(null);
    setState(null);
    setBusy(false);
    setLoadingMessage(null);
    setError(null);
  }, [resetTransient]);

  const openSession = useCallback(async (id: string) => {
    lastAutoResume.current = null;
    const operation = ++operationId.current;
    requestPending.current = true;
    setBusy(true);
    setError(null);
    setLoadingMessage(tr("loadingSaveShort"));
    try {
      const s = await api.getSession(id);
      if (operation === operationId.current) showSession(s);
    } catch (e) {
      if (operation === operationId.current) {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      if (operation === operationId.current) {
        requestPending.current = false;
        setBusy(false);
        setLoadingMessage(null);
      }
    }
  }, [showSession, lang]);

  const currentSceneId = scene?.sceneId;
  const currentSceneBlocks = scene?.blocks;
  const currentStateVersion = state?.stateVersion;
  useEffect(() => {
    if (!sessionId || !resolvingChoiceId || resolutionRefreshFailed) return;
    const operation = operationId.current;
    const controller = new AbortController();
    let active = true;
    let timer: number;
    let attempts = 0;
    let failures = 0;
    const stillCurrent = () => active && operation === operationId.current;
    const stop = (message: string) => {
      setResolutionRefreshFailed(true);
      requestPending.current = false;
      setBusy(false);
      setLoadingMessage(null);
      setError(message);
    };
    const poll = async () => {
      if (!stillCurrent()) return;
      attempts++;
      try {
        const latest = await api.getSession(sessionId, controller.signal);
        if (!stillCurrent()) return;
        failures = 0;
        if (!latest.resolvingChoiceId) {
          const unchanged = latest.scene.sceneId === currentSceneId
            && latest.state.stateVersion === currentStateVersion;
          showSession(latest);
          if (unchanged && !latest.pendingRoll) setError(tr("errChoiceUnfinished"));
          return;
        }
        // Do not showSession here: an unchanged pending roll must never restart choose.
        setSaveHealthy(latest.saveHealthy);
      } catch (e) {
        if (!stillCurrent()) return;
        if (e instanceof ApiError && e.code === "session_not_found") {
          returnToSetup();
          setError(tr("errSessionGone"));
          return;
        }
        if (++failures >= 3) {
          stop(tr("errProgressGone"));
          return;
        }
      }
      if (attempts >= 120) {
        stop(tr("errStillGenerating"));
        return;
      }
      timer = window.setTimeout(poll, Math.min(5000, 1500 * (attempts + 1)));
    };
    timer = window.setTimeout(poll, 1500);
    return () => {
      active = false;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [currentSceneId, currentStateVersion, lang, resolutionRefreshFailed, resolvingChoiceId, returnToSetup, sessionId, showSession]);

  useEffect(() => {
    if (!sessionId || !finished || !continuationPending || continuationRefreshFailed || busy) return;
    const operation = operationId.current;
    const controller = new AbortController();
    let active = true;
    let timer: number;
    const stillCurrent = () => active && operation === operationId.current && !requestPending.current;
    const poll = async () => {
      if (!stillCurrent()) return;
      try {
        const latest = await api.getSession(sessionId, controller.signal);
        if (!stillCurrent()) return;
        if (!latest.finished || latest.scene.sceneId !== currentSceneId
          || latest.state.stateVersion !== currentStateVersion) {
          const unchangedText = latest.scene.sceneId === currentSceneId
            && currentSceneBlocks?.length === latest.scene.blocks.length
            && currentSceneBlocks.every((block, index) => {
              const next = latest.scene.blocks[index];
              return block.type === next.type && block.text === next.text
                && block.speakerId === next.speakerId && block.speakerName === next.speakerName
                && block.expression === next.expression;
            });
          // A late arc plan only adds a continuation choice to this same ending. Keep
          // the reader's position, reveal and open dialog while updating that metadata.
          if (unchangedText && !latest.pendingRoll && !latest.resolvingChoiceId) adopt(latest, true);
          else showSession(latest);
          return;
        }
        // An unchanged response must not reset the dialogue the player is reading.
        setSaveHealthy(latest.saveHealthy);
        setContinuationPending(latest.continuationPending);
        if (latest.continuationPending) timer = window.setTimeout(poll, 2000);
      } catch {
        if (stillCurrent()) setContinuationRefreshFailed(true);
      }
    };
    // Schedule after completion rather than using setInterval: a slow GET can never
    // overlap the next one, and leaving this session cancels the outstanding read.
    timer = window.setTimeout(poll, 2000);
    return () => {
      active = false;
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [adopt, busy, continuationPending, continuationRefreshFailed, currentSceneBlocks, currentSceneId, currentStateVersion, finished, sessionId, showSession]);

  const blocks = scene?.blocks ?? [];
  const storyBlock = blocks[blockIndex];
  const block: Block | undefined = playerTurn ? {
    type: playerTurn.choice.actionKind === "dialogue" ? "dialogue" : "narration",
    speakerId: "player", speakerName: state?.player?.name ?? tr("you"), text: playerTurn.choice.text,
    expression: playerTurn.choice.playerExpression ?? (playerTurn.choice.actionKind === "dialogue" ? "talking" : "action"),
  } : storyBlock;
  const alreadyRead = !playerTurn && readPlayerSceneId === scene?.sceneId && blockIndex === 0;
  const reveal = useTypewriter(`${sessionId ?? ""}:${scene?.sceneId ?? ""}:${readingRevision}:${playerTurn ? `player:${playerTurn.choice.id}` : blockIndex}`, block?.text ?? "", alreadyRead || showQueue || showSheet || showInspector || showHistory || showRestructure || (busy && !playerTurn));
  const typed = alreadyRead ? { ...reveal, visible: block?.text ?? "", complete: true } : reveal;
  const atLastBlock = blockIndex >= blocks.length - 1;
  const choicesVisible = Boolean(scene) && atLastBlock && typed.complete && !rollCheck && !busy && !resolvingChoiceId && !playerTurn;

  const advance = useCallback(() => {
    if (showSheet || showInspector || showQueue || showHistory || showRestructure) return;
    if (playerTurn) {
      if (!typed.complete) { typed.finish(); return; }
      if (rollCheck || !playerTurn.confirmed || !queued) return;
      const next = queued;
      setPlayerTurn(null); setQueued(null); setAwaitingScene(false);
      adopt(next);
      // Canon retains the exact spoken choice. It was just read in this player turn,
      // so skip its repeated first block when moving into the generated scene.
      if (next.scene.blocks[0]?.speakerId === "player" && next.scene.blocks[0]?.text === playerTurn.choice.text) {
        if (next.scene.blocks.length > 1) setBlockIndex(1);
        else setReadPlayerSceneId(next.scene.sceneId);
      }
      return;
    }
    if (rollCheck || busy) return;
    if (!typed.complete) { typed.finish(); return; }
    setBlockIndex((i) => Math.max(0, Math.min(i + 1, blocks.length - 1)));
  }, [adopt, blocks.length, busy, playerTurn, queued, rollCheck, showSheet, showInspector, showQueue, showHistory, showRestructure, typed.complete, typed.finish]);

  const choose = useCallback(async (choiceId: string) => {
    if (!sessionId || !scene || !state || requestPending.current || resolvingChoiceId || rollCheck || playerTurn || showSheet || showInspector || showQueue || showHistory || showRestructure) return;
    const choice = scene.choices.find((c) => c.id === choiceId);
    if (!choice) return;
    const operation = ++operationId.current;
    requestPending.current = true;
    setBusy(true);
    setError(null);
    setLastRoll(null);
    if (choice.actionKind === "dialogue") setPlayerTurn({ choice, confirmed: false });
    if (choice.check) {
      setRollCheck(choice.check);
      setRoll(null);
    } else {
      setLoadingMessage(tr("loadingScene"));
    }
    try {
      if (choice.check) {
        // The die first. It is rolled and persisted server-side and comes back in milliseconds,
        // so the player sees the real number while the scene is still being written.
        const cast = await api.roll(sessionId, choiceId, scene.sceneId, state.stateVersion);
        if (operation !== operationId.current) return;
        setRoll(cast.roll);
        setLastRoll(cast.roll);
        setAwaitingScene(true);
      }
      // Then the scene: instant on a prefetch hit, otherwise as long as the model takes --
      // and the UI says so, instead of pretending the die is still rolling.
      const result = await api.choose(sessionId, choiceId, scene.sceneId, state.stateVersion);
      if (operation !== operationId.current) return;
      setSaveHealthy(result.saveHealthy);
      if (choice.text) {
        setPlayerTurn({ choice, confirmed: true });
        setQueued(result);
        setAwaitingScene(true);
        setBusy(false);
        setLoadingMessage(null);
      } else if (choice.check) {
        setQueued(result);
      } else {
        setRollCheck(null);
        adopt(result);
      }
    } catch (e) {
      if (operation !== operationId.current) return;
      resetTransient();
      if (e instanceof ApiError && e.code === "choice_resolving") {
        setResolvingChoiceId(choiceId);
        setLoadingMessage(tr("loadingChoice"));
        return;
      } else if (e instanceof ApiError && e.code === "scene_conflict") {
        setLoadingMessage(tr("loadingLatest"));
        try {
          const current = await api.getSession(sessionId);
          if (operation !== operationId.current) return;
          const sameScene = current.scene.sceneId === scene.sceneId
            && current.state.stateVersion === state.stateVersion;
          // Also tolerate an older server's undifferentiated 409 without a tight retry loop.
          showSession(sameScene && current.pendingRoll && !current.resolvingChoiceId
            ? { ...current, resolvingChoiceId: current.pendingRoll.choiceId } : current);
          if (!sameScene) setError(tr("errSceneMoved"));
          return;
        } catch (refreshError) {
          if (operation !== operationId.current) return;
          if (refreshError instanceof ApiError && refreshError.code === "session_not_found") {
            returnToSetup();
            setError(tr("errSessionGone"));
            return;
          }
          setError(tr("errRefreshFailed", { err: refreshError instanceof Error ? refreshError.message : String(refreshError) }));
        }
      } else if (e instanceof ApiError && e.code === "roll_pending") {
        // A die for another choice on this scene was already cast and is binding.
        // Reload: the session carries that die, and play resumes from it.
        setLoadingMessage(tr("loadingResumeDie"));
        try {
          const current = await api.getSession(sessionId);
          if (operation !== operationId.current) return;
          showSession(current);
          return;
        } catch (refreshError) {
          if (operation !== operationId.current) return;
          setError(tr("errPendingRollRefresh", { err: refreshError instanceof Error ? refreshError.message : String(refreshError) }));
        }
      } else if (e instanceof ApiError && e.code === "session_not_found") {
        returnToSetup();
        setError(tr("errSessionGone"));
        return;
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
      requestPending.current = false;
      setBusy(false);
      setLoadingMessage(null);
    }
  }, [adopt, resetTransient, lang, resolvingChoiceId, returnToSetup, rollCheck, scene, sessionId, playerTurn, showHistory, showInspector, showQueue, showRestructure, showSession, showSheet, state]);

  /** Only closes the reveal. What happens next is decided by the effect below. */
  const finishRoll = useCallback(() => {
    setRollCheck(null);
    setRoll(null);
  }, []);

  // Once the reveal is dismissed: adopt the scene if it has already arrived, otherwise say
  // plainly that it is still being generated. The die never spins for a generation's worth of time.
  useEffect(() => {
    if (rollCheck || !awaitingScene) return;
    if (queued && playerTurn) {
      // Keep the player's own words/action readable; advance adopts the queued scene once.
      setBusy(false); setLoadingMessage(null);
    } else if (queued) {
      setAwaitingScene(false);
      setQueued(null);
      adopt(queued);
    } else {
      setLoadingMessage(tr("loadingScene"));
    }
  }, [adopt, awaitingScene, lang, playerTurn, queued, rollCheck]);

  // A save reloaded with a cast die on it resumes from that die.
  useEffect(() => {
    if (!resumeChoiceId || !scene || !sessionId || requestPending.current || resolvingChoiceId) return;
    const id = resumeChoiceId;
    setResumeChoiceId(null);
    if (!scene.choices.some((c) => c.id === id)) return;
    const key = `${sessionId}:${scene.sceneId}:${state?.stateVersion}:${id}`;
    if (lastAutoResume.current === key) {
      setError(tr("errPendingRoll"));
      return;
    }
    lastAutoResume.current = key;
    void choose(id);
  }, [choose, lang, resolvingChoiceId, resumeChoiceId, scene, sessionId, state?.stateVersion]);

  const rewind = useCallback(async (nodeId: string) => {
    if (!sessionId || !scene || !state) return;
    if (requestPending.current || resolvingChoiceId || rollCheck || playerTurn) {
      setShowHistory(false);
      setError(tr("errRewindBusy"));
      return;
    }
    const operation = ++operationId.current;
    requestPending.current = true;
    setBusy(true);
    setShowHistory(false);
    setError(null);
    setLoadingMessage(tr("loadingRewind"));
    try {
      const latest = await api.rewind(sessionId, nodeId, scene.sceneId, state.stateVersion);
      if (operation !== operationId.current) return;
      showSession(latest);
    } catch (e) {
      if (operation !== operationId.current) return;
      if (e instanceof ApiError && e.code === "scene_conflict") {
        try {
          const current = await api.getSession(sessionId);
          if (operation !== operationId.current) return;
          showSession(current);
          setError(tr("errRewindConflict"));
          return;
        } catch (refreshError) {
          if (operation !== operationId.current) return;
          setError(refreshError instanceof Error ? refreshError.message : String(refreshError));
        }
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
      requestPending.current = false;
      setBusy(false);
      setLoadingMessage(null);
    }
  }, [lang, playerTurn, resolvingChoiceId, rollCheck, scene, sessionId, showSession, state]);

  // A rewrite is two model calls, so it runs as a job with a progress bar rather than a request.
  // The session it produces replaces the current one exactly as a rewind's would.
  const restructureJob = useRestructureJob(sessionId);
  const { onReady: onRestructured } = restructureJob;
  useEffect(() => {
    onRestructured((session) => {
      setShowRestructure(false);
      setRestructureAnchor(null);
      showSession(session);
    });
  }, [onRestructured, showSession]);

  /** Open the rewrite dialog on a scene. Without a node id it targets the one on screen. */
  const openRestructure = useCallback((nodeId?: string, label?: string) => {
    if (!scene) return;
    if (requestPending.current || resolvingChoiceId || rollCheck || playerTurn) {
      setShowHistory(false);
      setError(tr("errRestructureBusy"));
      return;
    }
    setShowQueue(false); setShowSheet(false); setShowInspector(false); setShowSettings(false); setShowHistory(false);
    setError(null);
    restructureJob.reset();
    setRestructureAnchor({ nodeId: nodeId ?? scene.sceneId, label: label ?? null });
    setShowRestructure(true);
  }, [lang, playerTurn, resolvingChoiceId, restructureJob, rollCheck, scene]);

  const submitRestructure = useCallback((instruction: string) => {
    if (!sessionId || !scene || !state || !restructureAnchor) return;
    setError(null);
    restructureJob.begin({
      nodeId: restructureAnchor.nodeId,
      instruction,
      expectedSceneId: scene.sceneId,
      expectedStateVersion: state.stateVersion,
    });
  }, [restructureAnchor, restructureJob, scene, sessionId, state]);

  useEffect(() => {
    if (!scene) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.repeat || e.isComposing || e.ctrlKey || e.metaKey || e.altKey
        || showSheet || showInspector || showQueue || showHistory || showSettings || showRestructure || (rollCheck && !playerTurn) || (busy && !playerTurn)) return;
      const target = e.target instanceof Element ? e.target : null;
      if (target?.closest("button, a, input, textarea, select, summary, [role='button'], [contenteditable]:not([contenteditable='false'])")) return;
      if (e.key === " " || e.code === "Space" || e.key === "Enter") {
        e.preventDefault();
        advance();
        return;
      }
      const n = /^[1-9]$/.test(e.key) ? Number(e.key) : 0;
      if (choicesVisible && n >= 1 && n <= scene.choices.length) {
        e.preventDefault();
        void choose(scene.choices[n - 1].id);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [advance, busy, choicesVisible, choose, playerTurn, rollCheck, scene, showHistory, showInspector, showSheet, showQueue, showSettings, showRestructure]);

  const notices = (
    <div className="notifications">
      {!saveHealthy && (
        <div className="save-warning" role="status">
          {tr("saveUnhealthy")}
        </div>
      )}
      {error && (
        <button className="toast" onClick={() => setError(null)} role="alert">
          {error}
          <span className="dismiss">{tr("dismissToast")}</span>
        </button>
      )}
    </div>
  );

  if (access === "checking") {
    return shell(<div className="loading"><div className="spinner" /><div className="msg">{tr("connecting")}</div></div>);
  }
  if (access !== "open") {
    return shell(<AccessGate reason={access} onGranted={() => setAccess("open")} />);
  }

  if (!sessionId || !scene || !state || !story) {
    return shell(
      <>
        <SetupView config={config} onStarted={showSession} onLoad={openSession} onOpenSettings={() => setShowSettings(true)} />
        {!welcomeSeen && !showSettings && <WelcomeDialog imageEnabled={Boolean(config?.imageEnabled)}
          onDone={() => { markSeen(WELCOME_KEY); setWelcomeSeen(true); }} />}
        {notices}
        {showSettings && <SettingsDialog onClose={() => setShowSettings(false)}
          onSaved={() => { api.config().then(applyConfig).catch(() => undefined); }}
          onGuideReset={() => { setWelcomeSeen(false); setTourSeen(false); }} />}
        {loadingMessage && (
          <div className="loading">
            <div className="spinner" />
            <div className="msg">{loadingMessage}</div>
          </div>
        )}
      </>,
    );
  }

  const speaking = playerTurn ? "player" : block?.type === "dialogue" ? block.speakerId : null;
  const playerName = state.player?.name ?? tr("you");
  const playerExpression = block?.expression ?? "base";
  const playerAsset = assets?.assets.find((a) => a.subjectId === "player" && a.variant === playerExpression && a.kind !== "CHARACTER_CARD")?.assetId
    ?? (playerTurn ? (playerTurn.choice.actionKind === "dialogue" ? "pt.player.talking" : "pt.player.action") : "pt.player.base");
  const playerPresence = { characterId: "player", name: playerName, assetId: playerAsset };
  const featuredCharacter = speaking === "player" ? playerPresence : scene.characters.find((c) => c.characterId === speaking) ?? scene.characters.find((c) => c.characterId !== "player");
  const featuredPortrait = featuredCharacter?.characterId === "player"
    ? displayUrl(assets, featuredCharacter.assetId) ?? displayUrl(assets, "pt.player.base")
    : featuredCharacter ? portraitUrl(assets, featuredCharacter.characterId,
      speaking === featuredCharacter.characterId ? block?.expression : null,
      featuredCharacter.assetId, scene.meta?.outcomeContext) : null;
  const progress = state.storyProgress;

  return shell(
    <div className="stage">
      <Backdrop
        url={displayUrl(assets, scene.location.backgroundAssetId)}
        fallback={backdropFor(scene.location.id, scene.location.visualDescription)}
      />
      <div className="grain" />

      <header className="hud">
        <div className="story-heading">
          <div className="story-tools"><button className="history-trigger" onClick={() => { setShowQueue(false); setShowSheet(false); setShowInspector(false); setShowSettings(false); setShowHistory(true); }} aria-haspopup="dialog" aria-expanded={showHistory}><span aria-hidden="true">☷</span> {tr("history")}</button><span className="story-kicker">CHAPTER {String(progress.arcNumber ?? 1).padStart(2, "0")}</span></div>
          <span className="title">{state.currentArcTitle ?? story.spine.arcTitle}</span>
          <span className="beat">{scene.location.name}</span>
        </div>
        <nav className="stage-controls" aria-label={tr("gameMenu")}>
          <button className={`icon-btn queue-trigger ${showQueue ? "on" : ""}`} onClick={() => { setShowHistory(false); setShowSheet(false); setShowInspector(false); setShowSettings(false); setShowQueue(true); }} aria-haspopup="dialog" aria-expanded={showQueue}>
            <span className={`queue-symbol ${(assets?.active ?? 0) > 0 ? "working" : ""}`} aria-hidden="true">✧</span> {tr("queue")}
          </button>
          <button className={`icon-btn ${showSheet ? "on" : ""}`} onClick={() => { setShowHistory(false); setShowQueue(false); setShowInspector(false); setShowSettings(false); setShowSheet((v) => !v); }}>{tr("myCharacter")}</button>
          <button className={`icon-btn restructure-trigger ${showRestructure ? "on" : ""}`} onClick={() => openRestructure()} aria-haspopup="dialog" aria-expanded={showRestructure}>
            <span aria-hidden="true">✎</span> {tr("restructure")}
          </button>
          <button className={`icon-btn ${showSettings ? "on" : ""}`} onClick={() => { setShowHistory(false); setShowQueue(false); setShowInspector(false); setShowSheet(false); setShowSettings(true); }}>{tr("settings")}</button>
          <button className="icon-btn menu-trigger" onClick={returnToSetup}>{tr("menu")}</button>
        </nav>
      </header>

      <aside className="cast" aria-label={tr("sceneCast")}>
        <span className="cast-label">{tr("presentNow")} <i /></span>
        <div className="cast-roster">
          <div className="player-card-slot"><CharacterCard characterId="player" name={playerName} player
            url={displayUrl(assets, "card.player.default")} speaking={speaking === "player"} /></div>
          <div className="cast-list">
          {scene.characters.filter((c) => c.characterId !== "player").map((c) => (
            <CharacterCard key={c.characterId} characterId={c.characterId} name={c.name}
              url={displayUrl(assets, assets?.assets.find((a) => a.kind === "CHARACTER_CARD" && a.subjectId === c.characterId)?.assetId)}
              speaking={speaking === c.characterId} />
          ))}
          </div>
        </div>
      </aside>

      <div className="sprites" aria-label={tr("stageSprites")}>
        {featuredCharacter && (
          <div className="sprite featured-sprite" key={featuredCharacter.characterId}>
            <Sprite characterId={featuredCharacter.characterId} name={featuredCharacter.name}
              url={featuredPortrait} dimmed={false}
              speaking={speaking === featuredCharacter.characterId} />
          </div>
        )}
      </div>

      <div className="textbox" onClick={advance}>
        <div className="textbox-inner">
          <div className="dialogue-topline">
            <div className={`nameplate ${block?.type === "narration" ? "is-narration" : ""}`}>
              <i />{playerTurn ? playerName : block?.type === "dialogue" ? block.speakerName : tr("narrator")}
              {block?.expression && <span className="expr">{expressionLabel(lang, block.expression)}</span>}
            </div>
            <div className="chapter-track" title={tr("chapterProgress")}><i style={{ width: `${Math.round(progress.fraction * 100)}%` }} /></div>
          </div>
          {lastRoll && (
            <div className={`roll-chip ${lastRoll.success ? "success" : "failure"}`}>
              <span className="d">🎲 {lastRoll.d20}</span>
              <span>+ {lastRoll.modifier}</span>
              <span>= <b>{lastRoll.total}</b></span>
              <span className="dc">DC {lastRoll.dc}</span>
              <span className="verd">{lastRoll.success ? tr("checkOk") : tr("checkFail")}</span>
            </div>
          )}
          {playerTurn && <div className="player-turn-status" role="status">{playerTurn.confirmed ? playerTurn.choice.actionKind === "dialogue" ? tr("youSay") : tr("yourAction") : tr("submittingLine")}</div>}
          <div className={`line ${block?.type ?? "narration"} ${typed.complete ? "is-complete" : "is-typing"}`}>
            <span aria-hidden="true">{typed.visible}</span><span className="sr-only">{block?.text}</span>
            {!typed.complete && <i className="type-cursor" aria-hidden="true" />}
          </div>

          {choicesVisible ? (
            scene.choices.length > 0 ? (
              <div className="choices" onClick={(e) => e.stopPropagation()}>
                {scene.choices.map((c, i) => (
                  <button
                    className="choice"
                    key={c.id}
                    onClick={(e) => {
                      e.currentTarget.blur();
                      void choose(c.id);
                    }}
                    disabled={busy}
                  >
                    <span className="approach">{approachLabel(lang, c.approach)}</span>
                    <span className="ctext">
                      <b style={{ color: "var(--ink-faint)", fontWeight: 400, marginRight: 8 }}>{i + 1}</b>
                      {c.text}
                    </span>
                    {c.check && (
                      <span className="check">🎲 {c.check.stat} DC {c.check.dc}</span>
                    )}
                  </button>
                ))}
              </div>
            ) : (
              <div className="choices">
                <div className="empty" style={{ padding: "10px 2px" }}>
                  {finished
                    ? continuationPending
                      ? continuationRefreshFailed
                        ? tr("arcReadFail")
                        : tr("preparingArc")
                      : tr("storyPaused")
                    : tr("waitingPlot")}
                </div>
              </div>
            )
          ) : (
            <div className="advance">
              <button className="advance-button" disabled={typed.complete && Boolean(playerTurn && (!playerTurn.confirmed || !queued || rollCheck))} aria-label={typed.complete ? tr("nextLine") : tr("showFull")} onClick={(e) => { e.stopPropagation(); advance(); }}>
                {typed.complete ? playerTurn && !playerTurn.confirmed ? tr("waitingReply") : tr("continue") : tr("showAll")}<span className="caret" aria-hidden="true" />
              </button>
            </div>
          )}
        </div>
      </div>

      {rollCheck && <div className={playerTurn ? "player-dice" : ""}><DiceOverlay check={rollCheck} roll={roll} onDone={finishRoll} /></div>}

      {showHistory && <HistoryDialog sessionId={sessionId} throughSceneId={playerTurn?.confirmed && queued ? queued.scene.sceneId : scene.sceneId}
        throughBlockIndex={playerTurn?.confirmed && queued ? -1 : playerTurn ? blocks.length - 1 : typed.complete ? blockIndex : blockIndex - 1}
        onClose={() => setShowHistory(false)} onRewind={rewind}
        onRestructure={(sceneId, label) => openRestructure(sceneId, label)}
        rewindBlocked={Boolean(playerTurn || requestPending.current || resolvingChoiceId || rollCheck)} />}

      {!tourSeen && !playerTurn && !rollCheck && !showRestructure && !showHistory && !showQueue
        && !showSheet && !showSettings && !showInspector
        && <StageTour onDone={() => { markSeen(TOUR_KEY); setTourSeen(true); }} />}

      {showRestructure && <RestructureDialog
        anchorLabel={restructureAnchor?.label ?? null}
        canRestructure={Boolean(restructureAnchor)}
        job={restructureJob.job} running={restructureJob.running} error={restructureJob.error}
        onSubmit={submitRestructure} onRetry={restructureJob.retry}
        onClose={() => { setShowRestructure(false); setRestructureAnchor(null); restructureJob.reset(); }} />}

      {showSheet && <SidePanel state={state} story={story} cardUrl={displayUrl(assets, "card.player.default")} onClose={() => setShowSheet(false)} />}

      {showSettings && <SettingsDialog onClose={() => setShowSettings(false)}
        onSaved={() => { api.config().then(applyConfig).catch(() => undefined); }}
        onGuideReset={() => { setWelcomeSeen(false); setTourSeen(false); }} />}

      {showQueue && <TaskQueue sessionId={sessionId} sceneId={scene.sceneId} assets={assets}
        onClose={() => setShowQueue(false)} onInspect={() => { setShowQueue(false); setShowInspector(true); }} />}

      {showInspector && (
        <DevInspector
          sessionId={sessionId}
          state={state}
          scene={scene}
          assets={assets}
          onClose={() => setShowInspector(false)}
        />
      )}

      {loadingMessage && (
        <div className="scene-loading" role="status">
          <div className="spinner" />
          <div className="msg">{loadingMessage}</div>
          {resolvingChoiceId && <button className="icon-btn" onClick={returnToSetup}>{tr("backMenu")}</button>}
        </div>
      )}

      {notices}
    </div>,
  );
}
