import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

const byClass = (ui, className) => nodes(ui.tree, (node) => node.props?.className === className);
const line = (ui) => nodes(ui.tree, (node) => node.props?.className?.startsWith?.("line "))[0];
const fullLine = (ui) => nodes(line(ui), (node) => node.props?.className === "sr-only")[0]?.props.children;
const shownLine = (ui) => nodes(line(ui), (node) => node.type === "span" && node.props["aria-hidden"] === "true")[0]?.props.children;
const tap = (ui) => byClass(ui, "textbox")[0].props.onClick();
const ending = () => ({
  sessionId: "s", story: { spine: { beats: [] } },
  state: { stateVersion: 4, player: { id: "player", name: "玩家" }, currentBeatId: null, storyProgress: { fraction: 1 } },
  scene: { sceneId: "scene-final", location: { id: "hall", name: "大厅" }, characters: [],
    blocks: [
      { type: "narration", text: "结尾的第一段。" },
      { type: "narration", text: "玩家正在读这段尚未说完的结尾".repeat(12) },
      { type: "narration", text: "结尾的最后一段。" },
    ], choices: [] },
  finished: true, saveHealthy: true, continuationPending: true, pendingRoll: null,
});
function continued(original) {
  return { ...original, finished: false, continuationPending: false,
    state: { ...original.state, stateVersion: 5 }, scene: { ...original.scene,
      choices: [{ id: "continue_arc", text: "继续下一章", actionKind: "action" }] } };
}
async function app(api = {}, assets = null) {
  const ui = harness("App.tsx", { "./api": { api: { config: async () => null, ...api } },
    "./assets": { useAssets: () => assets, preload() {}, displayUrl: (status, id) => status?.assets.find((asset) => asset.assetId === id)?.url ?? null } });
  ui.render(); await flush();
  const start = nodes(ui.tree, (node) => node.type === "./components/SetupView")[0].props.onStarted;
  return { ui, async start(session) { start(session); await flush(); } };
}

for (const historyOpen of [false, true]) test(`late continuation preserves the current text reveal and history dialog (${historyOpen})`, async () => {
  const original = ending(); let beforeRefresh;
  const { ui, start } = await app({ getSession: async () => { beforeRefresh = shownLine(ui); return continued(original); } });
  await start(original);
  tap(ui); await flush(); tap(ui); await flush();
  await ui.advance(64);
  if (historyOpen) { byClass(ui, "history-trigger")[0].props.onClick(); await flush(); }
  await ui.advance(1936);
  assert.equal(fullLine(ui), original.scene.blocks[1].text);
  assert.equal(shownLine(ui), beforeRefresh, "metadata must not restart or finish the current reveal");
  assert.equal(byClass(ui, "choice").length, 0, "a new continuation choice cannot skip unread ending text");
  const dialogs = nodes(ui.tree, (node) => node.type === "./components/HistoryDialog");
  assert.equal(dialogs.length, Number(historyOpen));
  if (historyOpen) {
    assert.equal(dialogs[0].props.throughBlockIndex, 0);
    dialogs[0].props.onClose(); await flush();
  }
  await ui.advance(32); assert.ok(shownLine(ui).length > beforeRefresh.length);
  tap(ui); await flush(); tap(ui); await flush(); tap(ui); await flush();
  assert.equal(byClass(ui, "choice").length, 1);
  ui.unmount();
});

for (const changedId of [false, true]) test(`a changed scene resets the read cursor and reveal even if its first line is unchanged (${changedId})`, async () => {
  const original = ending(); const latest = continued(original);
  latest.scene = { ...latest.scene, sceneId: changedId ? "scene-new" : original.scene.sceneId,
    blocks: [original.scene.blocks[0], { type: "narration", text: "新版本的第二段。" }] };
  const { ui, start } = await app({ getSession: async () => latest });
  await start(original);
  tap(ui); await flush();
  if (changedId) { tap(ui); await flush(); }
  await ui.advance(2000);
  assert.equal(fullLine(ui), original.scene.blocks[0].text);
  assert.equal(shownLine(ui), "", "the new reading revision must not reuse the old first-line reveal");
  assert.equal(byClass(ui, "choice").length, 0);
  ui.unmount();
});

test("NPC dialogue selects a ready matching pose without replaying the line; narration keeps the scene portrait", async () => {
  const original = { ...ending(), finished: false, continuationPending: false };
  original.scene = { ...original.scene, meta: { outcomeContext: "FAILURE" },
    characters: [{ characterId: "guard", name: "看守", expression: "worried", assetId: "worried" }], blocks: [
      { type: "dialogue", speakerId: "guard", speakerName: "看守", expression: "worried", text: "你还好吗？" },
      { type: "dialogue", speakerId: "guard", speakerName: "看守", expression: "angry", text: "立刻离开这里！" },
      { type: "narration", text: "门重新关上了。" },
    ] };
  const assets = { assets: [
    { assetId: "worried", kind: "PORTRAIT_VARIANT", subjectId: "guard", variant: "worried", status: "READY", applicability: "GENERAL", url: "/worried.png" },
    { assetId: "angry", kind: "PORTRAIT_VARIANT", subjectId: "guard", variant: "angry", status: "GENERATING", applicability: "OUTCOME:FAILURE", url: null },
  ] };
  const { ui, start } = await app({}, assets);
  const sprite = () => nodes(ui.tree, (node) => node.type === "./components/Sprite")[0].props.url;
  await start(original);
  tap(ui); await flush(); tap(ui); await flush(); await ui.advance(32);
  assert.equal(sprite(), "/worried.png");
  const before = shownLine(ui);
  assets.assets[1].status = "READY"; assets.assets[1].url = "/angry.png";
  ui.render(); await flush();
  assert.equal(sprite(), "/angry.png");
  assert.equal(shownLine(ui), before);
  tap(ui); await flush(); tap(ui); await flush();
  assert.equal(sprite(), "/worried.png");
  ui.unmount();
});

test("normal character and game surfaces omit private beat titles and purposes", async () => {
  const original = ending();
  const title = "凶手终于露出真面目";
  const purpose = "揭示看守是凶手，并在壁炉下找到凶器。";
  original.story = { spine: { beats: [{ id: "beat_reveal", title, purpose }] }, authorCanon: { facts: ["我到旧宅寻找日记。"] } };
  original.state = { ...original.state, currentBeatId: "beat_reveal", characters: {}, inventory: [], continuityLedger: [],
    player: { ...original.state.player, hp: 10, maxHp: 10, stats: {}, traits: [], conditions: [] } };
  const sheet = harness("components/SidePanel.tsx");
  sheet.render({ state: original.state, story: original.story, onClose() {} });
  const sheetText = JSON.stringify(sheet.tree);
  assert.ok(!sheetText.includes(title)); assert.ok(!sheetText.includes(purpose));
  assert.ok(sheetText.includes("我到旧宅寻找日记。"), "the player's own canon remains accessible");
  const { ui, start } = await app(); await start(original);
  const rendered = JSON.stringify(ui.tree);
  assert.ok(!rendered.includes(title)); assert.ok(!rendered.includes(purpose));
  assert.ok(rendered.includes("大厅"));
  ui.unmount(); sheet.unmount();
});
