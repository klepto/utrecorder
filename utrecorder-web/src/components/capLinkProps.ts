import type { KeyboardEvent } from "react";
import type { Demo } from "../api/types";
import { capUrl, openInNewTab } from "../site";

/**
 * Returns the props that make an element open the cap of `demo` on utbt.net: it is focusable, named after the
 * map, and a click or Enter opens the cap in a new tab.
 */
export const capLinkProps = (demo: Demo) => {
  const open = () => openInNewTab(capUrl(demo.capId));
  return {
    tabIndex: 0,
    "aria-label": `${demo.map} on utbt.net`,
    onClick: open,
    onKeyDown: (event: KeyboardEvent) => {
      if (event.key === "Enter") open();
    },
  };
};
