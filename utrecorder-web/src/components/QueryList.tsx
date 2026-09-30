import { Alert, Skeleton, Stack, Text } from "@mantine/core";
import type { ReactNode } from "react";

/** The part of a query result a list reads. A TanStack `UseQueryResult` of an array satisfies it. */
export interface QueryState<T> {
  data: T[] | undefined;
  isPending: boolean;
  isError: boolean;
}

interface QueryListProps<T> {
  query: QueryState<T>;
  /** The text of the empty state. */
  empty: string;
  /** Renders the loaded, non-empty items. */
  children: (items: T[]) => ReactNode;
}

/**
 * Renders the states of a list query around its items: `Skeleton` rows while loading, an `Alert` when the query failed
 * before any data arrived, the empty state for an empty list, and the items otherwise. A failed refetch keeps the last
 * data and adds a small "reconnecting" notice.
 */
export function QueryList<T>({ query, empty, children }: QueryListProps<T>) {
  const { data, isPending, isError } = query;
  if (data === undefined) {
    if (isError) {
      return (
        <Alert color="red" title="Could not load">
          The server did not answer.
        </Alert>
      );
    }
    if (isPending) {
      return (
        <Stack gap="xs" data-testid="loading">
          <Skeleton height={48} />
          <Skeleton height={48} />
          <Skeleton height={48} />
        </Stack>
      );
    }
  }
  return (
    <Stack gap="xs">
      {isError && (
        <Text size="xs" c="yellow">
          Reconnecting…
        </Text>
      )}
      {data === undefined || data.length === 0 ? <Text c="dimmed">{empty}</Text> : children(data)}
    </Stack>
  );
}
