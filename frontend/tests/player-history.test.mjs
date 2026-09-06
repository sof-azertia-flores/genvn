import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

class ApiError extends Error { constructor(message, code) { super(message); this.code = code; } }
const roll = { stat: "Presence", d20: 15, modifier: 2, total: 17, dc: 12, success: true };
const base = () => ({
  sessionId: "s", story: { spine: { beats: [] } },
  state: { stateVersion: 1, player: { id: "player", name: "青禾" }, currentBeatId: "b1", storyProgress: { fraction: 0 } },
  scene: { sceneId: "scene1", location: { id: "hall", name: "大厅" },
    characters: [{ characterId: "npc1", name: "看守", assetId: "pt.npc1.base" }],
    blocks: [{ type: "dialogue", speakerId: "npc1", speakerName: "看守", text: "你要去哪？" }],
    choices: [{ id: "c1", text: "请让我看看那封信。", actionKind: "dialogue", playerExpression: "talking", check: null }] },
  finished: false, saveHealthy: true, continuationPending: false, pendingRoll: null,
});
const result = (original, check = false) => ({ ...original,
  chosenText: original.scene.choices[0].text, roll: check ? roll : null,
  state: { ...original.state, stateVersion: 2 },
  scene: { ...original.scene, sceneId: "scene2", blocks: [
    ...(original.scene.choices[0].actionKind === "dialogue" ? [{ type: "dialogue", speakerId: "player", speakerName: "青禾", text: original.scene.choices[0].text }] : []),
    { type: "dialogue", speakerId: "npc1", speakerName: "看守", text: "这是下一幕的回应。" },
  ] },
});
const byClass = (ui, className) => nodes(ui.tree, (n) => n.props?.className === className);
const line = (ui) => nodes(ui.tree, (n) => n.props?.className?.startsWith?.("line "))[0];
const fullLine = (ui) => nodes(line(ui), (n) => n.props?.className === "sr-only")[0]?.props.children;
const visibleLine = (ui) => nodes(line(ui), (n) => n.type === "span" && n.props["aria-hidden"] === "true")[0]?.props.children;
const tapAdvance = (ui) => byClass(ui, "textbox")[0].props.onClick();
const submit = (ui) => byClass(ui, "choice")[0].props.onClick({ currentTarget: { blur() {} } });
const speaker = (ui) => nodes(ui.tree, (n) => n.type === "./components/Sprite")[0]?.props;
const openHistory = (ui) => byClass(ui, "history-trigger")[0].props.onClick();
async function app(overrides = {}) {
  const calls = { choose: 0, roll: 0 };
  const api = {
    config: async () => null,
    roll: async () => { calls.roll++; return { roll }; },
    choose: async () => { calls.choose++; return new Promise(() => {}); },
    ...overrides,
  };
  const ui = harness("App.tsx", { "./api": { api, ApiError }, "./assets": { useAssets: () => null, preload() {}, displayUrl: () => null } });
  ui.render(); await flush();
  const start = nodes(ui.tree, (n) => n.type === "./components/SetupView")[0].props.onStarted;
  return { ui, calls, async start(session = base()) { start(session); await flush(); tapAdvance(ui); await flush(); } };
}

test("player card is permanently first outside the scrolling NPC cast", async () => {
  const { ui, start } = await app(); await start();
  const cards = nodes(ui.tree, (n) => n.type === "./components/CharacterCard");
  assert.deepEqual(cards.map((card) => card.props.characterId), ["player", "npc1"]);
  assert.equal(cards[0].props.player, true);
  assert.equal(nodes(byClass(ui, "cast-list")[0], (n) => n.type === "./components/CharacterCard" && n.props.characterId === "player").length, 0);
  ui.unmount();
});

