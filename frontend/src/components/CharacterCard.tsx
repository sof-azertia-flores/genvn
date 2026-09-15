import { usePicture } from "../usePicture";
import { initials, portraitFor } from "../visual";
import { useTheme } from "../theme";
import { useT } from "../i18n";

interface Props { characterId: string; name: string; url: string | null; speaking: boolean; player?: boolean; }

/** One enduring identity card per person. Expression changes only replace the stage sprite. */
export default function CharacterCard({ characterId, name, url, speaking, player = false }: Props) {
  const tr = useT();
  const { shown, failed, retry } = usePicture(url);
  const { theme } = useTheme();
  return (
    <figure className={`cast-card ${player ? "is-player" : ""} ${speaking ? "is-speaking" : ""}`} aria-label={`${name}${speaking ? tr("speakingNow") : ""}`}>
      <div className="cast-art" style={{ background: portraitFor(characterId, theme) }}>
        {shown ? <img src={shown} alt="" draggable={false} /> : <span className="cast-initials">{initials(name)}</span>}
        <i className="cast-corner top" /><i className="cast-corner bottom" />
        {failed && <button className="cast-retry" onClick={retry} aria-label={tr("retryCard", { name })}>{tr("retry")}</button>}
      </div>
      <figcaption>{player && <small>{tr("you")}</small>}<span>{name}</span>{speaking && <i className="speaking-dot" />}</figcaption>
    </figure>
  );
}
