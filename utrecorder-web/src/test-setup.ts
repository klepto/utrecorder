import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./mocks/server";

// jsdom implements no matchMedia, which Mantine reads for the color scheme.
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// A request without a handler fails the test that made it, even when the code under test catches the failed fetch.
const unhandled: string[] = [];

beforeAll(() => {
  server.listen({
    onUnhandledRequest: (request) => {
      unhandled.push(`${request.method} ${request.url}`);
    },
  });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  const requests = unhandled.splice(0);
  if (requests.length > 0) throw new Error(`Requests without a handler: ${requests.join(", ")}`);
});

afterAll(() => {
  server.close();
});
