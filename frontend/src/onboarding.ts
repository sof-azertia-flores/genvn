/**
 * Whether this browser has been shown the guide.
 *
 * Deliberately per-browser rather than per-save: the guide teaches the app, not a story, so a
 * returning player should never see it again while someone opening the same deployment on a new
 * machine should. There is nothing here worth sending to the server, and nothing that would be
 * right to send -- a second browser genuinely is a first visit.
 *
 * Stored as a version, so a future rewrite of the guide can show itself again to people who saw
 * the old one, and an unreadable value simply counts as "not seen".
 */

export const WELCOME_KEY = "genvn.onboarding.welcome";
export const TOUR_KEY = "genvn.onboarding.tour";

/** Raise when the guide changes enough that returning players should see it again. */
export const GUIDE_VERSION = 1;

/** Private mode, disabled storage, or a quota error: remember for this page and move on. */
const memory = new Map<string, string>();

function store(): Storage | null {
  try {
    return typeof window === "undefined" || !window.localStorage ? null : window.localStorage;
  } catch {
    return null;
  }
}

function read(key: string): string | null {
  const local = store();
  if (local) {
    try {
      const value = local.getItem(key);
      if (value !== null) return value;
    } catch { /* blocked: fall through to this page's own memory */ }
  }
  return memory.get(key) ?? null;
}

function write(key: string, value: string): void {
  memory.set(key, value);
  const local = store();
  if (!local) return;
  try { local.setItem(key, value); } catch { /* full or blocked: the page copy still serves */ }
}

function clear(key: string): void {
  memory.delete(key);
  const local = store();
  if (!local) return;
  try { local.removeItem(key); } catch { /* nothing to do */ }
}

/** True when this browser has finished (or skipped) this version of that part of the guide. */
export function seen(key: string): boolean {
  const value = read(key);
  if (value === null) return false;
  const version = Number.parseInt(value, 10);
  return Number.isFinite(version) && version >= GUIDE_VERSION;
}

export function markSeen(key: string): void {
  write(key, String(GUIDE_VERSION));
}

/** Show the whole guide again from the start, as the settings dialog offers. */
export function resetGuide(): void {
  clear(WELCOME_KEY);
  clear(TOUR_KEY);
}
