import { Box, Button, Group, Text } from "@mantine/core";
import { IconChevronLeft, IconChevronRight } from "@tabler/icons-react";
import type { ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import { useRecordings } from "../api/queries";
import { RecordingList } from "../components/RecordingList";
import { Page } from "../layout/Page";

/** The number of recordings a page shows. */
const PAGE_SIZE = 10;

/** Returns the page that the `page` search parameter names, or `1` if it names no page. */
const pageOf = (value: string | null): number => {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 ? page : 1;
};

/** A link to `page`, or a disabled button when `page` is `null`. */
const PageButton = ({ page, children, ...sections }: PageButtonProps) => {
  if (page === null) {
    return (
      <Button variant="default" disabled {...sections}>
        {children}
      </Button>
    );
  }
  return (
    <Button
      component={Link}
      to={{ search: page === 1 ? "" : `?page=${page}` }}
      onClick={() => window.scrollTo(0, 0)}
      variant="default"
      {...sections}
    >
      {children}
    </Button>
  );
};

interface PageButtonProps {
  page: number | null;
  children: ReactNode;
  leftSection?: ReactNode;
  rightSection?: ReactNode;
}

/**
 * Every recording, 10 to a page, newest first. The page is the `page` search parameter. Each page asks for one
 * recording more than it shows, and links to an older page only when that recording came back.
 */
export const RecordingsPage = () => {
  const [params] = useSearchParams();
  const page = pageOf(params.get("page"));
  const recordings = useRecordings({ limit: PAGE_SIZE + 1, offset: (page - 1) * PAGE_SIZE });
  const shown = recordings.data?.slice(0, PAGE_SIZE);
  const hasOlder = recordings.data !== undefined && recordings.data.length > PAGE_SIZE;
  return (
    <Page title="Recordings">
      <Box
        aria-busy={recordings.isPlaceholderData}
        style={{ opacity: recordings.isPlaceholderData ? 0.5 : 1, transition: "opacity 150ms" }}
      >
        <RecordingList
          query={{ data: shown, isPending: recordings.isPending, isError: recordings.isError }}
          empty={page === 1 ? "No recordings yet." : "No recordings on this page."}
        />
      </Box>
      {(page > 1 || hasOlder) && (
        <Group component="nav" aria-label="Recording pages" justify="space-between">
          <PageButton page={page > 1 ? page - 1 : null} leftSection={<IconChevronLeft size={16} />}>
            Newer
          </PageButton>
          <Text c="dimmed" size="sm">
            Page {page}
          </Text>
          <PageButton page={hasOlder ? page + 1 : null} rightSection={<IconChevronRight size={16} />}>
            Older
          </PageButton>
        </Group>
      )}
    </Page>
  );
};
