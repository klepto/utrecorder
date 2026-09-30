import { Image } from "@mantine/core";
import { MAP_PLACEHOLDER_URL, mapThumbnailUrl } from "../site";

/** The utbt.net screenshot of `map`, `size` pixels square, or the placeholder if utbt.net has none. */
export const MapThumbnail = ({ map, size }: { map: string; size: number }) => {
  return (
    <Image src={mapThumbnailUrl(map)} fallbackSrc={MAP_PLACEHOLDER_URL} alt="" w={size} h={size} radius="default" />
  );
};
