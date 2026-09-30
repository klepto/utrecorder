import { AppShell } from "@mantine/core";
import { Outlet } from "react-router";
import { Favicon } from "./Favicon";
import { Footer } from "./Footer";
import { HEADER_HEIGHT } from "./header";
import { Navbar } from "./Navbar";
import classes from "./Shell.module.css";

/**
 * The frame every page renders in: navbar on top, the routed page, and the footer at the bottom of the page or the
 * window, whichever is lower.
 */
export const Shell = () => {
  return (
    <AppShell header={{ height: HEADER_HEIGHT }}>
      <Favicon />
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
};
