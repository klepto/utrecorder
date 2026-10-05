import type { RecorderStage } from "./api/types";

const DURATION = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d+))?S)?$/;

/** Splits the ISO-8601 duration `value` into whole seconds and the fraction digits of its seconds. */
const splitDuration = (value: string): { whole: number; fraction: string } => {
  const match = DURATION.exec(value);
  if (!match) throw new Error(`Not an ISO-8601 duration: ${value}`);
  const [, hours = "0", minutes = "0", seconds = "0", fraction = ""] = match;
  return { whole: Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds), fraction };
};

/** Returns the ISO-8601 duration `value` in whole seconds, its fraction dropped. */
export const durationSeconds = (value: string): number => {
  return splitDuration(value).whole;
};

/**
 * Formats the ISO-8601 duration `value` as `m:ss.ccc`, or as `s.ccc` under a minute. The seconds are truncated to three
 * decimals, never rounded, so `PT59.9999S` reads `59.999`.
 */
export const formatCapTime = (value: string): string => {
  const { whole, fraction } = splitDuration(value);
  const decimals = `${fraction}000`.slice(0, 3);
  if (whole < 60) return `${whole}.${decimals}`;
  const minutes = Math.floor(whole / 60);
  const seconds = String(whole % 60).padStart(2, "0");
  return `${minutes}:${seconds}.${decimals}`;
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

const GIB = 1024 ** 3;
const MIB = 1024 ** 2;

/** Formats `bytes` as `GB` with one decimal from 1 GiB, as whole `MB` below. Units are 1024-based, as the cache limits are. */
export const formatBytes = (bytes: number): string => {
  if (bytes >= GIB) return `${(bytes / GIB).toFixed(1)} GB`;
  return `${Math.round(bytes / MIB)} MB`;
};

/** Formats the ISO-8601 duration `value` as `2 h 30 min`, or `45 min` under an hour. Seconds are dropped. */
export const formatHours = (value: string): string => {
  const minutes = Math.floor(splitDuration(value).whole / 60);
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
};

/** Formats the ISO-8601 duration `value` as `1 min 31 s`, or `45 s` under a minute. The fraction of a second is dropped. */
export const formatElapsed = (value: string): string => {
  const seconds = splitDuration(value).whole;
  if (seconds < 60) return `${seconds} s`;
  return `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
};

/** Formats the recording `pace` as `2.5×`, or "Not enough data" when it is `null`. */
export const formatPace = (pace: number | null): string => {
  return pace === null ? "Not enough data" : `${pace.toFixed(1)}×`;
};

const counts = new Intl.NumberFormat("en");

/** Formats the whole number `n` with grouped thousands, such as `1,284`. */
export const formatCount = (n: number): string => counts.format(n);
