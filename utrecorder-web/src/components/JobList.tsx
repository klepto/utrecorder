import { Box, Card, Center, Group, Progress, Stack, Text, Tooltip } from "@mantine/core";
import type { Job } from "../api/types";
import { formatCapTime, formatDate, formatExactTime, formatStage } from "../format";
import { capUrl, openInNewTab } from "../site";
import classes from "./JobList.module.css";
import { MapThumbnail } from "./MapThumbnail";
import { PlayerLink } from "./PlayerLink";
import { QueryList, type QueryState } from "./QueryList";
import { Truncated } from "./Truncated";

/** The date of the ISO-8601 instant `value`, whose tooltip holds the exact date and time. */
function Timestamp({ value }: { value: string }) {
  return (
    <Tooltip label={formatExactTime(value)}>
      <Text size="sm" style={{ flexShrink: 0, alignSelf: "flex-start" }}>
        {formatDate(value)}
      </Text>
    </Tooltip>
  );
}

/**
 * The progress bar of the stage of `job`, labelled in its center with the stage and, once reported, the percentage done.
 * The label is drawn twice: light over the track, and dark over the fill, clipped where the fill ends.
 */
function StageProgress({ job }: { job: Job }) {
  const stage = formatStage(job.stage);
  const label = job.progress === null ? stage : `${stage} - ${Math.floor(job.progress * 100)}%`;
  const filled = job.progress === null ? 100 : job.progress * 100;
  return (
    <Box pos="relative" __vars={{ "--stage-progress-filled": `${filled}%` }}>
      {job.progress === null ? (
        <Progress
          className={classes.progress}
          value={100}
          size={24}
          color="var(--mantine-color-anchor)"
          animated
          aria-label="Progress unknown"
        />
      ) : (
        <Progress
          classNames={{ root: classes.progress, section: classes.section }}
          value={filled}
          size={24}
          color="var(--mantine-color-anchor)"
          aria-label="Stage progress"
        />
      )}
      <Center pos="absolute" inset={0}>
        <Text size="sm" fw={600}>
          {label}
        </Text>
      </Center>
      <Center pos="absolute" inset={0} className={classes.filled} aria-hidden>
        <Text size="sm" fw={600} className={classes.filledLabel} data-label={label} />
      </Center>
    </Box>
  );
}

/** The running recordings: map, time the cap was added, cap time and player, the stage with its progress, and the attempt after the first. */
export function JobList({ query }: { query: QueryState<Job> }) {
  return (
    <QueryList query={query} empty="Nothing is recording.">
      {(jobs) =>
        jobs.map((job) => (
          <Card
            key={job.demo.capId}
            withBorder
            padding="sm"
            role="link"
            tabIndex={0}
            aria-label={`${job.demo.map} on utbt.net`}
            style={{ cursor: "pointer" }}
            onClick={() => openInNewTab(capUrl(job.demo.capId))}
            onKeyDown={(event) => event.key === "Enter" && openInNewTab(capUrl(job.demo.capId))}
          >
            <Stack gap="sm">
              <Group wrap="nowrap">
                <MapThumbnail map={job.demo.map} size={48} />
                <Stack gap={0} miw={0} style={{ flex: 1 }}>
                  <Truncated text={job.demo.map} fw={600} />
                  <Text size="sm" c="dimmed" truncate="end">
                    {formatCapTime(job.demo.capTime)} by <PlayerLink demo={job.demo} />
                  </Text>
                </Stack>
                <Timestamp value={job.demo.timestamp} />
              </Group>
              {job.attempt > 1 && (
                <Text size="sm" ta="right">
                  Attempt {job.attempt}
                </Text>
              )}
              <StageProgress job={job} />
            </Stack>
          </Card>
        ))
      }
    </QueryList>
  );
}
