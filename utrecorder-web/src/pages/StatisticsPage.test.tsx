import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test } from "vitest";
import type { Statistics } from "../api/types";
import { statistics } from "../mocks/fixtures";
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

describe("StatisticsPage", () => {
  test("shows a loader while the first request runs", () => {
    renderPage();

    expect(screen.getByTestId("loading")).toBeInTheDocument();
  });

  test("opens with the time left to record the queue and the demos waiting", async () => {
    renderPage();

    expect(await screen.findByText("5 h 10 min remaining")).toBeInTheDocument();
    expect(screen.getByText(line("148 demos waiting"))).toBeInTheDocument();
    expect(screen.getByText(line("31 recent"))).toBeInTheDocument();
    expect(screen.getByText(line("117 backlog"))).toBeInTheDocument();
    expect(screen.getByText(line("5 missing packages"))).toBeInTheDocument();
  });

  test("shows the queued run time when there is no pace", async () => {
    serve({ ...statistics, recorder: { ...statistics.recorder, pace: null } });
    renderPage();

    expect(await screen.findByText("2 h 4 min of runs queued")).toBeInTheDocument();
    expect(screen.getByText("Not enough data")).toBeInTheDocument();
  });

  test("titles each panel", async () => {
    renderPage();

    await screen.findByText("5 h 10 min remaining");
    for (const title of ["Statistics", "Queue", "Recorder", "Storage", "Personal bests"]) {
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
    expect(screen.getByText(line("17 of 400 uploads in the last 24 hours"))).toBeInTheDocument();
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
      queue: { size: 0, recent: 0, backlog: 0, capTime: "PT0S", recording: 0, failed: 0, unresolvable: 0 },
      recorder: { ...statistics.recorder, days: empty(statistics.recorder.days) },
    });
    renderPage();

    expect(await screen.findByText("0 min remaining")).toBeInTheDocument();
    expect(screen.getByText(line("0 demos waiting"))).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Recorded run time per day" });
    expect(within(table).getAllByRole("row").slice(1)[29]).toHaveTextContent("28 Sept 20260 min");
  });

  test("explains that it retries when the first request fails", async () => {
    server.use(http.get("/api/statistics", () => new HttpResponse(null, { status: 500 })));
    renderPage();

    expect(await screen.findByText("Statistics could not be loaded. They retry every minute.")).toBeInTheDocument();
  });
});
