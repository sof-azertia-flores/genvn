import { useEffect, useMemo, useRef, useState } from "react";
import { api, setAccessKey } from "../api";
import { fieldHint, fieldLabel, groupLabel, LanguageSwitcher, parseLang, useLocale, useT } from "../i18n";
import type { SettingsField, SettingsKind, SettingsView } from "../types";

interface Props {
  onClose: () => void;
  onSaved?: () => void;
}

const GROUP_ORDER = ["服务器", "语言模型", "推理力度", "引擎", "预推演与续章", "图片"];

export default function SettingsDialog({ onClose, onSaved }: Props) {
  const { lang, setLang } = useLocale();
  const tr = useT();
  const [view, setView] = useState<SettingsView | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [group, setGroup] = useState(GROUP_ORDER[0]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let alive = true;
    api.settings().then((next) => {
      if (!alive) return;
      setView(next);
      setDraft(toDraft(next.fields));
      setGroup(next.fields[0]?.group ?? GROUP_ORDER[0]);
    }).catch((failure) => {
      if (alive) setError(failure instanceof Error ? failure.message : String(failure));
    }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  useEffect(() => { closeButton.current?.focus(); }, [loading]);

  const groups = useMemo(() => {
    const seen = new Set<string>();
    const names: string[] = [];
    for (const name of GROUP_ORDER) {
      if (view?.fields.some((field) => field.group === name)) { seen.add(name); names.push(name); }
    }
    for (const field of view?.fields ?? []) {
      if (!seen.has(field.group)) { seen.add(field.group); names.push(field.group); }
    }
    return names;
  }, [view]);

  const fields = (view?.fields ?? []).filter((field) => field.group === group);

  const save = async () => {
    if (!view || saving) return;
    setSaving(true); setError(null); setNotice(null);
    const values: Record<string, unknown> = {};
    for (const field of view.fields) {
      if (field.kind === "SECRET" && !touched[field.key]) continue;
      values[field.key] = fromDraft(field.kind, draft[field.key] ?? "");
    }
    try {
      const next = await api.saveSettings(values);
      setView(next);
      setDraft(toDraft(next.fields));
      if (touched["genvn.access-key"]) {
        const typed = String(values["genvn.access-key"] ?? "").trim();
        setAccessKey(typed || null);
      }
      if (touched["genvn.language"]) {
        setLang(parseLang(String(values["genvn.language"] ?? "zh")));
      }
      setTouched({});
      setNotice(next.restartPending.length > 0
        ? tr("settingsRestartPending", { keys: next.restartPending.join(lang === "en" ? ", " : "、") })
        : tr("settingsApplied"));
      onSaved?.();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="task-overlay settings-overlay" onClick={onClose}>
      <div className="task-dialog settings-dialog" role="dialog" aria-modal="true" aria-labelledby="settings-title" onClick={(event) => event.stopPropagation()}>
        <header className="task-header">
          <div><span className="eyebrow">RUNTIME CONFIG</span><h2 id="settings-title">{tr("settingsTitle")}</h2></div>
          <div className="settings-header-tools">
            <LanguageSwitcher />
            <button className="dialog-close" ref={closeButton} onClick={onClose} aria-label={tr("settingsClose")}>×</button>
          </div>
        </header>
        <p className="task-intro">{tr("settingsIntro")}</p>
        {loading && <p className="queue-empty" role="status">{tr("settingsLoading")}</p>}
        {error && <div className="queue-notice" role="alert">{error}</div>}
        {notice && <div className="settings-notice" role="status">{notice}</div>}
        {view && (
          <>
            <nav className="settings-tabs" aria-label={tr("settingsGroups")}>
              {groups.map((name) => (
                <button key={name} className={name === group ? "on" : ""} onClick={() => setGroup(name)}>{groupLabel(lang, name)}</button>
              ))}
            </nav>
            <div className="settings-body">
              {fields.map((field) => (
                <label className="settings-field" key={field.key}>
                  <span>{fieldLabel(lang, field)}{field.restartRequired && <small>{tr("settingsRestart")}</small>}</span>
                  {editor(field, draft[field.key] ?? "", (value) => {
                    setDraft((prev) => ({ ...prev, [field.key]: value }));
                    setTouched((prev) => ({ ...prev, [field.key]: true }));
                    if (field.key === "genvn.language") setLang(parseLang(value));
                  }, tr)}
                  <small>{fieldHint(lang, field)}</small>
                </label>
              ))}
            </div>
            <footer className="task-footer">
              <span>{view.file}</span>
              <button className="btn" disabled={saving} onClick={() => void save()}>{saving ? tr("settingsSaving") : tr("settingsSave")}</button>
            </footer>
          </>
        )}
      </div>
    </div>
  );
}

function toDraft(fields: SettingsField[]): Record<string, string> {
  const draft: Record<string, string> = {};
  for (const field of fields) {
    if (field.kind === "SECRET") draft[field.key] = "";
    else if (field.kind === "LIST") draft[field.key] = Array.isArray(field.value) ? field.value.join("\n") : "";
    else if (field.kind === "BOOLEAN") draft[field.key] = field.value ? "true" : "false";
    else draft[field.key] = field.value == null ? "" : String(field.value);
  }
  return draft;
}

function fromDraft(kind: SettingsKind, value: string): unknown {
  if (kind === "BOOLEAN") return value === "true";
  if (kind === "INTEGER") return value.trim() === "" ? 0 : Number.parseInt(value, 10);
  if (kind === "NUMBER") return value.trim() === "" ? 0 : Number.parseFloat(value);
  if (kind === "LIST") return value.split(/\n/).map((line) => line.trim()).filter(Boolean);
  return value;
}

function editor(field: SettingsField, value: string, onChange: (value: string) => void, tr: (key: string) => string) {
  if (field.key === "genvn.language") {
    const current = parseLang(value);
    return (
      <div className="language-switch">
        <button type="button" className={current === "zh" ? "on" : ""} aria-pressed={current === "zh"} onClick={() => onChange("zh")}>中文</button>
        <button type="button" className={current === "en" ? "on" : ""} aria-pressed={current === "en"} onClick={() => onChange("en")}>English</button>
      </div>
    );
  }
  if (field.kind === "BOOLEAN") {
    return (
      <button type="button" className={`settings-toggle ${value === "true" ? "on" : ""}`}
        onClick={() => onChange(value === "true" ? "false" : "true")}>
        {value === "true" ? tr("settingsOn") : tr("settingsOff")}
      </button>
    );
  }
  if (field.kind === "LIST") {
    return <textarea rows={3} value={value} onChange={(event) => onChange(event.target.value)} />;
  }
  return (
    <input
      type={field.kind === "SECRET" ? "password" : field.kind === "INTEGER" || field.kind === "NUMBER" ? "number" : "text"}
      value={value}
      placeholder={field.kind === "SECRET" ? (field.secretSet ? tr("settingsSecretSet") : tr("settingsSecretEmpty")) : undefined}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}
