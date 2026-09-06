import test from "node:test";
import assert from "node:assert/strict";
import { harness, nodes, flush } from "./componentHarness.mjs";

const job = (progress, status = "RUNNING", id = "job-one") => ({
  id, status, progress, stage: "接收故事框架", logs: [], sessionId: status === "READY" ? "session" : null, error: null,
});
const value = (h) => nodes(h.tree, (n) => n.props?.role === "progressbar")[0].props["aria-valuenow"];

test("compilation progress eases toward real milestones and stops there while the model waits", async () => {
  const h = harness("components/CompilationProgress.tsx");
  h.render({ job: job(38), pending: true });
  assert.equal(value(h), 0);
  await h.advance(400);
  const intermediate = value(h);
  assert.ok(intermediate > 0 && intermediate < 38, `Expected a smooth intermediate value, got ${intermediate}`);
  await h.advance(3000);
  assert.equal(value(h), 38);
  await h.advance(120000);
  assert.equal(value(h), 38, "Elapsed network waiting time must never invent completed work");
  h.render({ job: job(83), pending: true });
  assert.equal(value(h), 38, "The next milestone must start from the currently shown value");
  await h.advance(400);
  assert.ok(value(h) > 38 && value(h) < 83);
  await h.advance(3000);
  assert.equal(value(h), 83);
  h.unmount();
});

test("100 percent is reserved for READY and another job starts from zero", async () => {
  const h = harness("components/CompilationProgress.tsx");
  h.render({ job: job(100), pending: true });
  await h.advance(3000);
  assert.equal(value(h), 99);
  h.render({ job: job(100, "FAILED"), pending: false });
  await h.advance(3000);
  assert.equal(value(h), 99);
  h.render({ job: job(100, "READY"), pending: false });
  await h.advance(1000);
  assert.equal(value(h), 100);
  h.render({ job: job(4, "RUNNING", "job-two"), pending: true });
  assert.equal(value(h), 0);
  await h.advance(1000);
  assert.equal(value(h), 4);
  h.unmount();
});

test("progress honors reduced motion and cancels outstanding animation on unmount", async () => {
  const h = harness("components/CompilationProgress.tsx");
  h.window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} });
  h.render({ job: job(62), pending: true });
  await flush();
  assert.equal(value(h), 62);
  assert.equal(h.timers.size, 0);
  h.unmount();

  const animated = harness("components/CompilationProgress.tsx");
  animated.render({ job: job(90), pending: true });
  assert.ok(animated.timers.size > 0);
  animated.unmount();
  assert.equal(animated.timers.size, 0);
});
