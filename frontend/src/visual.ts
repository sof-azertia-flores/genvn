import type { Theme } from "./theme";

/**
 * Placeholder art. The data model already carries backgroundPrompt / visualDescription for a
 * real image provider; until one is wired in, we derive a stable, atmospheric gradient from the
 * location id so every place looks like itself every time you return to it.
 */
function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h);
}

export function backdropFor(locationId: string | null | undefined, hint?: string | null,
                            theme: Theme = "default"): string {
  const seed = hash(`${locationId ?? "nowhere"}`);
  const text = `${locationId ?? ""} ${hint ?? ""}`.toLowerCase();

  // Nudge the palette when the description tells us something obvious about the light.
  let hue = seed % 360;
  if (/night|dark|midnight|黑|夜|午夜|全黑/.test(text)) hue = 220 + (seed % 40);
  else if (/dusk|evening|黄昏|傍晚|冷色/.test(text)) hue = 250 + (seed % 40);
  else if (/dust|interior|室内|灰尘|暖褐/.test(text)) hue = 28 + (seed % 22);
  else if (/torch|light|手电|木屑/.test(text)) hue = 36 + (seed % 18);

  const h2 = (hue + 28 + (seed % 24)) % 360;
  const sat = 16 + (seed % 14);
  // On parchment the stage is paper, so the placeholder has to be a tint rather than a shadow:
  // same per-location variety, pulled towards warm and light instead of cold and dark. These are
  // inline styles, which no stylesheet could override.
  if (theme === "parchment") {
    const warm = 26 + (hue % 30);
    const warm2 = 30 + (h2 % 26);
    const s1 = 20 + (seed % 12);
    return [
      `radial-gradient(72% 52% at ${24 + (seed % 40)}% ${18 + (seed % 22)}%, hsl(${warm} ${s1}% 82%) 0%, transparent 62%)`,
      `radial-gradient(84% 66% at ${62 + (seed % 26)}% ${74 - (seed % 20)}%, hsl(${warm2} ${s1 - 4}% 76%) 0%, transparent 70%)`,
      `linear-gradient(168deg, hsl(${warm} ${s1 - 2}% 87%) 0%, hsl(${warm2} ${s1}% 78%) 100%)`,
    ].join(", ");
  }
  return [
    `radial-gradient(72% 52% at ${24 + (seed % 40)}% ${18 + (seed % 22)}%, hsl(${hue} ${sat + 12}% 22%) 0%, transparent 62%)`,
    `radial-gradient(84% 66% at ${62 + (seed % 26)}% ${74 - (seed % 20)}%, hsl(${h2} ${sat}% 15%) 0%, transparent 70%)`,
    `linear-gradient(168deg, hsl(${hue} ${sat}% 11%) 0%, hsl(${h2} ${sat + 4}% 6%) 100%)`,
  ].join(", ");
}

export function portraitFor(id: string, theme: Theme = "default"): string {
  const seed = hash(id);
  const hue = seed % 360;
  if (theme === "parchment") {
    const warm = 24 + (hue % 34);
    return `linear-gradient(165deg, hsl(${warm} 24% 82%) 0%, hsl(${(warm + 22) % 360} 22% 68%) 100%)`;
  }
  return `linear-gradient(165deg, hsl(${hue} 22% 30%) 0%, hsl(${(hue + 40) % 360} 20% 14%) 100%)`;
}

export function initials(name: string): string {
  const trimmed = (name ?? "").trim();
  if (!trimmed) return "?";
  // CJK names read best as their last character; latin names as first letters.
  if (/[一-鿿]/.test(trimmed)) return trimmed.slice(-2);
  return trimmed
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? "")
    .join("");
}

export const EXPRESSION_LABEL: Record<string, string> = {
  neutral: "平静",
  talking: "说话",
  action: "行动",
  base: "平静",
  worried: "担忧",
  afraid: "恐惧",
  angry: "愤怒",
  sad: "低落",
  happy: "愉快",
  suspicious: "戒备",
  surprised: "惊讶",
};

export const APPROACH_LABEL: Record<string, string> = {
  investigation: "调查",
  social: "交涉",
  cautious: "谨慎",
  risky: "冒险",
  resource: "利用道具",
  action: "行动",
};
