import { Anchor, List, Stack, Text, Title } from "@mantine/core";
import type { ReactNode } from "react";
import { Link } from "react-router";
import { Page } from "../layout/Page";
import { DISCORD_URL, GITHUB_URL, NEW_TAB, UTBT_URL } from "../site";

/** One part of the about page: its content stacked below its title, a third-level heading. */
const Topic = ({ title, children }: { title: string; children: ReactNode }) => {
  return (
    <Stack>
      <Title order={3}>{title}</Title>
      {children}
    </Stack>
  );
};

/** Static text about the project: what it is, how the server works and how a recording is made. */
export const AboutPage = () => {
  return (
    <Page title="About">
      <Text>
        utrecorder watches the cap list of{" "}
        <Anchor href={UTBT_URL} {...NEW_TAB}>
          utbt.net
        </Anchor>{" "}
        and turns verified Bunny Track caps into videos of the runs, with sound. It works on its own, with no one at the
        controls. Players and viewers watch the runs here, share them on Discord, and find them kept on the{" "}
        <Anchor href="https://archive.org" {...NEW_TAB}>
          Internet Archive
        </Anchor>
        .
      </Text>
      <Topic title="How the server works">
        <Text>
          Every minute, the server reads the newest verified caps from utbt.net. From time to time, it also reads the
          whole cap list again, to find caps it missed.
        </Text>
        <Text>
          Only the fastest run of each player on each map is recorded. A solo run and runs with different teammates
          count as different runs. Once a faster run is recorded, it replaces the old one, and the old video is removed
          later. A team run waits until the cap of every teammate is on the cap list.
        </Text>
        <Text>
          The queue puts caps from the last day first, then caps with higher medals, then shorter runs. Older caps fill
          the rest of the day. They follow the pace of the daily uploads to the Internet Archive, so the recorder never
          gets far ahead of them.
        </Text>
        <Text>
          A recording that fails is tried again a few times. If it still fails, the server tries it again some hours
          later. A demo that needs a map or a mod the server cannot find waits as a missing package. The{" "}
          <Anchor component={Link} to="/statistics">
            Statistics page
          </Anchor>{" "}
          lists the missing packages.
        </Text>
        <Text>
          Each new video is kept on the server first. A video of a cap from the last day is uploaded to the Internet
          Archive a couple of days after recording, and a video of an older cap right after recording. Each video goes
          into one item per player and map. While people still watch a video, it stays on the server. Once it goes
          unwatched, the server removes its copy, and the video plays from the Archive. The{" "}
          <Anchor component={Link} to="/statistics">
            Statistics page
          </Anchor>{" "}
          shows the uploads of the last day and the space the videos take on the server.
        </Text>
        <Text>
          Each video is sized to stay under the file size that Discord embeds, so a shared link plays right in the chat.
          Longer runs are recorded at a lower resolution to fit, and only the longest runs can still miss the limit. The
          resolution and the frame rate of each recording are listed under its video.
        </Text>
      </Topic>
      <Topic title="How a recording is made">
        <List type="ordered" spacing="sm">
          <List.Item>
            <Text span fw={700}>
              Downloading
            </Text>
            : The demo of the cap is downloaded from utbt.net.
          </List.Item>
          <List.Item>
            <Text span fw={700}>
              Preparing
            </Text>
            : The server sets up a real copy of Unreal Tournament 99 with the 469 patch. The game is installed once and
            reused for every recording, and the maps the demo needs are added to it.
          </List.Item>
          <List.Item>
            <Text span fw={700}>
              Resolving packages
            </Text>
            : Every map, mod and texture package the demo names is found in the exact version the demo was recorded
            with.
          </List.Item>
          <List.Item>
            <Text span fw={700}>
              Capturing
            </Text>
            : The game plays the demo twice at the same time, on a virtual screen and rendered in software. Both
            playbacks skip ahead to just before the run. One playback records the picture. A small library loaded into
            the game reads every frame as it is drawn, and streams it to ffmpeg, which encodes it to AV1. The video
            starts at the spawn and ends just after the timer stops. Chat and other text messages are hidden. The Bunny
            Track HUD is shown for recent versions of the mod, and hidden for older ones. For smooth motion, the game
            draws more frames than the video keeps, and blends them. The other playback records the sound. Its clock
            steps in time with the sound mixer, so the sound stays in line with the picture. In both playbacks, the
            library drives the clock of the game frame by frame, so the result does not depend on how fast the machine
            renders. A playback that stops making progress is stopped, and the recording is tried again.
          </List.Item>
          <List.Item>
            <Text span fw={700}>
              Muxing
            </Text>
            : The picture and the sound are joined into one mp4. Its length is checked against the run time the demo
            reports, and a thumbnail is taken from it for link previews.
          </List.Item>
        </List>
      </Topic>
      <Text>
        Questions and ideas are welcome on{" "}
        <Anchor href={DISCORD_URL} {...NEW_TAB}>
          Discord
        </Anchor>
        , and the code is on{" "}
        <Anchor href={GITHUB_URL} {...NEW_TAB}>
          GitHub
        </Anchor>
        .
      </Text>
    </Page>
  );
};
