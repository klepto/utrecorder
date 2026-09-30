import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, test } from "vitest";
import { jobs } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { useJobs, useQueue, useRecordings } from "./queries";

const wrapper = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
};

describe("queries", () => {
  test("useJobs returns the running jobs", async () => {
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.map((job) => job.demo.capId)).toEqual(["1100", "1099"]);
  });

  test("useQueue returns the queued demos", async () => {
    const { result } = renderHook(() => useQueue(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.map((demo) => demo.capId)).toEqual(["1101", "1102", "1103", "1104", "1105"]);
  });

  test("useRecordings returns the requested page", async () => {
    const { result } = renderHook(() => useRecordings({ limit: 2, offset: 1 }), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.map((recording) => recording.demo.capId)).toEqual(["1097", "1096"]);
  });

  test("useJobs surfaces an error response", async () => {
    server.use(http.get("/api/jobs", () => new HttpResponse(null, { status: 500 })));
    const { result } = renderHook(() => useJobs(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  test("useQueue surfaces an error response", async () => {
    server.use(http.get("/api/queue", () => new HttpResponse(null, { status: 500 })));
    const { result } = renderHook(() => useQueue(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  test("useRecordings surfaces an error response", async () => {
    const { result } = renderHook(() => useRecordings({ limit: -1, offset: 0 }), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  test("a job disappearing from the jobs refetches the recordings", async () => {
    let jobResponses = [jobs, [jobs[1]]];
    let recordingRequests = 0;
    server.use(
      http.get("/api/jobs", () => {
        const [next, ...rest] = jobResponses;
        if (rest.length > 0) jobResponses = rest;
        return HttpResponse.json(next);
      }),
      http.get("/api/recordings", () => {
        recordingRequests++;
        return HttpResponse.json([]);
      }),
    );
    const { result } = renderHook(() => ({ jobs: useJobs(), recordings: useRecordings({ limit: 10, offset: 0 }) }), {
      wrapper: wrapper(),
    });
    await waitFor(() => expect(result.current.jobs.isSuccess && result.current.recordings.isSuccess).toBe(true));
    expect(recordingRequests).toBe(1);

    await result.current.jobs.refetch();

    await waitFor(() => expect(recordingRequests).toBe(2));
  });

  test("a job staying in the jobs does not refetch the recordings", async () => {
    let recordingRequests = 0;
    server.use(
      http.get("/api/recordings", () => {
        recordingRequests++;
        return HttpResponse.json([]);
      }),
    );
    const { result } = renderHook(() => ({ jobs: useJobs(), recordings: useRecordings({ limit: 10, offset: 0 }) }), {
      wrapper: wrapper(),
    });
    await waitFor(() => expect(result.current.jobs.isSuccess && result.current.recordings.isSuccess).toBe(true));

    await result.current.jobs.refetch();
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(recordingRequests).toBe(1);
  });
});
