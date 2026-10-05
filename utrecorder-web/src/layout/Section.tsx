import { Group, Stack, Title } from "@mantine/core";
import type { ReactNode } from "react";

/** One titled section of a page: its content stacked below its title, and `aside` at the right of the title. */
export const Section = ({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) => {
  return (
    <Stack>
      <Group justify="space-between" align="baseline" gap="xs">
        <Title order={2}>{title}</Title>
        {aside}
      </Group>
      {children}
    </Stack>
  );
};
