import { useState, type FormEvent } from "react";
import { api, getAccessKey, setAccessKey } from "../api";
import { LanguageSwitcher, useT } from "../i18n";
import "../setup.css";

interface Props {
  /** "required": first visit with no key; "denied": the server rejected the key we had. */
  reason: "required" | "denied";
  onGranted: () => void;
}

/**
 * The key screen. Shown before anything else when the backend says it wants a key, and again
 * if a call is refused later (the key was changed on the server). The key is stored in this
 * browser once the server has accepted it; a wrong one is not kept.
 */
export default function AccessGate({ reason, onGranted }: Props) {
  const tr = useT();
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    const key = value.trim();
    if (!key || busy) return;
    setBusy(true);
    setError(null);
    setAccessKey(key);
    try {
      const access = await api.access();
      if (access.granted) {
        onGranted();
        return;
      }
      setAccessKey(null);
      setError(tr("accessWrong"));
    } catch {
      setAccessKey(null);
      setError(tr("accessUnreachable"));
    } finally {
      setBusy(false);
    }
  };

  const shownError = error ?? (reason === "denied" && getAccessKey() ? tr("accessExpired") : null);

  return (
    <main className="setup setup-scene access-scene">
      <form className="access-card" onSubmit={(e) => void submit(e)} aria-labelledby="access-title">
        <div className="access-card-tools"><LanguageSwitcher /></div>
        <span className="setup-wordmark"><span className="wordmark-glyph" aria-hidden="true">✧</span> genvn</span>
        <h1 id="access-title">{tr("accessTitle")}</h1>
        <p>{tr("accessBody")}</p>
        <input
          type="password"
          autoComplete="off"
          autoFocus
          aria-label={tr("accessKey")}
          placeholder={tr("accessKey")}
          value={value}
          disabled={busy}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void submit(); } }}
        />
        {shownError && <div className="error-banner" role="alert">{shownError}</div>}
        <button type="submit" className="btn" disabled={busy || !value.trim()}>
          {busy ? tr("accessChecking") : tr("accessEnter")} <span aria-hidden="true">↗</span>
        </button>
      </form>
    </main>
  );
}
