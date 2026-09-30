import { Anchor, Text } from "@mantine/core";
import { Link } from "react-router";
import { Page } from "../layout/Page";

/** Shown for every path no route matches. */
export const NotFoundPage = () => {
  return (
    <Page title="Page not found">
      <Text>This page does not exist.</Text>
      <Anchor component={Link} to="/">
        Back to the home page
      </Anchor>
    </Page>
  );
};
