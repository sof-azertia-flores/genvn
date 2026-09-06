import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { afterEach, test } from "node:test";
import ts from "typescript";

// Exercise the real client against controlled HTTP responses without a browser or
// another test dependency. The application's type-only imports disappear here.
const source = await readFile(new URL("../src/api.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
});
const { api, ApiError } = await import(`data:text/javascript;base64,${Buffer.from(compiled.outputText).toString("base64")}`);
const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

test("choice submission binds to the displayed scene and version, with encoded path segments", async () => {
  let actual;
  globalThis.fetch = async (path, init) => {
    actual = { path, init };
    return Response.json({ sessionId: "session id", saveHealthy: false });
  };
  const result = await api.choose("session id", "检查房间?#/", "scene-17", 23);
  assert.equal(actual.path, `/api/sessions/session%20id/choices/${encodeURIComponent("检查房间?#/")}`);
  assert.equal(actual.init.method, "POST");
  assert.deepEqual(JSON.parse(actual.init.body), { expectedSceneId: "scene-17", expectedStateVersion: 23 });
  assert.equal(result.saveHealthy, false);
});

test("stale-scene conflicts keep their recovery code and message", async () => {
  globalThis.fetch = async () => Response.json({ error: "scene_conflict", message: "场景已经更新" }, { status: 409 });
  await assert.rejects(api.choose("s1", "c1", "old-scene", 1), (error) =>
    error instanceof ApiError && error.code === "scene_conflict" && error.message === "场景已经更新");
});

test("generic missing resources remain distinct from a missing session", async () => {
  for (const code of ["not_found", "session_not_found"]) {
    globalThis.fetch = async () => Response.json({ error: code, message: "missing" }, { status: 404 });
    await assert.rejects(api.getSession("s1"), (error) => error instanceof ApiError && error.code === code);
  }
});

test("HTML errors and invalid success responses produce readable errors", async () => {
  globalThis.fetch = async () => new Response("<html>Service unavailable</html>", { status: 502 });
  await assert.rejects(api.getSession("s1"), (error) =>
    error instanceof ApiError && error.message === "请求失败 (502)");
  globalThis.fetch = async () => new Response("broken JSON", { status: 200 });
  await assert.rejects(api.getSession("s1"), (error) =>
    error instanceof ApiError && error.message === "服务端返回了无法读取的数据。");
});

test("all session paths encode the supplied identifier", async () => {
  const paths = [];
  globalThis.fetch = async (path) => { paths.push(path); return Response.json({}); };
  await api.getSession("story ?#");
  await api.debug("story ?#");
  assert.deepEqual(paths, ["/api/sessions/story%20%3F%23", "/api/sessions/story%20%3F%23/debug"]);
});

test("leaving a session can cancel its pending status read", async () => {
  const controller = new AbortController();
  globalThis.fetch = async (_path, init) => new Promise((_resolve, reject) => {
    assert.equal(init.signal, controller.signal);
    init.signal.addEventListener("abort", () => reject(init.signal.reason), { once: true });
  });
  const pending = api.getSession("s1", controller.signal);
  controller.abort();
  await assert.rejects(pending, { name: "AbortError" });
});

test("history encodes the visible reading boundary and image retry is an explicit POST", async () => {
  const calls = [];
  const controller = new AbortController();
  globalThis.fetch = async (path, init) => { calls.push({ path, init }); return Response.json({}); };
  await api.history("故事 ?", { throughSceneId: "幕#2", throughBlockIndex: -1, beforeSceneId: "幕#1", limit: 20 }, controller.signal);
  const url = new URL(calls[0].path, "http://localhost");
  assert.equal(url.searchParams.get("throughSceneId"), "幕#2");
  assert.equal(url.searchParams.get("throughBlockIndex"), "-1");
  assert.equal(url.searchParams.get("beforeSceneId"), "幕#1");
  assert.equal(calls[0].init.signal, controller.signal);
  await api.retryAsset("故事 ?", "pt.player.action");
  assert.equal(calls[1].path, `/api/sessions/${encodeURIComponent("故事 ?")}/assets/pt.player.action/retry`);
  assert.equal(calls[1].init.method, "POST");
});
