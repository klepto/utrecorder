/** Invite URL of the Discord server of the project. */
export const DISCORD_URL = "https://discord.gg/FZ9DVkkARN";

/** URL of the GitHub repository of the project. */
export const GITHUB_URL = "https://github.com/klepto/utrecorder";

/** Name of the author of the project. */
export const AUTHOR_NAME = "kleps";

/** The utbt.net user id of the author of the project. */
export const AUTHOR_USER_ID = "424992398657978370";

/** Returns the URL of the utbt.net page of the cap `capId`. */
export const capUrl = (capId: string): string => {
  return `https://utbt.net/caps/${encodeURIComponent(capId)}`;
};

/** Returns the URL of the utbt.net profile of the player `userId`. */
export const playerUrl = (userId: string): string => {
  return `https://utbt.net/players/${encodeURIComponent(userId)}`;
};

/** Returns the URL of the 96 by 96 pixel screenshot that utbt.net serves for `map`. */
export const mapThumbnailUrl = (map: string): string => {
  return `https://api.utbt.net/screenshots/derived/96/${encodeURIComponent(map)}.webp`;
};

/** URL of the 96 by 96 pixel placeholder that utbt.net shows for a map without a screenshot. */
export const MAP_PLACEHOLDER_URL = "https://api.utbt.net/screenshots/derived/96/default.webp";

/** Props that make a link open in a new tab that has no access to this page. */
export const NEW_TAB = { target: "_blank", rel: "noopener noreferrer" } as const;

/** Opens `url` in a new tab. */
export const openInNewTab = (url: string) => {
  window.open(url, "_blank", "noopener,noreferrer");
};
