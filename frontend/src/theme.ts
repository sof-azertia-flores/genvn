import { createContext, useContext } from "react";

/**
 * Which skin the app wears, remembered per browser.
 *
 * A theme is a look, not a setting the story depends on: nothing about it reaches the model or
 * the save, so it belongs beside the onboarding record in this browser's `localStorage` rather
 * than in `application.yml` next to the language (which the compiler genuinely reads).
 *
 * The default theme is the original stylesheet, untouched. A theme other than the default is a
 * `data-theme` attribute on <html> and a sheet of overrides scoped to it, so a theme can never
 * change how the default one renders.
 */

export const THEME_KEY = "genvn.theme";

export const THEMES = ["default", "parchment"] as const;
export type Theme = (typeof THEMES)[number];

export const DEFAULT_THEME: Theme = "default";

/** Label keys, so the names live in i18n with the rest of the copy. */
export const THEME_LABEL: Record<Theme, string> = {
  default: "themeDefault",
  parchment: "themeParchment",
};

export function isTheme(value: unknown): value is Theme {
  return typeof value === "string" && (THEMES as readonly string[]).includes(value);
}

/** Private mode, disabled storage or a quota error: keep the choice for this page and move on. */
let memory: Theme | null = null;

function store(): Storage | null {
  try {
    return typeof window === "undefined" || !window.localStorage ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function readTheme(): Theme {
  const local = store();
  if (local) {
    try {
      const saved = local.getItem(THEME_KEY);
      if (isTheme(saved)) return saved;
    } catch { /* blocked: fall through to this page's own memory */ }
  }
  return memory ?? DEFAULT_THEME;
}

/**
 * Put the choice on <html>, where the override sheet can see it, and remember it.
 * The default theme carries no attribute at all, so it renders from the base sheets alone.
 */
export function applyTheme(theme: Theme): void {
  memory = theme;
  try {
    const root = typeof document === "undefined" ? null : document.documentElement;
    if (root) {
      if (theme === DEFAULT_THEME) delete root.dataset.theme;
      else root.dataset.theme = theme;
    }
  } catch { /* no document (tests, SSR): the stored value still decides on the next paint */ }
  const local = store();
  if (!local) return;
  try { local.setItem(THEME_KEY, theme); } catch { /* full or blocked: the page copy still serves */ }
}

/** Called before the first render so the page never paints in the wrong skin and then swaps. */
export function initTheme(): Theme {
  const theme = readTheme();
  applyTheme(theme);
  return theme;
}

/**
 * Mirrors the locale context: the switcher and the two placeholder-gradient helpers all need the
 * current theme, and they sit far apart in the tree.
 */
export const ThemeContext = createContext<{ theme: Theme; setTheme: (theme: Theme) => void }>({
  theme: DEFAULT_THEME,
  setTheme: () => {},
});

export function useTheme() {
  return useContext(ThemeContext);
}
