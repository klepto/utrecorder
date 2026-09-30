import { setupWorker } from "msw/browser";
import { handlers } from "./handlers";

/** The MSW worker that serves the fixtures in the browser, in the default development mode. */
export const worker = setupWorker(...handlers);
