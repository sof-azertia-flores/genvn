import { useCallback, useEffect, useState } from "react";

/** Decode off screen; stale results never paint a different subject. Downloads retry finitely. */
export function usePicture(url: string | null) {
  const [loaded, setLoaded] = useState<string | null>(null);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const retry = useCallback(() => setRevision((v) => v + 1), []);

  useEffect(() => {
    setLoaded(null);
    setFailedUrl(null);
    if (!url) return;
    let alive = true;
    let attempts = 0;
    let timer: number | undefined;
    let image: HTMLImageElement | null = null;
    const detach = () => {
      if (timer !== undefined) window.clearTimeout(timer);
      if (image) { image.onload = null; image.onerror = null; }
    };
    const load = () => {
      detach();
      if (!alive) return;
      attempts++;
      const current = new Image();
      image = current;
      const fail = () => {
        if (!alive || image !== current) return;
        detach();
        if (attempts < 3) timer = window.setTimeout(load, attempts * 1000);
        else setFailedUrl(url);
      };
      current.onload = () => {
        if (!alive || image !== current) return;
        detach();
        setLoaded(url);
      };
      current.onerror = fail;
      timer = window.setTimeout(fail, 15000);
      current.src = url;
    };
    load();
    window.addEventListener("online", retry);
    return () => {
      alive = false;
      detach();
      window.removeEventListener("online", retry);
    };
  }, [url, revision, retry]);

  // Do not wait for an effect to clear state: even the first render of a new URL
  // must show that scene's placeholder, never the previous location or expression.
  return { shown: loaded === url ? loaded : null, failed: !!url && failedUrl === url, retry };
}
