import { useT } from "../i18n";
import { THEMES, THEME_LABEL, useTheme } from "../theme";

/**
 * Segmented control for the skin, shaped like the language switcher it sits beside. A theme is a
 * browser preference, so this writes straight to localStorage rather than to the settings form.
 */
export default function ThemeSwitcher() {
  const tr = useT();
  const { theme, setTheme } = useTheme();
  return (
    <div className="theme-switch" role="group" aria-label={tr("themeLabel")}>
      {THEMES.map((name) => (
        <button key={name} type="button" className={name === theme ? "on" : ""}
          aria-pressed={name === theme} onClick={() => setTheme(name)}>
          {tr(THEME_LABEL[name])}
        </button>
      ))}
    </div>
  );
}
