package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResidualExpansionTest {
    @Test void residualAfterOneExpandsBothBranchesWithoutAnyBudgetTenSearch(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));
        var twenty=ActiveErikssonAvoidanceTest.pair(20);
        var twentyOne=ActiveErikssonAvoidanceTest.pair(21);
        Set<ExtendedToricClassKey> expected=ConcurrentHashMap.newKeySet(),actual=ConcurrentHashMap.newKeySet();
        ErikssonInverseReconstruction.preimages(twenty,1,p -> expected.add(p.extendedToricClassKey()));
        ErikssonInverseReconstruction.preimages(twentyOne,0,p -> expected.add(p.extendedToricClassKey()));
        try(var set=new DiskStateSet(source.resolve("after-one"))) {
            set.add(twenty,10);set.add(twentyOne,10);assertEquals(2,set.seal());
        }
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,1,1,2,-1,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(source);
        var result=ErikssonAvoidance.resume(source,Files.createDirectory(directory.resolve("work")),(p,b) -> {
            assertEquals(11,b,"Only final roots may enter CPU search");actual.add(p.extendedToricClassKey());return true;
        },(p,b) -> {assertEquals(11,b,"Only final roots may enter GPU search");return true;},batch -> {
            batch.forEach(state -> assertEquals(11,state.maximumMoves(),"Only final roots may look up certificates"));return new boolean[batch.size()];
        });
        assertFalse(expected.isEmpty());assertEquals(expected,actual);assertEquals(expected.size(),result.roots());assertEquals(0,result.unresolved());
        var checkpoint=ErikssonCheckpoint.read(directory.resolve("work"));
        assertEquals(ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION,checkpoint.mode());
        assertEquals(ErikssonCheckpoint.Stage.ROOTS,checkpoint.stage());
    }

    @Test void residualAfterTwoChecksInventoryWithoutAnyBudgetNineSearch(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));
        try(var set=new DiskStateSet(source.resolve("after-two"))) {set.add(ActiveErikssonAvoidanceTest.pair(18),9);assertEquals(1,set.seal());}
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,1,2,-1,-1,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(source);
        assertThrows(IllegalStateException.class,() -> ErikssonAvoidance.resume(source,Files.createDirectory(directory.resolve("work")),
                (p,b) -> {fail("Residual source must expand without searching");return false;},
                batch -> {fail("Residual source must expand without certificate lookups");return new boolean[batch.size()];}));
    }

    @Test void checkpointVersionPreservesModeAndRejectsMissingMode(@TempDir Path directory) throws Exception {
        var checkpoint=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,2036,970331,-1,-1,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        checkpoint.write(directory);assertEquals(checkpoint,ErikssonCheckpoint.read(directory));
        Path file=directory.resolve("eriksson-reconstruction-v1.txt");var lines=Files.readAllLines(file);
        assertEquals("td-eriksson-reconstruction-v2",lines.getFirst());assertEquals("mode=RESIDUAL_EXPANSION",lines.getLast());
        Files.write(file,lines.subList(0,9));assertThrows(IllegalStateException.class,() -> ErikssonCheckpoint.read(directory));
        lines.set(0,"td-eriksson-reconstruction-v1");Files.write(file,lines.subList(0,9));
        assertEquals(ErikssonCheckpoint.Mode.FULL_SEARCH,ErikssonCheckpoint.read(directory).mode());
    }

    @Test void completedUncountedIntermediateWaveResumesAndCountsDuringExpansion(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));
        var twenty=ActiveErikssonAvoidanceTest.pair(20);
        var twentyOne=ActiveErikssonAvoidanceTest.pair(21);
        Set<ExtendedToricClassKey> expected=ConcurrentHashMap.newKeySet(),actual=ConcurrentHashMap.newKeySet();
        ErikssonInverseReconstruction.preimages(twenty,1,p -> expected.add(p.extendedToricClassKey()));
        ErikssonInverseReconstruction.preimages(twentyOne,0,p -> expected.add(p.extendedToricClassKey()));
        try(var set=new DiskStateSet(source.resolve("after-one"))) {
            set.add(twenty,10);set.add(twentyOne,10);set.add(twenty,10);
            assertEquals(-1,set.prepareScan());
            assertEquals(-1,set.knownSize(),"Draining completed writers must not traverse the intermediate inventory");
        }
        var pending=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,1,1,-1,-1,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        pending.write(source);
        assertEquals(pending,ErikssonCheckpoint.read(source));
        assertTrue(pending.afterOneCountPending());
        assertEquals("td-eriksson-reconstruction-v3",Files.readAllLines(source.resolve("eriksson-reconstruction-v1.txt")).getFirst());
        var result=ErikssonAvoidance.resume(source,Files.createDirectory(directory.resolve("work")),(p,b) -> {
            assertEquals(11,b);actual.add(p.extendedToricClassKey());return true;
        },batch -> {batch.forEach(state -> assertEquals(11,state.maximumMoves()));return new boolean[batch.size()];});
        assertEquals(expected,actual);assertEquals(2,result.afterOne());assertEquals(0,result.unresolved());
        assertEquals(2,ErikssonCheckpoint.read(source).afterOne());
        assertFalse(ErikssonCheckpoint.read(source).afterOneCountPending());
        var completed=ErikssonCheckpoint.read(directory.resolve("work"));
        assertEquals(2,completed.afterOne());assertFalse(completed.afterOneCountPending());
    }

    @Test void pendingCountCannotBeHiddenInLegacyVersionsOrFullSearch(@TempDir Path directory) throws Exception {
        assertThrows(IllegalArgumentException.class,() -> new ErikssonCheckpoint(
                ErikssonCheckpoint.Stage.AFTER_ONE,1,1,-1,-1,ErikssonCheckpoint.Mode.FULL_SEARCH));
        assertThrows(IllegalArgumentException.class,() -> new ErikssonCheckpoint(
                ErikssonCheckpoint.Stage.ROOTS,1,1,-1,0,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION));
        var pending=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,1,1,-1,-1,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        pending.write(directory);
        Path file=directory.resolve("eriksson-reconstruction-v1.txt");
        var lines=Files.readAllLines(file);lines.set(0,"td-eriksson-reconstruction-v2");Files.write(file,lines);
        assertThrows(IllegalStateException.class,() -> ErikssonCheckpoint.read(directory));
        lines.set(0,"td-eriksson-reconstruction-v3");lines.set(7,"afterOne=0");Files.write(file,lines);
        assertThrows(IllegalStateException.class,() -> ErikssonCheckpoint.read(directory));
    }
}
