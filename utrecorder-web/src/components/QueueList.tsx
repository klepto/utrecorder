import { Group, Text } from "@mantine/core";
import type { Demo } from "../api/types";
import { formatCapTime } from "../format";
import { QueryList, type QueryState } from "./QueryList";
import { Truncated } from "./Truncated";

/** The queued demos in the order the recorder takes them, numbered from 1. */
export function QueueList({ query }: { query: QueryState<Demo> }) {
  return (
    <QueryList query={query} empty="The queue is empty.">
      {(demos) => (
        <ol style={{ margin: 0, paddingLeft: "1.5rem" }}>
          {demos.map((demo) => (
            <li key={demo.capId}>
              <Group justify="space-between" wrap="nowrap" gap="xs">
                <Truncated text={demo.player} />
                <Truncated text={demo.map} c="dimmed" size="sm" />
                <Text size="sm">{formatCapTime(demo.capTime)}</Text>
              </Group>
            </li>
          ))}
        </ol>
      )}
    </QueryList>
  );
}
