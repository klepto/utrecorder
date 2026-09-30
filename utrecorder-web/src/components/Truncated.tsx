import { Text, type TextProps, Tooltip } from "@mantine/core";

/** Text cut with an ellipsis at the end of its line, whose tooltip holds the whole of it. */
export function Truncated({ text, ...props }: { text: string } & TextProps) {
  return (
    <Tooltip label={text} openDelay={300}>
      <Text truncate="end" {...props}>
        {text}
      </Text>
    </Tooltip>
  );
}
