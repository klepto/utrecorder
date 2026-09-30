import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test } from "vitest";
import { useQueue } from "../api/queries";
import { server } from "../mocks/server";
import { QueueList } from "./QueueList";

function Connected() {
  return <QueueList query={useQueue()} />;
}

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MantineProvider forceColorScheme="dark">
        <Connected />
      </MantineProvider>
    </QueryClientProvider>,
  );
}

describe("QueueList", () => {
  test("numbers the demos in queue order from 1", async () => {
    renderList();

    const items = await screen.findAllByRole("listitem");
    expect(items).toHaveLength(5);
    expect(items[0]).toHaveTextContent("Vex");
    expect(items[0]).toHaveTextContent("2:10.50");
    expect(items[4]).toHaveTextContent("Nyx");
    expect(screen.getByRole("list").tagName).toBe("OL");
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
