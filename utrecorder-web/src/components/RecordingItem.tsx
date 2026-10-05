import { Alert, Badge, Box, Card, Collapse, Group, Stack } from "@mantine/core";
import { IconChevronDown } from "@tabler/icons-react";
import { type KeyboardEvent, useState } from "react";
import type { Recording } from "../api/types";
import { DemoSummary } from "./DemoSummary";
import classes from "./RecordingItem.module.css";
import { RecordingStats } from "./RecordingStats";
import { Timestamp } from "./Timestamp";

/** The time a recording counts as made: when the mp4 was stored, or when the cap was added if that is unknown. */
const recordedTime = (recording: Recording): string => {
  return recording.recordedAt ?? recording.demo.timestamp;
};

/**
 * The player of one recording, with the figures of the recording below it. A playback error replaces both with an
 * inline `Alert`.
 */
const Player = ({ recording }: { recording: Recording }) => {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <Alert color="red" title="Cannot play the recording">
        The recording is no longer available.
      </Alert>
    );
  }
  return (
    <Stack gap="sm">
      {/* biome-ignore lint/a11y/useMediaCaption: recordings carry no captions. */}
      <video
        controls
        preload="metadata"
        src={`/mp4/${recording.demo.capId}`}
        onError={() => setFailed(true)}
        style={{ width: "100%", display: "block" }}
      />
      <RecordingStats recording={recording} />
    </Stack>
  );
};

/** Toggles the item on Enter or Space, the keys that press a button. */
const toggleOnKey = (event: KeyboardEvent<HTMLElement>, onToggle: () => void) => {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  onToggle();
};

/**
 * One recording as a card whose header expands it to its video. Beaten by a faster run, the header carries an
 * "outdated" label under the timestamp, level with the player. The video and the figures of the recording mount only
 * while `open`.
 */
export const RecordingItem = ({
  recording,
  open,
  onToggle,
}: {
  recording: Recording;
  open: boolean;
  onToggle: () => void;
}) => {
  const { demo } = recording;
  return (
    <Card withBorder padding="sm">
      <Group
        wrap="nowrap"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        className={classes.header}
        onClick={onToggle}
        onKeyDown={(event) => toggleOnKey(event, onToggle)}
      >
        <DemoSummary demo={demo} />
        <Stack gap={0} justify="space-between" align="flex-end" style={{ flexShrink: 0, alignSelf: "stretch" }}>
          <Group gap={4} wrap="nowrap">
            <Timestamp value={recordedTime(recording)} relative />
            <IconChevronDown
              size={16}
              style={{ transform: open ? "rotate(180deg)" : undefined, transition: "transform 200ms ease" }}
            />
          </Group>
          {recording.status === "Outdated" && (
            <Badge color="gray" size="sm">
              outdated
            </Badge>
          )}
        </Stack>
      </Group>
      <Collapse in={open}>
        <Box pt="sm">{open && <Player recording={recording} />}</Box>
      </Collapse>
    </Card>
  );
};
