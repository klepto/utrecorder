/**
 * The server's JSON, exactly as `HttpService` answers it. Every field is present; a nullable one is `null`, never
 * missing. A duration is an ISO-8601 duration string such as `PT1M2.345S`, an instant an ISO-8601 UTC string, and an
 * enum its constant's name.
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
}

/** A step of a recording, in the order a recording runs them. */
export type RecorderStage = "FetchingDemo" | "PreparingInstallation" | "ResolvingPackages" | "Capturing" | "Muxing";

/** One running recording, as `/api/jobs` lists it. */
export interface Job {
  /** The demo being recorded. */
  demo: Demo;
  /** The number of the current attempt, starting at `1`. */
  attempt: number;
  /** The stage of the current attempt, or `null` if it has not reported yet. */
  stage: RecorderStage | null;
  /** How much of `stage` is done, from `0.0` to `1.0`, or `null` if it has not reported yet. */
  progress: number | null;
  /** The time the first attempt started, as an ISO-8601 instant. */
  startedAt: string;
}

/** State of a recording. */
export type RecordingStatus =
  | "Pending"
  | "Recording"
  | "Recorded"
  | "Uploaded"
  | "Archived"
  | "Evicted"
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
}
