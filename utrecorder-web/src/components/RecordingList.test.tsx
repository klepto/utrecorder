import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, test } from "vitest";
import { useRecordings } from "../api/queries";
import { recordings } from "../mocks/fixtures";
import { server } from "../mocks/server";
import { RecordingList } from "./RecordingList";

const Connected = () => {
  return <RecordingList query={useRecordings({ limit: 10, offset: 0 })} />;
};

const wrap = (children: React.ReactNode) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MantineProvider forceColorScheme="dark">{children}</MantineProvider>
    </QueryClientProvider>
  );
};

describe("RecordingList", () => {
  test("shows a collapsed item per recording with no video", async () => {
    render(wrap(<Connected />));

    expect(await screen.findAllByRole("button")).toHaveLength(10);
    expect(screen.getAllByText("Shadow").length).toBeGreaterThan(0);
    expect(screen.getByText("40.000")).toBeInTheDocument();
    expect(document.querySelector("video")).toBeNull();
  });

  test("mounts one video on expand and unmounts it on collapse", async () => {
    render(wrap(<Connected />));

    const buttons = await screen.findAllByRole("button");
    fireEvent.click(buttons[0]);
    const video = await waitForVideo();
    expect(video).toHaveAttribute("src", "/mp4/1098");
    expect(video).toHaveAttribute("preload", "metadata");

    fireEvent.click(buttons[1]);
    await waitForVideo("/mp4/1097");
    expect(document.querySelectorAll("video")).toHaveLength(1);

    fireEvent.click(buttons[1]);
    await waitFor(() => expect(document.querySelector("video")).toBeNull());
  });

  test("expands on Enter", async () => {
    render(wrap(<Connected />));

    const button = (await screen.findAllByRole("button"))[0];
    fireEvent.keyDown(button, { key: "Enter" });

    expect(button).toHaveAttribute("aria-expanded", "true");
    await waitForVideo("/mp4/1098");
  });

  test("links the player to their utbt.net profile without expanding", async () => {
    render(wrap(<Connected />));

    const link = (await screen.findAllByRole("link", { name: "Nyx" }))[0];
    expect(link).toHaveAttribute("href", "https://utbt.net/players/u1097");

    fireEvent.click(link);
    expect(document.querySelector("video")).toBeNull();
  });

  test("shows the player unlinked when the cap list leaves out the user", () => {
    render(wrap(<RecordingList query={{ data: [recordings[0]], isPending: false, isError: false }} />));

    expect(screen.getByText("40.000").parentElement).toHaveTextContent("40.000 by Shadow");
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("replaces the video with an alert on a playback error", async () => {
    render(wrap(<Connected />));

    fireEvent.click((await screen.findAllByRole("button"))[0]);
    fireEvent.error(await waitForVideo());

    expect(await screen.findByText("Cannot play the recording")).toBeInTheDocument();
    expect(document.querySelector("video")).toBeNull();
  });

  test("shows the demo timestamp when recordedAt is null", () => {
    const last = recordings[recordings.length - 1];
    render(wrap(<RecordingList query={{ data: [last], isPending: false, isError: false }} />));

    expect(screen.getByText(/ago$/)).toBeInTheDocument();
    expect(last.recordedAt).toBeNull();
  });

  test("shows skeleton rows while loading", () => {
    server.use(http.get("/api/recordings", () => new Promise(() => {})));
    render(wrap(<Connected />));

    expect(screen.getByTestId("loading")).toBeInTheDocument();
  });

  test("shows an empty state", async () => {
    server.use(http.get("/api/recordings", () => HttpResponse.json([])));
    render(wrap(<Connected />));

    expect(await screen.findByText("No recordings yet.")).toBeInTheDocument();
  });

  test("shows an alert when the query fails before any data", async () => {
    server.use(http.get("/api/recordings", () => new HttpResponse(null, { status: 500 })));
    render(wrap(<Connected />));

    expect(await screen.findByText("Could not load")).toBeInTheDocument();
  });

  test("keeps the data and shows a notice when a refetch fails", () => {
    render(wrap(<RecordingList query={{ data: recordings.slice(0, 2), isPending: false, isError: true }} />));

    expect(screen.getByText("Reconnecting…")).toBeInTheDocument();
    expect(screen.getAllByRole("button")).toHaveLength(2);
  });

  test("shows the utbt.net thumbnail of the map and falls back to the placeholder", async () => {
    render(wrap(<Connected />));

    await screen.findAllByRole("button");
    const thumbnail = document.querySelector("img");
    expect(thumbnail).toHaveAttribute("src", "https://api.utbt.net/screenshots/derived/96/CTF-BT-Maverick.webp");

    fireEvent.error(thumbnail as HTMLImageElement);
    await waitFor(() =>
      expect(thumbnail).toHaveAttribute("src", "https://api.utbt.net/screenshots/derived/96/default.webp"),
    );
  });

  test("shows the outdated label on an Outdated recording", () => {
    const outdated = recordings.find((recording) => recording.status === "Outdated")!;
    render(wrap(<RecordingList query={{ data: [outdated], isPending: false, isError: false }} />));

    expect(screen.getByText("outdated")).toBeInTheDocument();
  });

  test("shows no outdated label on an Archived recording", () => {
    const archived = recordings.find((recording) => recording.status === "Archived")!;
    render(wrap(<RecordingList query={{ data: [archived], isPending: false, isError: false }} />));

    expect(screen.queryByText("outdated")).toBeNull();
  });

  test("renders none of error, md5, views or demoUrl", async () => {
    render(wrap(<Connected />));

    await screen.findAllByRole("button");
    expect(document.body.textContent).not.toMatch(/9e107d9d|example\.com|views/);
  });
});

const waitForVideo = async (src?: string): Promise<HTMLVideoElement> => {
  return waitFor(() => {
    const video = document.querySelector("video");
    if (!video || (src && video.getAttribute("src") !== src)) throw new Error("no video");
    return video;
  });
};
