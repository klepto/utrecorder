import { Table, Text } from "@mantine/core";
import { useEffect, useState } from "react";
import type { Recording } from "../api/types";
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

/** Returns the text of the up next label: "up next in ~" and the time left until `deadline`, or "up next in …" without one. */
const nextText = (deadline: number | null) => {
  return deadline === null ? "up next in …" : `up next in ~${formatRemaining(deadline)}`;
};

/**
 * The label of the next demo: "up next in" and the time left until `deadline`, marked with `~` as an estimate and
 * updated every second. Without a `deadline`, the time reads "…".
 */
const NextLabel = ({ deadline }: { deadline: number | null }) => {
  const [, tick] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => tick((ticks) => ticks + 1), 1_000);
    return () => clearInterval(timer);
  }, []);

  return (
    <Text size="xs" c="var(--mantine-color-anchor)" className={classes.label}>
      {nextText(deadline)}
    </Text>
  );
};

/**
 * One queued recording, whose click opens the cap on utbt.net. Its cap time shows the medal of its run. If `next`, it
 * is outlined and labelled as up next, with the time left until `deadline`.
 */
const QueueRow = ({ recording, next, deadline }: { recording: Recording; next: boolean; deadline: number | null }) => {
  const { demo, runMedal } = recording;
  return (
    <Table.Tr className={next ? `${classes.row} ${classes.next}` : classes.row} {...capLinkProps(demo)}>
      <Table.Td>
        {next && <NextLabel deadline={deadline} />}
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
}

/**
 * The queued recordings as a table of map, player, cap time and date added, in the order the recorder takes them. A
 * border and a label mark the first row as up next, with the time left until it starts, estimated from the capture
 * among `jobs`. A click on a row opens the cap on utbt.net.
 */
export const QueueList = ({ query, jobs }: QueueListProps) => {
  const deadline = useNextStart(query.data?.[0]?.demo.capId, jobs.data, jobs.dataUpdatedAt);
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
              <QueueRow key={recording.demo.capId} recording={recording} next={index === 0} deadline={deadline} />
            ))}
          </Table.Tbody>
        </Table>
      )}
    </QueryList>
  );
};
