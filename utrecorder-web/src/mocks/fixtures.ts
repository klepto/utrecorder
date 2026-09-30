import type { Demo, Job, Recording } from "../api/types";

/** Returns a sample demo of the cap `capId`. */
const demo = (capId: string, map: string, player: string, capTime: string, timestamp: string): Demo => {
  return {
    capId,
    map,
    player,
    userId: Number(capId) % 3 === 0 ? null : `u${capId}`,
    capTime,
    timestamp,
    demoUrl: `https://example.com/demos/${capId}.dem`,
  };
};

/** Sample running recordings: one that has not reported a stage yet, and one on its second attempt. */
export const jobs: Job[] = [
  {
    demo: demo("1100", "CTF-BT-Maverick", "Shadow", "PT1M2.345S", "2026-09-28T18:04:11Z"),
    attempt: 1,
    stage: null,
    progress: null,
    startedAt: "2026-09-28T18:05:00Z",
  },
  {
    demo: demo("1099", "CTF-BT-Skyfall", "Nyx", "PT48.12S", "2026-09-28T17:51:40Z"),
    attempt: 2,
    stage: "Capturing",
    progress: 0.42,
    startedAt: "2026-09-28T17:52:03Z",
  },
];

/** Sample queue: the demos to be recorded next, in the order they are taken. */
export const queue: Demo[] = [
  demo("1101", "CTF-BT-Andromeda", "Vex", "PT2M10.5S", "2026-09-28T18:10:22Z"),
  demo("1102", "CTF-BT-Maverick", "Kite", "PT59.999S", "2026-09-28T18:12:09Z"),
  demo("1103", "CTF-BT-Citadel", "Shadow", "PT3M0.01S", "2026-09-28T18:15:47Z"),
  demo("1104", "CTF-BT-Skyfall", "Orin", "PT41.7S", "2026-09-28T18:20:31Z"),
  demo("1105", "CTF-BT-Andromeda", "Nyx", "PT1M30S", "2026-09-28T18:22:02Z"),
];

const maps = ["CTF-BT-Maverick", "CTF-BT-Skyfall", "CTF-BT-Andromeda", "CTF-BT-Citadel"];
const players = ["Shadow", "Nyx", "Vex", "Kite", "Orin"];
const statuses = ["Recorded", "Uploaded", "Archived"] as const;

/** Returns the `index`-th sample recording, newest first, recorded `index` hours before the newest. */
const recording = (index: number): Recording => {
  const capId = String(1098 - index);
  const recordedAt = new Date(Date.UTC(2026, 8, 28, 17, 30) - index * 3_600_000).toISOString();
  const status = statuses[index % statuses.length];
  return {
    demo: demo(
      capId,
      maps[index % maps.length],
      players[index % players.length],
      `PT${40 + index}.${String(index * 37).padStart(3, "0")}S`,
      recordedAt,
    ),
    status,
    error: null,
    md5: status === "Recorded" ? null : `9e107d9d372bb6826bd81d3542a419${String(index).padStart(2, "0")}`,
    lastUpdate: recordedAt,
    views: index * 3,
    lastView: index % 2 === 0 ? null : recordedAt,
    recordedAt,
    processingTime: `PT${90 + index}.5S`,
  };
};

/**
 * Sample recordings, newest first as the server orders them: 23 recorded by the server, and one last that was not, so
 * its `recordedAt` and `processingTime` are `null`.
 */
export const recordings: Recording[] = [
  ...Array.from({ length: 23 }, (_, index) => recording(index)),
  {
    demo: demo("1050", "CTF-BT-Citadel", "Kite", "PT2M5.5S", "2026-09-20T09:00:00Z"),
    status: "Archived",
    error: null,
    md5: "e4d909c290d0fb1ca068ffaddf22cbd0",
    lastUpdate: "2026-09-20T12:00:00Z",
    views: 12,
    lastView: "2026-09-27T21:14:00Z",
    recordedAt: null,
    processingTime: null,
  },
];
