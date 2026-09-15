import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

const STUBS = {
  "../setup.css": {},
  "../api": { api: { listSessions: async () => [] } },
  "../useCreationJob": { default: () => ({ pending: false, job: null, begin() {} }) },
};

function setup(overrides = {}) {
  const payloads = [];
  const creation = { pending: false, job: null, begin: async (payload) => payloads.push(payload) };
  const ui = harness("components/SetupView.tsx", { ...STUBS, "../useCreationJob": { default: () => creation } });
  ui.render({ config: { imageEnabled: true }, onStarted() {}, onLoad() {}, ...overrides });
  return { ui, payloads };
}

const field = (ui, id) => nodes(ui.tree, (n) => n.props?.id === id)[0];
const chips = (ui) => nodes(ui.tree, (n) => n.props?.className === "sample-chip");
const start = (ui) => nodes(ui.tree, (n) => n.type === "button" && n.props?.onClick?.name === "start")[0].props.onClick();

test("the samples span ancient and modern China and Europe, and each one names its setting", async () => {
  const { ui } = setup();
  await flush();
  const row = chips(ui);
  assert.ok(row.length >= 5, "several samples, not one");
  const eras = row.map((chip) => chip.props.children[1].props.children);
  const titles = row.map((chip) => chip.props.children[0].props.children);
  assert.equal(new Set(titles).size, titles.length, "every sample is named distinctly");
  assert.ok(eras.some((era) => era.includes("唐") || era.includes("明")), "ancient China is represented");
  assert.ok(eras.some((era) => era.includes("中国现代")), "modern China is represented");
  assert.ok(eras.some((era) => era.includes("中世纪")), "older Europe is represented");
  assert.ok(eras.some((era) => era.includes("欧洲现代")), "modern Europe is represented");
  ui.unmount();
});

test("picking a sample fills the outline, the whole character, and the talent spread", async () => {
  const { ui, payloads } = setup();
  await flush();
  const before = field(ui, "outline").props.value;

  // The last chip is deliberately not the one the form opens on.
  chips(ui)[chips(ui).length - 1].props.onClick();
  await flush();

  assert.notEqual(field(ui, "outline").props.value, before, "the outline changed");
  for (const id of ["outline", "pname", "pbg", "ptraits", "pvisual"]) {
    assert.ok(field(ui, id).props.value.trim().length > 0, `${id} was filled in`);
  }
  start(ui); await flush();
  const player = payloads[0].player;
  assert.ok(player.name.length > 0);
  assert.ok(player.background.length > 0);
  assert.ok(player.visualDescription.length > 0);
  assert.ok(player.traits.length >= 2, "traits arrive as a list, already split");
  assert.equal(Object.values(player.stats).reduce((sum, n) => sum + n, 0), 15,
    "a sample never hands the player an illegal talent spread");
  ui.unmount();
});

test("the art direction ships filled in with the default style and is sent as written", async () => {
  const { ui, payloads } = setup();
  await flush();
  const art = field(ui, "art-style").props.value;
  assert.ok(art.length > 500, "the default is a real style brief, not a one-liner");
  assert.ok(art.includes("视觉风格"), "and it is the Chinese one under the Chinese UI");
  start(ui); await flush();
  assert.equal(payloads[0].artStyle, art.trim(), "what is in the box is what the story is drawn in");
  ui.unmount();
});

test("the samples and the default art brief exist in English as well as Chinese", () => {
  const ui = harness("components/SetupView.tsx", STUBS);
  const { t, dictionaries } = ui.load("i18n.tsx");

  // The art brief is the one the player is most likely to read, and the longest to translate.
  assert.ok(t("zh", "artDefault").startsWith("视觉风格"));
  assert.ok(t("en", "artDefault").startsWith("Visual style"));
  assert.notEqual(t("en", "artDefault"), t("zh", "artDefault"), "English gets the English brief");
  assert.ok(t("en", "artDefault").length > 500, "and it is the whole brief, not a stub");

  // Every sample is fully written in both languages: a half-translated one would fill the form
  // with a mix of the two.
  const ids = Object.keys(dictionaries.zh)
    .filter((key) => key.startsWith("exOutline."))
    .map((key) => key.slice("exOutline.".length));
  assert.ok(ids.length >= 5);
  for (const id of ids) {
    for (const part of ["Title", "Era", "Outline", "Name", "Background", "Traits", "Visual"]) {
      const key = `ex${part}.${id}`;
      assert.ok(t("zh", key).trim().length > 0, `${key} is missing in Chinese`);
      assert.ok(dictionaries.en[key]?.trim().length > 0, `${key} is missing in English`);
    }
    assert.notEqual(t("en", `exOutline.${id}`), t("zh", `exOutline.${id}`), `${id} was never translated`);
  }
  ui.unmount();
});

test("every UI string exists in both languages", () => {
  const ui = harness("components/SetupView.tsx", STUBS);
  const { dictionaries } = ui.load("i18n.tsx");
  const { zh, en } = dictionaries;
  const missingEn = Object.keys(zh).filter((key) => !(key in en));
  const strayEn = Object.keys(en).filter((key) => !(key in zh));
  assert.deepEqual(missingEn, [], "these keys have no English text and would silently fall back to Chinese");
  assert.deepEqual(strayEn, [], "these English keys answer to nothing in Chinese");
  ui.unmount();
});
