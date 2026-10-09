import { rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

const outDir = fileURLToPath(new URL("build/web", import.meta.url));

/** Removes the MSW worker that Vite copies from `public/`, so the build never ships it. */
const removeMockServiceWorker = (): Plugin => {
  return {
    name: "remove-mock-service-worker",
    apply: "build",
    closeBundle: async () => {
      await rm(`${outDir}/mockServiceWorker.js`, { force: true });
    },
  };
};

/** The server each proxying Vite mode forwards `/api`, `/mp4`, `/thumbnail` and `/view` to. */
const servers: Partial<Record<string, string>> = {
  server: "http://localhost:8080",
  live: "https://recorder.example.com",
};

/** Returns the proxy of `/api`, `/mp4`, `/thumbnail` and `/view` to `target`, or `undefined` without a target. */
const proxy = (target: string | undefined) => {
  if (target === undefined) return undefined;
  const route = { target, changeOrigin: true };
  return { "/api": route, "/mp4": route, "/thumbnail": route, "/view": route };
};

export default defineConfig(({ mode }) => ({
  plugins: [react(), removeMockServiceWorker()],
  define: {
    __APP_VERSION__: JSON.stringify(process.env.UTRECORDER_VERSION ?? "dev"),
  },
  server: {
    host: true,
    port: 5173,
    proxy: proxy(servers[mode]),
  },
  build: {
    outDir,
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["src/test-setup.ts"],
  },
}));
