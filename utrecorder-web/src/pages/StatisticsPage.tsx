import { Alert, Group, Paper, Progress, SimpleGrid, Skeleton, Stack, Text, Tooltip } from "@mantine/core";
import type { ReactNode } from "react";
import { useStatistics, useUnresolvable } from "../api/queries";
import type {
  CacheUsage,
  PersonalBestDay,
  PersonalBestStatistics,
  QueueStatistics,
  RecorderDay,
  RecorderStatistics,
  StorageStatistics,
} from "../api/types";
import { DayChart } from "../components/DayChart";
import { hasMissingPackage } from "../components/hasMissingPackage";
import { MissingPackageList } from "../components/MissingPackageList";
import { durationSeconds, formatBytes, formatCount, formatHours, formatPace } from "../format";
import { Page } from "../layout/Page";
import { Section } from "../layout/Section";
import classes from "./StatisticsPage.module.css";

/** Formats a number of seconds as `2 h 30 min`, or `45 min` under an hour. */
const formatSeconds = (seconds: number): string => formatHours(`PT${Math.round(seconds)}S`);

/** Returns `count` followed by `noun`, adding an `s` unless `count` is 1. */
const plural = (count: number, noun: string): string => `${formatCount(count)} ${noun}${count === 1 ? "" : "s"}`;

/** Formats a number of seconds in whole hours as `5 hours`, or in whole minutes as `45 minutes` under an hour. */
const formatLongDuration = (seconds: number): string => {
  return seconds < 3600 ? plural(Math.floor(seconds / 60), "minute") : plural(Math.floor(seconds / 3600), "hour");
};

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

/** Formats the share `part` holds of `whole` as `89.7%`. */
const formatShare = (part: number, whole: number): string => shares.format(percent(part, whole) / 100);

const shares = new Intl.NumberFormat("en", { style: "percent", maximumFractionDigits: 1 });

/** The bar of the demos `recorded` against the demos `waiting`, which shows the share of each on hover. */
const QueueBar = ({ recorded, waiting }: { recorded: number; waiting: number }) => {
  const total = recorded + waiting;
  return (
    <Tooltip
      label={`${formatShare(recorded, total)} recorded – ${formatShare(waiting, total)} waiting`}
      events={{ hover: true, focus: false, touch: true }}
    >
      <Progress.Root size={20} className={classes.track}>
        <Progress.Section
          value={percent(recorded, total)}
          color="var(--mantine-color-anchor)"
          aria-label="Demos recorded"
        />
        <Progress.Section
          value={percent(waiting, total)}
          color="var(--mantine-color-body)"
          aria-label="Demos waiting"
        />
      </Progress.Root>
    </Tooltip>
  );
};

/** The untitled main panel: the time left to record the queue, and the demos recorded against the demos waiting. */
const SummaryPanel = ({ queue, recorder }: { queue: QueueStatistics; recorder: RecorderStatistics }) => {
  const queued = durationSeconds(queue.capTime);
  const lead =
    recorder.pace === null
      ? `${formatLongDuration(queued)} of runs queued`
      : `${formatLongDuration(queued * recorder.pace)} remaining`;
  return (
    <Paper bg="var(--utrecorder-color-chrome)" p="lg">
      <Stack>
        <Group justify="space-between" align="flex-start" gap="xs">
          <Text className={classes.lead}>{lead}</Text>
          <Group gap="xl">
            <Text>
              <Figure>{formatCount(recorder.lastDay.recordings)}</Figure> recent
            </Text>
            <Text>
              <Figure>{formatCount(queue.failed + queue.unavailable)}</Figure> failed
            </Text>
            <Text>
              <Figure>{formatCount(queue.unresolvable)}</Figure> missing packages
            </Text>
          </Group>
        </Group>
        <Stack gap={6}>
          <QueueBar recorded={recorder.completed} waiting={queue.size} />
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              <Figure>{formatCount(recorder.completed)}</Figure> recorded
            </Text>
            <Text size="sm" c="dimmed">
              <Figure>{formatCount(queue.size)}</Figure> waiting
            </Text>
          </Group>
        </Stack>
      </Stack>
    </Paper>
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
        count={(day: RecorderDay) => plural(day.recordings, "recording")}
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

/**
 * The hot cache: its bytes against the size at which a trim starts, with the size a trim cuts down to marked on the
 * meter. Shows the bytes and files alone while either limit is not known.
 */
const CacheMeter = ({ cache, storage }: { cache: CacheUsage; storage: StorageStatistics }) => {
  const { cacheMaxBytes: max, cacheTargetBytes: target } = storage;
  if (max === null || target === null) {
    return (
      <Stack gap={6}>
        <Headline figure={formatBytes(cache.bytes)} caption="in the hot cache" />
        <Text size="sm" c="dimmed">
          {plural(cache.files, "file")}
        </Text>
      </Stack>
    );
  }
  return (
    <Stack gap={6}>
      <Headline figure={`${formatBytes(cache.bytes)} of ${formatBytes(max)}`} caption="in the hot cache" />
      <Tooltip
        label={`${formatBytes(cache.bytes)}, ${formatShare(cache.bytes, max)}`}
        events={{ hover: true, focus: false, touch: true }}
      >
        <div className={classes.meter} aria-hidden="true">
          <div className={classes.meterFill} style={{ width: `${percent(cache.bytes, max)}%` }} />
          <div className={classes.tick} style={{ left: `${percent(target, max)}%` }} />
        </div>
      </Tooltip>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {plural(cache.files, "file")}
        </Text>
        <Text size="sm" c="dimmed">
          target {formatBytes(target)}
        </Text>
      </Group>
    </Stack>
  );
};

const StoragePanel = ({ storage }: { storage: StorageStatistics }) => {
  const { cache } = storage;
  return (
    <Panel title="Storage">
      {cache === null ? (
        <Headline figure="Measuring…" caption="in the hot cache" />
      ) : (
        <CacheMeter cache={cache} storage={storage} />
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
        count={(day: PersonalBestDay) => plural(day.personalBests, "personal best")}
      />
      <Text>
        <Figure>{formatCount(personalBests.players)}</Figure> players with a personal best in the last 30 days
      </Text>
    </Panel>
  );
};

/**
 * The demos that name a package no source holds, listed with the package each misses. Hidden once the demos are loaded
 * and the server has stored the package of none of them.
 */
const MissingPackagesPanel = () => {
  const query = useUnresolvable();
  if (query.data !== undefined && !query.data.some(hasMissingPackage)) return null;
  return (
    <Panel title="Missing packages">
      <MissingPackageList query={query} />
    </Panel>
  );
};

/**
 * The aggregate statistics: the summary of the queue, then the recorder, storage and personal bests, and the missing
 * packages while any demo misses one.
 */
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
          <SummaryPanel queue={data.queue} recorder={data.recorder} />
          <SimpleGrid cols={{ base: 1, md: 3 }} className={classes.panels}>
            <RecorderPanel recorder={data.recorder} />
            <StoragePanel storage={data.storage} />
            <PersonalBestsPanel personalBests={data.personalBests} />
          </SimpleGrid>
          {data.queue.unresolvable > 0 && <MissingPackagesPanel />}
        </Stack>
      )}
    </Page>
  );
};
