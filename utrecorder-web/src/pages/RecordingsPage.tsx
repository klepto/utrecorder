import { Button, Container, Stack, Text, Title } from "@mantine/core";
import { useRecordingPages } from "../api/queries";
import { RecordingList } from "../components/RecordingList";

/** Every recording, paged. */
export function RecordingsPage() {
  const { query, recordings, hasMore, loadMore, loadMoreFailed } = useRecordingPages();
  return (
    <Container size="lg" py="xl">
      <Stack>
        <Title order={2}>Recordings</Title>
        <RecordingList
          query={{ data: recordings, isPending: query.isPending, isError: query.isError && !recordings }}
        />
        {hasMore && (
          <Stack gap="xs" align="flex-start">
            <Button onClick={loadMore} loading={query.isFetchingNextPage} variant="default">
              Load more
            </Button>
            {loadMoreFailed && (
              <Text size="sm" c="red">
                Could not load more recordings.
              </Text>
            )}
          </Stack>
        )}
      </Stack>
    </Container>
  );
}
