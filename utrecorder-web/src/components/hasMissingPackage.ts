import type { MissingPackage, Recording } from "../api/types";

/**
 * A recording whose missing package the server has stored.
 */
export type KnownRecording = Recording & { missingPackage: MissingPackage };

/**
 * Returns whether the server has stored the missing package of `recording`. A server older than `missingPackage` omits
 * the field, so an absent one is not known either.
 */
export const hasMissingPackage = (recording: Recording): recording is KnownRecording => {
  return (recording.missingPackage ?? null) !== null;
};
