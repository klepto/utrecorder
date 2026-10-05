import type { Recording } from "../api/types";
import { formatBytes, formatElapsed } from "../format";
import classes from "./RecordingStats.module.css";

/** Returns the figures of `recording` as term and value pairs, leaving out each one the server did not store. */
const figuresOf = (recording: Recording): [string, string][] => {
  const { width, height, fps, size, processingTime } = recording;
  return [
    ["Resolution", width !== null && height !== null ? `${width}×${height}` : null],
    ["Frame rate", fps !== null ? `${fps} fps` : null],
    ["File size", size !== null ? formatBytes(size) : null],
    ["Processing time", processingTime !== null ? formatElapsed(processingTime) : null],
  ].filter((figure): figure is [string, string] => figure[1] !== null);
};

/**
 * The figures of a recording as a description list: resolution, frame rate, file size and processing time. A figure
 * the server did not store is left out, and nothing renders when none is stored.
 */
export const RecordingStats = ({ recording }: { recording: Recording }) => {
  const figures = figuresOf(recording);
  if (figures.length === 0) return null;
  return (
    <dl className={classes.stats}>
      {figures.map(([term, value]) => (
        <div key={term}>
          <dt>{term}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
};
