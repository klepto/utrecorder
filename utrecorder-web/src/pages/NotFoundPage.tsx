import { Anchor, Container, Stack, Text, Title } from "@mantine/core";
import { Link } from "react-router";

/** Shown for every path no route matches. */
export function NotFoundPage() {
  return (
    <Container size="lg" py="xl">
      <Stack>
        <Title order={2}>Page not found</Title>
        <Text>This page does not exist.</Text>
        <Anchor component={Link} to="/">
          Back to the home page
        </Anchor>
      </Stack>
    </Container>
  );
}
