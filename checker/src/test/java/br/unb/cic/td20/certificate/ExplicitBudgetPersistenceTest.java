package br.unb.cic.td20.certificate;

import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitBudgetPersistenceTest {
    private static CyclicTargetPair pair() {
        int n=KnownDiameters.ROOT_SYMBOLS-3;
        int[] symbols=new int[n]; for (int i=0;i<n;i++) symbols[i]=i;
        int[] omega=new int[n]; for (int i=0;i<n;i++) omega[i]=(i+n-2)%n;
        var p=new MulticyclePermutation(); boolean[] seen=new boolean[n];
        for (int x=0;x<n;x++) if (!seen[x]) {
            List<Integer> cycle=new ArrayList<>(); int y=x;
            do { seen[y]=true; cycle.add(y); y=omega[y]; } while (y!=x);
            p.add(Cycle.of(cycle.stream().mapToInt(Integer::intValue).toArray()));
        }
        return CyclicTargetPair.ofOwned(p,Cycle.of(symbols)).canonicalRepresentative();
    }
    @Test void replayUsesTheRequestedBudgetForSingleAndBatchedLookup(@TempDir Path directory) throws Exception {
        var pair=pair(); int required=KnownDiameters.MAX_BUDGET-1,smaller=required-1;
        org.rocksdb.RocksDB.loadLibrary();
        try (var options=new org.rocksdb.DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
             var first=new org.rocksdb.ColumnFamilyOptions(); var second=new org.rocksdb.ColumnFamilyOptions()) {
            var handles=new ArrayList<org.rocksdb.ColumnFamilyHandle>();
            try (var database=org.rocksdb.RocksDB.open(options,directory.resolve("proof").toString(),List.of(
                    new org.rocksdb.ColumnFamilyDescriptor(org.rocksdb.RocksDB.DEFAULT_COLUMN_FAMILY,first),
                    new org.rocksdb.ColumnFamilyDescriptor("obstructions".getBytes(java.nio.charset.StandardCharsets.UTF_8),second)),handles)) {
                database.put(handles.get(0),"td20-schema".getBytes(java.nio.charset.StandardCharsets.UTF_8),"joint2-selection-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                database.put(handles.get(0),CertificateCodec.keyFor(pair),CertificateCodec.resultFor(SortingCertificate.closure(List.of())));
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
            }
        }
        try (var store=new CertificateStore(directory.resolve("proof"))) {
            assertTrue(store.find(pair,required).isPresent());
            assertTrue(store.find(pair,smaller).isEmpty());
            assertArrayEquals(new boolean[]{false},store.findAll(List.of(pair),smaller));
            assertArrayEquals(new boolean[]{true},store.findAll(List.of(pair),required));
        }
    }
}
