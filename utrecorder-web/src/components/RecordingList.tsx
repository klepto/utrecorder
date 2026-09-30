import { Stack } from "@mantine/core";
import { useState } from "react";
import type { Recording } from "../api/types";
import { QueryList, type QueryState } from "./QueryList";
import { RecordingItem } from "./RecordingItem";

/** Recordings as a stack of `RecordingItem`, of which at most one is expanded. */
export const RecordingList = ({ query }: { query: QueryState<Recording> }) => {
  const [opened, setOpened] = useState<string | null>(null);
  const toggle = (capId: string) => setOpened((current) => (current === capId ? null : capId));
  return (
    <QueryList query={query} empty="No recordings yet.">
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
