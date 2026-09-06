import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

// The pure resolution rules, compiled from the real source: no browser, no React.
const source = await readFile(new URL("../src/assetView.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
});
const { displayUrl, portraitUrl, preloadUrls, assetById } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputText).toString("base64")}`
);

const asset = (over) => ({
  assetId: "x", kind: "BACKGROUND", subjectId: "loc", subjectName: "L", variant: "default",
  status: "READY", fileName: "x.png", width: 1, height: 1, beatId: null, priority: 9, dependsOn: null,
  applicability: "GENERAL", attempts: 1, failureReason: null, generationVersion: 1, plannedAt: null,
  queuedAt: null, startedAt: null, readyAt: null, firstNeededAt: null, reuseCount: 0,
  readyBeforeNeeded: null, url: "/api/assets/s/x?v=1", ...over,
});

const status = {
  version: 3, counts: {}, pending: 1, provider: "fake", enabled: true, concurrency: 2, active: 0,
  highWaterConcurrency: 2, queueDepth: 0, queueCapacity: 64,
  assets: [
    asset({ assetId: "bg.hall.default", priority: 0, url: "/api/assets/s/bg.hall.default?v=1" }),
    asset({ assetId: "bg.attic.default", priority: 2, status: "GENERATING", url: null }),
    asset({ assetId: "pt.han.base", kind: "PORTRAIT", subjectId: "han", variant: "base", priority: 1, url: "/api/assets/s/pt.han.base?v=2" }),
    asset({ assetId: "pt.han.worried", kind: "PORTRAIT_VARIANT", subjectId: "han", variant: "worried", dependsOn: "pt.han.base", status: "QUEUED", url: null, priority: 5 }),
    asset({ assetId: "pt.grandpa.base", kind: "PORTRAIT", subjectId: "grandpa", variant: "base", priority: 1, url: "/api/assets/s/pt.grandpa.base?v=1" }),
    asset({ assetId: "pt.grandpa.afraid", kind: "PORTRAIT_VARIANT", subjectId: "grandpa", variant: "afraid", dependsOn: "pt.han.base", status: "QUEUED", url: null, priority: 6 }),
  ],
};

test("a READY reference paints its own versioned url; a pending one paints nothing", () => {
  assert.equal(displayUrl(status, "bg.hall.default"), "/api/assets/s/bg.hall.default?v=1");
  assert.equal(displayUrl(status, "bg.attic.default"), null);
  assert.equal(displayUrl(status, "nope"), null);
  assert.equal(displayUrl(null, "bg.hall.default"), null);
});

test("a variant still generating falls back to the SAME character's base, never another face", () => {
  assert.equal(displayUrl(status, "pt.han.worried"), "/api/assets/s/pt.han.base?v=2");
  // dependsOn points at a different character (corrupt manifest): refuse rather than mis-face.
  assert.equal(displayUrl(status, "pt.grandpa.afraid"), null);
});

test("preload warms what is on screen first, then a few READY pictures, without duplicates", () => {
  const urls = preloadUrls(status, ["bg.hall.default", "pt.han.worried", "bg.attic.default"], 2);
  assert.deepEqual(urls, [
    "/api/assets/s/bg.hall.default?v=1",
    "/api/assets/s/pt.han.base?v=2",
    "/api/assets/s/pt.grandpa.base?v=1",
  ]);
  assert.equal(new Set(urls).size, urls.length);
  assert.deepEqual(preloadUrls(null, ["x"]), []);
});

test("assetById is exact", () => {
  assert.equal(assetById(status, "pt.han.base").subjectId, "han");
  assert.equal(assetById(status, "pt.han"), null);
});

test("dialogue portraits follow ready expressions and keep the scene portrait while a new pose is pending", () => {
  const library = { ...status, assets: [
    asset({ assetId: "base", kind: "PORTRAIT", subjectId: "han", variant: "base", url: "/base.png" }),
    asset({ assetId: "scene", kind: "PORTRAIT_VARIANT", subjectId: "han", variant: "worried", dependsOn: "base", url: "/worried.png" }),
    asset({ assetId: "pose", kind: "PORTRAIT_VARIANT", subjectId: "han", variant: "angry", dependsOn: "base", status: "GENERATING", url: null }),
  ] };
  assert.equal(portraitUrl(library, "han", "angry", "scene", "NONE"), "/worried.png");
  library.assets[2].status = "READY"; library.assets[2].url = "/angry.png";
  assert.equal(portraitUrl(library, "han", "angry", "scene", "NONE"), "/angry.png");
  assert.equal(portraitUrl(library, "han", "neutral", "scene", "NONE"), "/base.png");
  assert.equal(portraitUrl(library, "han", null, "scene", "NONE"), "/worried.png");
  library.assets[1].status = "FAILED";
  assert.equal(portraitUrl(library, "han", "happy", "scene", "NONE"), "/base.png");
});

test("dialogue portrait matching rejects other people, cards and wrong outcome images, including fallbacks", () => {
  const library = { ...status, assets: [
    asset({ assetId: "foreign", kind: "PORTRAIT_VARIANT", subjectId: "someone_else", variant: "angry", url: "/wrong-face.png" }),
    asset({ assetId: "card", kind: "CHARACTER_CARD", subjectId: "han", variant: "angry", url: "/card.png" }),
    asset({ assetId: "success", kind: "PORTRAIT_VARIANT", subjectId: "han", variant: "angry", applicability: "OUTCOME:SUCCESS", url: "/success.png" }),
    asset({ assetId: "base", kind: "PORTRAIT", subjectId: "han", variant: "base", url: "/base.png" }),
  ] };
  assert.equal(portraitUrl(library, "han", "angry", "foreign", "FAILURE"), "/base.png");
  assert.equal(portraitUrl(library, "han", "angry", "card", "NONE"), "/base.png");
  assert.equal(portraitUrl(library, "han", "angry", "success", "FAILURE"), "/base.png");
  assert.equal(portraitUrl(library, "han", "angry", "base", "SUCCESS"), "/success.png");
  library.assets[3].applicability = "OUTCOME:SUCCESS";
  assert.equal(portraitUrl(library, "han", "angry", "success", "FAILURE"), null);
  assert.equal(portraitUrl(null, "han", "angry", "base", "SUCCESS"), null);
});

test("preload excludes warmed URLs before counting slots and prefers current/next beats", () => {
  const library = { ...status, assets: Array.from({ length: 8 }, (_, i) => asset({
    assetId: `bg${i}`, url: `/${i}.png`, priority: i, beatId: `beat${i}`,
  })) };
  assert.deepEqual(preloadUrls(library, ["bg4"], 2, new Set(["/0.png", "/1.png", "/2.png", "/3.png", "/4.png"]),
    ["beat4", "beat6", "beat5"]), ["/6.png", "/5.png"]);
});
