import type { StatName } from "./types";

/**
 * Sample stories a new player can start from.
 *
 * Each one fills the whole setup form -- outline, name, background, traits, appearance and the
 * 15 talent points -- so the shortest path from an empty screen to a playable story is one click.
 * The prose for every field lives in i18n under `ex<Field>.<id>`, in both languages, because a
 * sample is player-facing copy like any other.
 *
 * They deliberately span ancient and modern China and Europe: the compiler writes the world the
 * outline implies, and a player seeing only one setting tends to assume that is all it does.
 */
export interface StoryExample {
  id: string;
  /** Talent spread that suits this character. Always sums to the 15-point budget. */
  stats: Record<StatName, number>;
}

export const EXAMPLES: StoryExample[] = [
  // Modern, unplaced: the original sample, and still the gentlest first story.
  { id: "oldhouse", stats: { Body: 2, Agility: 2, Perception: 4, Intellect: 3, Will: 3, Presence: 1 } },
  // Tang Chang'an: a city with a curfew is a map with a clock on it.
  { id: "changan", stats: { Body: 3, Agility: 2, Perception: 4, Intellect: 3, Will: 3, Presence: 0 } },
  // Ming coastal garrison: paperwork as the murder weapon.
  { id: "mingsea", stats: { Body: 1, Agility: 2, Perception: 3, Intellect: 4, Will: 3, Presence: 2 } },
  // Modern China: a quiet room where a single wrong page is a catastrophe.
  { id: "guji", stats: { Body: 1, Agility: 2, Perception: 4, Intellect: 4, Will: 3, Presence: 1 } },
  // Medieval Europe: snowed in, with three hundred years of someone else's decision.
  { id: "abbey", stats: { Body: 2, Agility: 1, Perception: 4, Intellect: 4, Will: 3, Presence: 1 } },
  // Modern Europe: a timetable that will not delete.
  { id: "prague", stats: { Body: 3, Agility: 2, Perception: 4, Intellect: 2, Will: 3, Presence: 1 } },
];

export const DEFAULT_EXAMPLE = EXAMPLES[0];

/** i18n key for one field of one sample. */
export const exKey = (field: "Title" | "Era" | "Outline" | "Name" | "Background" | "Traits" | "Visual",
                      id: string) => `ex${field}.${id}`;
