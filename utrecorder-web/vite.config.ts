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

/** The server `npm run dev:server` proxies `/api`, `/mp4`, `/thumbnail` and `/view` to. */
const serverUrl = "http://localhost:8080";

export default defineConfig(({ mode }) => ({
  plugins: [react(), removeMockServiceWorker()],
  define: {
    __APP_VERSION__: JSON.stringify(process.env.UTRECORDER_VERSION ?? "dev"),
  },
  server: {
    host: true,
    port: 5173,
    proxy:
      mode === "server"
        ? { "/api": serverUrl, "/mp4": serverUrl, "/thumbnail": serverUrl, "/view": serverUrl }
        : undefined,
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
