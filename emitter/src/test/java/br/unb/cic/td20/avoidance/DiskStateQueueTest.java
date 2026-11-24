package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DiskStateQueueTest {

    @Test
    void preservesDomainBudgetAndClassAndRecordsFinalCount(@TempDir final Path directory) throws Exception {
        final Path path = directory.resolve("pending.queue");
        val expected = new ArrayList<DiskStateSet.State>();

        try (val queue = new DiskStateQueue(path)) {
            for (final int n : new int[] { 7, ErikssonRoute.ROOT_SYMBOLS - 3, ErikssonRoute.ROOT_SYMBOLS - 1, ErikssonRoute.ROOT_SYMBOLS }) {
                val state = new DiskStateSet.State(ActiveErikssonAvoidanceTest.pair(n),
                        n == ErikssonRoute.ROOT_SYMBOLS ? ErikssonRoute.ROOT_BUDGET - 1 : ErikssonRoute.ROOT_BUDGET - 2);
                expected.add(state);
                queue.add(state);
            }

            queue.finish();
            for (val state : expected) {
                val found = queue.take();
                assertEquals(state.maximumMoves(), found.maximumMoves());
                assertEquals(state.pair().getBeta().size(), found.pair().getBeta().size());
                assertEquals(state.pair().extendedToricClassKey(), found.pair().extendedToricClassKey());
            }

            assertNull(queue.take());
            assertEquals(4, queue.appended());
            assertThrows(IllegalStateException.class, () -> queue.add(expected.getFirst()));
        }

        try (val input = new DataInputStream(Files.newInputStream(path))) {
            assertEquals("td-reconstruction-queue-v1", input.readUTF());
            assertEquals(br.unb.cic.td20.joint.ErikssonRoute.ID, input.readUTF());
            assertEquals(ErikssonRoute.ROOT_SYMBOLS, input.readInt());
            assertEquals(ErikssonRoute.ROOT_BUDGET, input.readInt());

            final int recordBytes = input.readInt();
            assertEquals(4, input.readLong());
            assertEquals(recordBytes * 4, input.readAllBytes().length);
        }

        assertThrows(IllegalStateException.class, () -> new DiskStateQueue(path));
    }

    @Test
    void missingQueueRecordsAreErrors(@TempDir final Path directory) throws Exception {
        final Path path = directory.resolve("truncated.queue");

        try (val queue = new DiskStateQueue(path)) {
            queue.add(new DiskStateSet.State(ActiveErikssonAvoidanceTest.pair(7), 8));
            queue.finish();

            try (val file = FileChannel.open(path, StandardOpenOption.WRITE)) {
                file.truncate(Files.size(path) - 1);
            }

            assertThrows(IllegalStateException.class, queue::take);
        }
    }
}
