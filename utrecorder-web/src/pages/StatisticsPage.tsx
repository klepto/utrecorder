import { Alert, Group, Paper, Progress, SimpleGrid, Skeleton, Stack, Text } from "@mantine/core";
import type { ReactNode } from "react";
import { useStatistics } from "../api/queries";
import type {
  PersonalBestDay,
  PersonalBestStatistics,
  QueueStatistics,
  RecorderDay,
  RecorderStatistics,
  StorageStatistics,
} from "../api/types";
import { DayChart } from "../components/DayChart";
import { durationSeconds, formatBytes, formatCount, formatHours, formatPace } from "../format";
import { Page } from "../layout/Page";
import { Section } from "../layout/Section";
import classes from "./StatisticsPage.module.css";

/** Formats a number of seconds as `2 h 30 min`, or `45 min` under an hour. */
const formatSeconds = (seconds: number): string => formatHours(`PT${Math.round(seconds)}S`);

/** Returns `count` followed by `noun`, adding an `s` unless `count` is 1. */
const plural = (count: number, noun: string): string => `${formatCount(count)} ${noun}${count === 1 ? "" : "s"}`;

/** Returns the share `part` holds of `whole` as a percentage, 0 for an empty whole. */
const percent = (part: number, whole: number): number => (whole > 0 ? Math.min(100, (part / whole) * 100) : 0);

/** A number inside a line of text, set in the bright color. */
const Figure = ({ children }: { children: ReactNode }) => {
  return <span className={classes.figure}>{children}</span>;
};

