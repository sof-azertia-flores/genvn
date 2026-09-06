import { usePicture } from "../usePicture";
import { initials, portraitFor } from "../visual";

interface Props { characterId: string; name: string; url: string | null; speaking: boolean; player?: boolean; }

/** One enduring identity card per person. Expression changes only replace the stage sprite. */
export default function CharacterCard({ characterId, name, url, speaking, player = false }: Props) {
  const { shown, failed, retry } = usePicture(url);
  return (
    <figure className={`cast-card ${player ? "is-player" : ""} ${speaking ? "is-speaking" : ""}`} aria-label={`${name}${speaking ? "，正在说话" : ""}`}>
      <div className="cast-art" style={{ background: portraitFor(characterId) }}>
        {shown ? <img src={shown} alt="" draggable={false} /> : <span className="cast-initials">{initials(name)}</span>}
        <i className="cast-corner top" /><i className="cast-corner bottom" />
        {failed && <button className="cast-retry" onClick={retry} aria-label={`重试${name}的角色卡`}>重试</button>}
      </div>
      <figcaption>{player && <small>你</small>}<span>{name}</span>{speaking && <i className="speaking-dot" />}</figcaption>
    </figure>
  );
}
