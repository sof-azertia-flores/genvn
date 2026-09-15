import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

class ApiError extends Error {
  constructor(message, code) { super(message); this.code = code; }
}

const byClass = (ui, className) => nodes(ui.tree, (node) => node.props?.className === className);
const byClassPrefix = (ui, prefix) =>
  nodes(ui.tree, (node) => typeof node.props?.className === "string" && node.props.className.startsWith(prefix));
const dialog = (ui) => nodes(ui.tree, (node) => node.type === "./components/RestructureDialog")[0];
const tapAdvance = (ui) => byClass(ui, "textbox")[0].props.onClick();

function base(overrides = {}) {
  return {
    sessionId: "save1",
    story: { spine: { beats: [], arcTitle: "第一章" } },
    state: { stateVersion: 4, player: { id: "player", name: "玩家" }, currentBeatId: "b1", storyProgress: { fraction: 0.2 } },
    scene: {
      sceneId: "scene_003",
      location: { id: "hall", name: "大厅" },
      characters: [],
      blocks: [{ type: "narration", text: "邻居又一次挡在门口。" }],
      choices: [{ id: "c1", text: "退开", actionKind: "action" }],
    },
    finished: false, saveHealthy: true, continuationPending: false, pendingRoll: null,
    ...overrides,
  };
}

const job = (status, extra = {}) => ({
  id: "job-1", status, stage: "重塑中", progress: status === "READY" ? 100 : 40,
  logs: [{ id: 1, time: "2026-09-15T00:00:00Z", message: "正在改写故事框架。" }],
  sessionId: "save1", error: null, ...extra,
});

async function app(overrides = {}) {
  const api = { config: async () => null, ...overrides };
  const ui = harness("App.tsx", {
    "./api": { api, ApiError },
    "./assets": { useAssets: () => null, preload() {}, displayUrl: () => null },
  });
  ui.render(); await flush();
  const started = nodes(ui.tree, (node) => node.type === "./components/SetupView")[0].props.onStarted;
  return {
    ui,
    async start(session = base()) { started(session); await flush(); tapAdvance(ui); await flush(); },
  };
}

test("the stage offers a rewrite button that opens the dialog on the current scene", async () => {
  const { ui, start } = await app();
  await start();
  assert.equal(dialog(ui), undefined, "the dialog stays closed until asked for");

  const trigger = byClassPrefix(ui, "icon-btn restructure-trigger")[0];
  assert.ok(trigger, "the stage controls carry a rewrite trigger");
  trigger.props.onClick(); await flush();

  const open = dialog(ui);
  assert.ok(open, "pressing it opens the rewrite dialog");
  assert.equal(open.props.canRestructure, true);
  assert.equal(open.props.anchorLabel, null, "the current scene needs no label");
  assert.equal(open.props.running, false);
  ui.unmount();
});

test("submitting sends the typed words once, with the scene and version the page is showing", async () => {
  const calls = [];
  const { ui, start } = await app({
    restructure: async (id, nodeId, instruction, expectedSceneId, expectedStateVersion, requestId) => {
      calls.push({ id, nodeId, instruction, expectedSceneId, expectedStateVersion, requestId });
      return job("QUEUED");
    },
    restructureJob: async () => job("RUNNING"),
  });
  await start();
  byClassPrefix(ui, "icon-btn restructure-trigger")[0].props.onClick(); await flush();
  dialog(ui).props.onSubmit("让我的家族成为真正的对手"); await flush();

  assert.equal(calls.length, 1, "one submit is one rewrite");
  assert.deepEqual(
    { id: calls[0].id, nodeId: calls[0].nodeId, instruction: calls[0].instruction,
      expectedSceneId: calls[0].expectedSceneId, expectedStateVersion: calls[0].expectedStateVersion },
    { id: "save1", nodeId: "scene_003", instruction: "让我的家族成为真正的对手",
      expectedSceneId: "scene_003", expectedStateVersion: 4 });
  assert.ok(calls[0].requestId, "an idempotency key is sent so a lost response cannot rewrite twice");
  assert.equal(dialog(ui).props.running, true, "the dialog switches to its progress panel");
  ui.unmount();
});

