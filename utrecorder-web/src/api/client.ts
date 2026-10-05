import type { Demo, Job, Recording, Statistics } from "./types";

/** A page of `/api/recordings`: at most `limit` recordings, after skipping the `offset` newest. */
export interface RecordingsPage {
  limit: number;
  offset: number;
}

/** Fetches the JSON at `path` of the server, rejecting on a response that is not 2xx. */
const get = async <T>(path: string): Promise<T> => {
  const response = await fetch(new URL(path, document.baseURI));
  if (!response.ok) throw new Error(`GET ${path} answered ${response.status}`);
  return (await response.json()) as T;
};

/** Fetches every running recording. */
export const fetchJobs = (): Promise<Job[]> => {
  return get("/api/jobs");
};

/** Fetches the next demos to be recorded, in the order they are taken. */
export const fetchQueue = (): Promise<Demo[]> => {
  return get("/api/queue?limit=10");
};

/** Fetches one page of the finished recordings, newest first. */
export const fetchRecordings = ({ limit, offset }: RecordingsPage): Promise<Recording[]> => {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return get(`/api/recordings?${query}`);
};

/** Fetches the aggregate statistics of the queue, recorder, storage and personal bests. */
export const fetchStatistics = (): Promise<Statistics> => {
  return get("/api/statistics");
};
