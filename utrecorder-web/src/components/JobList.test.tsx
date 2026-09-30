import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test } from "vitest";
import { useJobs } from "../api/queries";
import { server } from "../mocks/server";
import { JobList } from "./JobList";

function Connected() {
  return <JobList query={useJobs()} />;
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

describe("JobList", () => {
  test("shows every job with its stage, and the attempt only above 1", async () => {
    renderList();

    expect(await screen.findByText("Shadow")).toBeInTheDocument();
    expect(screen.getByText("Nyx")).toBeInTheDocument();
    expect(screen.getByText("1:02.34")).toBeInTheDocument();
    expect(screen.getByText("0:48.12")).toBeInTheDocument();
    expect(screen.getByText("Starting")).toBeInTheDocument();
    expect(screen.getByText("Capturing")).toBeInTheDocument();
    expect(screen.getByText("Attempt 2")).toBeInTheDocument();
    expect(screen.queryByText("Attempt 1")).not.toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "Stage progress" })).toHaveAttribute("aria-valuenow", "42");
    expect(screen.getByRole("progressbar", { name: "Progress unknown" })).toBeInTheDocument();
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
