import { type QueryKey, useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef } from "react";
import type { QueryState } from "../components/QueryList";
import { fetchJobs, fetchQueue, fetchRecordings, type RecordingsPage } from "./client";
import type { Recording } from "./types";

/** The time between two fetches of the running jobs, in milliseconds. */
export const JOBS_INTERVAL = 2_000;
const QUEUE_INTERVAL = 10_000;
const RECORDINGS_INTERVAL = 30_000;

/** The number of recordings a page of the recordings list holds. */
const RECORDINGS_PAGE_SIZE = 20;

/** The key of every query. Each recordings key starts with `recordings`. */
const QUERY_KEYS = {
  jobs: ["jobs"],
  queue: ["queue"],
  recordings: ["recordings"],
  recordingsPage: (page: RecordingsPage): QueryKey => [...QUERY_KEYS.recordings, page],
  recordingPages: (): QueryKey => [...QUERY_KEYS.recordings, "paged"],
} as const;

/**
 * Returns the running jobs, polled every 2 s. When a job disappears between two fetches, invalidates every recordings
 * query, so the recording it finished shows up at once.
 */
export const useJobs = () => {
  const queryClient = useQueryClient();
  const result = useQuery({ queryKey: QUERY_KEYS.jobs, queryFn: fetchJobs, refetchInterval: JOBS_INTERVAL });
  const previous = useRef<ReadonlySet<string> | null>(null);
  const data = result.data;

  useEffect(() => {
    if (!data) return;
    const current = new Set(data.map((job) => job.demo.capId));
    const last = previous.current;
    previous.current = current;
    if (last && [...last].some((capId) => !current.has(capId))) {
      void queryClient.invalidateQueries({ queryKey: QUERY_KEYS.recordings });
    }
  }, [data, queryClient]);

  return result;
};

/** Returns the queued demos, polled every 10 s. */
export const useQueue = () => {
  return useQuery({ queryKey: QUERY_KEYS.queue, queryFn: fetchQueue, refetchInterval: QUEUE_INTERVAL });
};

/** Returns one page of the finished recordings, newest first, polled every 30 s. */
export const useRecordings = ({ limit, offset }: RecordingsPage) => {
  return useQuery({
    queryKey: QUERY_KEYS.recordingsPage({ limit, offset }),
    queryFn: () => fetchRecordings({ limit, offset }),
    refetchInterval: RECORDINGS_INTERVAL,
  });
};

/** The paged recordings list: the recordings loaded so far, and the loading of the next page. */
export interface RecordingPages {
  /** The loaded recordings. `isError` holds only while no recording is loaded. */
  query: QueryState<Recording>;
  /** Whether another recording follows the loaded ones. */
  hasMore: boolean;
  /** Fetches the next page. */
  loadMore: () => void;
  /** Whether the next page is being fetched. */
  loadingMore: boolean;
  /** Whether fetching the last requested next page failed. */
  loadMoreFailed: boolean;
}

/**
 * Pages through the finished recordings, `RECORDINGS_PAGE_SIZE` at a time. Each page asks for one recording more
 * than it shows, and another page follows only when that recording came back. The next page starts at the number of
 * pages loaded times `RECORDINGS_PAGE_SIZE`, which still counts the recordings skipped below. `query.data` holds every
 * shown recording, skipping one whose `capId` is already present. It is `undefined` until the first page arrives.
 */
export const useRecordingPages = (): RecordingPages => {
  const query = useInfiniteQuery({
    queryKey: QUERY_KEYS.recordingPages(),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchRecordings({ limit: RECORDINGS_PAGE_SIZE + 1, offset: pageParam }),
    getNextPageParam: (last, all) =>
      last.length > RECORDINGS_PAGE_SIZE ? all.length * RECORDINGS_PAGE_SIZE : undefined,
  });
  const recordings = useMemo(() => {
    if (!query.data) return undefined;
    const seen = new Set<string>();
    return query.data.pages
      .flatMap((page) => page.slice(0, RECORDINGS_PAGE_SIZE))
      .filter((recording) => {
        if (seen.has(recording.demo.capId)) return false;
        seen.add(recording.demo.capId);
        return true;
      });
  }, [query.data]);
  return {
    query: { data: recordings, isPending: query.isPending, isError: query.isError && !recordings },
    hasMore: query.hasNextPage,
    loadMore: () => void query.fetchNextPage(),
    loadingMore: query.isFetchingNextPage,
    loadMoreFailed: query.isFetchNextPageError,
  };
};
