import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

for (const [file, props, imageNode] of [
  ["components/Backdrop.tsx", { fallback: "gradient" }, (n) => n.props?.className === "backdrop-img shown"],
  ["components/Sprite.tsx", { characterId: "han", name: "Han", dimmed: false, speaking: true }, (n) => n.type === "img"],
]) {
  test(`${file}: a new URL immediately clears the old picture, and the same URL retries after failure`, async () => {
    const ui = harness(file);
    ui.render({ ...props, url: "old.png" });
    ui.images[0].onload(); await flush();
    assert.equal(nodes(ui.tree, imageNode).length, 1);
    ui.render({ ...props, url: "new.png" });
    assert.equal(nodes(ui.tree, imageNode).length, 0, "even the first new-scene render cannot paint old.png");
    await flush();
    ui.images[1].onerror(); await flush();
    await ui.advance(999);
    assert.equal(ui.images.length, 2);
    await ui.advance(1);
    assert.equal(ui.images.length, 3);
    assert.equal(ui.images[2].src, "new.png");
    ui.images[2].onload(); await flush();
    assert.equal(nodes(ui.tree, imageNode).length, 1);
    ui.unmount(); assert.equal(ui.timers.size, 0);
  });

  test(`${file}: retries stop after three attempts and explicit retry recovers without changing URL`, async () => {
    const ui = harness(file);
    ui.render({ ...props, url: "flaky.png" });
    for (let attempt = 0; attempt < 3; attempt++) {
      ui.images[attempt].onerror(); await flush();
      await ui.advance((attempt + 1) * 1000);
    }
    assert.equal(ui.images.length, 3);
    assert.equal(ui.timers.size, 0);
    const retry = nodes(ui.tree, (n) => n.type === "button")[0];
    assert.ok(retry);
    retry.props.onClick(); await flush();
    assert.equal(ui.images.length, 4);
    ui.images[3].onload(); await flush();
    assert.equal(nodes(ui.tree, imageNode).length, 1);
    ui.unmount();
  });
}

test("preloading releases failed URLs, deduplicates in-flight loads, and fills slots after warmed assets", async () => {
  const ui = harness("components/Backdrop.tsx");
  const { preload } = ui.load("assets.ts");
  const status = { assets: Array.from({ length: 8 }, (_, i) => ({
    assetId: `bg${i}`, kind: "BACKGROUND", status: "READY", url: `/${i}.png`, priority: i,
  })) };
  preload(status, ["bg0"], 4);
  assert.equal(ui.images.length, 5);
  preload(status, ["bg0"], 0);
  assert.equal(ui.images.length, 5, "the same in-flight URL is not downloaded twice");
  ui.images[0].onerror();
  for (const image of ui.images.slice(1)) image.onload();
  preload(status, ["bg0"], 0);
  assert.equal(ui.images.length, 5, "failed downloads have a short cooldown");
  await ui.advance(2000);
  preload(status, ["bg0"], 0);
  assert.equal(ui.images.length, 6);
  ui.images[5].onload();
  preload(status, ["bg4"], 4);
  assert.deepEqual(ui.images.slice(6).map((i) => i.src), ["/5.png", "/6.png", "/7.png"]);
  for (const image of ui.images.slice(6)) image.onload();
  assert.equal(ui.timers.size, 0);
  ui.unmount();
});

test("a continuously resolving choice eventually stops automatic polling without submitting anything", async () => {
  const { ui, counts, start } = await appHarness();
  start(session({ resolvingChoiceId: "c1" })); await flush();
  await ui.advance(15 * 60 * 1000);
  assert.deepEqual(counts, { get: 120, roll: 0, choose: 0 });
  assert.equal(ui.timers.size, 0);
  assert.ok(nodes(ui.tree, (n) => n.props?.role === "alert").length);
  ui.unmount();
});

