import { ActionIcon, Anchor, Burger, Collapse, Container, Divider, Group, Stack } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconBrandDiscordFilled, IconBrandGithubFilled } from "@tabler/icons-react";
import { Link, NavLink } from "react-router";
import { DISCORD_URL, GITHUB_URL, NEW_TAB } from "../site";
import { HEADER_HEIGHT } from "./header";
import classes from "./Navbar.module.css";

const links = [
  { to: "/", label: "Home" },
  { to: "/recordings", label: "Recordings" },
  { to: "/about", label: "About" },
];

const NavLinks = () => {
  return links.map((link) => (
    <NavLink key={link.to} to={link.to} end={link.to === "/"} className={classes.link}>
      {link.label}
    </NavLink>
  ));
};

const externalLinks = [
  { href: DISCORD_URL, label: "Discord", Icon: IconBrandDiscordFilled },
  { href: GITHUB_URL, label: "GitHub", Icon: IconBrandGithubFilled },
];

const ExternalLinks = () => {
  return externalLinks.map((link) => (
    <ActionIcon
      key={link.href}
      component="a"
      href={link.href}
      {...NEW_TAB}
      variant="subtle"
      color="var(--mantine-color-anchor)"
      size="lg"
      aria-label={link.label}
    >
      <link.Icon />
    </ActionIcon>
  ));
};

/**
 * The top bar: logo, then page links set off from it by a divider, and external icon links on the right. Below `sm`,
 * the links collapse behind a burger.
 */
export const Navbar = () => {
  const [opened, { toggle, close }] = useDisclosure(false);

  return (
    <Container size="lg" h={HEADER_HEIGHT}>
      <Group h="100%" justify="space-between" wrap="nowrap">
        <Group gap="lg" wrap="nowrap">
          <Anchor component={Link} to="/" onClick={close} display="flex">
            <span className={classes.logo} role="img" aria-label="utrecorder" />
          </Anchor>
          <Divider orientation="vertical" h={24} my="auto" visibleFrom="sm" />
          <Group gap="lg" visibleFrom="sm">
            <NavLinks />
          </Group>
        </Group>
        <Group gap="xs" visibleFrom="sm">
          <ExternalLinks />
        </Group>
        <Burger opened={opened} onClick={toggle} hiddenFrom="sm" aria-label="Toggle navigation" />
      </Group>
      <Collapse in={opened} hiddenFrom="sm" className={classes.menu}>
        <Stack gap="xs" p="md" onClick={close}>
          <NavLinks />
          <Group gap="xs">
            <ExternalLinks />
          </Group>
        </Stack>
      </Collapse>
    </Container>
  );
};
