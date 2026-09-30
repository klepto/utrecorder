import type { RecorderStage } from "./api/types";

const DURATION = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d+))?S)?$/;

/** Splits the ISO-8601 duration `value` into whole seconds and the fraction digits of its seconds. */
function splitDuration(value: string): { whole: number; fraction: string } {
  const match = DURATION.exec(value);
  if (!match) throw new Error(`Not an ISO-8601 duration: ${value}`);
  const [, hours = "0", minutes = "0", seconds = "0", fraction = ""] = match;
  return { whole: Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds), fraction };
}

/** Parses the ISO-8601 duration `value`, such as `PT1M2.345S`, into seconds. */
export function parseDuration(value: string): number {
  const { whole, fraction } = splitDuration(value);
  return whole + (fraction ? Number(`0.${fraction}`) : 0);
}

/**
 * Formats the ISO-8601 duration `value` as `m:ss.cc`, like the in-game timer. The seconds are truncated to two
 * decimals, never rounded, so `PT59.999S` reads `0:59.99` and a cap time never reads slower than the timer's.
 */
export function formatCapTime(value: string): string {
  const { whole, fraction } = splitDuration(value);
  const minutes = Math.floor(whole / 60);
  const seconds = String(whole % 60).padStart(2, "0");
  return `${minutes}:${seconds}.${`${fraction}00`.slice(0, 2)}`;
}

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400_000],
  ["hour", 3_600_000],
  ["minute", 60_000],
];

const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

/** Words the distance from `now` back to the ISO-8601 instant `value`, such as "3 hours ago". */
export function formatRelativeTime(value: string, now: number = Date.now()): string {
  const elapsed = now - Date.parse(value);
  for (const [unit, size] of UNITS) {
    if (Math.abs(elapsed) >= size) return relative.format(-Math.trunc(elapsed / size), unit);
  }
  return relative.format(0, "second");
}

/** Formats the ISO-8601 instant `value` as a local date and time, for a tooltip. */
export function formatExactTime(value: string): string {
  return new Date(value).toLocaleString();
}

const STAGES: Record<RecorderStage, string> = {
  FetchingDemo: "Downloading",
  PreparingInstallation: "Preparing",
  ResolvingPackages: "Resolving packages",
  Capturing: "Capturing",
  Muxing: "Muxing",
};

/** Labels the recording `stage`, or "Starting" for a job that has not reported one. */
export function formatStage(stage: RecorderStage | null): string {
  return stage ? STAGES[stage] : "Starting";
}