test("chosen dialogue immediately shows player and types during an outstanding request without revealing the future", async () => {
  let resolve;
  const original = base();
  const { ui, start } = await app({ choose: () => new Promise((done) => { resolve = done; }) });
  await start(original); submit(ui); await flush();
  assert.equal(fullLine(ui), original.scene.choices[0].text);
  assert.equal(speaker(ui).characterId, "player");
  await ui.advance(64); assert.ok(visibleLine(ui).length > 0, "busy generation cannot pause the player's own line");
  tapAdvance(ui); await flush();
  assert.equal(fullLine(ui), original.scene.choices[0].text);
  assert.equal(byClass(ui, "choice").length, 0);
  resolve(result(original)); await flush();
  assert.equal(fullLine(ui), original.scene.choices[0].text, "even a completed cache hit waits for the reader");
  tapAdvance(ui); await flush();
  assert.equal(fullLine(ui), "这是下一幕的回应。", "the duplicate canonical player line is skipped once");
  assert.equal(speaker(ui).characterId, "npc1");
  ui.unmount();
});

test("an immediate cache hit retains an action turn and cannot submit the choice twice", async () => {
  const original = base(); original.scene.choices[0] = { ...original.scene.choices[0], actionKind: "action", text: "检查锁孔。", playerExpression: "action" };
  let requests = 0;
  const { ui, start } = await app({ choose: async () => { requests++; return result(original); } });
  await start(original); const button = byClass(ui, "choice")[0];
  button.props.onClick({ currentTarget: { blur() {} } }); button.props.onClick({ currentTarget: { blur() {} } }); await flush();
  assert.equal(requests, 1); assert.equal(fullLine(ui), "检查锁孔。"); assert.equal(speaker(ui).characterId, "player");
  tapAdvance(ui); await flush(); assert.equal(fullLine(ui), "检查锁孔。");
  tapAdvance(ui); await flush(); assert.equal(fullLine(ui), "这是下一幕的回应。");
  ui.unmount();
});

test("an action is shown after execution, and remains until the die and the reading turn are dismissed", async () => {
  const original = base(); original.scene.choices[0] = { ...original.scene.choices[0], actionKind: "action", text: "撬开柜门。", playerExpression: "action", check: { stat: "Body", dc: 12 } };
  let resolve;
  const { ui, start } = await app({ choose: () => new Promise((done) => { resolve = done; }) });
  await start(original); submit(ui); await flush();
  assert.equal(fullLine(ui), "你要去哪？", "an uncommitted action is not presented as completed");
  resolve(result(original, true)); await flush();
  assert.equal(fullLine(ui), "撬开柜门。"); assert.equal(byClass(ui, "player-dice").length, 1);
  tapAdvance(ui); await flush(); tapAdvance(ui); await flush();
  assert.equal(fullLine(ui), "撬开柜门。", "the die must be acknowledged before continuing");
  nodes(ui.tree, (n) => n.type === "./components/DiceOverlay")[0].props.onDone(); await flush();
  assert.equal(fullLine(ui), "撬开柜门。", "dismissing the die does not erase the action");
  tapAdvance(ui); await flush(); assert.equal(fullLine(ui), "这是下一幕的回应。");
  ui.unmount();
});

for (const code of [null, "choice_resolving"]) test(`failed or conflicting dialogue clears its optimistic presentation (${code})`, async () => {
  let reject;
  const { ui, start } = await app({ choose: () => new Promise((_done, fail) => { reject = fail; }) });
  await start(); submit(ui); await flush(); assert.equal(speaker(ui).characterId, "player");
  reject(code ? new ApiError("处理中", code) : new Error("生成失败")); await flush();
  assert.equal(fullLine(ui), "你要去哪？"); assert.equal(speaker(ui).characterId, "npc1");
  ui.unmount();
});

test("history uses the read boundary and includes a committed player choice without revealing the next blocks", async () => {
  let resolve;
  const original = base();
  const { ui, start } = await app({ choose: () => new Promise((done) => { resolve = done; }) });
  await start(original);
  openHistory(ui); await flush();
  let dialog = nodes(ui.tree, (n) => n.type === "./components/HistoryDialog")[0];
  assert.equal(dialog.props.throughSceneId, "scene1"); assert.equal(dialog.props.throughBlockIndex, 0);
  dialog.props.onClose(); await flush(); submit(ui); await flush();
  resolve(result(original)); await flush();
  openHistory(ui); await flush();
  dialog = nodes(ui.tree, (n) => n.type === "./components/HistoryDialog")[0];
  assert.equal(dialog.props.throughSceneId, "scene2"); assert.equal(dialog.props.throughBlockIndex, -1);
  ui.unmount();
});

