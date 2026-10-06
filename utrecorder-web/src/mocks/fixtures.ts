import type {
  Demo,
  Job,
  PersonalBestDay,
  QueueHead,
  RecorderDay,
  Recording,
  RecordingStatus,
  Statistics,
} from "../api/types";

/** Returns a sample demo of the cap `capId`, whose medal cycles through every medal and none. */
const demo = (capId: string, map: string, player: string, capTime: string, timestamp: string): Demo => {
  return {
    capId,
    map,
    player,
    userId: Number(capId) % 3 === 0 ? null : `u${capId}`,
    capTime,
    timestamp,
    demoUrl: `https://example.com/demos/${capId}.dem`,
    medal: Number(capId) % 8 === 7 ? null : Number(capId) % 8,
  };
};

/** Returns the recording of `sample` with `status`, not recorded yet, whose run medal is the medal of `sample`. */
const unrecorded = (sample: Demo, status: RecordingStatus): Recording => {
  return {
    demo: sample,
    status,
    error: null,
    md5: null,
    lastUpdate: sample.timestamp,
    views: 0,
    lastView: null,
    recordedAt: null,
    processingTime: null,
    itemId: null,
    size: null,
    width: null,
    height: null,
    fps: null,
    runMedal: sample.medal,
  };
};

/** Sample running recordings: one that has not reported a stage yet, and one on its second attempt. */
export const jobs: Job[] = [
  {
    recording: unrecorded(demo("1100", "CTF-BT-Maverick", "Shadow", "PT1M2.345S", "2026-09-28T18:04:11Z"), "Recording"),
    attempt: 1,
    stage: null,
    progress: null,
    startedAt: "2026-09-28T18:05:00Z",
  },
  {
    recording: unrecorded(demo("1099", "CTF-BT-Skyfall", "Nyx", "PT48.12S", "2026-09-28T17:51:40Z"), "Recording"),
    attempt: 2,
    stage: "Capturing",
    progress: 0.42,
    startedAt: "2026-09-28T17:52:03Z",
  },
];

/** Sample queue: the recordings to be captured next, in the order they are taken. */
export const queue: Recording[] = [
  demo("1101", "CTF-BT-Andromeda", "Vex", "PT2M10.5S", "2026-09-28T18:10:22Z"),
  demo("1102", "CTF-BT-Maverick", "Kite", "PT59.999S", "2026-09-28T18:12:09Z"),
  demo("1103", "CTF-BT-Citadel", "Shadow", "PT3M0.01S", "2026-09-28T18:15:47Z"),
  demo("1104", "CTF-BT-Skyfall", "Orin", "PT41.7S", "2026-09-28T18:20:31Z"),
  demo("1105", "CTF-BT-Andromeda", "Nyx", "PT1M30S", "2026-09-28T18:22:02Z"),
].map((sample) => unrecorded(sample, "Pending"));

const maps = ["CTF-BT-Maverick", "CTF-BT-Skyfall", "CTF-BT-Andromeda", "CTF-BT-Citadel"];
const players = ["Shadow", "Nyx", "Vex", "Kite", "Orin"];
const statuses = ["Recorded", "Uploaded", "Archived", "Outdated"] as const;

/** Returns the `index`-th sample recording, newest first, recorded `index` hours before the newest. */
const recording = (index: number): Recording => {
  const capId = String(1098 - index);
  const recordedAt = new Date(Date.UTC(2026, 8, 28, 17, 30) - index * 3_600_000).toISOString();
  const status = statuses[index % statuses.length];
  const sample = demo(
    capId,
    maps[index % maps.length],
    players[index % players.length],
    `PT${40 + index}.${String(index * 37).padStart(3, "0")}S`,
    recordedAt,
  );
  const uploaded = status !== "Recorded" && sample.userId !== null;
  return {
    demo: sample,
    status,
    error: null,
    md5: status === "Recorded" ? null : `9e107d9d372bb6826bd81d3542a419${String(index).padStart(2, "0")}`,
    lastUpdate: recordedAt,
    views: index * 3,
    lastView: index % 2 === 0 ? null : recordedAt,
    recordedAt,
    processingTime: `PT${90 + index}.5S`,
    itemId: uploaded ? `${sample.userId}-${sample.map.toLowerCase()}` : null,
    size: status === "Archived" ? null : 20_000_000 + index * 1_500_000,
    width: index % 3 === 2 ? 1280 : 1920,
    height: index % 3 === 2 ? 720 : 1080,
    fps: 60,
    runMedal: sample.medal,
  };
};

