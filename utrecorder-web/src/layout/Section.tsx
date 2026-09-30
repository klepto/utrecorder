import { Stack, Title } from "@mantine/core";
import type { ReactNode } from "react";

/** One titled section of a page: its content stacked below its title. */
export const Section = ({ title, children }: { title: string; children: ReactNode }) => {
  return (
    <Stack>
      <Title order={2}>{title}</Title>
      {children}
    </Stack>
  );
};
