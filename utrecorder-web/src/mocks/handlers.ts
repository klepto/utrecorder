import { HttpResponse, http } from "msw";
import { jobs, queue, recordings, statistics } from "./fixtures";

const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 100;

/** Returns the non-negative integer `value`, `fallback` if it is absent, or `null` if it is not one. */
const nonNegativeInteger = (value: string | null, fallback: number): number | null => {
  if (value === null) return fallback;
  return /^\d+$/.test(value) ? Number(value) : null;
};

/** Handlers serving the fixtures on the server's JSON API. */
export const handlers = [
  http.get("/api/jobs", () => HttpResponse.json(jobs)),
  http.get("/api/statistics", () => HttpResponse.json(statistics)),
  http.get("/api/queue", () => HttpResponse.json(queue)),
  http.get("/api/recordings", ({ request }) => {
    const params = new URL(request.url).searchParams;
    const limit = nonNegativeInteger(params.get("limit"), DEFAULT_LIMIT);
    const offset = nonNegativeInteger(params.get("offset"), 0);
    if (limit === null || offset === null) {
      return new HttpResponse("limit and offset must be non-negative integers", { status: 400 });
    }
    return HttpResponse.json(recordings.slice(offset, offset + Math.min(limit, MAX_LIMIT)));
  }),
];
