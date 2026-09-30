import { setupServer } from "msw/node";
import { handlers } from "./handlers";

/** The MSW server that serves the fixtures to the tests. */
export const server = setupServer(...handlers);
