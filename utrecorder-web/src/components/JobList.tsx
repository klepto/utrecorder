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

/** A stage progress bar with flowing stripes and `label` centered over it. */
const StageBar = ({ label, ...props }: { label: string } & ProgressProps) => {
  return (
    <Box pos="relative">
      <Progress
        classNames={{ root: classes.progress, section: classes.section }}
        size={PROGRESS_SIZE}
        color="var(--mantine-color-anchor)"
        {...props}
      />
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
  return <StageBar label={stage} value={100} aria-label="Progress unknown" />;
};

/**
 * One running recording: map, cap time with the medal of its run, and player, the time the cap was added with the
 * attempt after the first below it, and the stage with its progress reported at `reportedAt`.
 */
const JobCard = ({ job, reportedAt }: { job: Job; reportedAt: number }) => {
  const { demo, runMedal } = job.recording;
  return (
    <Card withBorder padding="sm" className={classes.card} role="link" {...capLinkProps(demo)}>
      <Stack gap="sm">
        <Group wrap="nowrap">
          <DemoSummary demo={demo} medal={runMedal ?? demo.medal} />
          <Stack gap={0} align="flex-end" style={{ flexShrink: 0, alignSelf: "flex-start" }}>
            <Timestamp value={demo.timestamp} />
            {job.attempt > 1 && (
              <Text size="sm" c="dimmed">
                Attempt {job.attempt}
              </Text>
            )}
          </Stack>
        </Group>
        <StageProgress job={job} reportedAt={reportedAt} />
      </Stack>
    </Card>
  );
};

/**
 * The running recordings: map, cap time and player, the time the cap was added with the attempt after the first below
 * it, and the stage with its progress.
 */
export const JobList = ({ query }: { query: JobsState }) => {
  return (
    <QueryList query={query} empty="Nothing is recording.">
      {(jobs) =>
        jobs.map((job) => <JobCard key={job.recording.demo.capId} job={job} reportedAt={query.dataUpdatedAt} />)
      }
    </QueryList>
  );
};
