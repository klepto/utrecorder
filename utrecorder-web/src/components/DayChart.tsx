import { Group, Stack, Text, Tooltip, VisuallyHidden } from "@mantine/core";
import { useId } from "react";
import { formatDate } from "../format";
import classes from "./DayChart.module.css";

interface DayChartProps<T extends { date: string }> {
  /** The series, oldest first, ending with today, which is partial. */
  days: T[];
  /** Picks the number a day holds. */
  value: (day: T) => number;
  /** Formats a number of the series. */
  format: (value: number) => string;
  /** Names the series, such as "Personal bests per day". */
  label: string;
  /** Describes what a day counts, such as "23 recordings", shown when its bar is hovered. */
  count: (day: T) => string;
}

const SLOT = 10;
const BAR = 7;
const HEIGHT = 64;
const STUB = 2;
const TILE = 8;

/**
 * A bar chart of one value per day, drawn as SVG: bars scaled to the largest value, a zero day as a stub in the context
 * color, today in flowing stripes. The maximum is written at the top left, the first date and "today" under the ends,
 * and a visually hidden table lists every day and value. Hovering anywhere in the column of a day shows its date and
 * its count.
 */
export const DayChart = <T extends { date: string }>({ days, value, format, label, count }: DayChartProps<T>) => {
  const pattern = `today-${useId().replace(/[^\w-]/g, "")}`;
  const values = days.map(value);
  const max = Math.max(0, ...values);
  const last = days.length - 1;
  const summary = `${label} for the last ${days.length} days, ${format(values[last] ?? 0)} today`;

  return (
    <Stack gap={4}>
      <Text size="xs" c="dimmed">
        {format(max)}
      </Text>
      <svg
        className={classes.chart}
        viewBox={`0 0 ${days.length * SLOT} ${HEIGHT}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={summary}
      >
        <defs>
          <pattern id={pattern} width={TILE} height={TILE} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <g className={classes.flow}>
              <rect className={classes.data} width={TILE * 2} height={TILE} />
              <rect className={classes.stripe} width={TILE / 2} height={TILE} />
              <rect className={classes.stripe} x={TILE} width={TILE / 2} height={TILE} />
            </g>
          </pattern>
        </defs>
        {days.map((day, index) => {
          const amount = values[index];
          const height = amount > 0 ? Math.max(STUB, (amount / max) * HEIGHT) : STUB;
          const fill = amount === 0 ? classes.context : index === last ? undefined : classes.data;
          return (
            <Tooltip
              key={day.date}
              label={`${index === last ? "Today" : formatDate(day.date, "UTC")}: ${count(day)}`}
              events={{ hover: true, focus: false, touch: true }}
            >
              <g className={classes.day}>
                <rect className={classes.column} x={index * SLOT} width={SLOT} height={HEIGHT} />
                <rect
                  className={fill}
                  fill={amount > 0 && index === last ? `url(#${pattern})` : undefined}
                  x={index * SLOT}
                  y={HEIGHT - height}
                  width={BAR}
                  height={height}
                />
              </g>
            </Tooltip>
          );
        })}
      </svg>
      <Group justify="space-between">
        <Text size="xs" c="dimmed">
          {days.length > 0 ? formatDate(days[0].date, "UTC") : ""}
        </Text>
        <Text size="xs" c="dimmed">
          today
        </Text>
      </Group>
      <VisuallyHidden>
        <table>
          <caption>{label}</caption>
          <thead>
            <tr>
              <th>Day</th>
              <th>Value</th>
            </tr>
          </thead>
          <tbody>
            {days.map((day, index) => (
              <tr key={day.date}>
                <td>{formatDate(day.date, "UTC")}</td>
                <td>{format(values[index])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </VisuallyHidden>
    </Stack>
  );
};
