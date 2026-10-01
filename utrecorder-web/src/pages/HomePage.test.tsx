import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { MemoryRouter } from "react-router";
import { describe, expect, test } from "vitest";
import { recordings } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { HomePage } from "./HomePage";

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MantineProvider forceColorScheme="dark">
        <MemoryRouter>
          <HomePage />
        </MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
};

const latest = () => {
  return document.querySelectorAll("[aria-expanded]");
};

describe("HomePage", () => {
  test("renders the latest 6 recordings with a show more link", async () => {
    renderPage();

    await waitFor(() => expect(latest()).toHaveLength(6));
    expect(screen.getByRole("link", { name: "Show more" })).toBeInTheDocument();
  });

  test("hides the show more link when the latest recordings are the only ones", async () => {
    server.use(http.get("/api/recordings", () => HttpResponse.json(recordings.slice(0, 6))));
    renderPage();

    await waitFor(() => expect(latest()).toHaveLength(6));
    expect(screen.queryByRole("link", { name: "Show more" })).toBeNull();
  });
});
