import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test } from "vitest";
import { recordings } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { RecordingsPage } from "./RecordingsPage";

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MantineProvider forceColorScheme="dark">
        <RecordingsPage />
      </MantineProvider>
    </QueryClientProvider>,
  );
}

function items() {
  return document.querySelectorAll("[aria-expanded]");
}

describe("RecordingsPage", () => {
  test("renders the first 20 recordings with a load more button", async () => {
    renderPage();

    await waitFor(() => expect(items()).toHaveLength(20));
    expect(screen.getByRole("button", { name: "Load more" })).toBeInTheDocument();
  });

  test("appends the next page and hides the button after a short page", async () => {
    renderPage();
    await waitFor(() => expect(items()).toHaveLength(20));

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    await waitFor(() => expect(items()).toHaveLength(recordings.length));
    expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
  });

  test("skips a recording whose capId is already shown", async () => {
    renderPage();
    await waitFor(() => expect(items()).toHaveLength(20));
    server.use(http.get("/api/recordings", () => HttpResponse.json([recordings[19], recordings[20]])));

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    await waitFor(() => expect(items()).toHaveLength(21));
  });

  test("shows an alert when the first page fails", async () => {
    server.use(http.get("/api/recordings", () => new HttpResponse(null, { status: 500 })));
    renderPage();

    expect(await screen.findByText("Could not load")).toBeInTheDocument();
  });

  test("keeps the shown recordings and shows an error when load more fails", async () => {
    renderPage();
    await waitFor(() => expect(items()).toHaveLength(20));
    server.use(http.get("/api/recordings", () => new HttpResponse(null, { status: 500 })));

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByText("Could not load more recordings.")).toBeInTheDocument();
    expect(items()).toHaveLength(20);
  });
});
