import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness } from "./componentHarness.mjs";

const job = (status = "RUNNING", progress = 10) => ({
  id: "job1", status, progress, stage: "compile", logs: [{ id: 1, time: "2026-09-05T00:00:00Z", message: "正在编译世界" }],
  sessionId: status === "READY" ? "save1" : null, error: null,
});
const payload = { storyOutline: "a story", player: {} };

test("creation follows backend milestones without inventing progress, and loads the ready session once", async () => {
  let posts = 0, reads = 0, loads = 0;
  const ui = harness("useCreationJob.ts", { "./api": { api: {
    createSessionJob: async () => { posts++; return job(); },
    creationJob: async () => { reads++; return reads > 1 ? job("READY", 100) : job(); },
    getSession: async () => { loads++; return { sessionId: "save1" }; },
  } } });
  ui.render();
  void ui.tree.begin(payload); void ui.tree.begin(payload); await flush();
  assert.equal(posts, 1, "double click starts just one compilation");
  assert.equal(ui.tree.job.progress, 10);
  await ui.advance(1200); assert.equal(ui.tree.job.progress, 10);
  await ui.advance(1200);
  assert.equal(ui.tree.job.progress, 100);
  assert.equal(ui.tree.readySession.sessionId, "save1");
  assert.equal(loads, 1); assert.equal(ui.tree.pending, false);
  assert.equal(ui.timers.size, 0); ui.unmount();
});

test("temporary status failures pause polling and retry the same job without another POST", async () => {
  let posts = 0, reads = 0, recovered = false;
  const ui = harness("useCreationJob.ts", { "./api": { api: {
    createSessionJob: async () => { posts++; return job(); },
    creationJob: async () => { reads++; if (!recovered) throw new Error("offline"); return job("READY", 100); },
    getSession: async () => ({ sessionId: "save1" }),
  } } });
  ui.render(); void ui.tree.begin(payload); await flush();
  await ui.advance(10000);
  assert.equal(reads, 3); assert.equal(ui.tree.pending, false);
  assert.ok(ui.tree.error); assert.equal(ui.timers.size, 0);
  recovered = true; ui.tree.retry(); await flush();
  assert.equal(posts, 1); assert.equal(ui.tree.readySession.sessionId, "save1");
  assert.equal(ui.tree.error, null); ui.unmount();
});

test("leaving setup cancels its polling and ignores a late response", async () => {
  let resolve;
  const ui = harness("useCreationJob.ts", { "./api": { api: {
    createSessionJob: () => new Promise((done) => { resolve = done; }),
  } } });
  ui.render(); void ui.tree.begin(payload); await flush();
  const id = ui.tree.job.id;
  ui.unmount(); resolve(job()); await flush();
  assert.equal(ui.tree.job.id, id); assert.equal(ui.timers.size, 0);
});

test("a failed compilation keeps its last real progress and exposes the server failure", async () => {
  const ui = harness("useCreationJob.ts", { "./api": { api: {
    createSessionJob: async () => ({ ...job("FAILED", 48), error: "开场文字未能生成" }),
  } } });
  ui.render(); void ui.tree.begin(payload); await flush();
  assert.equal(ui.tree.job.progress, 48); assert.equal(ui.tree.error, "开场文字未能生成");
  assert.equal(ui.tree.readySession, null); assert.equal(ui.tree.pending, false);
  ui.tree.reset(); await flush(); assert.equal(ui.tree.job, null); ui.unmount();
});

test("a missing job after a server restart offers a return path instead of endless read retries", async () => {
  let reads = 0;
  const ui = harness("useCreationJob.ts", { "./api": { api: {
    createSessionJob: async () => job(),
    creationJob: async () => { reads++; throw { code: "not_found" }; },
  } } });
  ui.render(); void ui.tree.begin(payload); await flush();
  await ui.advance(4500);
  assert.equal(reads, 3); assert.equal(ui.tree.job.status, "FAILED");
  assert.equal(ui.tree.pending, false); assert.equal(ui.timers.size, 0); ui.unmount();
});

test("refresh recovers the known job, and a lost POST response can be read without resubmitting", async () => {
  const storage = new Map();
  let posts = 0, id;
  const api = {
    createSessionJob: async (_payload, _signal, requestId) => { posts++; id = requestId; throw new Error("response lost"); },
    creationJob: async (requested) => { assert.equal(requested, id); return { ...job("READY", 100), id }; },
    getSession: async () => ({ sessionId: "save1" }),
  };
  const first = harness("useCreationJob.ts", { "./api": { api } }, { storage });
  first.render(); void first.tree.begin(payload); await flush();
  assert.equal(first.tree.pending, false); assert.equal(first.tree.job.id, id);
  first.unmount();
  const refreshed = harness("useCreationJob.ts", { "./api": { api } }, { storage });
  refreshed.render(); await flush();
  assert.equal(posts, 1); assert.equal(refreshed.tree.readySession.sessionId, "save1");
  assert.equal(storage.size, 0); refreshed.unmount();
});

test("a hung status request times out and eventually offers recovery for the same job", async () => {
  let posts = 0;
  const ui = harness("useCreationJob.ts", { "./api": { api: {
    createSessionJob: async () => { posts++; return job(); },
    creationJob: () => new Promise(() => {}),
  } } });
  ui.render(); void ui.tree.begin(payload); await flush();
  await ui.advance(60000);
  assert.equal(ui.tree.pending, false); assert.ok(ui.tree.error);
  assert.equal(posts, 1); assert.equal(ui.timers.size, 0); ui.unmount();
});

test("refresh tolerates GET arriving before the original POST is registered", async () => {
  const storage = new Map([["genvn.pending-creation", "job1"]]);
  let reads = 0;
  const ui = harness("useCreationJob.ts", { "./api": { api: {
    creationJob: async () => { if (++reads === 1) throw { code: "not_found" }; return job("READY", 100); },
    getSession: async () => ({ sessionId: "save1" }),
  } } }, { storage });
  ui.render(); await flush();
  assert.equal(ui.tree.pending, true); assert.equal(ui.tree.error, null);
  await ui.advance(1000);
  assert.equal(reads, 2); assert.equal(ui.tree.readySession.sessionId, "save1");
  assert.equal(ui.tree.error, null); ui.unmount();
});
