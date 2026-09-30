import { AppShell } from "@mantine/core";
import { Outlet } from "react-router";
import { Footer } from "./Footer";
import { Navbar } from "./Navbar";
import classes from "./Shell.module.css";

/**
 * The frame every page renders in: navbar on top, the routed page, and the footer at the bottom of the page or the
 * window, whichever is lower.
 */
export function Shell() {
  return (
    <AppShell header={{ height: 60 }}>
      <AppShell.Header className={classes.header}>
        <Navbar />
      </AppShell.Header>
      <AppShell.Main className={classes.main}>
        <div className={classes.page}>
          <Outlet />
        </div>
        <Footer />
      </AppShell.Main>
    </AppShell>
  );
}