test("history dialog prepends earlier canon pages, passes exact bounds, and aborts on close", async () => {
  const requests = [];
  const api = { history: async (...args) => { requests.push(args); return args[1].beforeSceneId ? {
    sessionId: "s", entries: [{ sceneId: "older", choiceText: null, blocks: [{ type: "narration", text: "更早的事。" }] }], nextBeforeSceneId: null,
  } : { sessionId: "s", entries: [{ sceneId: "current", choiceText: "打开门", blocks: [{ type: "dialogue", speakerName: "看守", text: "已读对白。" }] }], nextBeforeSceneId: "current" }; } };
  let closed = 0;
  const ui = harness("components/HistoryDialog.tsx", { "../api": { api } });
  ui.render({ sessionId: "s", throughSceneId: "current", throughBlockIndex: 0, onClose: () => { closed++; } }); await flush();
  assert.equal(requests[0][1].throughBlockIndex, 0);
  byClass(ui, "history-earlier")[0].props.onClick(); await flush();
  assert.equal(requests[1][1].beforeSceneId, "current");
  const paragraphs = nodes(ui.tree, (n) => n.type === "p").map((n) => n.props.children);
  assert.ok(paragraphs.indexOf("更早的事。") < paragraphs.indexOf("已读对白。"));
  ui.emit("keydown", { key: "Escape", preventDefault() {}, stopPropagation() {} }); await flush();
  await ui.advance(160); assert.equal(closed, 1);
  ui.unmount(); assert.equal(requests[1][2].aborted, true);
});

function historyApi(requests) {
  return { history: async (...args) => { requests.push(args); return args[1].beforeSceneId ? {
    sessionId: "s", entries: [{ sceneId: "older", choiceText: null, blocks: [{ type: "narration", text: "更早的事。" }] }], nextBeforeSceneId: null,
  } : { sessionId: "s", entries: [{ sceneId: args[1].throughSceneId, choiceText: "打开门", blocks: [{ type: "dialogue", speakerName: "看守", text: "已读对白。" }] }], nextBeforeSceneId: "current" }; } };
}
/** A stand-in for the scrolling element; the dialog's ref receives it after the first render. */
function attachBody(ui, box) {
  const div = nodes(ui.tree, (n) => n.props?.className === "history-body")[0];
  div.props.ref.current = box;
  return box;
}

test("history opens scrolled to the latest text, before the first paint of the page", async () => {
  const requests = [];
  const ui = harness("components/HistoryDialog.tsx", { "../api": { api: historyApi(requests) } });
  ui.render({ sessionId: "s", throughSceneId: "current", throughBlockIndex: 0, onClose() {} });
  const box = attachBody(ui, { scrollTop: 0, scrollHeight: 2400, clientHeight: 600 });
  await flush();
  assert.equal(box.scrollTop, 2400, "the newest lines are in view as soon as the page renders");
  ui.unmount();
});

test("loading earlier pages keeps the line under the reader's eyes in place", async () => {
  const requests = [];
  const ui = harness("components/HistoryDialog.tsx", { "../api": { api: historyApi(requests) } });
  ui.render({ sessionId: "s", throughSceneId: "current", throughBlockIndex: 0, onClose() {} });
  const box = attachBody(ui, { scrollTop: 0, scrollHeight: 2400, clientHeight: 600 });
  await flush();
  box.scrollTop = 150; // the reader scrolled up a little and asks for more
  byClass(ui, "history-earlier")[0].props.onClick();
  box.scrollHeight = 3400; // the earlier page is taller by 1000px and lands above
  await flush();
  assert.equal(box.scrollTop, 1150, "same text, same place: shifted by exactly what was inserted above");
  ui.unmount();
});

