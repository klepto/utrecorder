import { MantineProvider } from "@mantine/core";
import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import { Placeholder } from "./Placeholder";

test("renders the site name", () => {
  render(
    <MantineProvider>
      <Placeholder />
    </MantineProvider>,
  );

  expect(screen.getByRole("heading", { name: "utrecorder" })).toBeInTheDocument();
});
