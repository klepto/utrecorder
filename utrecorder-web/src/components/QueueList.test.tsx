import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test, vi } from "vitest";
import { useQueue } from "../api/queries";
import type { Job } from "../api/types";
import { jobs, queue } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { QueueList } from "./QueueList";

const Connected = () => {
  return (
    <QueueList query={useQueue()} jobs={{ data: undefined, isPending: false, isError: false, dataUpdatedAt: 0 }} />
  );
};

/** Returns the queue with the jobs `running`, fetched `age` milliseconds ago, for the test to render. */
const listWith = (running: Job[] | undefined, age = 0) => {
  return (
    <MantineProvider forceColorScheme="dark">
      <QueueList
        query={{ data: queue, isPending: false, isError: false }}
        jobs={{ data: running, isPending: false, isError: false, dataUpdatedAt: Date.now() - age }}
      />
    </MantineProvider>
  );
};

const capturing = (progress: number): Job[] => {
  return [{ ...jobs[0], stage: "Capturing", progress }];
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

describe("QueueList", () => {
  test("lists the demos in queue order and labels only the first as up next", async () => {
    renderList();

    const rows = await screen.findAllByRole("row");
    expect(rows).toHaveLength(5);
    expect(screen.queryByRole("columnheader")).toBeNull();
    expect(rows[0]).toHaveTextContent("up next in ~1 minute");
    expect(rows[0]).toHaveTextContent("CTF-BT-Andromeda");
    expect(rows[0]).toHaveTextContent("Vex");
    expect(rows[0]).toHaveTextContent("2:10.500");
    expect(rows[0]).toHaveTextContent(/2[89] Sept 2026/);
    expect(rows[4]).toHaveTextContent("Nyx");
    expect(screen.getAllByText(/up next/)).toHaveLength(1);
    expect(document.querySelectorAll("img")).toHaveLength(5);
    expect(within(rows[0]).getByRole("img", { name: "Champion Medal" })).toBeInTheDocument();
  });

  test("shows the medal of the run of each recording", () => {
    const [first] = queue;
    const team = { ...first, demo: { ...first.demo, medal: 5 }, runMedal: 6 };
    render(
      <MantineProvider forceColorScheme="dark">
        <QueueList
          query={{ data: [team], isPending: false, isError: false }}
          jobs={{ data: undefined, isPending: false, isError: false, dataUpdatedAt: 0 }}
        />
      </MantineProvider>,
    );

    expect(screen.getByRole("img", { name: "World Record" })).toBeInTheDocument();
  });

  test("shows 1 minute when nothing is capturing, and still once the estimate has passed", () => {
    vi.useFakeTimers({ now: 0, toFake: ["Date", "setInterval", "clearInterval"] });
    render(listWith([{ ...jobs[0], stage: "FetchingDemo", progress: 0.5 }]));

    expect(screen.getByText("up next in ~1 minute")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(14_000));
    expect(screen.getByText("up next in ~1 minute")).toBeInTheDocument();
    vi.useRealTimers();
  });

  test("estimates from the capture speed plus 10 seconds whenever the capture progress changes", () => {
    vi.useFakeTimers({ now: 0, toFake: ["Date", "setInterval", "clearInterval"] });
    const { rerender } = render(listWith(capturing(0.25)));

    expect(screen.getByText("up next in …")).toBeInTheDocument();
    rerender(listWith(capturing(0.25)));
    expect(screen.getByText("up next in …")).toBeInTheDocument();
    vi.setSystemTime(100_000);
    rerender(listWith(capturing(0.5)));
    expect(screen.getByText("up next in ~4 minutes")).toBeInTheDocument();
    act(() => vi.advanceTimersByTime(60_000));
    expect(screen.getByText("up next in ~3 minutes")).toBeInTheDocument();
    rerender(listWith(capturing(0.7)));
    expect(screen.getByText("up next in ~2 minutes")).toBeInTheDocument();
    rerender(listWith(undefined));
    expect(screen.getByText("up next in ~1 minute")).toBeInTheDocument();
    vi.useRealTimers();
  });

  test("times the capture speed from when a cached capture was fetched, not from when the list appeared", () => {
    vi.useFakeTimers({ now: 100_000, toFake: ["Date", "setInterval", "clearInterval"] });
    const { rerender } = render(listWith(capturing(0.25), 100_000));

    vi.setSystemTime(102_000);
    rerender(listWith(capturing(0.5)));
    expect(screen.getByText("up next in ~4 minutes")).toBeInTheDocument();
    vi.useRealTimers();
  });

  test("opens the cap on utbt.net when a row is clicked", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    renderList();

    fireEvent.click((await screen.findAllByRole("row", { name: "CTF-BT-Andromeda on utbt.net" }))[0]);

    expect(open).toHaveBeenCalledWith("https://utbt.net/caps/1101", "_blank", "noopener,noreferrer");
    open.mockRestore();
  });

  test("links the player to their utbt.net profile without opening the cap", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    renderList();

    const link = await screen.findByRole("link", { name: "Kite" });
    fireEvent.click(link);

    expect(link).toHaveAttribute("href", "https://utbt.net/players/u1102");
    expect(open).not.toHaveBeenCalled();
    open.mockRestore();
  });

  test("follows only the player link on Enter, without opening the cap", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    renderList();

    fireEvent.keyDown(await screen.findByRole("link", { name: "Kite" }), { key: "Enter" });

    expect(open).not.toHaveBeenCalled();
    open.mockRestore();
  });

  test("shows skeleton rows while loading", () => {
    server.use(http.get("/api/queue", () => new Promise(() => {})));
    renderList();

    expect(screen.getByTestId("loading")).toBeInTheDocument();
  });

  test("shows an empty state", async () => {
    server.use(http.get("/api/queue", () => HttpResponse.json([])));
    renderList();

    expect(await screen.findByText("The queue is empty.")).toBeInTheDocument();
  });

  test("shows an alert when the query fails before any data", async () => {
    server.use(http.get("/api/queue", () => new HttpResponse(null, { status: 500 })));
    renderList();

    expect(await screen.findByText("Could not load")).toBeInTheDocument();
  });
});
