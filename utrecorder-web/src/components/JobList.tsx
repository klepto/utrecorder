import { Card, Group, Progress, Stack, Text } from "@mantine/core";
import type { Job } from "../api/types";
import { formatCapTime, formatStage } from "../format";
import { QueryList, type QueryState } from "./QueryList";
import { Truncated } from "./Truncated";

/** The running recordings: player, map, cap time, the stage with its progress, and the attempt after the first. */
export function JobList({ query }: { query: QueryState<Job> }) {
  return (
    <QueryList query={query} empty="Nothing is recording.">
      {(jobs) =>
        jobs.map((job) => (
          <Card key={job.demo.capId} withBorder padding="sm">
            <Stack gap={4}>
              <Group justify="space-between" wrap="nowrap">
                <Truncated text={job.demo.player} fw={600} />
                <Text size="sm">{formatCapTime(job.demo.capTime)}</Text>
              </Group>
              <Truncated text={job.demo.map} size="sm" c="dimmed" />
              <Group justify="space-between">
                <Text size="sm">{formatStage(job.stage)}</Text>
                {job.attempt > 1 && <Text size="sm">Attempt {job.attempt}</Text>}
              </Group>
              {job.progress === null ? (
                <Progress value={100} animated aria-label="Progress unknown" />
              ) : (
                <Progress value={job.progress * 100} aria-label="Stage progress" />
              )}
            </Stack>
          </Card>
        ))
      }
    </QueryList>
  );
}
