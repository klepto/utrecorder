import { Anchor } from "@mantine/core";
import type { Demo } from "../api/types";
import { NEW_TAB, playerUrl } from "../site";

/**
 * The alias of the player of `demo`, linked to their utbt.net profile if the cap list names them. A click or key press
 * on the link does not reach the elements around it, so Enter only follows the link, as a click does.
 */
export const PlayerLink = ({ demo }: { demo: Demo }) => {
  if (demo.userId === null) return demo.player;
  return (
    <Anchor
      href={playerUrl(demo.userId)}
      {...NEW_TAB}
      inherit
      onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
    >
      {demo.player}
    </Anchor>
  );
};
