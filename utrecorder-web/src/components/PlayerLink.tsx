import { Anchor } from "@mantine/core";
import type { Demo } from "../api/types";
import { NEW_TAB, playerUrl } from "../site";

/**
 * The alias of the player of `demo`, linked to their utbt.net profile if the cap list names them. A click on the link
 * does not reach the elements around it.
 */
export const PlayerLink = ({ demo }: { demo: Demo }) => {
  if (demo.userId === null) return demo.player;
  return (
    <Anchor href={playerUrl(demo.userId)} {...NEW_TAB} inherit onClick={(event) => event.stopPropagation()}>
      {demo.player}
    </Anchor>
  );
};
