import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, test } from "vitest";
import { AppRoutes } from "../routes";

const renderAt = (path: string) => {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MantineProvider forceColorScheme="dark">
        <MemoryRouter initialEntries={[path]}>
          <AppRoutes />
        </MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
};

describe("Shell", () => {
  test("renders the home page at /", () => {
    renderAt("/");

    expect(screen.getByRole("heading", { name: "Latest recordings" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Recording" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Queue" })).toBeInTheDocument();
  });

  test("renders the recordings page at /recordings", () => {
    renderAt("/recordings");

    expect(screen.getByRole("heading", { name: "Recordings" })).toBeInTheDocument();
  });

  test("renders the statistics page at /statistics, linked between Recordings and About", () => {
    renderAt("/statistics");

    expect(screen.getByRole("heading", { name: "Statistics" })).toBeInTheDocument();
    const labels = screen.getAllByRole("link").map((link) => link.textContent);
    expect(labels.indexOf("Statistics")).toBe(labels.indexOf("Recordings") + 1);
    expect(labels.indexOf("About")).toBe(labels.indexOf("Statistics") + 1);
  });

  test("renders the about page at /about", () => {
    renderAt("/about");

    expect(screen.getByRole("heading", { name: "About" })).toBeInTheDocument();
  });

  test("renders the not-found page for an unknown path", () => {
    renderAt("/nowhere");

    expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the home page" })).toHaveAttribute("href", "/");
  });

  test("credits the author in the footer, linked to their utbt.net profile", () => {
    renderAt("/");

    expect(screen.getByRole("link", { name: "kleps" })).toHaveAttribute(
      "href",
      "https://utbt.net/players/424992398657978370",
    );
    expect(screen.getByText("version dev")).toBeInTheDocument();
  });

  test("sets the favicon to the camera in the anchor color of the theme", () => {
    renderAt("/");

    const href = document.querySelector('link[rel="icon"]')?.getAttribute("href") ?? "";
    expect(href).toMatch(/^data:image\/svg\+xml,/);
    expect(decodeURIComponent(href)).toContain('fill="#4dabf7"');
  });

  test("opens external links in a new tab", () => {
    renderAt("/");

    for (const link of screen.getAllByRole("link", { name: /GitHub|Discord/ })) {
      expect(link).toHaveAttribute("target", "_blank");
      expect(link).toHaveAttribute("rel", "noopener noreferrer");
    }
  });
});
