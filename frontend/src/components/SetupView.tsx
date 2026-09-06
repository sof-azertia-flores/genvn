import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import useCreationJob from "../useCreationJob";
import CompilationProgress from "./CompilationProgress";
import { STATS, STAT_LABEL, type ConfigView, type SessionSummary, type SessionView, type StatName } from "../types";
import "../setup.css";

const EXAMPLE_STORY = "我来到一座多年无人居住的旧宅寻找祖父留下的日记。祖父失踪前寄给我一封信，说他在这栋房子里藏了一样不能落入别人手中的东西。附近唯一的邻居警告我不要在午夜后进入二楼，因为这些年来，每到午夜，那里都会传来脚步声。我决定在旧宅住下来调查真相。";
const EXAMPLE_STATS: Record<StatName, number> = { Body: 2, Agility: 2, Perception: 4, Intellect: 3, Will: 3, Presence: 1 };
const POINT_BUDGET = 15;
const STAT_HINT: Record<StatName, string> = { Body: "力量与耐力", Agility: "反应与潜行", Perception: "观察与直觉", Intellect: "知识与推理", Will: "勇气与自控", Presence: "交涉与影响" };
interface Props { config: ConfigView | null; onStarted: (session: SessionView) => void | Promise<void>; onLoad: (sessionId: string) => Promise<void> }

