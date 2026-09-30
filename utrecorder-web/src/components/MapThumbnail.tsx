import { Image } from "@mantine/core";
import { mapThumbnailUrl } from "../site";

/** The utbt.net screenshot of `map`, `size` pixels square, or the placeholder if utbt.net has none. */
export function MapThumbnail({ map, size }: { map: string; size: number }) {
  return (
    <Image src={mapThumbnailUrl(map)} fallbackSrc="/map-placeholder.svg" alt="" w={size} h={size} radius="default" />
  );
}
