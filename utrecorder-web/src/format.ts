import type { RecorderStage } from "./api/types";

const DURATION = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d+))?S)?$/;

/** Splits the ISO-8601 duration `value` into whole seconds and the fraction digits of its seconds. */
const splitDuration = (value: string): { whole: number; fraction: string } => {
  const match = DURATION.exec(value);
  if (!match) throw new Error(`Not an ISO-8601 duration: ${value}`);
  const [, hours = "0", minutes = "0", seconds = "0", fraction = ""] = match;
  return { whole: Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds), fraction };
};

/**
 * Formats the ISO-8601 duration `value` as `m:ss.cc`, like the in-game timer. The seconds are truncated to two
 * decimals, never rounded, so `PT59.999S` reads `0:59.99` and a cap time never reads slower than the timer's.
 */
export const formatCapTime = (value: string): string => {
  const { whole, fraction } = splitDuration(value);
  const minutes = Math.floor(whole / 60);
  const seconds = String(whole % 60).padStart(2, "0");
  return `${minutes}:${seconds}.${`${fraction}00`.slice(0, 2)}`;
};

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400_000],
  ["hour", 3_600_000],
  ["minute", 60_000],
];

const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

/** Words the distance from `now` back to the ISO-8601 instant `value`, such as "3 hours ago". */
export const formatRelativeTime = (value: string, now: number = Date.now()): string => {
  const elapsed = now - Date.parse(value);
  for (const [unit, size] of UNITS) {
    if (Math.abs(elapsed) >= size) return relative.format(-Math.trunc(elapsed / size), unit);
  }
  return relative.format(0, "second");
};

/** Formats the ISO-8601 instant `value` as a local date and time, such as `30/09/2026, 15:23:53`, for a tooltip. */
export const formatExactTime = (value: string): string => {
  return new Date(value).toLocaleString("en-GB");
};

/**
 * Formats the ISO-8601 instant `value` as a date, such as `30 Sept 2026`, in the browser's time zone. If `timeZone` is
 * provided, uses that time zone instead.
 */
export const formatDate = (value: string, timeZone?: string): string => {
  return new Date(value).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone });
};

/**
 * Words the time left from `now` until the epoch millisecond `deadline` in whole minutes, rounded, such as "2 minutes".
 * It never reads less than "1 minute", even once the deadline has passed.
 */
export const formatRemaining = (deadline: number, now: number = Date.now()): string => {
  const minutes = Math.max(1, Math.round((deadline - now) / 60_000));
  return minutes === 1 ? "1 minute" : `${minutes} minutes`;
};

const STAGES: Record<RecorderStage, string> = {
  FetchingDemo: "Downloading",
  PreparingInstallation: "Preparing",
  ResolvingPackages: "Resolving packages",
  Capturing: "Capturing",
  Muxing: "Muxing",
};

/** Labels the recording `stage`, or "Starting" for a job that has not reported one. */
export const formatStage = (stage: RecorderStage | null): string => {
  return stage ? STAGES[stage] : "Starting";
};
