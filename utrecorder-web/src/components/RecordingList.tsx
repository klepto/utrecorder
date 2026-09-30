import { Accordion } from "@mantine/core";
import { useState } from "react";
import type { Recording } from "../api/types";
import { QueryList, type QueryState } from "./QueryList";
import { RecordingItem } from "./RecordingItem";

/** Recordings as an `Accordion` of `RecordingItem`, of which at most one is expanded. */
export function RecordingList({ query }: { query: QueryState<Recording> }) {
  const [opened, setOpened] = useState<string | null>(null);
  return (
    <QueryList query={query} empty="No recordings yet.">
      {(recordings) => (
        <Accordion variant="separated" value={opened} onChange={setOpened}>
          {recordings.map((recording) => (
            <RecordingItem key={recording.demo.capId} recording={recording} open={opened === recording.demo.capId} />
          ))}
        </Accordion>
      )}
    </QueryList>
  );
}
