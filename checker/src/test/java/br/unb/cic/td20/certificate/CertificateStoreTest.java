package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CertificateStoreTest {
    static { RocksDB.loadLibrary(); }
    private static final CyclicTargetPair PAIR = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
    private static void database(final Path path, final byte[] value, final boolean marker) throws Exception {
        database(path, value, marker ? "eriksson-three-v1" : null, PAIR);
    }
    private static void database(final Path path, final byte[] value, final String marker, final CyclicTargetPair pair) throws Exception {
        try (final var options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
             final var first = new ColumnFamilyOptions(); final var second = new ColumnFamilyOptions()) {
            final var handles = new ArrayList<ColumnFamilyHandle>();
            try (final var db = RocksDB.open(options,path.toString(),List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY,first),
                    new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8),second)),handles);
                 final var writes = new WriteOptions().setDisableWAL(true);
                 final var flush = new FlushOptions().setWaitForFlush(true)) {
                if(marker!=null) db.put(handles.get(0),writes,"td20-schema".getBytes(StandardCharsets.UTF_8),marker.getBytes(StandardCharsets.UTF_8));
                if(value!=null) db.put(handles.get(0),writes,CertificateCodec.keyFor(pair),value);
                db.flush(flush,handles);
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }
    @Test void acceptsReplayedWitness(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0,1,2)))),true);
        try(final var store=new CertificateStore(path.resolve("db"))) {
            assertEquals(1,store.require(PAIR,1).size());
            final var missing=CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2,3,4)),Cycle.of(0,1,2,3,4));
            assertArrayEquals(new boolean[]{true,false,true},store.findAll(List.of(PAIR,missing,PAIR),1));
            assertArrayEquals(new boolean[]{false,false},store.findAll(List.of(PAIR,missing),0));
            assertFalse(store.hasObstruction(PAIR));
        }
    }
    @Test void zeroMoveClosureIsPresentAndItsDiameterConsumesBudget(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),CertificateCodec.resultFor(SortingCertificate.closure(List.of())),true);
        try(final var store=new CertificateStore(path.resolve("db"))) {
            assertTrue(store.find(PAIR,0).isEmpty());
            assertTrue(store.find(PAIR,1).isPresent());
            assertTrue(store.require(PAIR,1).isEmpty());
        }
    }
    @Test void invalidResultKindIsRejected(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),new byte[]{2,0},true);
        try(final var store=new CertificateStore(path.resolve("db"))) {
            assertThrows(IllegalArgumentException.class,()->store.find(PAIR,10));
            assertThrows(IllegalArgumentException.class,()->store.findAll(List.of(PAIR),10));
        }
    }
    @Test void longerValidWitnessIsUnavailableAtSmallerBudget(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0,1,2),Cycle.of(1,0,2),Cycle.of(0,1,2)))),true);
        try(final var store=new CertificateStore(path.resolve("db"))) {
            assertTrue(store.find(PAIR,1).isEmpty());
            assertEquals(3,store.require(PAIR,3).size());
        }
    }
    @Test void malformedWitnessFailsEvenAboveRequestedBudget(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0,1,2),Cycle.of(1,0,2)))),true);
        try(final var store=new CertificateStore(path.resolve("db"))) {
            assertThrows(IllegalArgumentException.class,()->store.find(PAIR,1));
            assertThrows(IllegalArgumentException.class,()->store.findAll(List.of(PAIR),1));
        }
    }

    @Test void lookupReplaysInTheCanonicalFrameForRotatedCurrentCycles(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0,1,2)))),true);
        var rotated=CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(1,2,0));
        var decoded=CertificateCodec.pairForKey(CertificateCodec.keyFor(PAIR),3);
        var missing=CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2,3,4)),Cycle.of(0,1,2,3,4));
        try(var store=new CertificateStore(path.resolve("db"))) {
            assertEquals(List.of(Cycle.of(0,1,2)),store.require(rotated,1));
            assertArrayEquals(new boolean[]{true,true,false},store.findAll(List.of(rotated,decoded,missing),1));
        }
    }

    @Test void projectMaximumIsValidatedBeforeRequestedBudgetComparison(@TempDir final Path path) throws Exception {
        var moves=new ArrayList<Cycle>();
        for(int i=0;i<4;i++) {moves.add(Cycle.of(0,1,2));moves.add(Cycle.of(1,0,2));}
        var pair=CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2,3,4,5,6)),Cycle.of(0,1,2,3,4,5,6)).canonicalRepresentative();
        // Eight moves return to this pair; TD(6)=4 makes the closure require twelve.
        database(path.resolve("db"),CertificateCodec.resultFor(SortingCertificate.closure(moves)),"eriksson-three-v1",pair);
        try(var store=new CertificateStore(path.resolve("db"))) {
            assertThrows(IllegalArgumentException.class,()->store.find(pair,20));
            assertThrows(IllegalArgumentException.class,()->store.findAll(List.of(pair),20));
        }
    }
    @Test void missingWitnessIsNotInventedFromInventory(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),null,true);
        try(final var store=new CertificateStore(path.resolve("db"))) {
            assertTrue(store.find(PAIR,10).isEmpty());
            assertThrows(IllegalStateException.class,()->store.require(PAIR,10));
        }
    }
    @Test void rejectsSupersededSchema(@TempDir final Path path) throws Exception {
        database(path.resolve("old-route"),null,"joint2-selection-v2",PAIR);
        assertThrows(IllegalStateException.class,()->new CertificateStore(path.resolve("old-route")));
    }
    @Test void rejectsMissingFormatMarker(@TempDir final Path path) throws Exception {
        database(path.resolve("db"),null,false);
        assertThrows(IllegalStateException.class,()->new CertificateStore(path.resolve("db")));
    }
}
