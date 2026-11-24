package br.unb.cic.td20.certificate;

import br.unb.cic.td20.compat.LegacyCertificates;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveCertificatePersistenceTest {

    @Test
    void mixedApisPreserveTheStrongestWitnessAndRemoveMarkers(@TempDir final Path directory) {
        val pair = pair(21);
        val strong = PrimitiveCertificate.complete(new int[] { pack(0, 1, 2) });
        val weak = PrimitiveCertificate.complete(new int[] { pack(0, 1, 2), pack(1, 0, 2), pack(0, 1, 2) });
        val legacy = LegacyPairs.toLegacy(pair);

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueue(legacy, LegacyCertificates.toLegacy(weak).moves());
            writer.flush();
            assertEquals(weak, writer.findPrimitiveResult(pair, 3).orElseThrow());
            writer.enqueueObstruction(pair, 1);
            writer.flush();
            writer.enqueue(pair, strong, 1);
            writer.enqueue(pair, weak);
            writer.flush();
            assertEquals(strong, writer.findPrimitiveResult(pair, 1).orElseThrow());
            writer.enqueue(legacy, LegacyCertificates.toLegacy(weak).moves());
            writer.flush();
            assertEquals(LegacyCertificates.toLegacy(strong), writer.findResult(legacy, 1).orElseThrow());

            val markers = new ArrayList<byte[]>();
            writer.forEachObstruction(markers::add);
            assertTrue(markers.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> writer.enqueue(pair, weak, 2));
        }

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            assertEquals(strong, writer.findPrimitiveResult(pair, 1).orElseThrow());
        }
    }

    @Test
    void primitiveBulkLookupPreservesEachRequestAndClosureBudget(@TempDir final Path directory) {
        val completePair = pair(21);
        val closurePair = PrimitivePair.of(new int[] { 1, 2, 0, 4, 5, 3 }, new int[] { 0, 1, 2, 3, 4, 5 });
        val missingPair = pair(18);
        val weak = PrimitiveCertificate.complete(new int[] { pack(0, 1, 2), pack(1, 0, 2), pack(0, 1, 2) });
        val closure = PrimitiveCertificate.closure(new int[] { pack(0, 1, 2) });
        val requests = List.of(missingPair, completePair, closurePair, completePair, missingPair);

        try (val writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueue(completePair, weak);
            writer.enqueueClosure(closurePair, closure, 2);
            writer.flush();
            assertTrue(writer.findPrimitiveResults(List.of(), 2).isEmpty());

            val results = writer.findPrimitiveResults(requests, 2);
            assertEquals(List.of(false, false, true, false, false), results.stream().map(Optional::isPresent).toList());
            assertEquals(closure, results.get(2).orElseThrow());
            assertEquals(List.of(false, true, true, true, false),
                    writer.findPrimitiveResults(requests, 3).stream().map(Optional::isPresent).toList());
            for (int i = 0; i < requests.size(); i++) {
                assertEquals(writer.findPrimitiveResult(requests.get(i), 2), results.get(i));
            }
        }
    }

    @Test
    void rawSourceReadsCompatibleFullLengthWitnessesInBatchesAndReplaysBeforeFiltering(@TempDir final Path directory) throws Exception {
        RocksDB.loadLibrary();

        val pair = pair(18);
        val missing = pair(20);
        val weak = PrimitiveCertificate.complete(new int[] { pack(0, 1, 2), pack(1, 0, 2), pack(0, 1, 2) });

        try (val options = new Options().setCreateIfMissing(true);
            val database = RocksDB.open(options, directory.toString());
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            database.put(writes,
                    CertificateCodec.keyFor(pair),
                    CertificateCodec.valueFor(LegacyCertificates.toLegacy(weak).moves()));
            database.flush(flush);
        }

        try (val source = new ReadOnlyWitnessSource(directory)) {
            assertTrue(source.findPrimitive(pair, 2).isEmpty());
            assertEquals(weak, source.findPrimitive(pair, 3).orElseThrow());
            assertEquals(LegacyCertificates.toLegacy(weak).moves(), source.find(LegacyPairs.toLegacy(pair), 3).orElseThrow());
            assertEquals(List.of(false, true, true, false),
                    source.findPrimitives(List.of(missing, pair, pair, missing), 3).stream().map(Optional::isPresent).toList());
            assertTrue(source.findPrimitives(List.of(), 3).isEmpty());
        }

        try (val options = new Options();
            val database = RocksDB.open(options, directory.toString());
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            database.put(writes,
                    CertificateCodec.keyFor(pair),
                    CertificateCodec.valueFor(PrimitiveCertificate.complete(new int[] { pack(0, 2, 1) })));
            database.flush(flush);
        }

        try (val source = new ReadOnlyWitnessSource(directory)) {
            assertThrows(IllegalArgumentException.class, () -> source.findPrimitive(pair, 1));
            assertThrows(IllegalArgumentException.class, () -> source.findPrimitives(List.of(missing, pair), 1));
        }
    }

    private static PrimitivePair pair(final int n) {
        final int[] images = IntStream.range(0, n).toArray();
        images[0] = 1;
        images[1] = 2;
        images[2] = 0;

        return PrimitivePair.of(images, IntStream.range(0, n).toArray());
    }

    private static int pack(final int a, final int b, final int c) {
        return a | b << 5 | c << 10;
    }
}
