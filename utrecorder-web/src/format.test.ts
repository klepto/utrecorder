import { describe, expect, test } from "vitest";
import {
  formatBytes,
  formatCapTime,
  formatCount,
  formatDate,
  formatElapsed,
  formatExactTime,
  formatHours,
  formatPace,
  formatRelativeTime,
  formatRemaining,
  formatStage,
} from "./format";

describe("statistics formatters", () => {
  test("formatBytes reads gigabytes with one decimal from 1 GiB, megabytes below", () => {
    expect(formatBytes(1_073_741_824)).toBe("1.0 GB");
    expect(formatBytes(1_073_741_823)).toBe("1024 MB");
    expect(formatBytes(5_368_709_120)).toBe("5.0 GB");
    expect(formatBytes(1_610_612_736)).toBe("1.5 GB");
    expect(formatBytes(52_428_800)).toBe("50 MB");
    expect(formatBytes(0)).toBe("0 MB");
  });

  test("formatHours reads hours and minutes, minutes under an hour, and zero", () => {
    expect(formatHours("PT2H30M")).toBe("2 h 30 min");
    expect(formatHours("PT1H")).toBe("1 h 0 min");
    expect(formatHours("PT100H5M")).toBe("100 h 5 min");
    expect(formatHours("PT45M")).toBe("45 min");
    expect(formatHours("PT59M59.9S")).toBe("59 min");
    expect(formatHours("PT30S")).toBe("0 min");
    expect(formatHours("PT0S")).toBe("0 min");
  });

  test("formatElapsed reads minutes and seconds, seconds under a minute, and drops the fraction", () => {
    expect(formatElapsed("PT1M31.5S")).toBe("1 min 31 s");
    expect(formatElapsed("PT2H")).toBe("120 min 0 s");
    expect(formatElapsed("PT45.9S")).toBe("45 s");
    expect(formatElapsed("PT0S")).toBe("0 s");
  });

  test("formatPace reads one decimal and a missing pace as not enough data", () => {
    expect(formatPace(2.5)).toBe("2.5×");
    expect(formatPace(1)).toBe("1.0×");
    expect(formatPace(0.04)).toBe("0.0×");
    expect(formatPace(null)).toBe("Not enough data");
  });

  test("formatCount groups thousands", () => {
    expect(formatCount(1284)).toBe("1,284");
    expect(formatCount(1_234_567)).toBe("1,234,567");
    expect(formatCount(7)).toBe("7");
    expect(formatCount(0)).toBe("0");
  });
});

describe("format", () => {
  test("formatCapTime truncates to three decimals", () => {
    expect(formatCapTime("PT1M2.3456S")).toBe("1:02.345");
    expect(formatCapTime("PT3M0.01S")).toBe("3:00.010");
    expect(formatCapTime("PT1M30S")).toBe("1:30.000");
  });

  test("formatCapTime omits the minutes under a minute", () => {
    expect(formatCapTime("PT59.9999S")).toBe("59.999");
    expect(formatCapTime("PT48.12S")).toBe("48.120");
    expect(formatCapTime("PT5.5S")).toBe("5.500");
    expect(formatCapTime("PT1M")).toBe("1:00.000");
  });

  test("formatCapTime rejects a string that is not a duration", () => {
    expect(() => formatCapTime("1:02")).toThrow();
  });

  test("formatRelativeTime words the distance from now", () => {
    const now = Date.parse("2026-09-28T18:00:00Z");

    expect(formatRelativeTime("2026-09-28T17:59:40Z", now)).toBe("now");
    expect(formatRelativeTime("2026-09-28T17:55:00Z", now)).toBe("5 minutes ago");
    expect(formatRelativeTime("2026-09-28T15:00:00Z", now)).toBe("3 hours ago");
    expect(formatRelativeTime("2026-09-26T18:00:00Z", now)).toBe("2 days ago");
    expect(formatRelativeTime("2026-09-28T17:00:00Z", now)).toBe("1 hour ago");
  });

  test("formatExactTime gives a British local date and time", () => {
    expect(formatExactTime("2026-09-28T12:00:00Z")).toMatch(/^28\/09\/2026, \d{2}:00:00$/);
  });

  test("formatDate gives day, short month and year", () => {
    expect(formatDate("2026-09-30T13:39:12Z", "UTC")).toBe("30 Sept 2026");
    expect(formatDate("2026-01-05T00:07:00Z", "UTC")).toBe("5 Jan 2026");
    expect(formatDate("2026-01-04T23:07:00Z", "Europe/Vilnius")).toBe("5 Jan 2026");
  });

  test("formatRemaining counts rounded minutes and never less than 1 minute", () => {
    expect(formatRemaining(150_000, 0)).toBe("3 minutes");
    expect(formatRemaining(90_000, 0)).toBe("2 minutes");
    expect(formatRemaining(60_000, 0)).toBe("1 minute");
    expect(formatRemaining(10_000, 0)).toBe("1 minute");
    expect(formatRemaining(0, 0)).toBe("1 minute");
    expect(formatRemaining(0, 5_000)).toBe("1 minute");
  });

  test("formatStage labels each stage, and Starting for none", () => {
    expect(formatStage("FetchingDemo")).toBe("Downloading");
    expect(formatStage("PreparingInstallation")).toBe("Preparing");
    expect(formatStage("ResolvingPackages")).toBe("Resolving packages");
    expect(formatStage("Capturing")).toBe("Capturing");
    expect(formatStage("Muxing")).toBe("Muxing");
    expect(formatStage(null)).toBe("Starting");
  });
});
