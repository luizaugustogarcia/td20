package br.unb.cic.td20.joint;

import br.unb.cic.td20.runtime.ProjectPaths;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ErikssonInventoryTest {

    private static Path paper() {
        return ProjectPaths.root(Path.of("")).resolve("docs/td20-reduction-count.tex");
    }

    @Test
    void sourceAndEndpointCoverHaveTheirCompleteConfiguredScope() throws Exception {
        val roots = ErikssonInventory.read(paper());
        assertEquals(ErikssonRoute.ROOT_PARTITIONS, roots.size());
        for (val row : roots) {
            assertEquals(ErikssonRoute.ROOT_SYMBOLS, Arrays.stream(row.partition()).sum());
        }

        val endpoints = ErikssonInventory.endpoints();
        assertEquals(ErikssonRoute.ENDPOINT_TYPES, endpoints.size());
        for (val endpoint : endpoints) {
            assertEquals(ErikssonRoute.ENDPOINT_SYMBOLS, Arrays.stream(endpoint.partition()).sum());
            assertTrue(Arrays.stream(endpoint.partition()).allMatch(k -> k >= 2));
            for (int i = 0; i < endpoint.partition().length; i++) {
                if (endpoint.partition()[i] == 2) {
                    assertFalse(endpoint.orientedByPart()[i]);
                }
            }
        }
    }

    @Test
    void missingDuplicateReorderedAndMalformedRowsAreRejected(@TempDir final Path path) throws Exception {
        val baseline = Files.readAllLines(paper());
        int at = 0;
        while (!baseline.get(at).startsWith("\\erikssonrow{") && !baseline.get(at).startsWith("\\erikssonpendingrow{")) {
            at++;
        }

        val missing = new ArrayList<>(baseline);
        missing.remove(at);
        reject(path, missing);

        val duplicate = new ArrayList<>(baseline);
        duplicate.add(at, baseline.get(at));
        reject(path, duplicate);

        val reordered = new ArrayList<>(baseline);
        final String first = reordered.get(at);
        reordered.set(at, reordered.get(at + 1));
        reordered.set(at + 1, first);
        reject(path, reordered);

        val malformed = new ArrayList<>(baseline);
        malformed.set(at, "\\erikssonrow{1}{" + ErikssonRoute.ROOT_SYMBOLS + "}{-1}{0}{0}");
        reject(path, malformed);
    }

    @Test
    void pendingCountsCannotDischargeAnObligation(@TempDir final Path path) throws Exception {
        val lines = new ArrayList<>(Files.readAllLines(paper()));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("\\erikssonrow{") || lines.get(i).startsWith("\\erikssonpendingrow{")) {
                lines.set(i, "\\erikssonpendingrow{1}{" + ErikssonRoute.ROOT_SYMBOLS + "}");
                break;
            }
        }

        final Path file = path.resolve("pending.tex");
        Files.write(file, lines);

        val row = ErikssonInventory.read(file).getFirst();
        assertFalse(row.countsComplete());
        assertThrows(IllegalStateException.class, row::requireCompleteCounts);
        assertThrows(IllegalStateException.class, row::directClasses);
    }

    private static void reject(final Path path, final java.util.List<String> lines) throws Exception {
        final Path file = path.resolve("invalid.tex");
        Files.write(file, lines);
        assertThrows(IllegalArgumentException.class, () -> ErikssonInventory.read(file));
    }
}
