package br.unb.cic.td20.avoidance;

import java.io.DataInputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import br.unb.cic.td20.joint.ErikssonRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiskStateQueueTest {
    @Test void preservesDomainBudgetAndClassAndRecordsFinalCount(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("pending.queue");
        var expected = new ArrayList<DiskStateSet.State>();
        try (var queue = new DiskStateQueue(path)) {
            for (int n : new int[]{7,ErikssonRoute.ROOT_SYMBOLS-3,ErikssonRoute.ROOT_SYMBOLS-1,ErikssonRoute.ROOT_SYMBOLS}) {
                var state = new DiskStateSet.State(ActiveErikssonAvoidanceTest.pair(n),
                        n==ErikssonRoute.ROOT_SYMBOLS?ErikssonRoute.ROOT_BUDGET-1:ErikssonRoute.ROOT_BUDGET-2);
                expected.add(state); queue.add(state);
            }
            queue.finish();
            for (var state : expected) {
                var found = queue.take();
                assertEquals(state.maximumMoves(), found.maximumMoves());
                assertEquals(state.pair().getBeta().size(), found.pair().getBeta().size());
                assertEquals(state.pair().extendedToricClassKey(), found.pair().extendedToricClassKey());
            }
            assertNull(queue.take()); assertEquals(4,queue.appended());
            assertThrows(IllegalStateException.class, () -> queue.add(expected.getFirst()));
        }
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            assertEquals("td-reconstruction-queue-v1",input.readUTF());
            assertEquals(br.unb.cic.td20.joint.ErikssonRoute.ID,input.readUTF());
            assertEquals(ErikssonRoute.ROOT_SYMBOLS,input.readInt());
            assertEquals(ErikssonRoute.ROOT_BUDGET,input.readInt());
            int recordBytes=input.readInt(); assertEquals(4,input.readLong());
            assertEquals(recordBytes*4,input.readAllBytes().length);
        }
        assertThrows(IllegalStateException.class, () -> new DiskStateQueue(path));
    }

    @Test void missingQueueRecordsAreErrors(@TempDir Path directory) throws Exception {
        Path path=directory.resolve("truncated.queue");
        try(var queue=new DiskStateQueue(path)) {
            queue.add(new DiskStateSet.State(ActiveErikssonAvoidanceTest.pair(7),8)); queue.finish();
            try(var file=FileChannel.open(path,StandardOpenOption.WRITE)){ file.truncate(Files.size(path)-1); }
            assertThrows(IllegalStateException.class,queue::take);
        }
    }
}