test("a refreshed bound keeps the reader's place, or the end if that is where they were", async () => {
  const requests = [];
  const ui = harness("components/HistoryDialog.tsx", { "../api": { api: historyApi(requests) } });
  ui.render({ sessionId: "s", throughSceneId: "current", throughBlockIndex: 0, onClose() {} });
  const box = attachBody(ui, { scrollTop: 0, scrollHeight: 2400, clientHeight: 600 });
  await flush();
  assert.equal(box.scrollTop, 2400);
  const shownBefore = nodes(ui.tree, (n) => n.type === "p").map((n) => n.props.children);
  // A newly committed scene moves the bound while the dialog is open.
  ui.render({ sessionId: "s", throughSceneId: "next", throughBlockIndex: -1, onClose() {} });
  const shownDuring = nodes(ui.tree, (n) => n.type === "p").map((n) => n.props.children);
  assert.deepEqual(shownDuring, shownBefore, "the old page stays on screen while the new one loads; no blank flash");
  assert.equal(nodes(ui.tree, (n) => n.props?.className === "queue-empty").length, 0);
  box.scrollHeight = 2900;
  await flush();
  assert.equal(box.scrollTop, 2900, "a reader at the end stays at the end");
  assert.equal(requests.length, 2);
  // The same refresh with the reader mid-page leaves their position alone.
  box.scrollTop = 800;
  ui.render({ sessionId: "s", throughSceneId: "later", throughBlockIndex: 0, onClose() {} });
  box.scrollHeight = 3300;
  await flush();
  assert.equal(box.scrollTop, 800);
  ui.unmount();
});

test("failed image can be requeued once per click burst and immediately displays returned queued status", async () => {
  const failed = { assetId: "pt.player.action", subjectName: "青禾", kind: "PORTRAIT_VARIANT", subjectId: "player", variant: "action", priority: 1, status: "FAILED", failureReason: "模型暂时不可用" };
  let complete, calls = 0;
  const api = { tasks: async () => ({ sceneId: "scene1", branches: [] }), retryAsset: async (sessionId, id) => {
    assert.equal(sessionId, "s"); assert.equal(id, failed.assetId); calls++;
    return new Promise((resolve) => { complete = resolve; });
  } };
  const ui = harness("components/TaskQueue.tsx", { "../api": { api } });
  const status = { version: 1, assets: [failed], enabled: true, active: 0, concurrency: 2 };
  ui.render({ sessionId: "s", sceneId: "scene1", assets: status, onClose() {}, onInspect() {} }); await flush();
  nodes(ui.tree, (n) => n.props?.id === "pictures-tab")[0].props.onClick(); await flush();
  const button = byClass(ui, "regenerate-button")[0]; button.props.onClick(); button.props.onClick(); await flush();
  assert.equal(calls, 1); assert.equal(byClass(ui, "regenerate-button")[0].props.disabled, true);
  complete({ ...status, version: 2, assets: [{ ...failed, status: "QUEUED" }] }); await flush();
  assert.equal(byClass(ui, "regenerate-button").length, 0);
  assert.equal(nodes(ui.tree, (n) => n.props?.className === "queue-state queued").length, 1);
  ui.unmount();
});

test("a scene containing only the canonical player line keeps it fully readable and exposes choices without retyping", async () => {
  const original = base(); const next = result(original);
  next.scene.blocks = [next.scene.blocks[0]];
  const { ui, start } = await app({ choose: async () => next });
  await start(original); submit(ui); await flush();
  tapAdvance(ui); await flush(); tapAdvance(ui); await flush();
  assert.equal(fullLine(ui), original.scene.choices[0].text);
  assert.equal(visibleLine(ui), original.scene.choices[0].text);
  assert.equal(byClass(ui, "choice").length, 1);
  assert.equal(ui.timers.size, 0, "an already read line does not schedule a second reveal");
  ui.unmount();
});