class ApiError extends Error { constructor(message, code) { super(message); this.code = code; } }
const die = { stat: "Body", d20: 1, modifier: 2, total: 3, dc: 10, success: false };
const session = (over = {}) => ({
  sessionId: "s", story: { spine: { beats: [] } },
  state: { stateVersion: 1, currentBeatId: "b1", storyProgress: { beatsCompleted: 0, totalBeats: 2, fraction: 0 } },
  scene: { sceneId: "scene1", location: { id: "hall", name: "Hall" }, characters: [],
    blocks: [{ type: "narration", text: "Waiting" }], choices: [{ id: "c1", check: { stat: "Body", dc: 10 } }] },
  finished: false, saveHealthy: true, continuationPending: false, pendingRoll: { choiceId: "c1", roll: die },
  ...over,
});
async function appHarness(overrides) {
  const counts = { roll: 0, choose: 0, get: 0 };
  const api = {
    config: async () => null,
    roll: async () => { counts.roll++; return { roll: die }; },
    choose: async () => { counts.choose++; return new Promise(() => {}); },
    getSession: async () => { counts.get++; return session({ resolvingChoiceId: "c1" }); },
    ...overrides?.(counts),
  };
  const ui = harness("App.tsx", { "./api": { api, ApiError }, "./assets": { useAssets: () => null, preload() {}, displayUrl: () => null } });
  ui.render(); await flush();
  const start = nodes(ui.tree, (n) => n.type === "./components/SetupView")[0].props.onStarted;
  return { ui, counts, start };
}

test("reopening an in-flight choice polls at intervals without roll/choose, then adopts the committed scene", async () => {
  const { ui, counts, start } = await appHarness((counts) => ({ getSession: async () => {
    counts.get++;
    return counts.get < 2 ? session({ resolvingChoiceId: "c1" }) : session({ pendingRoll: null,
      scene: { ...session().scene, sceneId: "scene2", blocks: [{ type: "narration", text: "Arrived" }] },
      state: { ...session().state, stateVersion: 2 }, resolvingChoiceId: null });
  } }));
  start(session({ resolvingChoiceId: "c1" })); await flush();
  assert.deepEqual(counts, { get: 0, roll: 0, choose: 0 });
  await ui.advance(1499); assert.equal(counts.get, 0);
  await ui.advance(1); assert.equal(counts.get, 1);
  await ui.advance(3000);
  assert.deepEqual(counts, { get: 2, roll: 0, choose: 0 });
  assert.ok(nodes(ui.tree, (n) => n.props?.children === "Arrived").length);
  await ui.advance(1000); // The new scene has a fresh typewriter reveal, but no further status request.
  assert.deepEqual(counts, { get: 2, roll: 0, choose: 0 });
  assert.equal(ui.timers.size, 0);
  ui.unmount();
});

test("if generation ended without committing, the saved die resumes once and a failed retry stops", async () => {
  const { ui, counts, start } = await appHarness((counts) => ({
    getSession: async () => { counts.get++; return session({ resolvingChoiceId: null }); },
    choose: async () => { counts.choose++; throw new Error("generation unavailable"); },
  }));
  start(session({ resolvingChoiceId: "c1" })); await flush();
  await ui.advance(1500); await flush();
  assert.deepEqual(counts, { get: 1, roll: 1, choose: 1 });
  await ui.advance(60000);
  assert.deepEqual(counts, { get: 1, roll: 1, choose: 1 });
  assert.ok(nodes(ui.tree, (n) => n.props?.role === "alert").length);
  ui.unmount();
});

test("choice_resolving and repeated status failures never resubmit a roll or choose", async () => {
  const { ui, counts, start } = await appHarness((counts) => ({
    choose: async () => { counts.choose++; throw new ApiError("busy", "choice_resolving"); },
    getSession: async () => { counts.get++; throw new Error("offline"); },
  }));
  start(session()); await flush();
  assert.deepEqual(counts, { get: 0, roll: 1, choose: 1 });
  await ui.advance(60000);
  assert.deepEqual(counts, { get: 3, roll: 1, choose: 1 });
  assert.equal(ui.timers.size, 0);
  ui.unmount();
});

test("legacy scene_conflict with unchanged pending roll cannot loop through automatic resume", async () => {
  const { ui, counts, start } = await appHarness((counts) => ({
    choose: async () => { counts.choose++; throw new ApiError("busy", "scene_conflict"); },
    getSession: async () => { counts.get++; return session(); },
  }));
  start(session()); await flush();
  assert.deepEqual(counts, { get: 1, roll: 1, choose: 1 });
  await ui.advance(60000);
  assert.deepEqual(counts, { get: 2, roll: 1, choose: 1 });
  ui.unmount();
});

