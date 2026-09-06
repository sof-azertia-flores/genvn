import { usePicture } from "../usePicture";

interface Props {
  characterId: string;
  name: string;
  url: string | null;
  dimmed: boolean;
  speaking: boolean;
  title?: string;
}

/** Transparent artwork on the stage, with no card background or frame. */
export default function Sprite({ name, url, dimmed, speaking, title }: Props) {
  const { shown, failed, retry } = usePicture(url);
  return (
    <div className={`portrait ${shown ? "has-image" : "is-placeholder"} ${dimmed ? "is-dimmed" : ""} ${speaking ? "is-speaking" : ""}`} title={title}>
      {shown ? <img src={shown} alt={name} className="shown" draggable={false} /> : (
        <div className="stage-placeholder" aria-label={`${name}的立绘正在准备`}><i /><span>{name}</span></div>
      )}
      {failed && <button className="icon-btn picture-retry" onClick={retry}>重试人物图片</button>}
    </div>
  );
}
