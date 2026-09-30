import { Container, Stack, Text, Title } from "@mantine/core";

/** Static text about the project. */
export function AboutPage() {
  return (
    <Container size="lg" py="xl">
      <Stack>
        <Title order={2}>About</Title>
        <Text>utrecorder records Unreal Tournament demos to mp4. This text is a placeholder.</Text>
      </Stack>
    </Container>
  );
}
