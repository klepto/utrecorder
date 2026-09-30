import { MantineProvider } from "@mantine/core";
import { render, screen } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import type { Demo } from "../api/types";
import { RecordTime } from "./RecordTime";

const demo: Demo = {
  capId: "1",
  map: "CTF-BT-Maverick",
  player: "Shadow",
  userId: null,
  capTime: "PT1M2.345S",
  timestamp: "2026-09-28T18:04:11Z",
  demoUrl: "https://example.com/demos/1.dem",
};

const renderTime = (value: Demo) => {
  render(
    <MantineProvider forceColorScheme="dark">
      <RecordTime demo={value} />
    </MantineProvider>,
  );
};

describe("RecordTime", () => {
  test("shows the cap time after the medal the demo names", () => {
    renderTime({ ...demo, medal: 6 });
    expect(screen.getByRole("img", { name: "World Record" })).toBeInTheDocument();
    expect(screen.getByText("1:02.34")).toBeInTheDocument();
  });

  test("shows the casual medal for a demo without a medal", () => {
    renderTime(demo);
    expect(screen.getByRole("img", { name: "Casual" })).toBeInTheDocument();
  });

  test("shows the casual medal for a medal it does not know", () => {
    renderTime({ ...demo, medal: 9 });
    expect(screen.getByRole("img", { name: "Casual" })).toBeInTheDocument();
  });
});
