import { useEffect, useRef, useState } from "react";

/** The time the shown progress takes to close most of its gap to the estimated progress, in milliseconds. */
const CATCH_UP = 1_000;

/** The time the shown progress takes to fill from `0` to the estimated progress when it first appears, in milliseconds. */
const FILL = 600;

/** The longest time one animation frame moves the shown progress on by, in milliseconds. */
const MAX_FRAME = 100;

/** How many poll intervals the estimated progress keeps rising after a report. */
const EXTRAPOLATED_INTERVALS = 1.5;

/**
 * Returns the speed at which the shown progress follows the estimated progress, `gap` ahead of it, when the estimate
 * rises at `rate`. Behind the estimate, the speed rises above `rate` to catch up. Ahead of it, the speed falls towards
 * zero the further ahead the shown progress is, but never below zero, so the shown progress never moves back.
 */
const followSpeed = (gap: number, rate: number) => {
  if (gap >= 0) return rate + gap / CATCH_UP;
  if (rate === 0) return 0;
  return rate * Math.exp(gap / (rate * CATCH_UP));
};

/** Returns the part of the first fill done `elapsed` milliseconds after it began, from `0` to `1`, slowing towards the end. */
const fillFraction = (elapsed: number) => {
  return 1 - (1 - Math.min(1, elapsed / FILL)) ** 3;
};

/** Returns the `performance.now()` time of the epoch millisecond `epoch`. */
const performanceTime = (epoch: number) => {
  return performance.now() - (Date.now() - epoch);
};

/**
 * Returns an estimate of `value`, reported at the epoch millisecond `reportedAt`, that moves on every animation frame.
 * The estimate continues from the last reported `value` at the rate the reports rose at, for at most
 * `EXTRAPOLATED_INTERVALS` times `interval` milliseconds after that report. The returned value follows the estimate with
 * a speed that changes smoothly, so it never jumps, and it only ever rises, up to `1`. When the estimate falls below it,
 * it slows down instead of moving back. It starts at `0` and fills to the estimate within `FILL` milliseconds.
 */
export const useExtrapolated = (value: number, reportedAt: number, interval: number) => {
  const [shown, setShown] = useState(0);
  const report = useRef({ value, time: performanceTime(reportedAt), rate: 0 });
  const current = useRef(0);

  useEffect(() => {
    const last = report.current;
    if (value === last.value) return;
    const time = performanceTime(reportedAt);
    const sample = Math.max(0, (value - last.value) / Math.max(1, time - last.time));
    const rate = last.rate === 0 ? sample : (last.rate + sample) / 2;
    report.current = { value, time, rate };
  }, [value, reportedAt]);

  useEffect(() => {
    const start = performance.now();
    let previous = start;
    let filled = false;
    let frame = 0;
    const step = (now: number) => {
      const { value, time, rate } = report.current;
      const estimate = Math.min(1, value + rate * Math.min(now - time, interval * EXTRAPOLATED_INTERVALS));
      const gap = estimate - current.current;
      const fill = fillFraction(now - start);
      const followed = current.current + followSpeed(gap, rate) * Math.min(now - previous, MAX_FRAME);
      const next = filled ? followed : Math.max(current.current, estimate * fill);
      filled = fill === 1;
      current.current = Math.min(1, gap >= 0 ? Math.min(estimate, next) : next);
      previous = now;
      setShown(current.current);
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [interval]);

  return shown;
};
