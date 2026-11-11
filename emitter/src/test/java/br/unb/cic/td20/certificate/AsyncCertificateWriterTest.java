package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCertificateWriterTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
    }
    @Test void drainsUnderQueuePressureAndReopens(@TempDir Path directory) throws Exception {
        String previous = System.getProperty("td20.certificateQueueCapacity");
        System.setProperty("td20.certificateQueueCapacity","2");
        try {
            try (var writer = new AsyncCertificateWriter(directory,60); var pool = Executors.newFixedThreadPool(4)) {
                var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i=0;i<100;i++) tasks.add(pool.submit(() -> writer.enqueue(pair(),List.of(Cycle.of(0,1,2)))));
                for (var task : tasks) task.get();
                writer.enqueueObstruction(pair(),11);
            }
            try (var writer = new AsyncCertificateWriter(directory,60)) {
                assertEquals(List.of(Cycle.of(0,1,2)),writer.find(pair(),10).orElseThrow());
                var keys = new ArrayList<byte[]>(); writer.forEachObstruction(keys::add); assertTrue(keys.isEmpty());
            }
        } finally {
            if (previous == null) System.clearProperty("td20.certificateQueueCapacity");
            else System.setProperty("td20.certificateQueueCapacity",previous);
        }
    }
    @Test void witnessRemovesAnUnresolvedKey(@TempDir Path directory) {
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            writer.enqueueObstruction(pair(),11); writer.flush();
            var keys = new ArrayList<byte[]>(); writer.forEachObstruction(keys::add);
            assertEquals(1,keys.size()); assertTrue(writer.find(pair(),10).isEmpty());
            writer.enqueue(pair(),List.of(Cycle.of(0,1,2))); writer.flush();
            keys.clear(); writer.forEachObstruction(keys::add); assertTrue(keys.isEmpty());
        }
    }
    @Test void validLongerWitnessIsABudgetMiss(@TempDir Path directory) {
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            var moves = List.of(Cycle.of(0,1,2),Cycle.of(1,0,2),Cycle.of(0,1,2));
            writer.enqueue(pair(),moves); writer.flush();
            assertTrue(writer.find(pair(),2).isEmpty()); assertEquals(moves,writer.find(pair(),3).orElseThrow());
        }
    }
    @Test void multiGetPreservesMissingDuplicateAndOverBudgetPositions(@TempDir Path directory) {
        var closure = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),Cycle.of(0,1,2,3,4,5));
        var missing = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2,3,4)),Cycle.of(0,1,2,3,4));
        var requests = List.of(missing, pair(), closure, pair(), missing);
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            writer.enqueue(pair(), List.of(Cycle.of(0,1,2),Cycle.of(1,0,2),Cycle.of(0,1,2)));
            writer.enqueueClosure(closure,List.of(Cycle.of(0,1,2)),2);
            writer.flush();
            assertTrue(writer.findResults(List.of(),2).isEmpty());
            var results = writer.findResults(requests,2);
            assertEquals(List.of(false,false,true,false,false),results.stream().map(java.util.Optional::isPresent).toList());
            assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE,results.get(2).orElseThrow().kind());
            assertEquals(List.of(false,true,true,true,false),writer.findResults(requests,3).stream()
                    .map(java.util.Optional::isPresent).toList());
            for(int i=0;i<requests.size();i++) assertEquals(writer.findResult(requests.get(i),2),results.get(i));
        }
    }
    @Test void multiGetRejectsCorruptCertificates(@TempDir Path directory) throws Exception {
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            writer.enqueue(pair(),List.of(Cycle.of(0,1,2))); writer.flush();
        }
        var handles = new ArrayList<org.rocksdb.ColumnFamilyHandle>();
        try (var options = new org.rocksdb.DBOptions(); var familyOptions = new org.rocksdb.ColumnFamilyOptions();
             var database = RocksDB.open(options,directory.toString(),List.of(
                     new org.rocksdb.ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY,familyOptions),
                     new org.rocksdb.ColumnFamilyDescriptor("obstructions".getBytes(java.nio.charset.StandardCharsets.UTF_8),familyOptions)),handles)) {
            try { database.put(handles.getFirst(),CertificateCodec.keyFor(pair()),new byte[]{42}); }
            finally { handles.forEach(org.rocksdb.ColumnFamilyHandle::close); }
        }
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            assertThrows(IllegalArgumentException.class,()->writer.findResults(List.of(pair()),10));
        }
    }
    @Test void closureRemovesObstructionAndReopensWithItsActualBudget(@TempDir Path directory) {
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            writer.enqueueObstruction(pair(),11); writer.flush();
            writer.enqueueClosure(pair(),List.of(),1); writer.flush();
            assertTrue(writer.find(pair(),0).isEmpty());
            assertTrue(writer.find(pair(),1).isPresent());
            var keys = new ArrayList<byte[]>(); writer.forEachObstruction(keys::add); assertTrue(keys.isEmpty());
        }
        try (var writer = new AsyncCertificateWriter(directory,60)) {
            assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE,writer.findResult(pair(),1).orElseThrow().kind());
            assertTrue(writer.find(pair(),1).orElseThrow().isEmpty());
        }
    }
    @Test void rejectsUnversionedDatabaseAndInvalidWitness(@TempDir Path directory) throws Exception {
        RocksDB.loadLibrary();
        try (var options = new Options().setCreateIfMissing(true); var ignored = RocksDB.open(options,directory.resolve("source").toString())) { }
        assertThrows(IllegalStateException.class, () -> new AsyncCertificateWriter(directory.resolve("source"),60));
        try (var writer = new AsyncCertificateWriter(directory.resolve("proof"),60)) {
            assertThrows(IllegalArgumentException.class, () -> writer.enqueue(pair(),List.of(Cycle.of(0,2,1))));
            assertThrows(IllegalArgumentException.class, () -> writer.enqueueObstruction(pair(),8));
        }
    }
}
