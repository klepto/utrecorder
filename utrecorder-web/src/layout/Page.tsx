import { Container, Stack, Title } from "@mantine/core";
import type { ReactNode } from "react";

/** The frame of a page: its content in a large container, stacked below the title of the page. */
export const Page = ({ title, children }: { title: string; children: ReactNode }) => {
  return (
    <Container size="lg" py="xl">
      <Stack>
        <Title order={2}>{title}</Title>
        {children}
      </Stack>
    </Container>
  );
};