/** Sample head of the queue: the first recording of `queue`, which the backlog limits let start at once. */
export const queueHead: QueueHead = { capId: "1101", startsAt: "2026-09-28T18:10:22Z" };

/**
 * Sample recordings, newest first as the server orders them: 23 recorded by the server, and one last that was not, so
 * its `recordedAt`, `processingTime`, `width`, `height` and `fps` are `null`.
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
    itemId: "u1050-ctf-bt-citadel",
    size: null,
    width: null,
    height: null,
    fps: null,
    runMedal: null,
  },
];

/** Returns the ISO-8601 duration of `total` whole seconds, such as `PT1H2M3S`. */
const seconds = (total: number): string => {
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;
  return `PT${hours ? `${hours}H` : ""}${minutes ? `${minutes}M` : ""}${rest ? `${rest}S` : ""}`.replace(
    /^PT$/,
    "PT0S",
  );
};

const DAYS = 30;
const TODAY = Date.UTC(2026, 8, 28);
const ZERO_DAY = 14;

/** Returns the UTC date `YYYY-MM-DD` of the day `back` days before the sample today. */
const dayDate = (back: number): string => new Date(TODAY - back * 86_400_000).toISOString().slice(0, 10);

/** Returns the `index`-th recorder day, oldest first: the middle day holds zeros, and the last day is partial. */
const recorderDay = (index: number): RecorderDay => {
  const date = dayDate(DAYS - 1 - index);
  if (index === ZERO_DAY) return { date, recordings: 0, capTime: "PT0S", processingTime: "PT0S" };
  const recordings = index === DAYS - 1 ? 9 : 14 + ((index * 7) % 19);
  return { date, recordings, capTime: seconds(recordings * 52), processingTime: seconds(recordings * 131) };
};

/** Returns the `index`-th personal best day, oldest first: the middle day holds zeros, and the last day is partial. */
const personalBestDay = (index: number): PersonalBestDay => {
  const date = dayDate(DAYS - 1 - index);
  if (index === ZERO_DAY) return { date, personalBests: 0, players: 0 };
  const personalBests = index === DAYS - 1 ? 22 : 30 + ((index * 11) % 41);
  return { date, personalBests, players: Math.ceil(personalBests / 3) };
};

/** Sample statistics: 30 days oldest first, one day of zeros in the middle and a partial today. */
export const statistics: Statistics = {
  generatedAt: "2026-09-28T17:45:00Z",
  queue: { size: 148, recent: 31, backlog: 117, capTime: "PT2H4M", recording: 2, failed: 3, unresolvable: 5 },
  recorder: {
    pace: 2.5,
    lastDay: { recordings: 23, capTime: "PT19M56S" },
    days: Array.from({ length: DAYS }, (_, index) => recorderDay(index)),
    completed: 1284,
    views: 20_417,
  },
  storage: {
    cache: { bytes: 6_012_954_214, files: 262, measuredAt: "2026-09-28T17:44:00Z" },
    cacheTargetBytes: 5_368_709_120,
    cacheMaxBytes: 10_737_418_240,
    archived: 912,
    archivedBytes: 19_327_352_832,
    archivedUnsized: 14,
    uploadsLastDay: 17,
    dailyUploads: 400,
  },
  personalBests: {
    lastDay: 61,
    days: Array.from({ length: DAYS }, (_, index) => personalBestDay(index)),
    players: 412,
    demos: 3071,
    maps: [
      { map: "CTF-BT-Maverick", personalBests: 612 },
      { map: "CTF-BT-Skyfall", personalBests: 488 },
      { map: "CTF-BT-Andromeda", personalBests: 301 },
      { map: "CTF-BT-Citadel", personalBests: 147 },
    ],
  },
};
