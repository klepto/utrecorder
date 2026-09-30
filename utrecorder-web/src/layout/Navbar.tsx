import { ActionIcon, Anchor, Burger, Collapse, Container, Group, Stack } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconBrandDiscord, IconBrandGithub } from "@tabler/icons-react";
import { Link, NavLink } from "react-router";
import { DISCORD_URL, GITHUB_URL } from "../site";
import classes from "./Navbar.module.css";

const links = [
  { to: "/", label: "Home" },
  { to: "/recordings", label: "Recordings" },
  { to: "/about", label: "About" },
];

function NavLinks() {
  return links.map((link) => (
    <NavLink key={link.to} to={link.to} end={link.to === "/"} className={classes.link}>
      {link.label}
    </NavLink>
  ));
}

function ExternalLinks() {
  return (
    <>
      <ActionIcon
        component="a"
        href={DISCORD_URL}
        target="_blank"
        rel="noopener noreferrer"
        variant="subtle"
        size="lg"
        aria-label="Discord"
      >
        <IconBrandDiscord />
      </ActionIcon>
      <ActionIcon
        component="a"
        href={GITHUB_URL}
        target="_blank"
        rel="noopener noreferrer"
        variant="subtle"
        size="lg"
        aria-label="GitHub"
      >
        <IconBrandGithub />
      </ActionIcon>
    </>
  );
}

/** The top bar: logo, page links and external icon links, collapsing behind a burger below `sm`. */
export function Navbar() {
  const [opened, { toggle, close }] = useDisclosure(false);

  return (
    <Container size="lg" h={60}>
      <Group h="100%" justify="space-between" wrap="nowrap">
        <Anchor component={Link} to="/" onClick={close} display="flex">
          <img src="/logo.svg" alt="utrecorder" height={28} />
        </Anchor>
        <Group gap="md" visibleFrom="sm">
          <NavLinks />
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
}
