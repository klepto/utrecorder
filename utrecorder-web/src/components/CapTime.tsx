import type { Demo } from "../api/types";
import { formatCapTime } from "../format";
import bronze from "./medals/bronze.webp";
import casual from "./medals/casual.webp";
import certified from "./medals/certified.webp";
import champion from "./medals/champion.webp";
import gold from "./medals/gold.webp";
import silver from "./medals/silver.webp";
import worldRecord from "./medals/world-record.webp";

/** A medal of UTBT, with the name utbt.net gives it, its icon and the color its times are written in. */
interface Medal {
  name: string;
  icon: string;
  color: string;
}

/** The medals of UTBT, indexed by the number the cap list gives each. */
const MEDALS: Medal[] = [
  { name: "Casual", icon: casual, color: "var(--mantine-color-dimmed)" },
  { name: "Certified", icon: certified, color: "var(--mantine-color-dimmed)" },
  { name: "Bronze Medal", icon: bronze, color: "var(--mantine-color-dimmed)" },
  { name: "Silver Medal", icon: silver, color: "var(--mantine-color-dimmed)" },
  { name: "Gold Medal", icon: gold, color: "oklch(85.2% .12 91.936)" },
  { name: "Champion Medal", icon: champion, color: "oklch(64% .17 22.216)" },
  { name: "World Record", icon: worldRecord, color: "oklch(70.7% .1 254.624)" },
];

/** The medal numbered `medal`, or the casual medal if it is `null` or a number this site does not know. */
const medalOf = (medal: number | null): Medal => {
  return (medal !== null && MEDALS[medal]) || MEDALS[0];
};

/**
 * The cap time of `demo` as `formatCapTime` writes it, in the color of its medal, after the icon of its medal named by
 * its alt text. If `medal` is provided, shows that medal instead of the medal of `demo`.
 */
export const CapTime = ({ demo, medal = demo.medal }: { demo: Demo; medal?: number | null }) => {
  const { name, icon, color } = medalOf(medal);
  return (
    <span style={{ whiteSpace: "nowrap", color }}>
      <img
        src={icon}
        alt={name}
        width={14}
        height={14}
        style={{ verticalAlign: "calc(-0.2em + 2px)", marginRight: 4 }}
      />
      {formatCapTime(demo.capTime)}
    </span>
  );
};
