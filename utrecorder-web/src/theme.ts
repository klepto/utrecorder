import { createTheme } from "@mantine/core";

const fontFamily = "Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif";

/** The Mantine theme of the site: palette, fonts and radius. */
export const theme = createTheme({
  primaryColor: "violet",
  fontFamily,
  headings: { fontFamily },
  defaultRadius: "md",
});
