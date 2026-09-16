import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import { randomUUID } from "node:crypto";
import ts from "typescript";

// Deterministic hooks/DOM boundary for the actual TSX functions, including effect cleanup
// and separate renders. No source-text assertions and no live service or real timers.
export const flush = () => new Promise((done) => setImmediate(done));
/** An api stub that says nothing about the access key means the server wants none. Mutated in place so a test's later edits to its stub still apply. */
function withOpenAccess(stub) {
  if (stub && typeof stub === "object") {
    if (stub.api && typeof stub.api === "object" && !("access" in stub.api)) stub.api.access = async () => ({ required: false, granted: true });
    if (!("onAccessDenied" in stub)) stub.onAccessDenied = () => () => {};
  }
  return stub;
}
export function harness(file, stubs = {}, options = {}) {
  let hooks = [], cursor = 0, effects = [], scheduled = false, active = true;
  let props = {}, tree, now = 0, nextTimer = 0;
  const timers = new Map(), listeners = new Map(), images = [], cache = new Map();
  class Element { closest() { return null; } }
  class HTMLElement extends Element { focus() {} }
  // dataset carries the theme attribute the override sheet keys off.
  const document = { activeElement: null, documentElement: { lang: "zh-CN", dataset: {} } };
  const root = fileURLToPath(new URL("../src/", import.meta.url));
  const storage = options.storage ?? new Map();
  // Separate from sessionStorage, and deliberately only on `window`: api.ts reads the bare
  // `localStorage` global, which stays undefined here so the access key keeps its memory path.
  const persistent = options.localStorage ?? new Map();
  const window = {
    crypto: { randomUUID },
    sessionStorage: { getItem: (k) => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: (k) => storage.delete(k) },
    localStorage: { getItem: (k) => persistent.get(k) ?? null, setItem: (k, v) => persistent.set(k, String(v)), removeItem: (k) => persistent.delete(k) },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, at: now + delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    addEventListener(type, fn) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(fn); },
    removeEventListener(type, fn) { listeners.get(type)?.delete(fn); },
  };
  const schedule = () => {
    if (scheduled || !active) return;
    scheduled = true;
    queueMicrotask(() => { scheduled = false; if (active) render(); });
  };
  const changed = (old, deps) => !old || deps.length !== old.deps.length || deps.some((d, i) => !Object.is(d, old.deps[i]));
  const react = {
    useState(initial) {
      const id = cursor++;
      if (!(id in hooks)) hooks[id] = typeof initial === "function" ? initial() : initial;
      return [hooks[id], (value) => {
        const next = typeof value === "function" ? value(hooks[id]) : value;
        if (!Object.is(next, hooks[id])) { hooks[id] = next; schedule(); }
      }];
    },
    useRef(initial) { const id = cursor++; return hooks[id] ??= { current: initial }; },
    useCallback(fn, deps) { const id = cursor++; if (changed(hooks[id], deps)) hooks[id] = { fn, deps }; return hooks[id].fn; },
    useEffect(fn, deps) {
      const id = cursor++;
      if (changed(hooks[id], deps)) {
        hooks[id] = { deps, cleanup: hooks[id]?.cleanup };
        effects.push(() => { hooks[id].cleanup?.(); hooks[id].cleanup = fn(); });
      }
    },
  };
  react.useLayoutEffect = react.useEffect;
  const jsx = { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }), Fragment: "Fragment" };
  react.createContext = (defaultValue) => {
    const ctx = { _current: defaultValue, defaultValue };
    ctx.Provider = (props) => { ctx._current = props.value; return props.children; };
    return ctx;
  };
  react.useContext = (ctx) => ctx._current ?? ctx.defaultValue;
  function load(path) {
    if (cache.has(path)) return cache.get(path);
    const module = { exports: {} };
    cache.set(path, module.exports);
    const code = ts.transpileModule(readFileSync(path, "utf8"), {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
    }).outputText;
    const require = (name) => {
      if (name === "react") return react;
      if (name === "react/jsx-runtime") return jsx;
      if (name in stubs) return name.endsWith("/api") ? withOpenAccess(stubs[name]) : stubs[name];
      if (name.startsWith("./components/")) return { default: name };
      const full = resolve(dirname(path), name);
      let target = `${full}.ts`;
      try { readFileSync(target); } catch { target = `${full}.tsx`; }
      return load(target);
    };
    class Image { constructor() { this.onload = null; this.onerror = null; images.push(this); } }
    class ClockDate extends Date { static now() { return now; } }
    vm.runInNewContext(code, { require, module, exports: module.exports, console, window, document, Element, HTMLElement, Image,
      AbortController, Date: ClockDate, setTimeout: window.setTimeout, clearTimeout: window.clearTimeout });
    return module.exports;
  }
  const Component = load(resolve(root, file)).default;
  function render(next = props) {
    props = next; cursor = 0; effects = [];
    tree = Component(props);
    for (const effect of effects) effect();
    return tree;
  }
  return {
    render, images, timers, window, document, get tree() { return tree; },
    load: (name) => load(resolve(root, name)),
    emit: (type, event = {}) => { for (const fn of listeners.get(type) ?? []) fn(event); },
    async advance(ms) {
      const end = now + ms;
      while (true) {
        const entry = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
        if (!entry || entry[1].at > end) break;
        now = entry[1].at; timers.delete(entry[0]); entry[1].fn(); await flush();
      }
      now = end; await flush();
    },
    unmount() { active = false; for (const hook of hooks) hook?.cleanup?.(); },
  };
}

export function nodes(tree, predicate) {
  if (!tree || typeof tree !== "object") return [];
  if (Array.isArray(tree)) return tree.flatMap((child) => nodes(child, predicate));
  return [...(predicate(tree) ? [tree] : []), ...nodes(tree.props?.children, predicate)];
}
