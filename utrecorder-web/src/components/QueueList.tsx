import { Table, Text } from "@mantine/core";
import { useEffect, useState } from "react";
import type { QueueHead, Recording } from "../api/types";
import { formatRemaining } from "../format";
import { CapTime } from "./CapTime";
import { capLinkProps } from "./capLinkProps";
import type { JobsState } from "./JobsState";
import { PlayerLink } from "./PlayerLink";
import { QueryList, type QueryState } from "./QueryList";
import classes from "./QueueList.module.css";
import { Timestamp } from "./Timestamp";
import { Truncated } from "./Truncated";
import { useNextStart } from "./useNextStart";

/**
 * The start of the next demo: an epoch millisecond, `null` while it is not known yet, or `"uploads"` while the backlog
 * waits for uploads to catch up.
 */
type NextStart = number | null | "uploads";

/**
 * Returns the start of the next demo `next`: the later of `deadline`, the estimate from the running capture, and the
 * time `head` lets it start. If `head` is not about `next`, follows `deadline` alone.
 */
const nextStart = (
  next: string | undefined,
  deadline: number | null,
  head: QueueHead | null | undefined,
): NextStart => {
  if (!head || head.capId !== next) return deadline;
  if (head.startsAt === null) return "uploads";
  return deadline === null ? null : Math.max(deadline, Date.parse(head.startsAt));
};

/** Returns the text of the up next label for `start`. */
const nextText = (start: NextStart) => {
  if (start === "uploads") return "up next once uploads catch up";
  return start === null ? "up next in …" : `up next in ~${formatRemaining(start)}`;
};

/**
 * The label of the next demo: "up next in" and the time left until `start`, marked with `~` as an estimate and
 * updated every second. While `start` is not known, the time reads "…".
 */
const NextLabel = ({ start }: { start: NextStart }) => {
  const [, tick] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => tick((ticks) => ticks + 1), 1_000);
    return () => clearInterval(timer);
  }, []);

  return (
    <Text size="xs" c="var(--mantine-color-anchor)" className={classes.label}>
      {nextText(start)}
    </Text>
  );
};

/**
 * One queued recording, whose click opens the cap on utbt.net. Its cap time shows the medal of its run. If `next`, it
 * is outlined and labelled as up next, with the time left until `start`.
 */
const QueueRow = ({ recording, next, start }: { recording: Recording; next: boolean; start: NextStart }) => {
  const { demo, runMedal } = recording;
  return (
    <Table.Tr className={next ? `${classes.row} ${classes.next}` : classes.row} {...capLinkProps(demo)}>
      <Table.Td>
        {next && <NextLabel start={start} />}
        <Truncated text={demo.map} size="sm" />
      </Table.Td>
      <Table.Td>
        <Text size="sm" truncate="end">
          <PlayerLink demo={demo} />
        </Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm">
          <CapTime demo={demo} medal={runMedal ?? demo.medal} />
        </Text>
      </Table.Td>
      <Table.Td ta="right">
        <Timestamp value={demo.timestamp} />
      </Table.Td>
    </Table.Tr>
  );
};

interface QueueListProps {
  query: QueryState<Recording>;
  /** The running jobs, whose capture the up next estimate follows. */
  jobs: JobsState;
  /** The head of the queue as the server reports it, `null` for an empty queue, or `undefined` until it is fetched. */
  head: QueueHead | null | undefined;
}

/**
 * The queued recordings as a table of map, player, cap time and date added, in the order the recorder takes them. A
 * border and a label mark the first row as up next, with the time left until it starts, estimated from the capture
 * among `jobs` and held back until the time `head` reports. A click on a row opens the cap on utbt.net.
 */
export const QueueList = ({ query, jobs, head }: QueueListProps) => {
  const next = query.data?.[0]?.demo.capId;
  const start = nextStart(next, useNextStart(next, jobs.data, jobs.dataUpdatedAt), head);
  return (
    <QueryList query={query} empty="The queue is empty.">
      {(recordings) => (
        <Table
          layout="fixed"
          verticalSpacing="var(--row-gap)"
          horizontalSpacing="xs"
          withRowBorders={false}
          className={classes.table}
        >
          <colgroup>
            <col style={{ width: "40%" }} />
            <col style={{ width: "20%" }} />
            <col style={{ width: "20%" }} />
            <col style={{ width: "20%" }} />
          </colgroup>
          <Table.Tbody>
            {recordings.map((recording, index) => (
              <QueueRow key={recording.demo.capId} recording={recording} next={index === 0} start={start} />
            ))}
          </Table.Tbody>
        </Table>
      )}
    </QueryList>
  );
};
