import { MantineProvider } from "@mantine/core";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import type { Recording } from "../api/types";
import { unresolvable } from "../mocks/fixtures";
import { MissingPackageList } from "./MissingPackageList";

const renderList = (recordings: Recording[]) => {
  render(
    <MantineProvider forceColorScheme="dark">
      <MissingPackageList query={{ data: recordings, isPending: false, isError: false }} />
    </MantineProvider>,
  );
};

const bodyRows = () => screen.getAllByRole("row").slice(1);

describe("MissingPackageList", () => {
  test("heads the columns package, GUID, map and cap", () => {
    renderList(unresolvable);

    const headers = screen.getAllByRole("columnheader").map((header) => header.textContent);
    expect(headers).toEqual(["Package", "GUID", "Map", "Cap"]);
  });

  test("orders the packages by map and leaves out the one that is not known", () => {
    renderList(unresolvable);

    const rows = bodyRows();
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("CTF-BT-Andromeda");
    expect(rows[1]).toHaveTextContent("CTF-BT-Coolio-v2");
    expect(rows[2]).toHaveTextContent("CTF-BT-Skyfall");
    expect(screen.queryByText("CTF-BT-Maverick")).toBeNull();
  });

  test("shows a GUID once, with the first map that misses it", () => {
    renderList(unresolvable);

    expect(screen.getAllByText("6F1C3A9E4B2D47A1B0C5D8E9F7A6B3C2")).toHaveLength(1);
    expect(screen.queryByText("CTF-BT-PortalCubeRush")).toBeNull();
  });

  test("tells packages without a GUID apart by name", () => {
    const withoutGuid = unresolvable.map((recording) =>
      recording.missingPackage === null
        ? recording
        : { ...recording, missingPackage: { ...recording.missingPackage, guid: "" } },
    );
    renderList(withoutGuid);

    expect(bodyRows()).toHaveLength(3);
  });

  test("shows the package and its GUID, or that the demo recorded none", () => {
    renderList(unresolvable);

    const [andromeda, coolio] = bodyRows();
    expect(within(andromeda).getByText("BTSkins")).toBeInTheDocument();
    expect(within(andromeda).getByText("None recorded")).toBeInTheDocument();
    expect(within(coolio).getByText("Colors")).toBeInTheDocument();
    expect(within(coolio).getByText("6F1C3A9E4B2D47A1B0C5D8E9F7A6B3C2")).toBeInTheDocument();
  });

  test("links the cap time and player to the cap on utbt.net", () => {
    renderList(unresolvable);

    const link = within(bodyRows()[0]).getByRole("link");
    expect(link).toHaveTextContent("41.200 by Kite");
    expect(link).toHaveAttribute("href", "https://utbt.net/caps/1203");
    expect(link).toHaveAttribute("target", "_blank");
  });

  test("shows the empty state when no demo has a known package", () => {
    renderList(unresolvable.filter((recording) => recording.missingPackage === null));

    expect(screen.getByText("No package is missing.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
  });
});
