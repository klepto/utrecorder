import { rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

const outDir = fileURLToPath(new URL("build/web", import.meta.url));

/** Removes the MSW worker that Vite copies from `public/`, so the build never ships it. */
function removeMockServiceWorker(): Plugin {
  return {
    name: "remove-mock-service-worker",
    apply: "build",
    async closeBundle() {
      await rm(`${outDir}/mockServiceWorker.js`, { force: true });
    },
  };
}

export default defineConfig({
  plugins: [react(), removeMockServiceWorker()],
  define: {
    __APP_VERSION__: JSON.stringify(process.env.UTRECORDER_VERSION ?? "dev"),
    __APP_COMMIT__: JSON.stringify(process.env.UTRECORDER_COMMIT ?? "unknown"),
  },
  server: {
    port: 5173,
  },
  build: {
    outDir,
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["src/test-setup.ts"],
  },
});
