import { Alert, Box, Card, Collapse, Group } from "@mantine/core";
import { IconChevronDown } from "@tabler/icons-react";
import { type KeyboardEvent, useState } from "react";
import type { Recording } from "../api/types";
import { DemoSummary } from "./DemoSummary";
import classes from "./RecordingItem.module.css";
import { Timestamp } from "./Timestamp";

/** The time a recording counts as made: when the mp4 was stored, or when the cap was added if that is unknown. */
const recordedTime = (recording: Recording): string => {
  return recording.recordedAt ?? recording.demo.timestamp;
};

/** The player of one recording. A playback error replaces it with an inline `Alert`. */
const Player = ({ capId }: { capId: string }) => {
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
};

/** Toggles the item on Enter or Space, the keys that press a button. */
const toggleOnKey = (event: KeyboardEvent<HTMLElement>, onToggle: () => void) => {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  onToggle();
};

/**
 * One recording as a card whose header expands it to its video. The video mounts only while `open`, because `Collapse`
 * keeps its children mounted, and a mounted video downloads.
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
        <Group gap={4} wrap="nowrap" style={{ flexShrink: 0, alignSelf: "flex-start" }}>
          <Timestamp value={recordedTime(recording)} relative />
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
};
