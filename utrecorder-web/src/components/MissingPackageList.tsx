import { Anchor, Box, Table, Text } from "@mantine/core";
import type { MissingPackage, Recording } from "../api/types";
import { capUrl, NEW_TAB } from "../site";
import { CapTime } from "./CapTime";
import { hasMissingPackage, type KnownRecording } from "./hasMissingPackage";
import { QueryList, type QueryState } from "./QueryList";
import { Truncated } from "./Truncated";

/**
 * Orders recordings by map.
 */
const byMap = (a: Recording, b: Recording): number => {
  return a.demo.map.localeCompare(b.demo.map);
};

/**
 * Returns what tells one missing package from another: its GUID, or its name if the demo recorded no GUID.
 */
const packageKey = ({ missingPackage }: KnownRecording): string => {
  return missingPackage.guid || missingPackage.name;
};

/**
 * Returns the recordings whose missing package the server has stored, ordered by map, with only the first recording of
 * each package.
 */
const missingPackages = (recordings: Recording[]): KnownRecording[] => {
  const known = recordings.filter(hasMissingPackage).sort(byMap);
  const keys = known.map(packageKey);
  return known.filter((recording, index) => keys.indexOf(packageKey(recording)) === index);
};

/**
 * One demo whose package resolved nowhere: the package, its GUID, the map, and the cap time and player linked to the
 * cap on utbt.net.
 */
const MissingPackageRow = ({ recording, missingPackage }: { recording: Recording; missingPackage: MissingPackage }) => {
  const { demo } = recording;
  return (
    <Table.Tr>
      <Table.Td>
        <Truncated text={missingPackage.name} size="sm" />
      </Table.Td>
      <Table.Td>
        <Text size="sm" ff="monospace" c={missingPackage.guid ? undefined : "dimmed"} truncate="end">
          {missingPackage.guid || "None recorded"}
        </Text>
      </Table.Td>
      <Table.Td>
        <Truncated text={demo.map} size="sm" />
      </Table.Td>
      <Table.Td>
        <Text size="sm" truncate="end">
          <Anchor href={capUrl(demo.capId)} {...NEW_TAB} inherit>
            <CapTime demo={demo} medal={recording.runMedal ?? demo.medal} /> by {demo.player}
          </Anchor>
        </Text>
      </Table.Td>
    </Table.Tr>
  );
};

/**
 * The packages no source holds, as a table of package, GUID, map and cap, ordered by map. Each package shows once,
 * with the first demo by map that names it. A demo whose package the server has not stored yet is left out.
 */
export const MissingPackageList = ({ query }: { query: QueryState<Recording> }) => {
  const known = { ...query, data: query.data && missingPackages(query.data) };
  return (
    <QueryList query={known} empty="No package is missing.">
      {(recordings) => (
        <Box style={{ overflowX: "auto" }}>
          <Table layout="fixed" miw={640} verticalSpacing="xs" horizontalSpacing="xs">
            <colgroup>
              <col style={{ width: "20%" }} />
              <col style={{ width: "30%" }} />
              <col style={{ width: "25%" }} />
              <col style={{ width: "25%" }} />
            </colgroup>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Package</Table.Th>
                <Table.Th ta="center">GUID</Table.Th>
                <Table.Th ta="center">Map</Table.Th>
                <Table.Th ta="center">Cap</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {recordings.map((recording) => (
                <MissingPackageRow
                  key={recording.demo.capId}
                  recording={recording}
                  missingPackage={recording.missingPackage}
                />
              ))}
            </Table.Tbody>
          </Table>
        </Box>
      )}
    </QueryList>
  );
};
