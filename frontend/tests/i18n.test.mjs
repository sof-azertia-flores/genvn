import assert from "node:assert/strict";
import { test } from "node:test";
import { harness } from "./componentHarness.mjs";

test("UI copy switches by language and unknown codes fall back to Chinese", () => {
  const ui = harness("components/SetupView.tsx", { "../setup.css": {}, "../api": { api: { listSessions: async () => [] } }, "../useCreationJob": { default: () => ({ pending: false, job: null, begin() {} }) } });
  const { t, parseLang } = ui.load("i18n.tsx");
  assert.equal(parseLang("en-US"), "en");
  assert.equal(parseLang("English"), "en");
  assert.equal(parseLang("zh-CN"), "zh");
  assert.equal(parseLang(null), "zh");
  assert.equal(t("en", "settings"), "Settings");
  assert.equal(t("zh", "settings"), "设置");
  assert.equal(t("en", "startStory"), "Begin the story");
  ui.unmount();
});
