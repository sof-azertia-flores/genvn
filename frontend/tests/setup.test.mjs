import assert from "node:assert/strict";
import { test } from "node:test";
import { flush, harness, nodes } from "./componentHarness.mjs";

test("pregame art style is available with images enabled and goes with the player's appearance", async () => {
  const payloads = [];
  const creation = { pending: false, job: null, begin: async (payload) => payloads.push(payload) };
  const ui = harness("components/SetupView.tsx", { "../setup.css": {}, "../api": { api: { listSessions: async () => [] } }, "../useCreationJob": { default: () => creation } });
  const props = { config: { imageEnabled: true }, onStarted() {}, onLoad() {} };
  ui.render(props); await flush();
  const field = (id) => nodes(ui.tree, (n) => n.props?.id === id)[0];
  field("art-style").props.onChange({ target: { value: "  铅笔线稿，柔和水彩  " } });
  field("pvisual").props.onChange({ target: { value: "  银灰短发、蓝色风衣  " } });
  await flush();
  nodes(ui.tree, (n) => n.type === "button" && n.props?.onClick?.name === "start")[0].props.onClick();
  await flush();
  assert.equal(payloads[0].artStyle, "铅笔线稿，柔和水彩");
  assert.equal(payloads[0].player.visualDescription, "银灰短发、蓝色风衣");
  ui.render({ ...props, config: { imageEnabled: false } });
  assert.equal(field("art-style"), undefined);
  ui.unmount();
});
