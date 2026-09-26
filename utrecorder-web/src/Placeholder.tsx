import { Center, Stack, Text, Title } from "@mantine/core";

/** Page shown until the site has real pages. */
export function Placeholder() {
  return (
    <Center h="100vh">
      <Stack align="center" gap="xs">
        <Title>utrecorder</Title>
        <Text c="dimmed" size="sm">
          {__APP_VERSION__} ({__APP_COMMIT__})
        </Text>
      </Stack>
    </Center>
  );
}
