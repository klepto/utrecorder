import { type CSSVariablesResolver, createTheme } from "@mantine/core";

const fontFamily = "Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif";

/** The Mantine theme of the site: palette, fonts and radius. */
export const theme = createTheme({
  primaryColor: "pink",
  fontFamily,
  headings: { fontFamily },
  defaultRadius: "lg",
});

/** The CSS variables the site sets: `--utrecorder-color-chrome`, the navbar and footer background, the shade of a card. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {},
  dark: {
    "--utrecorder-color-chrome": "var(--mantine-color-dark-6)",
  },
});