test("when the job reports READY the rewritten save replaces what is on screen", async () => {
  const rewritten = base({
    state: { stateVersion: 7, player: { id: "player", name: "玩家" }, currentBeatId: "rw1_turn", storyProgress: { fraction: 0.2 } },
    scene: {
      sceneId: "scene_004", location: { id: "hall", name: "大厅" }, characters: [],
      blocks: [{ type: "narration", text: "这一次，挡路的是你的堂兄。" }],
      choices: [{ id: "c9", text: "叫出他的名字", actionKind: "action" }],
    },
  });
  let polls = 0;
  const { ui, start } = await app({
    restructure: async () => job("QUEUED"),
    restructureJob: async () => (++polls >= 2 ? job("READY") : job("RUNNING")),
    getSession: async () => rewritten,
  });
  await start();
  byClassPrefix(ui, "icon-btn restructure-trigger")[0].props.onClick(); await flush();
  dialog(ui).props.onSubmit("换一个对手"); await flush();

  // Two poll intervals, each 1200ms in the hook.
  await ui.advance(1200); await flush();
  await ui.advance(1200); await flush();

  assert.equal(dialog(ui), undefined, "the dialog closes itself once the rewrite lands");
  tapAdvance(ui); await flush();
  const shown = nodes(ui.tree, (node) => node.props?.className?.startsWith?.("line "))[0];
  const full = nodes(shown, (node) => node.props?.className === "sr-only")[0]?.props.children;
  assert.equal(full, "这一次，挡路的是你的堂兄。", "the rewritten scene is what the player now reads");
  const choices = byClass(ui, "choice");
  assert.equal(choices.length, 1, "the rewritten scene brings its own choices");
  const label = nodes(choices[0], (node) => node.props?.className === "ctext")[0];
  assert.equal(label.props.children[1], "叫出他的名字", "which are the new ones, not the refused scene's");
  ui.unmount();
});

test("a failed job says so in the dialog and leaves the scene alone", async () => {
  const { ui, start } = await app({
    restructure: async () => job("QUEUED"),
    restructureJob: async () => job("FAILED", { error: "重塑未能完成，剧情没有改动。" }),
  });
  await start();
  byClassPrefix(ui, "icon-btn restructure-trigger")[0].props.onClick(); await flush();
  dialog(ui).props.onSubmit("换一个对手"); await flush();
  await ui.advance(1200); await flush();

  const open = dialog(ui);
  assert.ok(open, "the dialog stays open so the player can read what happened");
  assert.equal(open.props.job.status, "FAILED");
  assert.equal(open.props.error, "重塑未能完成，剧情没有改动。");
  assert.equal(open.props.running, false, "a failed job is not still running");
  ui.unmount();
});

test("a rejected submit is reported and never retried as a second rewrite", async () => {
  let attempts = 0;
  const { ui, start } = await app({
    restructure: async () => { attempts++; throw new ApiError("场景已更新。", "scene_conflict"); },
    restructureJob: async () => { throw new Error("the job was never created"); },
  });
  await start();
  byClassPrefix(ui, "icon-btn restructure-trigger")[0].props.onClick(); await flush();
  dialog(ui).props.onSubmit("换一个对手"); await flush();
  await ui.advance(5000); await flush();

  assert.equal(attempts, 1, "a settled refusal is never resubmitted");
  assert.equal(dialog(ui).props.job.status, "FAILED");
  assert.equal(dialog(ui).props.error, "场景已更新。");
  ui.unmount();
});

test("the rewrite button refuses while a choice is still resolving", async () => {
  const { ui, start } = await app({ getSession: async () => base() });
  await start(base({ resolvingChoiceId: "c1" }));
  byClassPrefix(ui, "icon-btn restructure-trigger")[0].props.onClick(); await flush();

  assert.equal(dialog(ui), undefined, "no dialog opens over a choice that is mid-flight");
  const toast = byClass(ui, "toast")[0];
  assert.ok(toast, "the player is told why");
  ui.unmount();
});

