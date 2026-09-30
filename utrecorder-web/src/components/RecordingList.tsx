import { Badge, Box, Stack } from "@mantine/core";
import { useState } from "react";
import type { Recording } from "../api/types";
import { QueryList, type QueryState } from "./QueryList";
import { RecordingItem } from "./RecordingItem";

/** Recordings as a stack of `RecordingItem`, of which at most one is expanded. Beaten by a faster run gets an "outdated" label. */
export const RecordingList = ({ query }: { query: QueryState<Recording> }) => {
  const [opened, setOpened] = useState<string | null>(null);
  const toggle = (capId: string) => setOpened((current) => (current === capId ? null : capId));
  return (
    <QueryList query={query} empty="No recordings yet.">
      {(recordings) => (
        <Stack gap="md">
          {recordings.map((recording) => (
            <Box key={recording.demo.capId} pos="relative">
              {recording.status === "Outdated" && (
                <Badge
                  color="gray"
                  size="sm"
                  pos="absolute"
                  top={8}
                  right={8}
                  style={{ zIndex: 1, pointerEvents: "none" }}
                >
                  outdated
                </Badge>
              )}
              <RecordingItem
                recording={recording}
                open={opened === recording.demo.capId}
                onToggle={() => toggle(recording.demo.capId)}
              />
            </Box>
          ))}
        </Stack>
      )}
    </QueryList>
  );
};
