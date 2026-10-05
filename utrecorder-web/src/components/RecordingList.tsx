import { Stack } from "@mantine/core";
import { useState } from "react";
import type { Recording } from "../api/types";
import { QueryList, type QueryState } from "./QueryList";
import { RecordingItem } from "./RecordingItem";

interface RecordingListProps {
  query: QueryState<Recording>;
  /** The text of the empty state. */
  empty?: string;
}

/** Recordings as a stack of `RecordingItem`, of which at most one is expanded. */
export const RecordingList = ({ query, empty = "No recordings yet." }: RecordingListProps) => {
  const [opened, setOpened] = useState<string | null>(null);
  const toggle = (capId: string) => setOpened((current) => (current === capId ? null : capId));
  return (
    <QueryList query={query} empty={empty}>
      {(recordings) => (
        <Stack gap="md">
          {recordings.map((recording) => (
            <RecordingItem
              key={recording.demo.capId}
              recording={recording}
              open={opened === recording.demo.capId}
              onToggle={() => toggle(recording.demo.capId)}
            />
          ))}
        </Stack>
      )}
    </QueryList>
  );
};
