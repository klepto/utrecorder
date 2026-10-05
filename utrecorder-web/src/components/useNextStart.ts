import { useState } from "react";
import type { Job } from "../api/types";

/** The time the recorder takes to start the next demo after a capture ends, or when none runs, in milliseconds. */
const START_DELAY = 10_000;

/** The estimated start of the next demo, and what it was estimated from. */
interface Estimate {
  /** The `capId` of the next demo. */
  next: string;
  /** The capture the estimate follows, as `capId#attempt`, or `null` if none runs. */
  capture: string | null;
  /** The last reported progress of `capture`, `0` if none runs. */
  progress: number;
  /** The progress `capture` had when first seen. */
  startProgress: number;
  /** The time the jobs that first showed `capture` were fetched, in epoch milliseconds. */
  startTime: number;
  /** The estimated start of the next demo, in epoch milliseconds, or `null` while the speed of `capture` is unknown. */
  deadline: number | null;
}

/**
 * Returns the estimate after `last`, for the next demo `next`, while `capture` runs at `progress` in the jobs fetched at
 * the epoch millisecond `now`. Without a capture, the deadline is `START_DELAY` after the capture ended or `next`
 * changed. With one, it is `START_DELAY` after the end of the capture, extrapolated at the average speed since the
 * capture was first seen, and it only changes when the progress does. Until the progress has risen since the capture was first seen, the deadline is `null`.
 */
const reestimate = (last: Estimate | null, next: string, capture: string | null, progress: number, now: number) => {
  if (last === null || last.next !== next || last.capture !== capture) {
    const deadline = capture === null ? now + START_DELAY : null;
    return { next, capture, progress, startProgress: progress, startTime: now, deadline };
  }
  const speed = (progress - last.startProgress) / (now - last.startTime);
  if (!(speed > 0)) return { ...last, progress };
  return { ...last, progress, deadline: now + (1 - progress) / speed + START_DELAY };
};

/**
 * Returns the estimated start of the next demo `next`, in epoch milliseconds, from the capture among `jobs`, fetched at
 * the epoch millisecond `jobsUpdatedAt`, or `null` if nothing is queued or the capture speed is not known yet. The
 * estimate is taken again whenever the capture appears, ends or reports new progress.
 */
export const useNextStart = (next: string | undefined, jobs: readonly Job[] | undefined, jobsUpdatedAt: number) => {
  const job = jobs?.find((job) => job.stage === "Capturing" && job.progress !== null);
  const capture = job ? `${job.recording.demo.capId}#${job.attempt}` : null;
  const progress = job?.progress ?? 0;
  const [estimate, setEstimate] = useState<Estimate | null>(null);
  if (
    next !== undefined &&
    (estimate === null || estimate.next !== next || estimate.capture !== capture || estimate.progress !== progress)
  ) {
    setEstimate(reestimate(estimate, next, capture, progress, jobsUpdatedAt));
  }
  return next === undefined ? null : (estimate?.deadline ?? null);
};
