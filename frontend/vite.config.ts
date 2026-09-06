import { readFileSync } from "node:fs";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Local-first: the UI talks to the Java runtime through a same-origin /api proxy,
// so nothing has to be exposed beyond loopback.
//
// Settings come from genvn.config.json next to this file -- edit that, not this.
// If it is missing or unreadable we fall back to the defaults below rather than
// refusing to start.
const defaults = {
  devServerPort: 5180,
  devServerHost: "127.0.0.1",
  backend: "http://127.0.0.1:8080",
  // Where a BUILT copy (npm run build) finds the backend. Empty = the same origin that
  // serves the files, i.e. a reverse proxy forwarding /api. For a front end served from a
  // CDN, give the backend's public origin, e.g. "https://api.example.com". The dev
  // server ignores this and proxies /api to `backend` above.
  apiBase: "",
};

function loadConfig(): typeof defaults {
  try {
    const raw = readFileSync(new URL("./genvn.config.json", import.meta.url), "utf8");
    return { ...defaults, ...JSON.parse(raw) };
  } catch (e) {
    console.warn(`[genvn] could not read genvn.config.json, using defaults (${String(e)})`);
    return defaults;
  }
}

const config = loadConfig();
// A build pipeline may override the file: GENVN_API_BASE=https://api.example.com npm run build
const apiBase = (process.env.GENVN_API_BASE ?? config.apiBase ?? "").trim().replace(/\/+$/, "");
if (apiBase && !/^https?:\/\//.test(apiBase)) {
  throw new Error(`[genvn] apiBase must be an origin such as https://api.example.com, got "${apiBase}"`);
}

export default defineConfig({
  plugins: [react()],
  define: { __GENVN_API_BASE__: JSON.stringify(apiBase) },
  server: {
    port: config.devServerPort,
    host: config.devServerHost,
    proxy: {
      "/api": {
        target: config.backend,
        changeOrigin: true,
        // A real model can take a couple of minutes to compile a story. The backend owns
        // that budget (llm.timeout-seconds); the dev proxy must never be the thing that 504s.
        timeout: 10 * 60 * 1000,
        proxyTimeout: 10 * 60 * 1000,
      },
    },
  },
});
