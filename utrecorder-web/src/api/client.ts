import type { Demo, Job, Recording } from "./types";

/** A page of `/api/recordings`: at most `limit` recordings, after skipping the `offset` newest. */
export interface RecordingsPage {
  limit: number;
  offset: number;
}

/** Fetches the JSON at `path` of the server, rejecting on a response that is not 2xx. */
async function get<T>(path: string): Promise<T> {
  const response = await fetch(new URL(path, document.baseURI));
  if (!response.ok) throw new Error(`GET ${path} answered ${response.status}`);
  return (await response.json()) as T;
}

/** Fetches every running recording. */
export function fetchJobs(): Promise<Job[]> {
  return get("/api/jobs");
}

/** Fetches the next demos to be recorded, in the order they are taken. */
export function fetchQueue(): Promise<Demo[]> {
  return get("/api/queue");
}

/** Fetches one page of the finished recordings, newest first. */
export function fetchRecordings({ limit, offset }: RecordingsPage): Promise<Recording[]> {
  return get(`/api/recordings?limit=${limit}&offset=${offset}`);
}
