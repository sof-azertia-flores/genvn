import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { afterEach, test } from "node:test";
import ts from "typescript";
import { flush, harness, nodes } from "./componentHarness.mjs";

// A build for a CDN bakes the backend origin in; this module instance sees one, unlike api.test.mjs.
globalThis.__GENVN_API_BASE__ = "https://api.example.com/";
const source = await readFile(new URL("../src/api.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
});
const { api, ApiError, API_BASE, apiUrl, getAccessKey, setAccessKey, onAccessDenied } =
  await import(`data:text/javascript;base64,${Buffer.from(compiled.outputText + "\n// split deployment").toString("base64")}`);
const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; setAccessKey(null); });

test("a baked-in backend origin prefixes every call and every picture URL, without a double slash", async () => {
  assert.equal(API_BASE, "https://api.example.com");
  let seen;
  globalThis.fetch = async (url) => { seen = url; return Response.json({ required: false, granted: true }); };
  await api.access();
  assert.equal(seen, "https://api.example.com/api/access");
  assert.equal(apiUrl("/api/assets/s1/bg.hall?v=2&t=abc"), "https://api.example.com/api/assets/s1/bg.hall?v=2&t=abc");
  assert.equal(apiUrl("https://cdn.example.com/x.png"), "https://cdn.example.com/x.png");
});

test("the stored key rides along as a header on every request, including ones with their own headers", async () => {
  const headers = [];
  globalThis.fetch = async (_url, init) => { headers.push(init.headers); return Response.json({}); };
  await api.getSession("s1");
  assert.equal(headers[0]["X-Genvn-Key"], undefined, "no key, no header");
  setAccessKey("open sesame");
  assert.equal(getAccessKey(), "open sesame");
  await api.getSession("s1");
  await api.createSessionJob({ storyOutline: "x", player: {} }, undefined, "11111111-1111-4111-8111-111111111111");
  assert.equal(headers[1]["X-Genvn-Key"], "open sesame");
  assert.equal(headers[2]["X-Genvn-Key"], "open sesame");
  assert.equal(headers[2]["Idempotency-Key"], "11111111-1111-4111-8111-111111111111");
  assert.equal(headers[2]["Content-Type"], "application/json");
});

test("a refused key surfaces as its own error code and wakes the listeners; other 401s do not", async () => {
  const codes = [];
  const stop = onAccessDenied((code) => codes.push(code));
  globalThis.fetch = async () => Response.json({ error: "access_key_invalid", message: "访问密钥不正确。" }, { status: 401 });
  await assert.rejects(api.getSession("s1"), (e) => e instanceof ApiError && e.code === "access_key_invalid" && e.message === "访问密钥不正确。");
  globalThis.fetch = async () => Response.json({ error: "something_else", message: "no" }, { status: 401 });
  await assert.rejects(api.getSession("s1"), (e) => e instanceof ApiError && e.code === "something_else");
  assert.deepEqual(codes, ["access_key_invalid"]);
  stop();
  globalThis.fetch = async () => Response.json({ error: "access_key_required", message: "x" }, { status: 401 });
  await assert.rejects(api.getSession("s1"));
  assert.deepEqual(codes, ["access_key_invalid"], "an unsubscribed listener hears nothing more");
});

test("the key screen keeps an accepted key, drops a refused one, and reports a backend that is unreachable", async () => {
  let stored = null;
  let accepted = "secret";
  let failNext = false;
  const stub = {
    api: { access: async () => { if (failNext) { failNext = false; throw new TypeError("fetch failed"); } return { required: true, granted: stored === accepted }; } },
    getAccessKey: () => stored,
    setAccessKey: (k) => { stored = k; },
  };
  const ui = harness("components/AccessGate.tsx", { "../api": stub, "../setup.css": {} });
  let granted = 0;
  ui.render({ reason: "required", onGranted: () => granted++ });
  const input = () => nodes(ui.tree, (n) => n.type === "input")[0];
  const form = () => nodes(ui.tree, (n) => n.type === "form")[0];
  const alert = () => nodes(ui.tree, (n) => n.props?.role === "alert")[0];
  const button = () => nodes(ui.tree, (n) => n.type === "button" && n.props?.type === "submit")[0];
  assert.equal(alert(), undefined);
  assert.equal(button().props.disabled, true, "nothing typed, nothing to submit");

  input().props.onChange({ target: { value: "wrong" } }); await flush();
  await form().props.onSubmit({ preventDefault() {} }); await flush();
  assert.equal(stored, null, "a refused key is not kept");
  assert.equal(alert().props.children, "访问密钥不正确。");
  assert.equal(granted, 0);

  failNext = true;
  input().props.onChange({ target: { value: "secret" } }); await flush();
  await form().props.onSubmit({ preventDefault() {} }); await flush();
  assert.equal(alert().props.children, "无法连接后端，请稍后再试。");
  assert.equal(stored, null);

  await form().props.onSubmit({ preventDefault() {} }); await flush();
  assert.equal(stored, "secret", "the accepted key stays in the browser");
  assert.equal(granted, 1);
});

test("the key screen explains itself when a previously saved key stopped working", () => {
  const stub = { api: { access: async () => ({ required: true, granted: false }) }, getAccessKey: () => "old", setAccessKey: () => {} };
  const ui = harness("components/AccessGate.tsx", { "../api": stub, "../setup.css": {} });
  ui.render({ reason: "denied", onGranted: () => {} });
  assert.equal(nodes(ui.tree, (n) => n.props?.role === "alert")[0].props.children, "保存的访问密钥已失效，请重新输入。");
});
