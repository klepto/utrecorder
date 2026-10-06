/**
 * The server's JSON, exactly as the endpoints of `HttpService` answer it. Every field is present; a nullable one is
 * `null`, never missing. A duration is an ISO-8601 duration string such as `PT1M2.345S`, an instant an ISO-8601 UTC
 * string, and an enum its constant's name.
 */

/** A cap and its demo. */
export interface Demo {
  /** The identifier of the cap. */
  capId: string;
  /** The name of the map the cap was made on. */
  map: string;
  /** The alias of the player who capped. */
  player: string;
  /** The UTBT identifier of the player who capped, or `null` if the cap list leaves it out. */
  userId: string | null;
  /** The time the cap took, as an ISO-8601 duration. */
  capTime: string;
  /** The time the cap was added, as an ISO-8601 instant. */
  timestamp: string;
  /** The URL the `.dem` file downloads from. */
  demoUrl: string;
  /**
   * The medal of the cap, as UTBT numbers it: `0` casual, `1` certified, `2` bronze, `3` silver, `4` gold, `5`
   * champion, `6` world record. `null` if the cap list leaves it out, which reads as casual.
   */
  medal: number | null;
}

/** A step of a recording, in the order a recording runs them. */
export type RecorderStage = "FetchingDemo" | "PreparingInstallation" | "ResolvingPackages" | "Capturing" | "Muxing";

/** One running recording, as `/api/jobs` lists it. */
export interface Job {
  /** The recording being captured, as it was when the capture was claimed. */
  recording: Recording;
  /** The number of the current attempt, starting at `1`. */
  attempt: number;
  /** The stage of the current attempt, or `null` if it has not reported yet. */
  stage: RecorderStage | null;
  /** How much of `stage` is done, from `0.0` to `1.0`, or `null` if it has not reported yet. */
  progress: number | null;
  /** The time the first attempt started, as an ISO-8601 instant. */
  startedAt: string;
}

/** The head of the queue, as `/api/queue/next` reports it. */
export interface QueueHead {
  /** The `capId` of the demo at the head of the queue. */
  capId: string;
  /**
   * The earliest time a recorder may take the head, as an ISO-8601 instant, or `null` while the backlog waits for
   * uploads to catch up. Only the backlog limits set it, never a running capture.
   */
  startsAt: string | null;
}

/** State of a recording. */
export type RecordingStatus =
  | "Pending"
  | "Recording"
  | "Recorded"
  | "Uploaded"
  | "Archived"
  | "Outdated"
  | "Failed"
  | "Unresolvable";

/** Recording of one demo, as `/api/recordings` lists it. */
export interface Recording {
  /** The demo recorded. */
  demo: Demo;
  /** How far the recording has come. */
  status: RecordingStatus;
  /** The failure of the last capture, or `null` if none has failed. */
  error: string | null;
  /** The MD5 checksum of the uploaded mp4, or `null` if it was not uploaded. */
  md5: string | null;
  /** The time `status` last changed, as an ISO-8601 instant. */
  lastUpdate: string;
  /** The number of times the recording was requested. */
  views: number;
  /** The time the recording was last requested, or `null` if it never was. */
  lastView: string | null;
  /** The time the mp4 was stored, or `null` if the server did not record it. */
  recordedAt: string | null;
  /** The time the last attempt took to produce the mp4, as an ISO-8601 duration, or `null` if the server did not record it. */
  processingTime: string | null;
  /**
   * The Internet Archive item the mp4 was uploaded into, or `null` if the mp4 is not in the Archive, or its file there
   * has been deleted.
   */
  itemId: string | null;
  /** The size of the mp4 in bytes, or `null` if the server has not measured it. */
  size: number | null;
  /** The width of the video in pixels, or `null` if the server did not store it. */
  width: number | null;
  /** The height of the video in pixels, or `null` if the server did not store it. */
  height: number | null;
  /** The frames per second of the video, or `null` if the server did not store it. */
  fps: number | null;
  /**
   * The medal of the run the demo belongs to, numbered as `Demo.medal`: the highest medal among the caps of its team
   * run, or the medal of the demo itself for a solo cap. `null` if no cap of the run has a medal, or if the server has
   * not set it yet.
   */
  runMedal: number | null;
}

/** The size and age of the hot cache of mp4 files. */
export interface CacheUsage {
  /** The bytes the cached files hold. */
  bytes: number;
  /** The number of cached files. */
  files: number;
  /** The time the cache was measured, as an ISO-8601 instant. */
  measuredAt: string;
}

/** The demos waiting to be recorded and the recordings that did not finish. */
export interface QueueStatistics {
  size: number;
  /** The demos added within the last day. */
  recent: number;
  /** The demos added before the last day. */
  backlog: number;
  /** The summed cap time of the queued demos, as an ISO-8601 duration. */
  capTime: string;
  /** The recordings running now. */
  recording: number;
  failed: number;
  unresolvable: number;
}

/** What the recorder produced in the last day. */
export interface DayOutput {
  recordings: number;
  /** The summed cap time of those recordings, as an ISO-8601 duration. */
  capTime: string;
}

/** What the recorder produced on one UTC day. */
export interface RecorderDay {
  /** The day, as `YYYY-MM-DD`. */
  date: string;
  recordings: number;
  /** The summed cap time of the recordings, as an ISO-8601 duration. */
  capTime: string;
  /** The summed processing time of the recordings, as an ISO-8601 duration. */
  processingTime: string;
}

/** The recorder's throughput. */
export interface RecorderStatistics {
  /** The cap time recorded per unit of processing time, or `null` if there is not enough data. */
  pace: number | null;
  lastDay: DayOutput;
  /** Exactly 30 days, oldest first, ending with today, which is partial. */
  days: RecorderDay[];
  completed: number;
  views: number;
}

/** Where the mp4 files are kept. */
export interface StorageStatistics {
  /** The hot cache, or `null` if it has not been measured. */
  cache: CacheUsage | null;
  cacheTargetBytes: number;
  cacheMaxBytes: number;
  archived: number;
  archivedBytes: number;
  /** The archived recordings whose size is not known. */
  archivedUnsized: number;
  uploadsLastDay: number;
  dailyUploads: number;
}

/** The personal bests of one UTC day. */
export interface PersonalBestDay {
  /** The day, as `YYYY-MM-DD`. */
  date: string;
  personalBests: number;
  players: number;
}

/** The number of personal bests on one map. */
export interface MapCount {
  map: string;
  personalBests: number;
}

/** The personal bests the server knows. */
export interface PersonalBestStatistics {
  lastDay: number;
  /** Exactly 30 days, oldest first, ending with today, which is partial. */
  days: PersonalBestDay[];
  players: number;
  demos: number;
  maps: MapCount[];
}

/** The aggregate figures of `/api/statistics`. */
export interface Statistics {
  /** The time the figures were computed, as an ISO-8601 instant. */
  generatedAt: string;
  queue: QueueStatistics;
  recorder: RecorderStatistics;
  storage: StorageStatistics;
  personalBests: PersonalBestStatistics;
}
