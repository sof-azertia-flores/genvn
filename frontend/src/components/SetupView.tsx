import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import useCreationJob from "../useCreationJob";
import CompilationProgress from "./CompilationProgress";
import { LanguageSwitcher, STAT_I18N, t, useLocale, useT } from "../i18n";
import { STATS, type ConfigView, type SessionSummary, type SessionView, type StatName } from "../types";
import "../setup.css";

const POINT_BUDGET = 15;
const EXAMPLE_KEYS = { outline: "exampleOutline", background: "exampleBackground", traits: "exampleTraits" } as const;

interface Props { config: ConfigView | null; onStarted: (session: SessionView) => void | Promise<void>; onLoad: (sessionId: string) => Promise<void>; onOpenSettings?: () => void }

export default function SetupView({ config, onStarted, onLoad, onOpenSettings }: Props) {
  const { lang } = useLocale();
  const tr = useT();
  const [outline, setOutline] = useState(() => t(lang, "exampleOutline"));
  const [name, setName] = useState("Alex");
  const [background, setBackground] = useState(() => t(lang, "exampleBackground"));
  const [artStyle, setArtStyle] = useState("");
  const [visualDescription, setVisualDescription] = useState("");
  const [traits, setTraits] = useState(() => t(lang, "exampleTraits"));
  const [stats, setStats] = useState<Record<StatName, number>>({ Body: 2, Agility: 2, Perception: 4, Intellect: 3, Will: 3, Presence: 1 });
  const [loadingSave, setLoadingSave] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saves, setSaves] = useState<SessionSummary[]>([]);
  const loadPending = useRef(false);
  const logWindow = useRef<HTMLOListElement>(null);
  const creation = useCreationJob();
  const preparing = creation.pending || creation.job !== null;
  const busy = preparing || loadingSave;
  const clock = lang === "en" ? "en-GB" : "zh-CN";

  useEffect(() => {
    setOutline((prev) => stillExample(prev, "exampleOutline") ? t(lang, "exampleOutline") : prev);
    setBackground((prev) => stillExample(prev, "exampleBackground") ? t(lang, "exampleBackground") : prev);
    setTraits((prev) => stillExample(prev, "exampleTraits") ? t(lang, "exampleTraits") : prev);
  }, [lang]);

  useEffect(() => {
    let active = true;
    api.listSessions().then((sessions) => { if (active) setSaves(sessions); })
      .catch(() => { if (active) setError(tr("savesLoadError")); });
    return () => { active = false; };
  }, [preparing, tr]);
  useEffect(() => { if (logWindow.current) logWindow.current.scrollTop = logWindow.current.scrollHeight; }, [creation.job?.logs.length]);
  const spent = STATS.reduce((sum, s) => sum + stats[s], 0);
  const adjust = (stat: StatName, delta: number) => setStats((prev) => {
    if (delta > 0 && STATS.reduce((sum, s) => sum + prev[s], 0) >= POINT_BUDGET) return prev;
    return { ...prev, [stat]: Math.max(0, Math.min(5, prev[stat] + delta)) };
  });
  const start = () => {
    if (busy || outline.trim().length < 10) return;
    setError(null);
    void creation.begin({ storyOutline: outline.trim(), artStyle: config?.imageEnabled ? artStyle.trim() : "", player: {
      name: name.trim() || tr("traveler"), background: background.trim(), visualDescription: visualDescription.trim(), stats,
      traits: traits.split(/[,，]/).map((item) => item.trim()).filter(Boolean), maxHp: 10,
    } });
  };
  const load = async (id: string) => {
    if (loadPending.current || busy) return;
    loadPending.current = true; setLoadingSave(true); setError(null);
    try { await onLoad(id); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { loadPending.current = false; setLoadingSave(false); }
  };
  const enter = async () => {
    if (!creation.readySession || loadPending.current) return;
    loadPending.current = true; setLoadingSave(true);
    try { await onStarted(creation.readySession); }
    catch (e) { setError(e instanceof Error ? e.message : tr("enterFailed")); }
    finally { loadPending.current = false; setLoadingSave(false); }
  };
  const statName = (stat: StatName) => t(lang, STAT_I18N[stat].label);

  return <main className="setup setup-scene"><div className="setup-shell">
    <header className="setup-masthead">
      <span className="setup-wordmark"><span className="wordmark-glyph" aria-hidden="true">✧</span> genvn</span>
      <div className="setup-mast-actions">
        <div className="setup-language"><LanguageSwitcher /></div>
        <span className="setup-mode"><i />{config?.mockMode ? tr("offline") : config ? tr("connected") : tr("connecting")}<b>{tr("localSpace")}</b></span>
        {onOpenSettings && <button className="text-button setup-settings" type="button" onClick={onOpenSettings}>{tr("settings")}</button>}
      </div>
    </header>
    <div className="setup-intro"><span className="eyebrow">YOUR NEXT CHAPTER</span>
      <h1>{tr("setupTitle1")}<br className="intro-break" />{tr("setupTitle2")}</h1><p>{tr("setupLead")}</p>
    </div>
    {preparing ? <section className={`compilation-panel ${creation.job?.status === "READY" ? "is-ready" : ""}`} aria-labelledby="compile-title">
      <div className="compile-emblem" aria-hidden="true"><span>✧</span></div>
      <div className="compile-heading"><span className="eyebrow">PREPARING YOUR STORY</span>
        <h2 id="compile-title">{creation.readySession ? tr("compileReadyTitle") : creation.job?.status === "FAILED" ? tr("compileFailedTitle") : tr("compileWorkingTitle")}</h2>
        <p>{creation.readySession ? tr("compileReadyBody") : tr("compileWorkingBody")}</p>
      </div>
      <div className="compile-log-head"><span>{tr("compileLogHead")}</span><span>{creation.pending ? tr("compileRunning") : creation.readySession ? tr("compileDone") : tr("compilePaused")}</span></div>
      <ol className="compile-log" ref={logWindow} role="log" aria-live="polite" aria-relevant="additions" aria-label={tr("compileLogAria")}>
        <li><time>{tr("compilePreludeTime")}</time><span>{tr("compilePreface")}</span></li>
        {creation.job?.logs.map((entry) => <li key={entry.id}><time dateTime={entry.time}>{new Date(entry.time).toLocaleTimeString(clock, { hour12: false })}</time><span>{entry.message}</span></li>)}
        {creation.pending && <li className="compile-wait" aria-hidden="true"><time>···</time><span>{tr("compileWaiting")}</span></li>}
      </ol>
      <CompilationProgress job={creation.job} pending={creation.pending} />
      <p className="compile-footnote">{tr("compileFoot")}</p>
      {(creation.error || error) && <div className="error-banner" role="alert">{creation.error || error}</div>}
      <div className="compile-actions">
        {creation.readySession && <button className="btn" onClick={() => void enter()} disabled={loadingSave}>{tr("enterStory")} <span aria-hidden="true">↗</span></button>}
        {creation.error && creation.job?.status !== "FAILED" && <button className="btn ghost" onClick={creation.retry} disabled={creation.pending}>{tr("retryProgress")}</button>}
        {creation.job?.status === "FAILED" && <button className="btn ghost" onClick={creation.reset}>{tr("backToEdit")}</button>}
      </div>
    </section> : <div className="setup-compose">
      <section className="story-paper" aria-labelledby="story-title">
        <div className="section-caption"><span>{tr("storyCaption")}</span><button className="text-button" disabled={busy} onClick={() => setOutline(tr("exampleOutline"))}>{tr("tryExample")}</button></div>
        <h2 id="story-title">{tr("storyHeading")}</h2>
        <label className="sr-only" htmlFor="outline">{tr("outline")}</label>
        <textarea id="outline" value={outline} maxLength={6000} onChange={(e) => setOutline(e.target.value)} placeholder={tr("outlinePlaceholder")} />
        <div className="paper-footer"><span>{tr("outlineFoot")}</span><span>{outline.length} / 6000</span></div>
        <div className="story-quote" aria-hidden="true"><span>“</span>{tr("storyQuote")}</div>
      </section>
      <section className="character-paper" aria-labelledby="character-title">
        <div className="section-caption"><span>{tr("characterCaption")}</span><span className="character-sigil" aria-hidden="true">◇</span></div>
        <h2 id="character-title">{tr("characterHeading")}</h2>
        <div className="field"><label htmlFor="pname">{tr("name")}</label><input id="pname" type="text" maxLength={60} value={name} onChange={(e) => setName(e.target.value)} /></div>
        <div className="field"><label htmlFor="pbg">{tr("background")}</label><input id="pbg" type="text" maxLength={400} value={background} onChange={(e) => setBackground(e.target.value)} /></div>
        <div className="field"><label htmlFor="ptraits">{tr("traits")} <small>{tr("traitsHint")}</small></label><input id="ptraits" type="text" maxLength={600} value={traits} onChange={(e) => setTraits(e.target.value)} /></div>
        <div className="field"><label htmlFor="pvisual">{tr("appearance")} <small>{tr("appearanceHint")}</small></label>
          <textarea id="pvisual" className="appearance-input" value={visualDescription} maxLength={2000} onChange={(e) => setVisualDescription(e.target.value)} placeholder={tr("appearancePlaceholder")} />
        </div>
        <div className="attribute-heading"><span>{tr("statsHeading")}</span><span>{tr("statsUsed")} <b>{spent}</b> / {POINT_BUDGET}</span></div>
        <div className="setup-stat-grid">{STATS.map((stat) => <div className="setup-stat" key={stat}>
          <div><span>{statName(stat)}</span><small>{t(lang, STAT_I18N[stat].hint)}</small></div>
          <button aria-label={tr("decrease", { stat: statName(stat) })} onClick={() => adjust(stat, -1)} disabled={stats[stat] <= 0}>−</button><b>{stats[stat]}</b>
          <button aria-label={tr("increase", { stat: statName(stat) })} onClick={() => adjust(stat, 1)} disabled={stats[stat] >= 5 || spent >= POINT_BUDGET}>+</button>
        </div>)}</div>
        <p className="rule-note">{tr("diceNote")}</p>
      </section>
      {config?.imageEnabled && <section className="art-paper" aria-labelledby="art-title">
        <div className="section-caption"><span>{tr("artCaption")}</span><span>{tr("artOptional")}</span></div>
        <h2 id="art-title">{tr("artHeading")}</h2>
        <p id="art-hint">{tr("artHint")}</p>
        <label className="sr-only" htmlFor="art-style">{tr("artLabel")}</label>
        <textarea id="art-style" className="art-style-input" aria-describedby="art-hint" value={artStyle} maxLength={6000} onChange={(e) => setArtStyle(e.target.value)} placeholder={tr("artPlaceholder")} />
        <div className="paper-footer"><span>{tr("artFoot")}</span><span>{artStyle.length} / 6000</span></div>
      </section>}
      <div className="setup-launch"><p><span aria-hidden="true">✧</span> {tr("launchNote")}</p><button className="btn" onClick={start} disabled={busy || outline.trim().length < 10}>{loadingSave ? tr("loadingSave") : tr("startStory")}<span aria-hidden="true">↗</span></button></div>
    </div>}
    {!preparing && (error || creation.error) && <div className="error-banner" role="alert">{error || creation.error}</div>}
    {!preparing && saves.length > 0 && <section className="setup-library" aria-labelledby="library-title">
      <div className="section-caption"><h2 id="library-title">{tr("libraryTitle")}</h2><span>{tr("notebookCount", { n: saves.length })}</span></div>
      <div className="save-grid">{saves.map((s) => <button className="save-book" key={s.id} onClick={() => void load(s.id)} disabled={busy}>
        <span className="book-symbol" aria-hidden="true">❧</span><span><strong>{s.title || tr("unnamedStory")}</strong><small>{s.playerName} · {tr("scenesWalked", { n: s.scenesPlayed })}{s.finished ? ` · ${tr("chapterDone")}` : ""}</small></span><span className="book-arrow" aria-hidden="true">↗</span>
      </button>)}</div>
    </section>}
    <footer className="setup-footer"><span>GENVN</span><span>{tr("footerWait")}</span><span>STORIES, UNFOLDING.</span></footer>
  </div></main>;
}

function stillExample(value: string, key: typeof EXAMPLE_KEYS[keyof typeof EXAMPLE_KEYS]): boolean {
  return value === t("zh", key) || value === t("en", key);
}
