import { Stack, Text } from "@mantine/core";
import type { Demo } from "../api/types";
import { CapTime } from "./CapTime";
import { MapThumbnail } from "./MapThumbnail";
import { PlayerLink } from "./PlayerLink";
import { Truncated } from "./Truncated";

/** The thumbnail of the map of `demo`, next to the map name above its cap time and player, filling the width left. */
export const DemoSummary = ({ demo }: { demo: Demo }) => {
  return (
    <>
      <MapThumbnail map={demo.map} size={48} />
      <Stack gap={0} miw={0} flex={1}>
        <Truncated text={demo.map} fw={600} />
        <Text size="sm" c="dimmed" truncate="end">
          <CapTime demo={demo} /> by <PlayerLink demo={demo} />
        </Text>
      </Stack>
    </>
  );
};
