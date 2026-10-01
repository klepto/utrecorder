import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test, vi } from "vitest";
import { useJobs } from "../api/queries";
import type { Job } from "../api/types";
import { jobs } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { JobList } from "./JobList";

const Connected = () => {
  return <JobList query={useJobs()} />;
};

const renderList = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MantineProvider forceColorScheme="dark">
        <Connected />
      </MantineProvider>
    </QueryClientProvider>,
  );
};

/**
 * Renders one capturing job at `progress`, fetched `age` milliseconds ago, and returns a function that reports the next
 * progress of that job, fetched now.
 */
const renderProgress = (progress: number, age = 0) => {
  const list = (value: number, dataUpdatedAt: number) => {
    const job: Job = { ...jobs[1], progress: value };
    return (
      <MantineProvider forceColorScheme="dark">
        <JobList query={{ data: [job], isPending: false, isError: false, dataUpdatedAt }} />
      </MantineProvider>
    );
  };
  const { rerender } = render(list(progress, Date.now() - age));
  return (value: number) => rerender(list(value, Date.now()));
};

/** Advances the clock by `milliseconds`, and returns the percentage the stage progress bar shows afterwards. */
const advance = (milliseconds: number) => {
  act(() => vi.advanceTimersByTime(milliseconds));
  return Number(screen.getByRole("progressbar", { name: "Stage progress" }).getAttribute("aria-valuenow"));
};

describe("JobList", () => {
  test("shows every job with its stage, and the attempt only above 1", async () => {
    renderList();

    expect(await screen.findByText("CTF-BT-Maverick")).toBeInTheDocument();
    expect(screen.getByText("CTF-BT-Skyfall")).toBeInTheDocument();
    expect(screen.getByText("1:02.345")).toBeInTheDocument();
    expect(screen.getByText("48.120")).toBeInTheDocument();
    expect(screen.getAllByText(/^2[89] Sept 2026$/)).toHaveLength(2);
    expect(screen.getByText("Starting")).toBeInTheDocument();
    expect(screen.getByText(/^Capturing - \d+%$/)).toBeInTheDocument();
    expect(screen.getByText("Attempt 2")).toBeInTheDocument();
    expect(screen.queryByText("Attempt 1")).not.toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Progress unknown" })).toBeInTheDocument();
  });

  test("fills the bar quickly from empty to the reported progress when it appears", () => {
    vi.useFakeTimers({ toFake: ["requestAnimationFrame", "cancelAnimationFrame", "performance"] });
    renderProgress(0.42);

    const start = advance(0);
    const filling = advance(200);
    const filled = advance(500);

    expect(start).toBe(0);
    expect(filling).toBeGreaterThan(0);
    expect(filling).toBeLessThan(42);
    expect(filled).toBe(42);
    expect(screen.getByText("Capturing - 42%")).toBeInTheDocument();
    vi.useRealTimers();
  });

  test("moves the bar on between two polls at the rate the progress rose at", () => {
    vi.useFakeTimers({ toFake: ["requestAnimationFrame", "cancelAnimationFrame", "performance"] });
    const report = renderProgress(0.2);

    advance(2_000);
    report(0.4);
    const reported = advance(16);
    const halfway = advance(1_000);
    const later = advance(1_000);

    expect(reported).toBeGreaterThan(20);
    expect(halfway).toBeGreaterThan(reported);
    expect(later).toBeGreaterThan(halfway);
    expect(later).toBeGreaterThan(40);
    vi.useRealTimers();
  });

  test("never moves the bar back when a poll reports less than it shows", () => {
    vi.useFakeTimers({ toFake: ["requestAnimationFrame", "cancelAnimationFrame", "performance"] });
    const report = renderProgress(0.2);
    advance(2_000);
    report(0.4);
    const ahead = advance(2_900);

    report(0.3);
    const shown = [16, 16, 500, 1_000, 2_000].map(advance);

    expect(shown[0]).toBeGreaterThanOrEqual(ahead);
    for (const [index, value] of shown.slice(1).entries()) expect(value).toBeGreaterThanOrEqual(shown[index]);
    vi.useRealTimers();
  });

  test("does not move the bar far ahead on the first frame after the tab was hidden", () => {
    vi.useFakeTimers({ toFake: ["requestAnimationFrame", "cancelAnimationFrame", "performance"] });
    const report = renderProgress(0.2);
    advance(2_000);
    report(0.4);
    advance(2_900);
    report(0.3);
    const before = advance(16);

    let paused: FrameRequestCallback = () => {};
    const frames = vi.spyOn(window, "requestAnimationFrame").mockImplementation((step) => {
      paused = step;
      return 0;
    });
    act(() => vi.advanceTimersByTime(60_016));
    frames.mockRestore();
    requestAnimationFrame(paused);
    const after = advance(16);

    expect(after - before).toBeLessThan(1);
    vi.useRealTimers();
  });

  test("rises at the rate since a cached report was fetched, not since the bar appeared", () => {
    vi.useFakeTimers({ toFake: ["requestAnimationFrame", "cancelAnimationFrame", "performance", "Date"] });
    const report = renderProgress(0.2, 18_000);
    advance(2_000);
    report(0.4);

    expect(advance(3_000)).toBeLessThan(45);
    vi.useRealTimers();
  });

  test("links the player to their utbt.net profile", async () => {
    renderList();

    expect(await screen.findByRole("link", { name: "Shadow" })).toHaveAttribute(
      "href",
      "https://utbt.net/players/u1100",
    );
  });

  test("opens the cap on utbt.net when the job is clicked", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    renderList();

    fireEvent.click(await screen.findByRole("link", { name: "CTF-BT-Maverick on utbt.net" }));

    expect(open).toHaveBeenCalledWith("https://utbt.net/caps/1100", "_blank", "noopener,noreferrer");
    open.mockRestore();
  });

  test("does not open the cap when the player is clicked", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    renderList();

    fireEvent.click(await screen.findByRole("link", { name: "Shadow" }));

    expect(open).not.toHaveBeenCalled();
    open.mockRestore();
  });

  test("shows skeleton rows while loading", () => {
    server.use(http.get("/api/jobs", () => new Promise(() => {})));
    renderList();

    expect(screen.getByTestId("loading")).toBeInTheDocument();
  });

  test("shows an empty state", async () => {
    server.use(http.get("/api/jobs", () => HttpResponse.json([])));
    renderList();

    expect(await screen.findByText("Nothing is recording.")).toBeInTheDocument();
  });

  test("shows an alert when the query fails before any data", async () => {
    server.use(http.get("/api/jobs", () => new HttpResponse(null, { status: 500 })));
    renderList();

    expect(await screen.findByText("Could not load")).toBeInTheDocument();
  });
});
