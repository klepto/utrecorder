import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef } from "react";
import { fetchJobs, fetchQueue, fetchRecordings, type RecordingsPage } from "./client";

const JOBS_INTERVAL = 2_000;
const QUEUE_INTERVAL = 10_000;
const RECORDINGS_INTERVAL = 30_000;

/**
 * Returns the running jobs, polled every 2 s. When a job disappears between two fetches, invalidates every recordings
 * query, so the recording it finished shows up at once.
 */
export function useJobs() {
  const queryClient = useQueryClient();
  const result = useQuery({ queryKey: ["jobs"], queryFn: fetchJobs, refetchInterval: JOBS_INTERVAL });
  const previous = useRef<ReadonlySet<string> | null>(null);
  const data = result.data;

  useEffect(() => {
    if (!data) return;
    const current = new Set(data.map((job) => job.demo.capId));
    const last = previous.current;
    previous.current = current;
    if (last && [...last].some((capId) => !current.has(capId))) {
      void queryClient.invalidateQueries({ queryKey: ["recordings"] });
    }
  }, [data, queryClient]);

  return result;
}

/** Returns the queued demos, polled every 10 s. */
export function useQueue() {
  return useQuery({ queryKey: ["queue"], queryFn: fetchQueue, refetchInterval: QUEUE_INTERVAL });
}

/** Returns one page of the finished recordings, newest first, polled every 30 s. */
export function useRecordings({ limit, offset }: RecordingsPage) {
  return useQuery({
    queryKey: ["recordings", { limit, offset }],
    queryFn: () => fetchRecordings({ limit, offset }),
    refetchInterval: RECORDINGS_INTERVAL,
  });
}

/** The number of recordings a page of the recordings list holds. */
export const RECORDINGS_PAGE_SIZE = 20;

/**
 * Pages through the finished recordings, `RECORDINGS_PAGE_SIZE` at a time. The next page starts at the number of
 * recordings fetched so far. `recordings` holds every fetched recording, skipping one whose `capId` is already
 * present, since a recording that lands meanwhile shifts every offset. It is `undefined` until the first page arrives.
 */
export function useRecordingPages() {
  const query = useInfiniteQuery({
    queryKey: ["recordings", "paged"],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchRecordings({ limit: RECORDINGS_PAGE_SIZE, offset: pageParam }),
    getNextPageParam: (last, all) =>
      last.length < RECORDINGS_PAGE_SIZE ? undefined : all.reduce((count, page) => count + page.length, 0),
  });
  const recordings = useMemo(() => {
    if (!query.data) return undefined;
    const seen = new Set<string>();
    return query.data.pages.flat().filter((recording) => {
      if (seen.has(recording.demo.capId)) return false;
      seen.add(recording.demo.capId);
      return true;
    });
  }, [query.data]);
  return {
    query,
    recordings,
    hasMore: query.hasNextPage,
    loadMore: () => void query.fetchNextPage(),
    loadMoreFailed: query.isFetchNextPageError,
  };
}
