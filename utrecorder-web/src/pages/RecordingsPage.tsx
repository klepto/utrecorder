import { Button, Stack, Text } from "@mantine/core";
import { useRecordingPages } from "../api/queries";
import { RecordingList } from "../components/RecordingList";
import { Page } from "../layout/Page";

/** Every recording, paged. */
export const RecordingsPage = () => {
  const { query, hasMore, loadMore, loadingMore, loadMoreFailed } = useRecordingPages();
  return (
    <Page title="Recordings">
      <RecordingList query={query} />
      {hasMore && (
        <Stack gap="xs" align="flex-start">
          <Button onClick={loadMore} loading={loadingMore} variant="default">
            Load more
          </Button>
          {loadMoreFailed && (
            <Text size="sm" c="red">
              Could not load more recordings.
            </Text>
          )}
        </Stack>
      )}
    </Page>
  );
};
