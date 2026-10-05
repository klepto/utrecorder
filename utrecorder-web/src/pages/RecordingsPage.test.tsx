import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { MemoryRouter } from "react-router";
import { describe, expect, test, vi } from "vitest";
import { recordings } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { RecordingsPage } from "./RecordingsPage";

const renderPage = (path = "/recordings") => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MantineProvider forceColorScheme="dark">
        <MemoryRouter initialEntries={[path]}>
          <RecordingsPage />
        </MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
};

const items = () => {
  return document.querySelectorAll("[aria-expanded]");
};

const firstPlayer = () => {
  return items()[0].textContent;
};

describe("RecordingsPage", () => {
  test("renders the first 10 recordings with a link to the older page", async () => {
    renderPage();

    await waitFor(() => expect(items()).toHaveLength(10));
    expect(screen.getByText("Page 1")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Older" })).toHaveAttribute("href", "/recordings?page=2");
    expect(screen.getByRole("button", { name: "Newer" })).toBeDisabled();
  });

  test("shows the page the search parameter names", async () => {
    renderPage("/recordings?page=3");

    await waitFor(() => expect(items()).toHaveLength(4));
    expect(screen.getByText("Page 3")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Newer" })).toHaveAttribute("href", "/recordings?page=2");
    expect(screen.getByRole("button", { name: "Older" })).toBeDisabled();
  });

  test("links page 2 back to the page without a search parameter", async () => {
    renderPage("/recordings?page=2");

    await waitFor(() => expect(items()).toHaveLength(10));
    expect(screen.getByRole("link", { name: "Newer" })).toHaveAttribute("href", "/recordings");
  });

  test("moves to the older page on click and scrolls to the top", async () => {
    const scrollTo = vi.spyOn(window, "scrollTo").mockImplementation(() => {});
    renderPage();
    await waitFor(() => expect(items()).toHaveLength(10));
    const first = firstPlayer();

    fireEvent.click(screen.getByRole("link", { name: "Older" }));

    expect(await screen.findByText("Page 2")).toBeInTheDocument();
    await waitFor(() => expect(firstPlayer()).not.toBe(first));
    expect(items()).toHaveLength(10);
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    scrollTo.mockRestore();
  });

  test("reads a page that is not a positive integer as the first", async () => {
    renderPage("/recordings?page=-2");

    await waitFor(() => expect(items()).toHaveLength(10));
    expect(screen.getByText("Page 1")).toBeInTheDocument();
  });

  test("hides the pages when the first page holds every recording", async () => {
    server.use(http.get("/api/recordings", () => HttpResponse.json(recordings.slice(0, 10))));
    renderPage();

    await waitFor(() => expect(items()).toHaveLength(10));
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  test("links back from a page past the last recording", async () => {
    renderPage("/recordings?page=9");

    expect(await screen.findByText("No recordings on this page.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Newer" })).toHaveAttribute("href", "/recordings?page=8");
  });

  test("shows an alert when the page fails", async () => {
    server.use(http.get("/api/recordings", () => new HttpResponse(null, { status: 500 })));
    renderPage();

    expect(await screen.findByText("Could not load")).toBeInTheDocument();
  });
});
