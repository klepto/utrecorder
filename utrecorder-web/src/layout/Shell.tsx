import { AppShell } from "@mantine/core";
import { Outlet } from "react-router";
import { Footer } from "./Footer";
import { Navbar } from "./Navbar";

/** The frame every page renders in: navbar on top, the routed page, footer below. */
export function Shell() {
  return (
    <AppShell header={{ height: 60 }}>
      <AppShell.Header>
        <Navbar />
      </AppShell.Header>
      <AppShell.Main>
        <Outlet />
        <Footer />
      </AppShell.Main>
    </AppShell>
  );
}
