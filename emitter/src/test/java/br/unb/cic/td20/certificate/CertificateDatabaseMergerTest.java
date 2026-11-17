package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import static org.junit.jupiter.api.Assertions.*;

class CertificateDatabaseMergerTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
    }
    @Test void replayedUnionKeepsStrongestWitnessAndRecordsExactCounts(@TempDir Path root) throws Exception {
        var strong = List.of(Cycle.of(0,1,2));
        var weak = List.of(Cycle.of(0,1,2),Cycle.of(1,0,2),Cycle.of(0,1,2));
        var closure = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),Cycle.of(0,1,2,3,4,5));
        try (var writer = new AsyncCertificateWriter(root.resolve("a"),60)) {
            writer.enqueue(pair(),weak); writer.enqueueObstruction(closure,2);
        }
        try (var writer = new AsyncCertificateWriter(root.resolve("b"),60)) {
            writer.enqueue(pair(),strong); writer.enqueueClosure(closure,List.of(),3);
        }
        var progress = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var result = CertificateDatabaseMerger.merge(root.resolve("merged"),List.of(root.resolve("a"),root.resolve("b")),progress::add);
        assertEquals(new CertificateDatabaseMerger.Result(3,2,1),result);
        assertTrue(progress.stream().anyMatch(line -> line.contains("duplicates=1")));
        try (var writer = new AsyncCertificateWriter(root.resolve("merged"),60)) {
            assertEquals(strong,writer.find(pair(),1).orElseThrow());
            assertTrue(writer.findResult(closure,3).isPresent());
            var markers = new ArrayList<byte[]>(); writer.forEachObstruction(markers::add); assertTrue(markers.isEmpty());
        }
        assertTrue(Files.readString(root.resolve("merged/merge-v1.txt")).contains("uniqueWitnesses=2"));
        assertThrows(IllegalArgumentException.class,() -> CertificateDatabaseMerger.merge(root.resolve("merged"),List.of(root.resolve("a"))));
        assertThrows(Exception.class,() -> CertificateDatabaseMerger.merge(root.resolve("missing"),List.of(root.resolve("absent"))));
    }
    @Test void corruptDuplicateCannotHideBehindAValidWinner(@TempDir Path root) throws Exception {
        for (String name : List.of("good","bad")) try (var writer = new AsyncCertificateWriter(root.resolve(name),60)) {
            writer.enqueue(pair(),List.of(Cycle.of(0,1,2)));
        }
        var handles = new ArrayList<ColumnFamilyHandle>();
        try (var options = new DBOptions(); var family = new ColumnFamilyOptions();
             var db = RocksDB.open(options,root.resolve("bad").toString(),List.of(
                     new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY,family),
                     new ColumnFamilyDescriptor(AsyncCertificateWriter.OBSTRUCTIONS,family)),handles)) {
            try (var writes = new WriteOptions().setDisableWAL(true); var flush = new FlushOptions().setWaitForFlush(true)) {
                db.put(handles.getFirst(),writes,CertificateCodec.keyFor(pair()),new byte[]{42}); db.flush(flush,handles);
            } finally { handles.forEach(ColumnFamilyHandle::close); }
        }
        assertThrows(IllegalStateException.class,() -> CertificateDatabaseMerger.merge(root.resolve("output"),List.of(root.resolve("good"),root.resolve("bad"))));
        assertFalse(Files.exists(root.resolve("output/merge-v1.txt")));
    }
    @Test void inferredDomainsDistinguishEqualByteLengths() {
        var random = new Random(414);
        for (int n = 1; n <= 21; n++) {
            int[] beta = java.util.stream.IntStream.range(0,n).toArray();
            var order = new ArrayList<Integer>(); for (int i = 0; i < n; i++) order.add(i);
            Collections.shuffle(order,random);
            var target = Cycle.of(order.stream().mapToInt(Integer::intValue).toArray());
            var pair = CyclicTargetPair.of(PermutationGroups.computeProduct(target,Cycle.of(beta).getInverse()),Cycle.of(beta));
            byte[] key = CertificateCodec.keyFor(pair);
            assertEquals(n,CertificateCodec.pairForKey(key).getBeta().size());
            assertArrayEquals(key,CertificateCodec.keyFor(CertificateCodec.pairForKey(key)));
        }
        assertThrows(IllegalArgumentException.class,() -> CertificateCodec.pairForKey(new byte[]{(byte)255}));
    }
    @Test void equalBudgetTiesHaveTheSameEncodedWinnerInEitherInputOrder(@TempDir Path root) throws Exception {
        try (var writer = new AsyncCertificateWriter(root.resolve("closure"),60)) { writer.enqueueClosure(pair(),List.of(),1); }
        try (var writer = new AsyncCertificateWriter(root.resolve("complete"),60)) { writer.enqueue(pair(),List.of(Cycle.of(0,1,2))); }
        for (var names : List.of(List.of("closure","complete"),List.of("complete","closure"))) {
            Path output = root.resolve("merged-" + names.getFirst());
            assertEquals(new CertificateDatabaseMerger.Result(2,1,0),CertificateDatabaseMerger.merge(output,names.stream().map(root::resolve).toList()));
            try (var reader = new AsyncCertificateWriter(output,60)) {
                assertEquals(SortingCertificate.Kind.COMPLETE,reader.findResult(pair(),1).orElseThrow().kind());
            }
        }
    }
}
