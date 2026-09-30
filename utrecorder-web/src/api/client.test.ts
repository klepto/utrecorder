import { describe, expect, test } from "vitest";
import { fetchJobs, fetchQueue, fetchRecordings } from "./client";

describe("client", () => {
  test("fetches the running jobs", async () => {
    const jobs = await fetchJobs();

    expect(jobs.map((job) => job.demo.capId)).toEqual(["1100", "1099"]);
    expect(jobs[0].stage).toBeNull();
    expect(jobs[1].attempt).toBe(2);
  });

  test("fetches the queue", async () => {
    const queue = await fetchQueue();

    expect(queue.map((demo) => demo.capId)).toEqual(["1101", "1102", "1103", "1104", "1105"]);
  });

  test("fetches a page of recordings", async () => {
    const recordings = await fetchRecordings({ limit: 3, offset: 2 });

    expect(recordings.map((recording) => recording.demo.capId)).toEqual(["1096", "1095", "1094"]);
  });

  test("fetches the last page of recordings, whose last recording was not recorded by the server", async () => {
    const recordings = await fetchRecordings({ limit: 10, offset: 20 });

    expect(recordings.map((recording) => recording.demo.capId)).toEqual(["1078", "1077", "1076", "1050"]);
    expect(recordings[3].recordedAt).toBeNull();
  });

  test("caps the limit at 50", async () => {
    const recordings = await fetchRecordings({ limit: 100, offset: 0 });

    expect(recordings).toHaveLength(24);
  });

  test("rejects a limit that is not a non-negative integer", async () => {
    await expect(fetchRecordings({ limit: -1, offset: 0 })).rejects.toThrow("400");
  });

  test("rejects an offset that is not a non-negative integer", async () => {
    await expect(fetchRecordings({ limit: 10, offset: 1.5 })).rejects.toThrow("400");
  });
});
