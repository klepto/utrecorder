import { describe, expect, test } from "vitest";
import { formatCapTime, formatDate, formatExactTime, formatRelativeTime, formatRemaining, formatStage } from "./format";

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
