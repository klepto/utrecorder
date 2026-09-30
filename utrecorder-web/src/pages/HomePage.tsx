import { Container, SimpleGrid, Stack, Title } from "@mantine/core";
import { useJobs, useQueue, useRecordings } from "../api/queries";
import { JobList } from "../components/JobList";
import { QueueList } from "../components/QueueList";
import { RecordingList } from "../components/RecordingList";

const LATEST_LIMIT = 10;

/** The landing page: recording and queue on the left, latest recordings on the right. */
export function HomePage() {
  const jobs = useJobs();
  const queue = useQueue();
  const recordings = useRecordings({ limit: LATEST_LIMIT, offset: 0 });
  return (
    <Container size="lg" py="xl">
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl">
        <Stack gap="xl">
          <Stack>
            <Title order={2}>Recording</Title>
            <JobList query={jobs} />
          </Stack>
          <Stack>
            <Title order={2}>Queue</Title>
            <QueueList query={queue} />
          </Stack>
        </Stack>
        <Stack>
          <Title order={2}>Latest recordings</Title>
          <RecordingList query={recordings} />
        </Stack>
      </SimpleGrid>
    </Container>
  );
}