/** One panel of the page: a titled section on the chrome background, with `aside` at the right of the title. */
const Panel = ({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) => {
  return (
    <Paper bg="var(--utrecorder-color-chrome)" p="lg" className={classes.panel}>
      <Section title={title} aside={aside}>
        {children}
      </Section>
    </Paper>
  );
};

/** The headline figure of a panel with the line that says what it counts. */
const Headline = ({ figure, caption }: { figure: string; caption: string }) => {
  return (
    <div>
      <Text className={classes.headline}>{figure}</Text>
      <Text size="sm" c="dimmed">
        {caption}
      </Text>
    </div>
  );
};

const QueuePanel = ({ queue, pace }: { queue: QueueStatistics; pace: number | null }) => {
  const queued = durationSeconds(queue.capTime);
  const lead = pace === null ? `${formatSeconds(queued)} of runs queued` : `${formatSeconds(queued * pace)} remaining`;
  const counts = (
    <Group gap="xl">
      <Text>
        <Figure>{formatCount(queue.recording)}</Figure> recording now
      </Text>
      <Text>
        <Figure>{formatCount(queue.failed)}</Figure> failed
      </Text>
      <Text>
        <Figure>{formatCount(queue.unresolvable)}</Figure> missing packages
      </Text>
    </Group>
  );
  return (
    <Panel title="Queue" aside={counts}>
      <Group justify="space-between" align="baseline" gap="xs">
        <Text className={classes.lead}>{lead}</Text>
        <Text size="lg" c="dimmed">
          <Figure>{formatCount(queue.size)}</Figure> demos waiting
        </Text>
      </Group>
      <Stack gap={6}>
        <Progress.Root size={20} className={classes.track}>
          <Progress.Section
            value={percent(queue.recent, queue.size)}
            color="var(--mantine-color-anchor)"
            aria-label="Recent demos"
          />
          <Progress.Section
            value={percent(queue.backlog, queue.size)}
            color="var(--mantine-color-dark-3)"
            aria-label="Backlog"
          />
        </Progress.Root>
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            <Figure>{formatCount(queue.recent)}</Figure> recent
          </Text>
          <Text size="sm" c="dimmed">
            <Figure>{formatCount(queue.backlog)}</Figure> backlog
          </Text>
        </Group>
      </Stack>
    </Panel>
  );
};

const RecorderPanel = ({ recorder }: { recorder: RecorderStatistics }) => {
  return (
    <Panel title="Recorder">
      <Headline figure={formatHours(recorder.lastDay.capTime)} caption="recorded in the last 24 hours" />
      <DayChart
        days={recorder.days}
        value={(day: RecorderDay) => durationSeconds(day.capTime)}
        format={formatSeconds}
        label="Recorded run time per day"
      />
      <div>
        <Text className={classes.figure}>
          {recorder.pace === null ? formatPace(null) : `${formatPace(recorder.pace)} run time`}
        </Text>
        <Text size="sm" c="dimmed">
          time to record one minute of run
        </Text>
      </div>
      <Stack gap={4}>
        <Text>
          <Figure>{formatCount(recorder.completed)}</Figure> recordings
        </Text>
        <Text>
          <Figure>{formatCount(recorder.views)}</Figure> views
        </Text>
      </Stack>
    </Panel>
  );
};

const StoragePanel = ({ storage }: { storage: StorageStatistics }) => {
  const { cache } = storage;
  return (
    <Panel title="Storage">
      {cache === null ? (
        <Headline figure="Measuring…" caption="in the hot cache" />
      ) : (
        <Stack gap={6}>
          <Headline
            figure={`${formatBytes(cache.bytes)} of ${formatBytes(storage.cacheMaxBytes)}`}
            caption="in the hot cache"
          />
          <div className={classes.meter} aria-hidden="true">
            <div className={classes.meterFill} style={{ width: `${percent(cache.bytes, storage.cacheMaxBytes)}%` }} />
            <div
              className={classes.tick}
              style={{ left: `${percent(storage.cacheTargetBytes, storage.cacheMaxBytes)}%` }}
            />
          </div>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {plural(cache.files, "file")}
            </Text>
            <Text size="sm" c="dimmed">
              target {formatBytes(storage.cacheTargetBytes)}
            </Text>
          </Group>
        </Stack>
      )}
      <div>
        <Text>
          <Figure>{formatCount(storage.archived)}</Figure> runs archived
        </Text>
        <Text>
          <Figure>{formatBytes(storage.archivedBytes)}</Figure> of video
        </Text>
        {storage.archivedUnsized > 0 && (
          <Text size="sm" c="dimmed">
            {storage.archivedUnsized === 1
              ? "1 run is still being measured."
              : `${formatCount(storage.archivedUnsized)} runs are still being measured.`}
          </Text>
        )}
      </div>
      <Text>
        <Figure>{formatCount(storage.uploadsLastDay)}</Figure> of {formatCount(storage.dailyUploads)} uploads in the
        last 24 hours
      </Text>
    </Panel>
  );
};

const PersonalBestsPanel = ({ personalBests }: { personalBests: PersonalBestStatistics }) => {
  return (
    <Panel title="Personal bests">
      <Headline figure={formatCount(personalBests.lastDay)} caption="set in the last 24 hours" />
      <DayChart
        days={personalBests.days}
        value={(day: PersonalBestDay) => day.personalBests}
        format={formatCount}
        label="Personal bests per day"
      />
      <Text>
        <Figure>{formatCount(personalBests.players)}</Figure> players with a personal best in the last 30 days
      </Text>
    </Panel>
  );
};

/** The aggregate statistics: the queue, the recorder, storage and personal bests. */
export const StatisticsPage = () => {
  const { data, isError } = useStatistics();
  return (
    <Page title="Statistics">
      {data === undefined ? (
        isError ? (
          <Alert color="red">Statistics could not be loaded. They retry every minute.</Alert>
        ) : (
          <Stack data-testid="loading">
            <Skeleton height={220} />
            <SimpleGrid cols={{ base: 1, md: 3 }}>
              <Skeleton height={420} />
              <Skeleton height={420} />
              <Skeleton height={420} />
            </SimpleGrid>
          </Stack>
        )
      ) : (
        <Stack className={classes.page}>
          {isError && (
            <Text size="xs" c="yellow">
              Reconnecting…
            </Text>
          )}
          <QueuePanel queue={data.queue} pace={data.recorder.pace} />
          <SimpleGrid cols={{ base: 1, md: 3 }} className={classes.panels}>
            <RecorderPanel recorder={data.recorder} />
            <StoragePanel storage={data.storage} />
            <PersonalBestsPanel personalBests={data.personalBests} />
          </SimpleGrid>
        </Stack>
      )}
    </Page>
  );
};
