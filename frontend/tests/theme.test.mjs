import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

const THEME_KEY = "genvn.theme";
const byType = (ui, type) => nodes(ui.tree, (node) => node.type === type);
const byClass = (ui, className) => nodes(ui.tree, (node) => node.props?.className === className);
const tapAdvance = (ui) => byClass(ui, "textbox")[0].props.onClick();

function base() {
  return {
    sessionId: "save1",
    story: { spine: { beats: [], arcTitle: "第一章" } },
    state: { stateVersion: 1, player: { id: "player", name: "玩家" }, currentBeatId: "b1", storyProgress: { fraction: 0 } },
    scene: {
      sceneId: "scene_000", location: { id: "hall", name: "大厅" }, characters: [],
      blocks: [{ type: "narration", text: "门开了。" }],
      choices: [{ id: "c1", text: "走进去", actionKind: "action" }],
    },
    finished: false, saveHealthy: true, continuationPending: false, pendingRoll: null,
  };
}

async function app(localStorage = new Map()) {
  const ui = harness("App.tsx", {
    "./api": { api: { config: async () => ({ imageEnabled: true }) } },
    "./assets": { useAssets: () => null, preload() {}, displayUrl: () => null },
  }, { localStorage });
  ui.render(); await flush();
  const started = nodes(ui.tree, (node) => node.type === "./components/SetupView")[0].props.onStarted;
  return { ui, store: localStorage, async start() { started(base()); await flush(); tapAdvance(ui); await flush(); } };
}

test("the default theme sets no attribute at all, so the base stylesheet alone renders it", () => {
  const ui = harness("theme.ts", {}, { localStorage: new Map() });
  const { initTheme, readTheme } = ui.load("theme.ts");
  assert.equal(readTheme(), "default");
  assert.equal(initTheme(), "default");
  assert.equal(ui.document.documentElement.dataset.theme, undefined,
    "an attribute would let an override sheet reach the original look");
  ui.unmount();
});

test("choosing a theme writes it to this browser and puts it on the document", () => {
  const store = new Map();
  const ui = harness("theme.ts", {}, { localStorage: store });
  const { applyTheme } = ui.load("theme.ts");
  applyTheme("parchment");
  assert.equal(store.get(THEME_KEY), "parchment");
  assert.equal(ui.document.documentElement.dataset.theme, "parchment");
  applyTheme("default");
  assert.equal(store.get(THEME_KEY), "default", "the choice is remembered even when it is the default");
  assert.equal(ui.document.documentElement.dataset.theme, undefined, "but the attribute is removed again");
  ui.unmount();
});

test("a stored theme is restored on the next visit, and an unknown one falls back", () => {
  const saved = harness("theme.ts", {}, { localStorage: new Map([[THEME_KEY, "parchment"]]) });
  assert.equal(saved.load("theme.ts").initTheme(), "parchment");
  assert.equal(saved.document.documentElement.dataset.theme, "parchment");
  saved.unmount();

  const broken = harness("theme.ts", {}, { localStorage: new Map([[THEME_KEY, "papyrus"]]) });
  assert.equal(broken.load("theme.ts").readTheme(), "default", "a theme that no longer exists is not applied");
  broken.unmount();
});

test("the app opens in the stored theme, with no flash of the other skin", async () => {
  const { ui } = await app(new Map([[THEME_KEY, "parchment"]]));
  assert.equal(ui.document.documentElement.dataset.theme, "parchment");
  ui.unmount();
});

test("the switcher offers every theme and marks the current one", async () => {
  const ui = harness("components/ThemeSwitcher.tsx", {}, { localStorage: new Map() });
  ui.render({});
  await flush();
  const buttons = nodes(ui.tree, (node) => node.type === "button");
  const { THEMES } = ui.load("theme.ts");
  assert.equal(buttons.length, THEMES.length, "one button per theme");
  const pressed = buttons.filter((button) => button.props["aria-pressed"]);
  assert.equal(pressed.length, 1, "exactly one is current");
  assert.equal(pressed[0].props.children, "夜色", "and it is the default until something is stored");
  assert.deepEqual(buttons.map((button) => button.props.children), ["夜色", "羊皮纸"]);
  ui.unmount();
});

test("the placeholder backdrop and portrait follow the theme, because no stylesheet can reach them", () => {
  const ui = harness("visual.ts", {});
  const { backdropFor, portraitFor } = ui.load("visual.ts");

  const dark = backdropFor("loc_hall", "室内");
  const paper = backdropFor("loc_hall", "室内", "parchment");
  assert.notEqual(dark, paper);
  assert.ok(/% 1[0-9]%\)/.test(dark) || /% [0-2][0-9]%\)/.test(dark), "the default placeholder is dark");
  assert.ok(/% 7[0-9]%\)|% 8[0-9]%\)/.test(paper), "the parchment placeholder is light paper");

  // Same location still looks like itself in either theme.
  assert.equal(backdropFor("loc_hall", "室内", "parchment"), paper, "and it stays stable per location");
  assert.notEqual(backdropFor("loc_attic", "室内", "parchment"), paper);
  assert.notEqual(portraitFor("npc_a", "parchment"), portraitFor("npc_a"));
  ui.unmount();
});

test("both themes are offered, each with a name in both languages", () => {
  const ui = harness("theme.ts", {});
  const { THEMES, THEME_LABEL, DEFAULT_THEME } = ui.load("theme.ts");
  const { t } = ui.load("i18n.tsx");
  assert.deepEqual([...THEMES], ["default", "parchment"]);
  assert.equal(DEFAULT_THEME, "default", "the original look stays the one a fresh browser gets");
  for (const name of THEMES) {
    for (const lang of ["zh", "en"]) {
      const label = t(lang, THEME_LABEL[name]);
      assert.notEqual(label, THEME_LABEL[name], `${name} has no ${lang} name`);
      assert.ok(label.trim().length > 0);
    }
  }
  ui.unmount();
});
