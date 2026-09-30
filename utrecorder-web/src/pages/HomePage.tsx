import { Anchor, Container, SimpleGrid, Stack } from "@mantine/core";
import { Link } from "react-router";
import { useJobs, useQueue, useRecordings } from "../api/queries";
import { JobList } from "../components/JobList";
import { QueueList } from "../components/QueueList";
import { RecordingList } from "../components/RecordingList";
import { Section } from "../layout/Section";

const LATEST_LIMIT = 6;

/** The landing page: recording and queue on the left, latest recordings on the right. */
export const HomePage = () => {
  const jobs = useJobs();
  const queue = useQueue();
  const recordings = useRecordings({ limit: LATEST_LIMIT, offset: 0 });
  return (
    <Container size="lg" py="xl">
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl">
        <Stack gap="xl">
          <Section title="Recording">
            <JobList query={jobs} />
          </Section>
          <Section title="Queue">
            <QueueList query={queue} jobs={jobs} />
          </Section>
        </Stack>
        <Section title="Latest recordings">
          <RecordingList query={recordings} />
          {recordings.data?.length === LATEST_LIMIT && (
            <Anchor component={Link} to="/recordings" ta="center">
              Show more
            </Anchor>
          )}
        </Section>
      </SimpleGrid>
    </Container>
  );
};
