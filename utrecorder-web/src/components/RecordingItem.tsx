import { Alert, Box, Card, Collapse, Group, Stack, Text, Tooltip } from "@mantine/core";
import { IconChevronDown } from "@tabler/icons-react";
import { type KeyboardEvent, useState } from "react";
import type { Recording } from "../api/types";
import { formatCapTime, formatExactTime, formatRelativeTime } from "../format";
import { MapThumbnail } from "./MapThumbnail";
import { PlayerLink } from "./PlayerLink";
import { Truncated } from "./Truncated";

/** The time a recording counts as made: when the mp4 was stored, or when the cap was added if that is unknown. */
function recordedTime(recording: Recording): string {
  return recording.recordedAt ?? recording.demo.timestamp;
}

/** The player of one recording. A playback error replaces it with an inline `Alert`. */
function Player({ capId }: { capId: string }) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <Alert color="red" title="Cannot play the recording">
        The recording is no longer available.
      </Alert>
    );
  }
  return (
    // biome-ignore lint/a11y/useMediaCaption: recordings carry no captions.
    <video
      controls
      preload="metadata"
      src={`/demo/${capId}`}
      onError={() => setFailed(true)}
      style={{ width: "100%" }}
    />
  );
}

/** Toggles the item on Enter or Space, the keys that press a button. */
function toggleOnKey(event: KeyboardEvent<HTMLElement>, onToggle: () => void) {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  onToggle();
}

/**
 * One recording as a card whose header expands it to its video. The video mounts only while `open`, because `Collapse`
 * keeps its children mounted, and a mounted video downloads.
 */
export function RecordingItem({
  recording,
  open,
  onToggle,
}: {
  recording: Recording;
  open: boolean;
  onToggle: () => void;
}) {
  const { demo } = recording;
  const time = recordedTime(recording);
  return (
    <Card withBorder padding="sm">
      <Group
        wrap="nowrap"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        style={{ cursor: "pointer" }}
        onClick={onToggle}
        onKeyDown={(event) => toggleOnKey(event, onToggle)}
      >
        <MapThumbnail map={demo.map} size={48} />
        <Stack gap={0} miw={0} style={{ flex: 1 }}>
          <Truncated text={demo.map} fw={600} />
          <Text size="sm" c="dimmed" truncate="end">
            {formatCapTime(demo.capTime)} by <PlayerLink demo={demo} />
          </Text>
        </Stack>
        <Group gap={4} wrap="nowrap" style={{ flexShrink: 0, alignSelf: "flex-start" }}>
          <Tooltip label={formatExactTime(time)}>
            <Text size="sm">{formatRelativeTime(time)}</Text>
          </Tooltip>
          <IconChevronDown
            size={16}
            style={{ transform: open ? "rotate(180deg)" : undefined, transition: "transform 200ms ease" }}
          />
        </Group>
      </Group>
      <Collapse in={open}>
        <Box pt="sm">{open && <Player capId={demo.capId} />}</Box>
      </Collapse>
    </Card>
  );
}
