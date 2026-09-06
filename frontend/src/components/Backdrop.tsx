import { usePicture } from "../usePicture";

interface Props {
  /** The real background, or null for the placeholder gradient. */
  url: string | null;
  fallback: string;
}

/**
 * Paints the placeholder gradient at once and only ever lays a real picture over it AFTER the
 * browser has decoded it, so a finished image fades in rather than flashing white or jumping.
 */
export default function Backdrop({ url, fallback }: Props) {
  const { shown, failed, retry } = usePicture(url);

  return (
    <div className="backdrop" style={{ background: fallback }}>
      {shown && <div className="backdrop-img shown" key={shown} style={{ backgroundImage: `url("${shown}")` }} />}
      {failed && <button className="icon-btn picture-retry background-retry" onClick={retry}>重试背景图片</button>}
    </div>
  );
}