export default function SetupView({ config, onStarted, onLoad }: Props) {
  const [outline, setOutline] = useState(EXAMPLE_STORY);
  const [name, setName] = useState("Alex");
  const [background, setBackground] = useState("档案整理员，习惯把混乱的东西排成顺序");
  const [artStyle, setArtStyle] = useState("");
  const [visualDescription, setVisualDescription] = useState("");
  const [traits, setTraits] = useState("怕黑但不承认, 记得住细节");
  const [stats, setStats] = useState<Record<StatName, number>>({ ...EXAMPLE_STATS });
  const [loadingSave, setLoadingSave] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saves, setSaves] = useState<SessionSummary[]>([]);
  const loadPending = useRef(false);
  const logWindow = useRef<HTMLOListElement>(null);
  const creation = useCreationJob();
  const preparing = creation.pending || creation.job !== null;
  const busy = preparing || loadingSave;

  useEffect(() => {
    let active = true;
    api.listSessions().then((sessions) => { if (active) setSaves(sessions); })
      .catch(() => { if (active) setError("无法读取存档列表，请确认后端正在运行后刷新页面。"); });
    return () => { active = false; };
  }, [preparing]);
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
      name: name.trim() || "旅人", background: background.trim(), visualDescription: visualDescription.trim(), stats,
      traits: traits.split(/[,，]/).map((t) => t.trim()).filter(Boolean), maxHp: 10,
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
    catch (e) { setError(e instanceof Error ? e.message : "暂时无法进入故事。"); }
    finally { loadPending.current = false; setLoadingSave(false); }
  };

  return <main className="setup setup-scene"><div className="setup-shell">
    <header className="setup-masthead">
      <span className="setup-wordmark"><span className="wordmark-glyph" aria-hidden="true">✧</span> genvn</span>
      <span className="setup-mode"><i />{config?.mockMode ? "离线体验" : config ? "灵感已连接" : "正在连接…"}<b>本地故事空间</b></span>
    </header>
    <div className="setup-intro"><span className="eyebrow">YOUR NEXT CHAPTER</span>
      <h1>写下开端，<br className="intro-break" />让故事遇见你。</h1><p>世界从你的几行文字展开。前方的命运，由选择与骰子共同书写。</p>
    </div>
    {preparing ? <section className={`compilation-panel ${creation.job?.status === "READY" ? "is-ready" : ""}`} aria-labelledby="compile-title">
      <div className="compile-emblem" aria-hidden="true"><span>✧</span></div>
      <div className="compile-heading"><span className="eyebrow">PREPARING YOUR STORY</span>
        <h2 id="compile-title">{creation.readySession ? "故事已为你展开" : creation.job?.status === "FAILED" ? "这次准备暂未完成" : "正在点亮你的世界"}</h2>
        <p>{creation.readySession ? "第一幕已经就绪。其余画面会在你阅读时继续绘制。" : "整理故事、认识人物，为尚未发生的相遇准备画面。"}</p>
      </div>
      <div className="compile-log-head"><span>准备手记</span><span>{creation.pending ? "正在进行" : creation.readySession ? "已完成" : "已暂停"}</span></div>
      <ol className="compile-log" ref={logWindow} role="log" aria-live="polite" aria-relevant="additions" aria-label="故事编译进度日志">
        <li><time>序</time><span>已收下你的故事，等待世界展开。</span></li>
        {creation.job?.logs.map((entry) => <li key={entry.id}><time dateTime={entry.time}>{new Date(entry.time).toLocaleTimeString("zh-CN", { hour12: false })}</time><span>{entry.message}</span></li>)}
        {creation.pending && <li className="compile-wait" aria-hidden="true"><time>···</time><span>故事正在向前。</span></li>}
      </ol>
      <CompilationProgress job={creation.job} pending={creation.pending} />
      <p className="compile-footnote">每一步准备都会留在手记中 · 图片在后台继续绘制</p>
      {(creation.error || error) && <div className="error-banner" role="alert">{creation.error || error}</div>}
      <div className="compile-actions">
        {creation.readySession && <button className="btn" onClick={() => void enter()} disabled={loadingSave}>步入故事 <span aria-hidden="true">↗</span></button>}
        {creation.error && creation.job?.status !== "FAILED" && <button className="btn ghost" onClick={creation.retry} disabled={creation.pending}>重新读取进度</button>}
        {creation.job?.status === "FAILED" && <button className="btn ghost" onClick={creation.reset}>返回修改故事</button>}
      </div>
    </section> : <div className="setup-compose">
      <section className="story-paper" aria-labelledby="story-title">
        <div className="section-caption"><span>01 / 故事的起点</span><button className="text-button" disabled={busy} onClick={() => setOutline(EXAMPLE_STORY)}>试读一段灵感 ↗</button></div>
        <h2 id="story-title">这一次，你将走向哪里？</h2>
        <label className="sr-only" htmlFor="outline">故事大纲</label>
        <textarea id="outline" value={outline} maxLength={6000} onChange={(e) => setOutline(e.target.value)} placeholder="一封迟来的信，一座无人居住的旧宅，或是一场尚未结束的旅行……" />
        <div className="paper-footer"><span>你写下的设定，是这个世界的起点。</span><span>{outline.length} / 6000</span></div>
        <div className="story-quote" aria-hidden="true"><span>“</span>尚未落笔的下一页，<br />藏着你自己的答案。</div>
      </section>
      <section className="character-paper" aria-labelledby="character-title">
        <div className="section-caption"><span>02 / 你的角色</span><span className="character-sigil" aria-hidden="true">◇</span></div>
        <h2 id="character-title">以谁的名字出发</h2>
        <div className="field"><label htmlFor="pname">姓名</label><input id="pname" type="text" maxLength={60} value={name} onChange={(e) => setName(e.target.value)} /></div>
        <div className="field"><label htmlFor="pbg">来处与经历</label><input id="pbg" type="text" maxLength={400} value={background} onChange={(e) => setBackground(e.target.value)} /></div>
        <div className="field"><label htmlFor="ptraits">性格与特质 <small>用逗号分隔</small></label><input id="ptraits" type="text" maxLength={600} value={traits} onChange={(e) => setTraits(e.target.value)} /></div>
        <div className="field"><label htmlFor="pvisual">外貌与衣着 <small>用来绘制你的立绘与角色卡</small></label>
          <textarea id="pvisual" className="appearance-input" value={visualDescription} maxLength={2000} onChange={(e) => setVisualDescription(e.target.value)} placeholder="年龄、发型、面容、身形、衣着与随身物件……写下你希望保留的特征。" />
        </div>
        <div className="attribute-heading"><span>天赋分配</span><span>已用 <b>{spent}</b> / {POINT_BUDGET}</span></div>
        <div className="setup-stat-grid">{STATS.map((stat) => <div className="setup-stat" key={stat}>
          <div><span>{STAT_LABEL[stat].split(" ")[0]}</span><small>{STAT_HINT[stat]}</small></div>
          <button aria-label={`减少${STAT_LABEL[stat].split(" ")[0]}`} onClick={() => adjust(stat, -1)} disabled={stats[stat] <= 0}>−</button><b>{stats[stat]}</b>
          <button aria-label={`增加${STAT_LABEL[stat].split(" ")[0]}`} onClick={() => adjust(stat, 1)} disabled={stats[stat] >= 5 || spent >= POINT_BUDGET}>+</button>
        </div>)}</div>
        <p className="rule-note">掷一枚二十面骰，加上天赋，与命运的难度相遇。</p>
      </section>
      {config?.imageEnabled && <section className="art-paper" aria-labelledby="art-title">
        <div className="section-caption"><span>03 / 世界的画风</span><span>可选 · 贯穿整部故事</span></div>
        <h2 id="art-title">你想看见怎样的世界？</h2>
        <p id="art-hint">自由描述插图风格、色彩、线条、光影与时代气质。场景、角色卡和人物立绘都会沿用这份美术设定。</p>
        <label className="sr-only" htmlFor="art-style">整体插图设计风格</label>
        <textarea id="art-style" className="art-style-input" aria-describedby="art-hint" value={artStyle} maxLength={6000} onChange={(e) => setArtStyle(e.target.value)} placeholder="例如：细腻的水彩插画，保留纸张肌理和手绘线条；以灰蓝、苔绿为主，暖黄色灯光点缀。人物比例写实，衣着参考故事发生的年代，整体安静而略带神秘感。" />
        <div className="paper-footer"><span>留空时，系统会按故事氛围安排画风。</span><span>{artStyle.length} / 6000</span></div>
      </section>}
      <div className="setup-launch"><p><span aria-hidden="true">✧</span> 故事、骰子、每一次相遇，都留在你的世界里。</p><button className="btn" onClick={start} disabled={busy || outline.trim().length < 10}>{loadingSave ? "正在读取存档…" : "让故事开始"}<span aria-hidden="true">↗</span></button></div>
    </div>}
    {!preparing && (error || creation.error) && <div className="error-banner" role="alert">{error || creation.error}</div>}
    {!preparing && saves.length > 0 && <section className="setup-library" aria-labelledby="library-title">
      <div className="section-caption"><h2 id="library-title">继续未完的故事</h2><span>{saves.length} 本手记</span></div>
      <div className="save-grid">{saves.map((s) => <button className="save-book" key={s.id} onClick={() => void load(s.id)} disabled={busy}>
        <span className="book-symbol" aria-hidden="true">❧</span><span><strong>{s.title || "未命名故事"}</strong><small>{s.playerName} · 已走过 {s.scenesPlayed} 幕{s.finished ? " · 本章完" : ""}</small></span><span className="book-arrow" aria-hidden="true">↗</span>
      </button>)}</div>
    </section>}
    <footer className="setup-footer"><span>GENVN</span><span>每一条未选择的路，都曾在此等待。</span><span>STORIES, UNFOLDING.</span></footer>
  </div></main>;
}
