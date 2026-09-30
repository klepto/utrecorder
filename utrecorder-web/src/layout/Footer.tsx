import { Anchor, Container, Group, Text } from "@mantine/core";
import { AUTHOR_NAME, GITHUB_URL } from "../site";
import classes from "./Footer.module.css";

/** The bottom bar: build version and commit, the author and a GitHub link. */
export function Footer() {
  return (
    <footer className={classes.footer}>
      <Container size="lg">
        <Group justify="space-between" py="md">
          <Text size="sm" c="dimmed">{`utrecorder ${__APP_VERSION__} (${__APP_COMMIT__})`}</Text>
          <Text size="sm" c="dimmed">
            {AUTHOR_NAME}
          </Text>
          <Anchor size="sm" href={GITHUB_URL} target="_blank" rel="noopener noreferrer">
            GitHub
          </Anchor>
        </Group>
      </Container>
    </footer>
  );
}
