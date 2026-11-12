package br.unb.cic.td20.joint;

import br.unb.cic.td20.runtime.ProjectPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class JointInventoryTest {
    @TempDir Path directory;

    @Test
    void paperContainsEveryRootPartitionAndAuditedTotals() throws Exception {
        final var rows = JointInventory.read(paper());
        assertEquals(83, rows.size());
        assertEquals(12_704_114_061L, rows.stream().mapToLong(JointInventory.Root::unorientedClasses).sum());
        for (final var row : rows) {
            if (row.countsComplete()) assertEquals(row.unorientedResidualClasses() + row.orientedClasses(), row.directClasses());
            else assertThrows(IllegalStateException.class, row::directClasses);
        }
        assertArrayEquals(new int[]{21}, rows.getFirst().partition());
        rows.getFirst().partition()[0] = 1;
        assertArrayEquals(new int[]{21}, rows.getFirst().partition());
        assertThrows(UnsupportedOperationException.class, rows::clear);
    }

    @Test
    void missingDuplicateMalformedAndInconsistentRowsReject() throws Exception {
        final String original = Files.readString(paper());
        final String row = original.lines().map(String::strip)
                .filter(line -> line.startsWith("\\unorientedjointrow{1}") || line.startsWith("\\jointrow{1}"))
                .findFirst().orElseThrow();
        assertTrue(original.contains(row));
        for (final String changed : new String[]{
                original.replace(row, ""),
                original.replace(row, row + "\n" + row),
                original.replace(row, "\\jointrow{1}{21}{0}{0}{1}{0}"),
                original.replace(row, row + "trailing"),
                original.replace(row, row.replace("{21}", "{19,2}"))}) {
            final Path corrupt = directory.resolve("corrupt.tex");
            Files.writeString(corrupt, changed);
            assertThrows(IllegalArgumentException.class, () -> JointInventory.read(corrupt));
        }
    }

    @Test
    void explicitPendingRowIsReadableButCannotSupplyProofCounts() throws Exception {
        final String original = Files.readString(paper());
        final String row = original.lines().map(String::strip)
                .filter(line -> line.startsWith("\\unorientedjointrow{1}") || line.startsWith("\\jointrow{1}"))
                .findFirst().orElseThrow();
        final Path pending = directory.resolve("pending.tex");
        Files.writeString(pending, original.replace(row, "\\unorientedjointrow{1}{21}{0}"));
        final var root = JointInventory.read(pending).getFirst();
        assertFalse(root.countsComplete());
        assertThrows(IllegalStateException.class, root::directClasses);
    }

    @Test
    void endpointCoverIsCompleteAndHasOneHundredFourElementaryClosures() {
        final var endpoints = JointInventory.endpoints();
        assertEquals(214, endpoints.size());
        assertEquals(104, endpoints.stream().filter(JointInventory.Endpoint::elementaryClosure).count());
        final var seen = new HashSet<String>();
        for (final var endpoint : endpoints) {
            final int[] partition = endpoint.partition();
            final boolean[] orientations = endpoint.orientedByPart();
            assertEquals(18, Arrays.stream(partition).sum());
            assertTrue(KnownDiameters.threeNorm(partition) <= 8);
            assertEquals(0, Arrays.stream(partition).filter(x -> x % 2 == 0).count() % 2);
            assertTrue(seen.add(Arrays.toString(partition) + Arrays.toString(orientations)));
            for (int i = 0; i < partition.length; i++) {
                if (partition[i] == 2) assertFalse(orientations[i]);
                if (i > 0 && partition[i] == partition[i - 1] && orientations[i - 1]) {
                    assertTrue(orientations[i]);
                }
            }
        }
        final int[] original = endpoints.getFirst().partition();
        endpoints.getFirst().partition()[0] = 1;
        assertArrayEquals(original, endpoints.getFirst().partition());
        assertThrows(UnsupportedOperationException.class, endpoints::clear);
    }

    @Test
    void knownDiameterClosuresUseTheCyclicDomainConvention() {
        assertTrue(KnownDiameters.closes(19, 1));
        assertFalse(KnownDiameters.closes(19, 2));
        assertTrue(KnownDiameters.closes(17, 2));
        assertFalse(KnownDiameters.closes(17, 3));
        assertEquals(7, KnownDiameters.knownDiameter(13));
        assertEquals(10, KnownDiameters.knownDiameter(19));
        assertEquals(11, KnownDiameters.knownDiameter(20));
        assertEquals(Integer.MAX_VALUE, KnownDiameters.knownDiameter(21));
        assertEquals(4, KnownDiameters.threeNorm(new int[]{5, 4, 1, 1}));
    }

    @Test
    void missingDuplicateOrOverCensusUnorientedResidualCountsReject() throws Exception {
        String original = Files.readString(paper());
        String macro = Character.toString(92) + "unorientedresidualrow{1}{0}";
        for (String changed : new String[]{original.replace(macro, ""),
                original.replace(macro, macro + System.lineSeparator() + macro),
                original.replace(macro, Character.toString(92) + "unorientedresidualrow{1}{1}")}) {
            Path corrupt = directory.resolve("missing-residual.tex");
            Files.writeString(corrupt, changed);
            assertThrows(IllegalArgumentException.class, () -> JointInventory.read(corrupt));
        }
    }

    private static Path paper() {
        return ProjectPaths.root(Path.of("")).resolve("docs/td20-reduction-count.tex");
    }
}