test("leaving an in-flight choice aborts its poll and ignores a late status response", async () => {
  let complete, signal;
  const { ui, start } = await appHarness(() => ({ getSession: (_id, s) => {
    signal = s;
    return new Promise((resolve) => { complete = resolve; });
  } }));
  start(session({ resolvingChoiceId: "c1" })); await flush();
  await ui.advance(1500);
  nodes(ui.tree, (n) => n.props?.children === "返回菜单")[0].props.onClick(); await flush();
  assert.equal(signal.aborted, true);
  complete(session()); await flush();
  assert.ok(nodes(ui.tree, (n) => n.type === "./components/SetupView").length);
  assert.equal(ui.timers.size, 0);
  ui.unmount();
});

const displayedLine = (ui) => nodes(ui.tree, (n) => n.props?.className?.startsWith?.("line "))[0];
const shownText = (ui) => nodes(displayedLine(ui), (n) => n.type === "span" && n.props["aria-hidden"] === "true")[0]?.props.children;
const tapAdvance = (ui) => nodes(ui.tree, (n) => n.props?.className === "textbox")[0].props.onClick();

test("dialogue reveals complete graphemes, survives unrelated renders, and exposes choices only after completion", async () => {
  const { ui, start, counts } = await appHarness();
  start(session({ pendingRoll: null, scene: { ...session().scene, blocks: [{ type: "narration", text: "你👨‍👩‍👧‍👦好！" }] } }));
  await flush();
  assert.equal(shownText(ui), "");
  assert.equal(nodes(ui.tree, (n) => n.props?.className === "choice").length, 0);
  await ui.advance(32); assert.equal(shownText(ui), "你");
  await ui.advance(32); assert.equal(shownText(ui), "你👨‍👩‍👧‍👦");
  ui.render(); await flush(); assert.equal(shownText(ui), "你👨‍👩‍👧‍👦", "asset/status rerenders do not replay dialogue");
  tapAdvance(ui); await flush();
  assert.equal(shownText(ui), "你👨‍👩‍👧‍👦好！");
  assert.equal(nodes(ui.tree, (n) => n.props?.className === "choice").length, 1);
  assert.deepEqual(counts, { get: 0, roll: 0, choose: 0 });
  ui.unmount();
});

test("Space completes the current line before advancing, and identical next lines start a new reveal", async () => {
  const { ui, start } = await appHarness();
  start(session({ pendingRoll: null, scene: { ...session().scene, blocks: [{ type: "narration", text: "相同的句子" }, { type: "narration", text: "相同的句子" }] } })); await flush();
  const space = () => ui.emit("keydown", { key: " ", code: "Space", target: null, preventDefault() {} });
  space(); await flush(); assert.equal(shownText(ui), "相同的句子");
  assert.equal(nodes(ui.tree, (n) => n.props?.className === "choice").length, 0);
  space(); await flush(); assert.equal(shownText(ui), "");
  await ui.advance(32); assert.equal(shownText(ui), "相");
  tapAdvance(ui); await flush();
  assert.equal(nodes(ui.tree, (n) => n.props?.className === "choice").length, 1);
  ui.unmount(); assert.equal(ui.timers.size, 0);
});

test("reduced motion shows the whole line without scheduling a reveal", async () => {
  const ui = harness("App.tsx", { "./api": { api: { config: async () => null }, ApiError }, "./assets": { useAssets: () => null, preload() {}, displayUrl: () => null } });
  ui.window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} });
  ui.render(); await flush();
  nodes(ui.tree, (n) => n.type === "./components/SetupView")[0].props.onStarted(session({ pendingRoll: null })); await flush();
  assert.equal(shownText(ui), "Waiting");
  assert.equal(ui.timers.size, 0);
  assert.equal(nodes(ui.tree, (n) => n.props?.className === "choice").length, 1);
  ui.unmount();
});

test("opening the task queue pauses dialogue and blocks Space until it is closed", async () => {
  const { ui, start } = await appHarness();
  start(session({ pendingRoll: null })); await flush();
  await ui.advance(32); assert.equal(shownText(ui), "W");
  nodes(ui.tree, (n) => n.props?.className?.includes?.("queue-trigger"))[0].props.onClick(); await flush();
  ui.emit("keydown", { key: " ", code: "Space", target: null, preventDefault() {} }); await flush();
  await ui.advance(5000); assert.equal(shownText(ui), "W");
  nodes(ui.tree, (n) => n.type === "./components/TaskQueue")[0].props.onClose(); await flush();
  await ui.advance(32); assert.equal(shownText(ui), "Wa");
  ui.unmount();
});

