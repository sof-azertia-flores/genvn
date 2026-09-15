import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

const WELCOME_KEY = "genvn.onboarding.welcome";
const TOUR_KEY = "genvn.onboarding.tour";
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

test("a browser with no record is shown the welcome, and finishing it is remembered", async () => {
  const { ui, store } = await app();
  const welcome = byType(ui, "./components/WelcomeDialog")[0];
  assert.ok(welcome, "a first visit gets the welcome");
  assert.equal(welcome.props.imageEnabled, true, "the art page is offered when pictures are on");
  assert.equal(store.get(WELCOME_KEY), undefined, "nothing is written until it is actually seen");

  welcome.props.onDone(); await flush();
  assert.equal(byType(ui, "./components/WelcomeDialog").length, 0, "it closes");
  assert.equal(store.get(WELCOME_KEY), "1", "and this browser now has a record of it");
  ui.unmount();
});

test("a browser that has already seen the guide is never shown it again", async () => {
  const { ui } = await app(new Map([[WELCOME_KEY, "1"], [TOUR_KEY, "1"]]));
  assert.equal(byType(ui, "./components/WelcomeDialog").length, 0);
  ui.unmount();
});

test("an older record counts as unseen, so a rewritten guide shows itself again", async () => {
  const { ui } = await app(new Map([[WELCOME_KEY, "0"]]));
  assert.ok(byType(ui, "./components/WelcomeDialog")[0], "version 0 predates this guide");
  ui.unmount();
});

test("a damaged record is treated as never seen rather than as seen", async () => {
  const { ui } = await app(new Map([[WELCOME_KEY, "yes"]]));
  assert.ok(byType(ui, "./components/WelcomeDialog")[0]);
  ui.unmount();
});

test("the in-game tour runs on the first story and is remembered separately", async () => {
  const { ui, store, start } = await app(new Map([[WELCOME_KEY, "1"]]));
  await start();
  const tour = byType(ui, "./components/StageTour")[0];
  assert.ok(tour, "entering a story for the first time starts the tour");
  assert.equal(store.get(TOUR_KEY), undefined);

  tour.props.onDone(); await flush();
  assert.equal(byType(ui, "./components/StageTour").length, 0);
  assert.equal(store.get(TOUR_KEY), "1");
  assert.equal(store.get(WELCOME_KEY), "1", "the two halves are recorded independently");
  ui.unmount();
});

test("the tour stays out of the way of a dice reveal and of every dialog", async () => {
  const { ui, start } = await app(new Map([[WELCOME_KEY, "1"]]));
  await start();
  assert.ok(byType(ui, "./components/StageTour")[0]);

  byClass(ui, "history-trigger")[0].props.onClick(); await flush();
  assert.equal(byType(ui, "./components/StageTour").length, 0, "a tour bubble must not sit over an open dialog");

  byType(ui, "./components/HistoryDialog")[0].props.onClose(); await flush();
  assert.ok(byType(ui, "./components/StageTour")[0], "and it comes back when the dialog closes");
  ui.unmount();
});

test("settings can put the guide back, and the record is cleared", async () => {
  const store = new Map([[WELCOME_KEY, "1"], [TOUR_KEY, "1"]]);
  const { ui } = await app(store);
  assert.equal(byType(ui, "./components/WelcomeDialog").length, 0);

  nodes(ui.tree, (n) => typeof n.props?.className === "string" && n.props.className.includes("setup-settings"));
  const setup = byType(ui, "./components/SetupView")[0];
  setup.props.onOpenSettings(); await flush();
  byType(ui, "./components/SettingsDialog")[0].props.onGuideReset(); await flush();
  byType(ui, "./components/SettingsDialog")[0].props.onClose(); await flush();

  assert.ok(byType(ui, "./components/WelcomeDialog")[0], "the welcome runs again");
  ui.unmount();
});

test("the welcome drops its art page when the deployment has pictures off", async () => {
  const ui = harness("components/WelcomeDialog.tsx", {});
  ui.render({ imageEnabled: false, onDone() {} });
  await flush();
  const next = () => nodes(ui.tree, (n) => n.props?.className === "btn")[0];
  let pages = 1;
  while (next().props.children !== undefined && pages < 12) {
    const label = next().props.children;
    if (typeof label === "string" && label !== "下一步") break;
    next().props.onClick(); await flush();
    pages++;
  }
  assert.equal(pages, 4, "four pages without art direction, five with it");
  ui.unmount();
});

test("skipping the welcome counts as having been offered it", async () => {
  let done = 0;
  const ui = harness("components/WelcomeDialog.tsx", {});
  ui.render({ imageEnabled: true, onDone: () => { done++; } });
  await flush();
  nodes(ui.tree, (n) => n.props?.className === "dialog-close")[0].props.onClick();
  await flush();
  await ui.advance(200);
  await flush();
  assert.equal(done, 1, "a player who does not want the guide is not asked twice");
  ui.unmount();
});

test("the tour swallows the keys the stage uses, so stepping through never advances the story", async () => {
  const ui = harness("components/StageTour.tsx", {});
  ui.render({ onDone() {} });
  await flush();
  const prevented = [];
  ui.emit("keydown", { key: " ", preventDefault: () => prevented.push("space"), stopPropagation() {} });
  await flush();
  assert.deepEqual(prevented, ["space"], "space moves the tour, not the scene");
  ui.unmount();
});
