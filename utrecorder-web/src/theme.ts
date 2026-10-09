import { Anchor, type CSSVariablesResolver, createTheme } from "@mantine/core";
import classes from "./theme.module.css";

const fontFamily = "Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif";

/** The Mantine theme of the site: palette, fonts, radius, and links that glow in their own color on hover. */
export const theme = createTheme({
  primaryColor: "red",
  fontFamily,
  headings: { fontFamily },
  defaultRadius: "lg",
  components: {
    Anchor: Anchor.extend({
      defaultProps: { underline: "never" },
      classNames: { root: classes.anchor },
    }),
  },
});

/** The CSS variables the site sets: `--utrecorder-color-chrome`, the navbar and footer background, the shade of a card. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {},
  dark: {
    "--utrecorder-color-chrome": "var(--mantine-color-dark-6)",
  },
});
