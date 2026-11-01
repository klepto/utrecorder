import { MantineProvider } from "@mantine/core";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, test } from "vitest";
import { AboutPage } from "./AboutPage";

const renderPage = () => {
  render(
    <MantineProvider forceColorScheme="dark">
      <MemoryRouter>
        <AboutPage />
      </MemoryRouter>
    </MantineProvider>,
  );
};

describe("AboutPage", () => {
  test("titles the page About, with its two parts below it", () => {
    renderPage();

    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("About");
    expect(screen.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent)).toEqual([
      "How the server works",
      "How a recording is made",
    ]);
  });

  test("lists the stages of a recording in order, each item starting with its stage label", () => {
    renderPage();

    const list = screen.getByRole("list");
    expect(list.tagName).toBe("OL");
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => item.textContent?.split(":")[0]),
    ).toEqual(["Downloading", "Preparing", "Resolving packages", "Capturing", "Muxing"]);
  });

  test("links the statistics page within the site", () => {
    renderPage();

    for (const link of screen.getAllByRole("link", { name: "Statistics page" })) {
      expect(link).toHaveAttribute("href", "/statistics");
      expect(link).not.toHaveAttribute("target");
    }
  });

  test("opens every external link in a new tab", () => {
    renderPage();

    const external = screen.getAllByRole("link").filter((link) => link.getAttribute("href")?.startsWith("http"));
    expect(external.map((link) => link.getAttribute("href"))).toEqual([
      "https://utbt.net",
      "https://archive.org",
      "https://discord.gg/FZ9DVkkARN",
      "https://github.com/klepto/utrecorder",
    ]);
    for (const link of external) {
      expect(link).toHaveAttribute("target", "_blank");
      expect(link).toHaveAttribute("rel", "noopener noreferrer");
    }
  });
});
