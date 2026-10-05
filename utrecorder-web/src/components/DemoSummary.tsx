import { Stack, Text } from "@mantine/core";
import type { Demo } from "../api/types";
import { CapTime } from "./CapTime";
import { MapThumbnail } from "./MapThumbnail";
import { PlayerLink } from "./PlayerLink";
import { Truncated } from "./Truncated";

/**
 * The thumbnail of the map of `demo`, next to the map name above its cap time and player, filling the width left. If
 * `medal` is provided, the cap time shows that medal instead of the medal of `demo`.
 */
export const DemoSummary = ({ demo, medal }: { demo: Demo; medal?: number | null }) => {
  return (
    <>
      <MapThumbnail map={demo.map} size={48} />
      <Stack gap={0} miw={0} flex={1}>
        <Truncated text={demo.map} fw={600} />
        <Text size="sm" c="dimmed" truncate="end">
          <CapTime demo={demo} medal={medal} /> by <PlayerLink demo={demo} />
        </Text>
      </Stack>
    </>
  );
};
