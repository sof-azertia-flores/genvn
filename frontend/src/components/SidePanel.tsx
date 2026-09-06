import type { CompiledStory, GameState } from "../types";
import { STAT_LABEL, STATS, statValue } from "../types";

interface Props {
  state: GameState;
  story: CompiledStory;
  onClose: () => void;
}

export default function SidePanel({ state, story, onClose }: Props) {
  const player = state.player;
  const hpPercent = Math.max(0, Math.round((player.hp / Math.max(1, player.maxHp)) * 100));
  const relations = Object.values(state.characters).filter((c) => c.met);

  return (
    <aside className="drawer">
      <button className="icon-btn close" onClick={onClose}>关闭</button>
      <h2>{player.name}</h2>
      <div className="sub">{player.background || "（未填写背景）"}</div>

      <div className="hp-bar"><i style={{ width: `${hpPercent}%` }} /></div>
      <div className="hp-text">HP {player.hp} / {player.maxHp}</div>

      <h3>属性 Stats</h3>
      <div className="sheet-stats">
        {STATS.map((s) => (
          <div className="sheet-stat" key={s}>
            <span>{STAT_LABEL[s].split(" ")[0]}</span>
            <b>{statValue(player.stats, s)}</b>
          </div>
        ))}
      </div>

      {player.traits.length > 0 && (
        <>
          <h3>特质 Traits</h3>
          <div className="tag-list">
            {player.traits.map((t) => <span className="tag" key={t}>{t}</span>)}
          </div>
        </>
      )}

      {player.conditions.length > 0 && (
        <>
          <h3>状态 Conditions</h3>
          <div className="tag-list">
            {player.conditions.map((c) => <span className="tag warn" key={c}>{c}</span>)}
          </div>
        </>
      )}

      <h3>持有物 Inventory</h3>
      {state.inventory.length === 0 ? (
        <div className="empty">空无一物。</div>
      ) : (
        state.inventory.map((item) => (
          <div className="item" key={item.name}>
            <div className="n">{item.name}</div>
            {item.description && <div className="d">{item.description}</div>}
          </div>
        ))
      )}

      {relations.length > 0 && (
        <>
          <h3>关系 Relations</h3>
          {relations.map((c) => (
            <div className="relation" key={c.id}>
              <span>{c.name}</span>
              <span className="bar">
                <i
                  style={{
                    left: c.relationship >= 0 ? "50%" : `${50 + c.relationship * 5}%`,
                    width: `${Math.abs(c.relationship) * 5}%`,
                    background: c.relationship >= 0 ? "var(--accent)" : "var(--failure)",
                  }}
                />
              </span>
              <span className="v">{c.relationship > 0 ? `+${c.relationship}` : c.relationship}</span>
            </div>
          ))}
        </>
      )}

      <h3>未解之谜 Threads</h3>
      {state.continuityLedger.length === 0 ? (
        <div className="empty">暂无。</div>
      ) : (
        state.continuityLedger.map((t) => (
          <div className={`thread ${t.status === "resolved" ? "resolved" : ""}`} key={t.id}>
            <code>{t.id}</code>
            <span>{t.description}</span>
          </div>
        ))
      )}

      <h3>作者铁律 Author Canon</h3>
      {story.authorCanon.facts.map((f, i) => (
        <div className="thread" key={i}>
          <code>■</code>
          <span>{f}</span>
        </div>
      ))}
    </aside>
  );
}
