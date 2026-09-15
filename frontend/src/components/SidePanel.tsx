import type { CompiledStory, GameState } from "../types";
import { STATS, statValue } from "../types";
import { statLabel, useLocale, useT } from "../i18n";
import CharacterCard from "./CharacterCard";

interface Props {
  state: GameState;
  story: CompiledStory;
  cardUrl?: string | null;
  onClose: () => void;
}

export default function SidePanel({ state, story, cardUrl, onClose }: Props) {
  const { lang } = useLocale();
  const tr = useT();
  const player = state.player;
  const hpPercent = Math.max(0, Math.round((player.hp / Math.max(1, player.maxHp)) * 100));
  const relations = Object.values(state.characters).filter((c) => c.met);

  return (
    <aside className="drawer">
      <button className="icon-btn close" onClick={onClose}>{tr("close")}</button>
      <h2>{player.name}</h2>
      <div className="sheet-portrait">
        <CharacterCard characterId="player" name={player.name} url={cardUrl ?? null} speaking={false} player />
      </div>
      <div className="sub">{player.background || tr("noBackground")}</div>

      <div className="hp-bar"><i style={{ width: `${hpPercent}%` }} /></div>
      <div className="hp-text">HP {player.hp} / {player.maxHp}</div>

      <h3>{tr("stats")}</h3>
      <div className="sheet-stats">
        {STATS.map((s) => (
          <div className="sheet-stat" key={s}>
            <span>{statLabel(lang, s)}</span>
            <b>{statValue(player.stats, s)}</b>
          </div>
        ))}
      </div>

      {player.traits.length > 0 && (
        <>
          <h3>{tr("traitsTitle")}</h3>
          <div className="tag-list">
            {player.traits.map((item) => <span className="tag" key={item}>{item}</span>)}
          </div>
        </>
      )}

      {player.conditions.length > 0 && (
        <>
          <h3>{tr("conditions")}</h3>
          <div className="tag-list">
            {player.conditions.map((c) => <span className="tag warn" key={c}>{c}</span>)}
          </div>
        </>
      )}

      <h3>{tr("inventory")}</h3>
      {state.inventory.length === 0 ? (
        <div className="empty">{tr("inventoryEmpty")}</div>
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
          <h3>{tr("relations")}</h3>
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

      <h3>{tr("threads")}</h3>
      {state.continuityLedger.length === 0 ? (
        <div className="empty">{tr("threadsEmpty")}</div>
      ) : (
        state.continuityLedger.map((item) => (
          <div className={`thread ${item.status === "resolved" ? "resolved" : ""}`} key={item.id}>
            <code>{item.id}</code>
            <span>{item.description}</span>
          </div>
        ))
      )}

      <h3>{tr("authorCanon")}</h3>
      {story.authorCanon.facts.map((f, i) => (
        <div className="thread" key={i}>
          <code>■</code>
          <span>{f}</span>
        </div>
      ))}
    </aside>
  );
}
