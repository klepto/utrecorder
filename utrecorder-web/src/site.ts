/** Invite URL of the Discord server of the project. */
export const DISCORD_URL = "https://discord.gg/utrecorder";

/** URL of the GitHub repository of the project. */
export const GITHUB_URL = "https://github.com/klepto/utrecorder";

/** Name of the author of the project. */
export const AUTHOR_NAME = "klepto";

/** Returns the URL of the utbt.net page of the cap `capId`. */
export function capUrl(capId: string): string {
  return `https://utbt.net/caps/${encodeURIComponent(capId)}`;
}

/** Returns the URL of the utbt.net profile of the player `userId`. */
export function playerUrl(userId: string): string {
  return `https://utbt.net/players/${encodeURIComponent(userId)}`;
}

/** Returns the URL of the 96 by 96 pixel screenshot that utbt.net serves for `map`. */
export function mapThumbnailUrl(map: string): string {
  return `https://api.utbt.net/screenshots/derived/96/${encodeURIComponent(map)}.webp`;
}

/** Opens `url` in a new tab. */
export function openInNewTab(url: string) {
  window.open(url, "_blank", "noopener,noreferrer");
}
