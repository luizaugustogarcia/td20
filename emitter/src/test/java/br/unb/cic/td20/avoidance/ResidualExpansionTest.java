package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ResidualExpansionTest {

    private static boolean elementary(final br.unb.cic.td20.model.CyclicTargetPair pair) {
        val primitive = br.unb.cic.td20.compat.LegacyPairs.fromLegacy(pair);
        return FinalInverseReconstructionTest.arbitraryClosure(primitive.omegaImages(), primitive.betaSymbols(), primitive.size());
    }

    @ParameterizedTest
    @EnumSource(ErikssonCheckpoint.Mode.class)
    void afterOneExpandsBothBranchesWithoutAnyBudgetTenSearch(final ErikssonCheckpoint.Mode mode,
            @TempDir final Path directory) throws Exception {
        final Path source = Files.createDirectory(directory.resolve("source"));
        val twenty = br.unb.cic.td20.compat.LegacyPairs.toLegacy(FinalInverseReconstructionTest.reverseSource(20));
        val twentyOne = br.unb.cic.td20.compat.LegacyPairs.toLegacy(FinalInverseReconstructionTest.reverseSource(21));
        final Set<ExtendedToricClassKey> expected = ConcurrentHashMap.newKeySet(), actual = ConcurrentHashMap.newKeySet();
        ErikssonInverseReconstruction.preimages(twenty, 1, p -> {
            if (!elementary(p)) {
                expected.add(p.extendedToricClassKey());
            }
        });
        ErikssonInverseReconstruction.preimages(twentyOne, 0, p -> {
            if (!elementary(p)) {
                expected.add(p.extendedToricClassKey());
            }
        });

        try (val set = new DiskStateSet(source.resolve("after-one"))) {
            set.add(twenty, 10);
            set.add(twentyOne, 10);
            assertEquals(2, set.seal());
        }

        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE, 1, 1, 2, -1, mode).write(source);

        val result = ErikssonAvoidance.resume(source,
                Files.createDirectory(directory.resolve("work")),
                (p, b) -> {
                    assertEquals(11, b, "Only final roots may enter CPU search");
                    actual.add(p.extendedToricClassKey());

                    return true;
                },
                (p, b) -> {
                    assertEquals(11, b, "Only final roots may enter GPU search");
                    return true;
                },
                batch -> {
                    batch.forEach(state -> assertEquals(11, state.maximumMoves(), "Only final roots may look up certificates"));
                    return new boolean[batch.size()];
                });
        assertFalse(expected.isEmpty());
        assertEquals(expected, actual);
        assertEquals(expected.size(), result.roots());
        assertEquals(0, result.unresolved());

        val checkpoint = ErikssonCheckpoint.read(directory.resolve("work"));
        assertEquals(mode, checkpoint.mode());
        assertEquals(ErikssonCheckpoint.Stage.ROOTS, checkpoint.stage());
        assertEquals(ErikssonCheckpoint.RootInventory.ELEMENTARY_RESIDUALS, checkpoint.rootInventory());
    }

    @Test
    void residualAfterTwoChecksInventoryWithoutAnyBudgetNineSearch(@TempDir final Path directory) throws Exception {
        final Path source = Files.createDirectory(directory.resolve("source"));

        try (val set = new DiskStateSet(source.resolve("after-two"))) {
            set.add(ActiveErikssonAvoidanceTest.pair(18), 9);
            assertEquals(1, set.seal());
        }

        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, 1, 2, -1, -1, ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(source);
        assertThrows(IllegalStateException.class,
                () -> ErikssonAvoidance.resume(source,
                        Files.createDirectory(directory.resolve("work")),
                        (p, b) -> {
                            fail("Residual source must expand without searching");
                            return false;
                        },
                        batch -> {
                            fail("Residual source must expand without certificate lookups");
                            return new boolean[batch.size()];
                        }));
    }

    @Test
    void checkpointVersionPreservesModeAndRejectsMissingMode(@TempDir final Path directory) throws Exception {
        val checkpoint = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, 2036, 970331, -1, -1, ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        checkpoint.write(directory);
        assertEquals(checkpoint, ErikssonCheckpoint.read(directory));

        final Path file = directory.resolve("eriksson-reconstruction-v1.txt");
        val lines = Files.readAllLines(file);
        assertEquals("td-eriksson-reconstruction-v2", lines.getFirst());
        assertEquals("mode=RESIDUAL_EXPANSION", lines.getLast());
        Files.write(file, lines.subList(0, 9));
        assertThrows(IllegalStateException.class, () -> ErikssonCheckpoint.read(directory));
        lines.set(0, "td-eriksson-reconstruction-v1");
        Files.write(file, lines.subList(0, 9));
        assertEquals(ErikssonCheckpoint.Mode.FULL_SEARCH, ErikssonCheckpoint.read(directory).mode());
    }

    @Test
    void completedUncountedIntermediateWaveCountsBeforeFinalExpansion(@TempDir final Path directory) throws Exception {
        final Path source = Files.createDirectory(directory.resolve("source"));
        val twenty = br.unb.cic.td20.compat.LegacyPairs.toLegacy(FinalInverseReconstructionTest.reverseSource(20));
        val twentyOne = br.unb.cic.td20.compat.LegacyPairs.toLegacy(FinalInverseReconstructionTest.reverseSource(21));
        final Set<ExtendedToricClassKey> expected = ConcurrentHashMap.newKeySet(), actual = ConcurrentHashMap.newKeySet();
        ErikssonInverseReconstruction.preimages(twenty, 1, p -> {
            if (!elementary(p)) {
                expected.add(p.extendedToricClassKey());
            }
        });
        ErikssonInverseReconstruction.preimages(twentyOne, 0, p -> {
            if (!elementary(p)) {
                expected.add(p.extendedToricClassKey());
            }
        });

        try (val set = new DiskStateSet(source.resolve("after-one"))) {
            set.add(twenty, 10);
            set.add(twentyOne, 10);
            set.add(twenty, 10);
            assertEquals(-1, set.prepareScan());
            assertEquals(-1, set.knownSize(), "Draining completed writers must not traverse the intermediate inventory");
        }

        val pending = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE, 1, 1, -1, -1, ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        pending.write(source);
        assertEquals(pending, ErikssonCheckpoint.read(source));
        assertTrue(pending.afterOneCountPending());
        assertEquals("td-eriksson-reconstruction-v3",
                Files.readAllLines(source.resolve("eriksson-reconstruction-v1.txt")).getFirst());

        val result = ErikssonAvoidance.resume(source,
                Files.createDirectory(directory.resolve("work")),
                (p, b) -> {
                    assertEquals(11, b);
                    assertEquals(2,
                            ErikssonCheckpoint.read(source).afterOne(),
                            "Source count must be checkpointed before any final-root search");
                    actual.add(p.extendedToricClassKey());

                    return true;
                },
                batch -> {
                    assertEquals(2, ErikssonCheckpoint.read(source).afterOne(), "Source count must precede even advisory root lookup");
                    batch.forEach(state -> assertEquals(11, state.maximumMoves()));

                    return new boolean[batch.size()];
                });
        assertEquals(expected, actual);
        assertEquals(2, result.afterOne());
        assertEquals(0, result.unresolved());
        assertEquals(2, ErikssonCheckpoint.read(source).afterOne());
        assertFalse(ErikssonCheckpoint.read(source).afterOneCountPending());

        val completed = ErikssonCheckpoint.read(directory.resolve("work"));
        assertEquals(2, completed.afterOne());
        assertFalse(completed.afterOneCountPending());
    }

    @Test
    void completedWaveCanBeCountedWithoutStartingFinalGeneration(@TempDir final Path directory) throws Exception {
        try (val states = new DiskStateSet(directory.resolve("after-one"))) {
            states.add(ActiveErikssonAvoidanceTest.pair(20), 10);
            states.add(ActiveErikssonAvoidanceTest.pair(20), 10);
            states.add(ActiveErikssonAvoidanceTest.pair(21), 10);
        }

        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,
                2036,
                970331,
                -1,
                -1,
                ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(directory);

        val counted = ErikssonAvoidance.countCompletedAfterOne(directory);
        assertEquals(2, counted.afterOne());
        assertEquals(-1, counted.roots());
        assertEquals(counted, ErikssonCheckpoint.read(directory));
        assertFalse(Files.exists(directory.resolve("roots")));
        assertEquals(counted, ErikssonAvoidance.countCompletedAfterOne(directory));
    }

    @Test
    void keyCountFailureKeepsTheExistingCheckpoint(@TempDir final Path directory) throws Exception {
        try (val states = new DiskStateSet(directory.resolve("after-one"))) {
            states.add(ActiveErikssonAvoidanceTest.pair(20), 10);
        }

        val expected = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,
                1,
                1,
                2,
                -1,
                ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        expected.write(directory);
        assertThrows(IllegalStateException.class, () -> ErikssonAvoidance.countCompletedAfterOne(directory));
        assertEquals(expected, ErikssonCheckpoint.read(directory));
    }

    @Test
    void pendingCountCannotBeHiddenInLegacyVersionsOrFullSearch(@TempDir final Path directory) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE, 1, 1, -1, -1, ErikssonCheckpoint.Mode.FULL_SEARCH));
        assertThrows(IllegalArgumentException.class,
                () -> new ErikssonCheckpoint(ErikssonCheckpoint.Stage.ROOTS, 1, 1, -1, 0, ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION));

        val pending = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE, 1, 1, -1, -1, ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        pending.write(directory);

        final Path file = directory.resolve("eriksson-reconstruction-v1.txt");
        val lines = Files.readAllLines(file);
        lines.set(0, "td-eriksson-reconstruction-v2");
        Files.write(file, lines);
        assertThrows(IllegalStateException.class, () -> ErikssonCheckpoint.read(directory));
        lines.set(0, "td-eriksson-reconstruction-v3");
        lines.set(7, "afterOne=0");
        Files.write(file, lines);
        assertThrows(IllegalStateException.class, () -> ErikssonCheckpoint.read(directory));
    }
}
