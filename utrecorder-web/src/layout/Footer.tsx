import { Anchor, Container, Group, Text } from "@mantine/core";
import { IconHeartFilled } from "@tabler/icons-react";
import { AUTHOR_NAME, AUTHOR_USER_ID, NEW_TAB, playerUrl } from "../site";
import classes from "./Footer.module.css";

/** The bottom bar: the version of utrecorder on the left, and a credit linking the author to their utbt.net profile on the right. */
export const Footer = () => {
  return (
    <footer className={classes.footer}>
      <Container size="lg">
        <Group justify="space-between" py="md">
          <Text size="sm" c="dimmed">
            version {__APP_VERSION__}
          </Text>
          <Text size="sm" c="dimmed">
            made with{" "}
            <IconHeartFilled
              size={14}
              aria-label="love"
              className={classes.heart}
            />{" "}
            by{" "}
            <Anchor size="sm" href={playerUrl(AUTHOR_USER_ID)} {...NEW_TAB}>
              {AUTHOR_NAME}
            </Anchor>
          </Text>
        </Group>
      </Container>
    </footer>
  );
};
