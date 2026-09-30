import { Text, type TextProps, Tooltip } from "@mantine/core";
import { formatDate, formatExactTime, formatRelativeTime } from "../format";

/**
 * The ISO-8601 instant `value` as `sm` text, whose tooltip holds the exact date and time. It reads as a date, or, if
 * `relative`, as the distance back from now.
 */
export const Timestamp = ({ value, relative = false, ...props }: { value: string; relative?: boolean } & TextProps) => {
  return (
    <Tooltip label={formatExactTime(value)}>
      <Text size="sm" {...props}>
        {relative ? formatRelativeTime(value) : formatDate(value)}
      </Text>
    </Tooltip>
  );
};
