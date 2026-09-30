import type { Job } from "../api/types";
import type { QueryState } from "./QueryList";

/**
 * The running jobs as a query state. `dataUpdatedAt` is the epoch millisecond the jobs were fetched at, as TanStack
 * Query reports it.
 */
export type JobsState = QueryState<Job> & { dataUpdatedAt: number };
