import { keepPreviousData, type QueryKey, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import {
  fetchJobs,
  fetchQueue,
  fetchQueueHead,
  fetchRecordings,
  fetchStatistics,
  fetchUnresolvable,
  type RecordingsPage,
} from "./client";

/** The time between two fetches of the running jobs, in milliseconds. */
export const JOBS_INTERVAL = 2_000;
const QUEUE_INTERVAL = 10_000;
const RECORDINGS_INTERVAL = 30_000;
const STATISTICS_INTERVAL = 60_000;

/** The key of every query. Each recordings key starts with `recordings`. */
const QUERY_KEYS = {
  jobs: ["jobs"],
  queue: ["queue"],
  queueHead: ["queueHead"],
  statistics: ["statistics"],
  unresolvable: ["unresolvable"],
  recordings: ["recordings"],
  recordingsPage: (page: RecordingsPage): QueryKey => [...QUERY_KEYS.recordings, page],
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
    const current = new Set(data.map((job) => job.recording.demo.capId));
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

/** Returns the head of the queue with the earliest time it may start, polled every 10 s. */
export const useQueueHead = () => {
  return useQuery({ queryKey: QUERY_KEYS.queueHead, queryFn: fetchQueueHead, refetchInterval: QUEUE_INTERVAL });
};

/** Returns the aggregate statistics, polled every 60 s. */
export const useStatistics = () => {
  return useQuery({ queryKey: QUERY_KEYS.statistics, queryFn: fetchStatistics, refetchInterval: STATISTICS_INTERVAL });
};

/** Returns the recordings whose demo names a package no source holds, polled every 60 s. */
export const useUnresolvable = () => {
  return useQuery({
    queryKey: QUERY_KEYS.unresolvable,
    queryFn: fetchUnresolvable,
    refetchInterval: STATISTICS_INTERVAL,
  });
};

/**
 * Returns one page of the finished recordings, newest first, polled every 30 s. While another page loads, keeps the
 * data of the page before it and sets `isPlaceholderData`.
 */
export const useRecordings = ({ limit, offset }: RecordingsPage) => {
  return useQuery({
    queryKey: QUERY_KEYS.recordingsPage({ limit, offset }),
    queryFn: () => fetchRecordings({ limit, offset }),
    refetchInterval: RECORDINGS_INTERVAL,
    placeholderData: keepPreviousData,
  });
};
