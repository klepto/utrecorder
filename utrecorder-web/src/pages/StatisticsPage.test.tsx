import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test } from "vitest";
import type { Statistics } from "../api/types";
import { statistics, unresolvable } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { StatisticsPage } from "./StatisticsPage";

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MantineProvider forceColorScheme="dark">
        <StatisticsPage />
      </MantineProvider>
    </QueryClientProvider>,
  );
};

const serve = (body: Statistics) => {
  server.use(http.get("/api/statistics", () => HttpResponse.json(body)));
};

/** Matches the paragraph whose whole text is `content`, figures set apart in their own span included. */
const line = (content: string) => (_: string, element: Element | null) => {
  return element?.tagName === "P" && element.textContent === content;
};

const storagePanel = () => {
  return screen.getByRole("heading", { name: "Storage" }).closest(".mantine-Paper-root") as HTMLElement;
};

describe("StatisticsPage", () => {
  test("shows a loader while the first request runs", () => {
    renderPage();

    expect(screen.getByTestId("loading")).toBeInTheDocument();
  });

  test("opens with the time left to record the queue and the demos waiting", async () => {
    renderPage();

    expect(await screen.findByText("5 hours remaining")).toBeInTheDocument();
    expect(screen.getByText(line("148 waiting"))).toBeInTheDocument();
    expect(screen.getByText(line("1,284 recorded"))).toBeInTheDocument();
    expect(screen.getByText(line("5 missing packages"))).toBeInTheDocument();
    expect(screen.getByText(line("23 recent"))).toBeInTheDocument();
  });

  test("counts the recordings whose demo is unavailable among the failed ones", async () => {
    serve({ ...statistics, queue: { ...statistics.queue, failed: 3, unavailable: 2 } });
    renderPage();

    expect(await screen.findByText(line("5 failed"))).toBeInTheDocument();
  });

  test("shows the share of both parts of the bar on hover anywhere over it", async () => {
    renderPage();

    const recorded = await screen.findByRole("progressbar", { name: "Demos recorded" });
    fireEvent.mouseEnter(recorded.parentElement as HTMLElement);
    expect(await screen.findByText("89.7% recorded – 10.3% waiting")).toBeInTheDocument();
  });

  test("shows the time remaining in minutes under an hour", async () => {
    serve({ ...statistics, recorder: { ...statistics.recorder, pace: 0.25 } });
    renderPage();

    expect(await screen.findByText("31 minutes remaining")).toBeInTheDocument();
  });

  test("shows the queued run time when there is no pace", async () => {
    serve({ ...statistics, recorder: { ...statistics.recorder, pace: null } });
    renderPage();

    expect(await screen.findByText("2 hours of runs queued")).toBeInTheDocument();
    expect(screen.getByText("Not enough data")).toBeInTheDocument();
  });

  test("titles each panel", async () => {
    renderPage();

    await screen.findByText("5 hours remaining");
    for (const title of ["Statistics", "Recorder", "Storage", "Personal bests"]) {
      expect(screen.getByRole("heading", { name: title })).toBeInTheDocument();
    }
  });

  test("lists every day of recorded run time in the chart's table, today last", async () => {
    renderPage();

    const table = await screen.findByRole("table", { name: "Recorded run time per day" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(30);
    expect(rows[0]).toHaveTextContent("30 Aug 2026");
    expect(rows[14]).toHaveTextContent("13 Sept 20260 min");
    expect(rows[29]).toHaveTextContent("28 Sept 20267 min");
    expect(
      screen.getByRole("img", { name: "Recorded run time per day for the last 30 days, 7 min today" }),
    ).toBeInTheDocument();
  });

  test("shows the date and count of a day on hover over its column", async () => {
    renderPage();

    const chart = await screen.findByRole("img", { name: /^Recorded run time per day/ });
    const days = chart.querySelectorAll(":scope > g");
    expect(days).toHaveLength(30);
    fireEvent.mouseEnter(days[14]);
    expect(await screen.findByText("13 Sept 2026: 0 recordings")).toBeInTheDocument();
    fireEvent.mouseEnter(days[29]);
    expect(await screen.findByText("Today: 9 recordings")).toBeInTheDocument();
  });

  test("lists the demos missing a package while any is unresolvable", async () => {
    renderPage();

    expect(await screen.findByRole("heading", { name: "Missing packages" })).toBeInTheDocument();
    expect(await screen.findByText("BTSkins")).toBeInTheDocument();
  });

  test("hides the missing packages when no unresolvable demo has a known package", async () => {
    const unknown = unresolvable.filter((recording) => recording.missingPackage === null);
    server.use(http.get("/api/recordings", () => HttpResponse.json(unknown)));
    renderPage();

    await screen.findByText("5 hours remaining");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Missing packages" })).toBeNull());
    expect(screen.queryByText("No package is missing.")).toBeNull();
  });

  test("lists every day of personal bests in the chart's table", async () => {
    renderPage();

    const table = await screen.findByRole("table", { name: "Personal bests per day" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(30);
    expect(rows[29]).toHaveTextContent("28 Sept 202622");
  });

  test("notes the runs still being measured under the archive size", async () => {
    renderPage();

    expect(await screen.findByText("18.0 GB")).toBeInTheDocument();
    expect(screen.getByText("14 runs are still being measured.")).toBeInTheDocument();
  });

  test("shows the archive size as is when every run is measured", async () => {
    serve({ ...statistics, storage: { ...statistics.storage, archivedUnsized: 0 } });
    renderPage();

    expect(await screen.findByText("18.0 GB")).toBeInTheDocument();
    expect(screen.queryByText(/still being measured/)).toBeNull();
  });

  test("shows the hot cache against its limits", async () => {
    renderPage();

    expect(await screen.findByText("5.6 GB of 10.0 GB")).toBeInTheDocument();
    expect(screen.getByText("262 files")).toBeInTheDocument();
    expect(screen.getByText("target 5.0 GB")).toBeInTheDocument();
    expect(storagePanel().querySelectorAll("[aria-hidden='true'] > div")).toHaveLength(2);
    expect(screen.getByText(line("17 of 400 uploads in the last 24 hours"))).toBeInTheDocument();
  });

  test("shows the hot cache bytes alone when its limits are not known", async () => {
    serve({ ...statistics, storage: { ...statistics.storage, cacheMaxBytes: null, cacheTargetBytes: null } });
    renderPage();

    expect(await screen.findByText("5.6 GB")).toBeInTheDocument();
    expect(screen.getByText("262 files")).toBeInTheDocument();
    expect(screen.queryByText(/ of 10.0 GB$/)).toBeNull();
    expect(screen.queryByText(/^target /)).toBeNull();
    expect(storagePanel().querySelector("[aria-hidden='true']")).toBeNull();
  });

  test("shows the hot cache bytes alone when only its maximum is known", async () => {
    serve({ ...statistics, storage: { ...statistics.storage, cacheTargetBytes: null } });
    renderPage();

    expect(await screen.findByText("5.6 GB")).toBeInTheDocument();
    expect(screen.queryByText("5.6 GB of 10.0 GB")).toBeNull();
    expect(screen.queryByText(/^target /)).toBeNull();
    expect(storagePanel().querySelector("[aria-hidden='true']")).toBeNull();
  });

  test("shows the hot cache bytes alone when only its target is known", async () => {
    serve({ ...statistics, storage: { ...statistics.storage, cacheMaxBytes: null } });
    renderPage();

    expect(await screen.findByText("5.6 GB")).toBeInTheDocument();
    expect(screen.queryByText(/^target /)).toBeNull();
    expect(storagePanel().querySelector("[aria-hidden='true']")).toBeNull();
  });

  test("shows the hot cache as measuring before its first measurement", async () => {
    serve({ ...statistics, storage: { ...statistics.storage, cache: null } });
    renderPage();

    expect(await screen.findByText("Measuring…")).toBeInTheDocument();
  });

  test("shows zeros and stubs for empty data, never a blank panel", async () => {
    const empty = (days: Statistics["recorder"]["days"]) =>
      days.map((day) => ({ ...day, recordings: 0, capTime: "PT0S" }));
    serve({
      ...statistics,
      queue: {
        size: 0,
        recent: 0,
        backlog: 0,
        capTime: "PT0S",
        recording: 0,
        failed: 0,
        unresolvable: 0,
        unavailable: 0,
      },
      recorder: { ...statistics.recorder, days: empty(statistics.recorder.days) },
    });
    renderPage();

    expect(await screen.findByText("0 minutes remaining")).toBeInTheDocument();
    expect(screen.getByText(line("0 waiting"))).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Recorded run time per day" });
    expect(within(table).getAllByRole("row").slice(1)[29]).toHaveTextContent("28 Sept 20260 min");
  });

  test("explains that it retries when the first request fails", async () => {
    server.use(http.get("/api/statistics", () => new HttpResponse(null, { status: 500 })));
    renderPage();

    expect(await screen.findByText("Statistics could not be loaded. They retry every minute.")).toBeInTheDocument();
  });
});
