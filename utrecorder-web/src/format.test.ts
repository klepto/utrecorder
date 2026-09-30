import { describe, expect, test } from "vitest";
import { formatCapTime, formatExactTime, formatRelativeTime, formatStage, parseDuration } from "./format";

describe("format", () => {
  test("parseDuration reads hours, minutes and seconds", () => {
    expect(parseDuration("PT1M2.345S")).toBeCloseTo(62.345);
    expect(parseDuration("PT48.12S")).toBeCloseTo(48.12);
    expect(parseDuration("PT1H2M3S")).toBe(3723);
    expect(parseDuration("PT30S")).toBe(30);
  });

  test("parseDuration rejects a string that is not a duration", () => {
    expect(() => parseDuration("1:02")).toThrow();
  });

  test("formatCapTime truncates to two decimals", () => {
    expect(formatCapTime("PT1M2.345S")).toBe("1:02.34");
    expect(formatCapTime("PT59.999S")).toBe("0:59.99");
    expect(formatCapTime("PT48.12S")).toBe("0:48.12");
    expect(formatCapTime("PT3M0.01S")).toBe("3:00.01");
    expect(formatCapTime("PT1M30S")).toBe("1:30.00");
    expect(formatCapTime("PT41.7S")).toBe("0:41.70");
  });

  test("formatRelativeTime words the distance from now", () => {
    const now = Date.parse("2026-09-28T18:00:00Z");

    expect(formatRelativeTime("2026-09-28T17:59:40Z", now)).toBe("now");
    expect(formatRelativeTime("2026-09-28T17:55:00Z", now)).toBe("5 minutes ago");
    expect(formatRelativeTime("2026-09-28T15:00:00Z", now)).toBe("3 hours ago");
    expect(formatRelativeTime("2026-09-26T18:00:00Z", now)).toBe("2 days ago");
    expect(formatRelativeTime("2026-09-28T17:00:00Z", now)).toBe("1 hour ago");
  });

  test("formatExactTime gives a local date and time", () => {
    expect(formatExactTime("2026-09-28T18:00:00Z")).toMatch(/2026/);
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
