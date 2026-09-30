import { Table, Text, Tooltip } from "@mantine/core";
import type { Demo } from "../api/types";
import { formatCapTime, formatDate, formatExactTime } from "../format";
import { capUrl, openInNewTab } from "../site";
import { PlayerLink } from "./PlayerLink";
import { QueryList, type QueryState } from "./QueryList";
import classes from "./QueueList.module.css";
import { Truncated } from "./Truncated";

/** One queued demo, whose click opens the cap on utbt.net. If `next`, it is outlined and labelled as up next. */
function QueueRow({ demo, next }: { demo: Demo; next: boolean }) {
  return (
    <Table.Tr
      tabIndex={0}
      aria-label={`${demo.map} on utbt.net`}
      className={next ? `${classes.row} ${classes.next}` : classes.row}
      onClick={() => openInNewTab(capUrl(demo.capId))}
      onKeyDown={(event) => event.key === "Enter" && openInNewTab(capUrl(demo.capId))}
    >
      <Table.Td>
        {next && (
          <Text size="xs" c="var(--mantine-color-anchor)" className={classes.label}>
            up next
          </Text>
        )}
        <Truncated text={demo.map} size="sm" />
      </Table.Td>
      <Table.Td>
        <Text size="sm" truncate="end">
          <PlayerLink demo={demo} />
        </Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{formatCapTime(demo.capTime)}</Text>
      </Table.Td>
      <Table.Td>
        <Tooltip label={formatExactTime(demo.timestamp)}>
          <Text size="sm">{formatDate(demo.timestamp)}</Text>
        </Tooltip>
      </Table.Td>
    </Table.Tr>
  );
}

/**
 * The queued demos as a table of map, player, cap time and date added, in the order the recorder takes them. A border
 * and a label mark the first row as up next, and a click on a row opens the cap on utbt.net.
 */
export function QueueList({ query }: { query: QueryState<Demo> }) {
  return (
    <QueryList query={query} empty="The queue is empty.">
      {(demos) => (
        <Table
          layout="fixed"
          verticalSpacing="xs"
          horizontalSpacing="xs"
          withRowBorders={false}
          className={classes.table}
        >
          <colgroup>
            <col />
            <col style={{ width: "30%" }} />
            <col style={{ width: "4.5rem" }} />
            <col style={{ width: "6.5rem" }} />
          </colgroup>
          <Table.Tbody>
            {demos.map((demo, index) => (
              <QueueRow key={demo.capId} demo={demo} next={index === 0} />
            ))}
          </Table.Tbody>
        </Table>
      )}
    </QueryList>
  );
}
