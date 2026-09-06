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

export function backdropFor(locationId: string | null | undefined, hint?: string | null): string {
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
  return [
    `radial-gradient(72% 52% at ${24 + (seed % 40)}% ${18 + (seed % 22)}%, hsl(${hue} ${sat + 12}% 22%) 0%, transparent 62%)`,
    `radial-gradient(84% 66% at ${62 + (seed % 26)}% ${74 - (seed % 20)}%, hsl(${h2} ${sat}% 15%) 0%, transparent 70%)`,
    `linear-gradient(168deg, hsl(${hue} ${sat}% 11%) 0%, hsl(${h2} ${sat + 4}% 6%) 100%)`,
  ].join(", ");
}

export function portraitFor(id: string): string {
  const seed = hash(id);
  const hue = seed % 360;
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