test("the recap offers a rewrite on every restorable scene and passes a readable label", async () => {
  const api = { history: async () => ({
    sessionId: "s",
    entries: [
      { sceneId: "scene_000", choiceText: null, restorable: true, blocks: [{ type: "narration", text: "开场。" }] },
      { sceneId: "scene_001", choiceText: "上楼", restorable: false, blocks: [{ type: "narration", text: "楼梯间。" }] },
    ],
    nextBeforeSceneId: null,
  }) };
  const asked = [];
  const ui = harness("components/HistoryDialog.tsx", { "../api": { api } });
  ui.render({ sessionId: "s", throughSceneId: "scene_001", throughBlockIndex: 0, onClose() {},
    onRewind() {}, onRestructure: (id, label) => asked.push([id, label]) });
  await flush();

  const buttons = byClass(ui, "history-restructure");
  assert.equal(buttons.length, 1, "only a scene that was actually played can be rewritten");
  buttons[0].props.onClick({ stopPropagation() {} });
  assert.deepEqual(asked, [["scene_000", "开场。"]]);
  ui.unmount();
});

test("the recap's rewrite button is disabled for the same reasons rewind is", async () => {
  const api = { history: async () => ({
    sessionId: "s",
    entries: [{ sceneId: "scene_000", choiceText: null, restorable: true, blocks: [{ type: "narration", text: "开场。" }] }],
    nextBeforeSceneId: null,
  }) };
  const asked = [];
  const ui = harness("components/HistoryDialog.tsx", { "../api": { api } });
  ui.render({ sessionId: "s", throughSceneId: "scene_001", throughBlockIndex: 0, rewindBlocked: true,
    onClose() {}, onRewind() {}, onRestructure: (id) => asked.push(id) });
  await flush();

  const button = byClass(ui, "history-restructure")[0];
  assert.equal(button.props.disabled, true);
  button.props.onClick({ stopPropagation() {} });
  assert.deepEqual(asked, [], "a disabled button does nothing when clicked anyway");
  ui.unmount();
});

test("the dialog will not submit empty or near-empty words, and shows a live character count", async () => {
  const sent = [];
  const ui = harness("components/RestructureDialog.tsx", { "../api": { api: {} } });
  ui.render({ anchorLabel: null, canRestructure: true, job: null, running: false, error: null,
    onSubmit: (text) => sent.push(text), onRetry() {}, onClose() {} });
  await flush();

  const submit = () => byClass(ui, "btn")[0];
  assert.equal(submit().props.disabled, true, "an empty field cannot be submitted");

  const field = nodes(ui.tree, (node) => node.type === "textarea")[0];
  field.props.onChange({ target: { value: "太短" } }); await flush();
  assert.equal(submit().props.disabled, true, "two characters are not a direction");

  nodes(ui.tree, (node) => node.type === "textarea")[0]
    .props.onChange({ target: { value: "让我的家族成为真正的对手" } });
  await flush();
  assert.equal(submit().props.disabled, false);
  submit().props.onClick();
  assert.deepEqual(sent, ["让我的家族成为真正的对手"]);
  ui.unmount();
});

test("while the rewrite runs the dialog shows the log and cannot be closed by mistake", async () => {
  let closed = 0;
  const ui = harness("components/RestructureDialog.tsx", { "../api": { api: {} } });
  ui.render({ anchorLabel: "上楼", canRestructure: true, job: job("RUNNING"), running: true, error: null,
    onSubmit() {}, onRetry() {}, onClose: () => { closed++; } });
  await flush();

  assert.equal(nodes(ui.tree, (node) => node.type === "textarea").length, 0, "the field gives way to progress");
  assert.equal(byClass(ui, "compile-log").length, 1, "the job's own log is on screen");
  byClass(ui, "dialog-close")[0].props.onClick();
  await flush();
  assert.equal(closed, 0, "closing mid-rewrite would only hide work that keeps running");
  ui.unmount();
});

