import { Box, Card, Center, Group, Progress, type ProgressProps, Stack, Text } from "@mantine/core";
import { JOBS_INTERVAL } from "../api/queries";
import type { Job } from "../api/types";
import { formatStage } from "../format";
import { capLinkProps } from "./capLinkProps";
import { DemoSummary } from "./DemoSummary";
import classes from "./JobList.module.css";
import type { JobsState } from "./JobsState";
import { QueryList } from "./QueryList";
import { Timestamp } from "./Timestamp";
import { useExtrapolated } from "./useExtrapolated";

/** The height of the stage progress bar: one line of its `sm` label with `xs` spacing split above and below. */
const PROGRESS_SIZE = "calc(var(--mantine-font-size-sm) * var(--mantine-line-height-sm) + var(--mantine-spacing-xs))";

/** A stage progress bar with `label` centered over it. */
const StageBar = ({ label, ...props }: { label: string } & ProgressProps) => {
  return (
    <Box pos="relative">
      <Progress size={PROGRESS_SIZE} color="var(--mantine-color-anchor)" {...props} />
      <Center pos="absolute" inset={0}>
        <Text size="sm" fw={600} className={classes.label}>
          {label}
        </Text>
      </Center>
    </Box>
  );
};

/**
 * The progress bar of a stage that reports `progress` at the epoch millisecond `reportedAt`, labelled with `stage` and
 * the percentage done. The bar fills quickly from empty when it appears, and between two polls it keeps moving at the
 * rate the reported progress rose at.
 */
const ReportedProgress = ({ stage, progress, reportedAt }: { stage: string; progress: number; reportedAt: number }) => {
  const shown = useExtrapolated(progress, reportedAt, JOBS_INTERVAL);
  return (
    <StageBar
      label={`${stage} - ${Math.floor(shown * 100)}%`}
      classNames={{ root: classes.progress, section: classes.section }}
      value={shown * 100}
      transitionDuration={0}
      aria-label="Stage progress"
    />
  );
};

/**
 * The progress bar of the stage of `job`, reported at the epoch millisecond `reportedAt`, labelled in its center with the
 * stage and, once reported, the percentage done.
 */
const StageProgress = ({ job, reportedAt }: { job: Job; reportedAt: number }) => {
  const stage = formatStage(job.stage);
  if (job.progress !== null) {
    return <ReportedProgress key={job.stage} stage={stage} progress={job.progress} reportedAt={reportedAt} />;
  }
  return (
    <StageBar
      label={stage}
      classNames={{ root: classes.progress }}
      value={100}
      animated
      aria-label="Progress unknown"
    />
  );
};

/**
 * The running recordings: map, time the cap was added, cap time and player, the stage with its progress, and the attempt
 * after the first.
 */
export const JobList = ({ query }: { query: JobsState }) => {
  return (
    <QueryList query={query} empty="Nothing is recording.">
      {(jobs) =>
        jobs.map((job) => (
          <Card
            key={job.demo.capId}
            withBorder
            padding="sm"
            className={classes.card}
            role="link"
            {...capLinkProps(job.demo)}
          >
            <Stack gap="sm">
              <Group wrap="nowrap">
                <DemoSummary demo={job.demo} />
                <Timestamp value={job.demo.timestamp} style={{ flexShrink: 0, alignSelf: "flex-start" }} />
              </Group>
              {job.attempt > 1 && (
                <Text size="sm" ta="right">
                  Attempt {job.attempt}
                </Text>
              )}
              <StageProgress job={job} reportedAt={query.dataUpdatedAt} />
            </Stack>
          </Card>
        ))
      }
    </QueryList>
  );
};
