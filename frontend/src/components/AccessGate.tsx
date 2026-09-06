import { useState, type FormEvent } from "react";
import { api, getAccessKey, setAccessKey } from "../api";
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
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(
    reason === "denied" && getAccessKey() ? "保存的访问密钥已失效，请重新输入。" : null,
  );

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
      setError("访问密钥不正确。");
    } catch {
      setAccessKey(null);
      setError("无法连接后端，请稍后再试。");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="setup setup-scene access-scene">
      <form className="access-card" onSubmit={(e) => void submit(e)} aria-labelledby="access-title">
        <span className="setup-wordmark"><span className="wordmark-glyph" aria-hidden="true">✧</span> genvn</span>
        <h1 id="access-title">输入访问密钥</h1>
        <p>这个故事空间已上锁。输入部署时设置的密钥即可进入；密钥会保存在这台浏览器里，下次无需再输。</p>
        <input
          type="password"
          autoComplete="off"
          autoFocus
          aria-label="访问密钥"
          placeholder="访问密钥"
          value={value}
          disabled={busy}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); void submit(); } }}
        />
        {error && <div className="error-banner" role="alert">{error}</div>}
        <button type="submit" className="btn" disabled={busy || !value.trim()}>
          {busy ? "正在验证…" : "进入"} <span aria-hidden="true">↗</span>
        </button>
      </form>
    </main>
  );
}
