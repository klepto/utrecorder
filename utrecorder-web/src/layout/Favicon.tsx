import { useMantineTheme } from "@mantine/core";
import { useEffect } from "react";
import favicon from "./favicon.svg?raw";

/** Sets the favicon of the page to the camera of the logo, in the anchor color of the theme. Renders nothing. */
export const Favicon = () => {
  const theme = useMantineTheme();
  const color = theme.colors[theme.primaryColor][4];

  useEffect(() => {
    let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    if (!link) {
      link = document.createElement("link");
      link.rel = "icon";
      document.head.append(link);
    }
    link.type = "image/svg+xml";
    link.href = `data:image/svg+xml,${encodeURIComponent(favicon.replace(/fill="[^"]*"/, `fill="${color}"`))}`;
  }, [color]);

  return null;
};
