import { Accordion, Alert, Group, Image, Stack, Text, Tooltip } from "@mantine/core";
import { useState } from "react";
import type { Recording } from "../api/types";
import { formatCapTime, formatExactTime, formatRelativeTime } from "../format";
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

/**
 * One recording as an `Accordion` item. The player mounts only while `open`, because Mantine keeps the children of a
 * collapsed panel mounted, and a mounted video downloads.
 */
export function RecordingItem({ recording, open }: { recording: Recording; open: boolean }) {
  const { demo } = recording;
  const time = recordedTime(recording);
  return (
    <Accordion.Item value={demo.capId}>
      <Accordion.Control>
        <Group wrap="nowrap">
          <Image src="/map-placeholder.svg" alt="" w={64} h={36} radius="sm" />
          <Stack gap={0} miw={0} style={{ flex: 1 }}>
            <Group justify="space-between" wrap="nowrap">
              <Truncated text={demo.player} fw={600} />
              <Text size="sm">{formatCapTime(demo.capTime)}</Text>
            </Group>
            <Group justify="space-between" wrap="nowrap">
              <Truncated text={demo.map} size="sm" c="dimmed" />
              <Tooltip label={formatExactTime(time)}>
                <Text size="xs" c="dimmed">
                  {formatRelativeTime(time)}
                </Text>
              </Tooltip>
            </Group>
          </Stack>
        </Group>
      </Accordion.Control>
      <Accordion.Panel>{open && <Player capId={demo.capId} />}</Accordion.Panel>
    </Accordion.Item>
  );
}