test("a paused progress reader offers GET-only recovery while the rewrite may still be running", async () => {
  let posts = 0;
  let unavailable = true;
  const reads = [];
  const { ui, start } = await app({
    restructure: async () => { posts++; return job("RUNNING"); },
    restructureJob: async (id) => {
      reads.push(id);
      if (unavailable) throw new Error("temporary connection failure");
      return job("READY");
    },
    getSession: async () => base(),
  });
  await start();
  byClassPrefix(ui, "icon-btn restructure-trigger")[0].props.onClick(); await flush();
  dialog(ui).props.onSubmit("换一个对手"); await flush();
  await ui.advance(8100);

  const stopped = dialog(ui).props;
  assert.equal(reads.length, 3, "automatic polling stops after three transient failures");
  assert.equal(stopped.job.status, "RUNNING", "connection loss never invents a server failure");
  assert.equal(stopped.running, true, "a server operation with an unknown outcome still blocks dismissal");
  assert.equal(stopped.canRetry, true, "a stopped reader has a recovery path independent of server status");
  assert.match(stopped.error, /重试读取进度/);

  const panel = harness("components/RestructureDialog.tsx");
  panel.render(stopped); await flush();
  assert.equal(byClass(panel, "dialog-close")[0].props.disabled, true);
  const retry = nodes(panel.tree, (node) => node.type === "button" && node.props?.children === "重试读取进度")[0];
  assert.ok(retry, "the actual progress dialog exposes the action to the player");
  unavailable = false;
  retry.props.onClick();
  retry.props.onClick();
  await flush();

  assert.equal(posts, 1, "recovering a reader never resubmits the rewrite");
  assert.deepEqual(reads, ["job-1", "job-1", "job-1", "job-1"], "rapid retries read the same job only once");
  assert.equal(dialog(ui), undefined, "a recovered READY result is adopted and closes progress");
  panel.unmount();
  ui.unmount();
});

test("a READY job whose updated save failed to load can be recovered without another rewrite", async () => {
  let posts = 0;
  let loads = 0;
  const reads = [];
  const { ui, start } = await app({
    restructure: async () => { posts++; return job("READY"); },
    restructureJob: async (id) => { reads.push(id); return job("READY"); },
    getSession: async () => {
      if (++loads === 1) throw new Error("save response was interrupted");
      return base();
    },
  });
  await start();
  byClassPrefix(ui, "icon-btn restructure-trigger")[0].props.onClick(); await flush();
  dialog(ui).props.onSubmit("换一个对手"); await flush();

  const stopped = dialog(ui).props;
  assert.equal(stopped.job.status, "READY");
  assert.equal(stopped.running, false, "the server job is already settled");
  assert.equal(stopped.canRetry, true, "READY is not mistaken for a successfully adopted save");
  assert.equal(stopped.error, "save response was interrupted");
  const panel = harness("components/RestructureDialog.tsx");
  panel.render(stopped); await flush();
  const retry = nodes(panel.tree, (node) => node.type === "button" && node.props?.children === "重试读取进度")[0];
  assert.ok(retry, "the READY recovery action is visible too");
  retry.props.onClick(); await flush();

  assert.equal(posts, 1);
  assert.deepEqual(reads, ["job-1"]);
  assert.equal(loads, 2);
  assert.equal(dialog(ui), undefined);
  panel.unmount();
  ui.unmount();
});

test("Escape follows a rewrite from the editable dialog through running and settled states", async () => {
  let closed = 0;
  const ui = harness("components/RestructureDialog.tsx");
  const props = { anchorLabel: null, canRestructure: true, job: null, running: false, error: null,
    onSubmit() {}, onRetry() {}, onClose: () => { closed++; } };
  ui.render(props); await flush();
  ui.render({ ...props, job: job("RUNNING"), running: true }); await flush();
  const escape = () => ui.emit("keydown", { key: "Escape", preventDefault() {}, stopPropagation() {} });
  escape(); await flush(); await ui.advance(200);
  assert.equal(closed, 0, "the listener cannot retain running=false from the compose screen");
  assert.ok(!ui.tree.props.className.includes("is-closing"));

  ui.render({ ...props, job: job("FAILED"), running: false }); await flush();
  escape(); await flush(); await ui.advance(200);
  assert.equal(closed, 1, "a settled job can be dismissed with Escape");
  ui.unmount();
});

test("Escape also closes a dialog first mounted with a running job after it settles", async () => {
  let closed = 0;
  const ui = harness("components/RestructureDialog.tsx");
  const props = { anchorLabel: null, canRestructure: true, job: job("RUNNING"), running: true, error: null,
    onSubmit() {}, onRetry() {}, onClose: () => { closed++; } };
  ui.render(props); await flush();
  ui.render({ ...props, job: job("READY"), running: false }); await flush();
  ui.emit("keydown", { key: "Escape", preventDefault() {}, stopPropagation() {} });
  await flush(); await ui.advance(200);
  assert.equal(closed, 1, "the listener cannot retain running=true from restored progress");
  ui.unmount();
});