test("character cards retain identity while the speaking state changes and recover a failed download", async () => {
  const ui = harness("components/CharacterCard.tsx");
  ui.render({ characterId: "han", name: "Han", url: "card.png", speaking: false });
  ui.images[0].onload(); await flush();
  ui.render({ characterId: "han", name: "Han", url: "card.png", speaking: true });
  assert.equal(ui.images.length, 1);
  assert.equal(nodes(ui.tree, (n) => n.type === "img")[0].props.src, "card.png");
  assert.match(ui.tree.props.className, /is-speaking/);
  ui.render({ characterId: "mira", name: "Mira", url: "mira.png", speaking: false });
  assert.equal(nodes(ui.tree, (n) => n.type === "img").length, 0);
  await ui.advance(15000); await ui.advance(1000);
  assert.equal(ui.images.length, 3);
  ui.images[2].onload(); await flush();
  assert.equal(nodes(ui.tree, (n) => n.type === "img")[0].props.src, "mira.png");
  ui.unmount();
});

test("task queue fetches non-spoiler statuses, waits for each response, and cancels on close", async () => {
  let calls = 0, resolve, signal;
  const ui = harness("components/TaskQueue.tsx", { "../api": { api: { tasks(_id, s) { calls++; signal = s; return new Promise((r) => { resolve = r; }); } } } });
  ui.render({ sessionId: "s", sceneId: "scene1", assets: null, onClose() {}, onInspect() {} });
  await ui.advance(10000); assert.equal(calls, 1, "slow reads never overlap");
  resolve({ sessionId: "s", sceneId: "scene1", speculationEnabled: true, branches: [{ id: "b", choiceId: "c", choiceText: "A choice the reader has not reached", check: true, status: "ready", error: null, elapsedMillis: null }] }); await flush();
  const text = JSON.stringify(ui.tree);
  assert.ok(!text.includes("A choice the reader has not reached"));
  assert.ok(text.includes("可能的走向"));
  assert.ok(text.includes("结果已封存"), "a checked choice says a die exists, never how it landed");
  for (const spoiler of ["检定成功", "检定失败", "success", "failure"]) assert.ok(!text.includes(spoiler));
  await ui.advance(2000); assert.equal(calls, 2);
  ui.unmount(); assert.equal(signal.aborted, true); assert.equal(ui.timers.size, 0);
});

test("second-round queue explains probability ranking and shows only anonymous candidates", async () => {
  const response = { sessionId: "s", sceneId: "scene1", speculationEnabled: true, speculationConcurrency: 4,
    branches: [{ id: "b", choiceId: "current-choice", choiceText: "Unread first choice", check: false, status: "ready" }],
    secondRound: { status: "generating", limit: 4, candidates: [
      { key: "candidate1", parentChoiceId: "current-choice", choiceId: "future-id", check: false, probability: 0.235, status: "generating", choiceText: "Secret future dialogue", scene: { text: "Hidden future scene" } },
      { key: "candidate2", parentChoiceId: "current-choice", choiceId: "another-future-id", check: true, probability: 0.17, status: "ready" },
    ] } };
  const ui = harness("components/TaskQueue.tsx", { "../api": { api: { tasks: async () => structuredClone(response) } } });
  ui.render({ sessionId: "s", sceneId: "scene1", assets: null, onClose() {}, onInspect() {} });
  await flush();
  const text = JSON.stringify(ui.tree);
  assert.ok(text.includes("第二轮")); assert.ok(text.includes("估计概率相乘"));
  assert.ok(text.includes("后续候选")); assert.ok(text.includes("23.5%"));
  assert.ok(text.includes("接在走向 01 之后")); assert.ok(text.includes("正在准备后续走向"));
  for (const secret of ["Unread first choice", "Secret future dialogue", "Hidden future scene", "future-id", "another-future-id"]) assert.ok(!text.includes(secret));
  response.secondRound.status = "completed_with_failures";
  response.secondRound.candidates.forEach((candidate) => { candidate.status = "failed"; });
  await ui.advance(2000); await flush();
  const failedText = JSON.stringify(ui.tree);
  assert.ok(failedText.includes("未完成的会在选择时继续准备"));
  assert.ok(!failedText.includes("后续走向已就绪"));
  ui.unmount();
});
