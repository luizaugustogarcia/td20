package br.unb.cic.td20.certificate;

import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitBudgetPersistenceTest {

    private static CyclicTargetPair pair() {
        final int n = KnownDiameters.ROOT_SYMBOLS - 3;
        val symbols = new int[n];
        for (int i = 0; i < n; i++) {
            symbols[i] = i;
        }

        val omega = new int[n];
        for (int i = 0; i < n; i++) {
            omega[i] = (i + n - 2) % n;
        }

        val p = new MulticyclePermutation();
        val seen = new boolean[n];
        for (int x = 0; x < n; x++) {
            if (!seen[x]) {
                final List<Integer> cycle = new ArrayList<>();
                int y = x;
                do {
                    seen[y] = true;
                    cycle.add(y);
                    y = omega[y];
                } while (y != x);

                p.add(Cycle.of(cycle.stream().mapToInt(Integer::intValue).toArray()));
            }
        }

        return CyclicTargetPair.ofOwned(p, Cycle.of(symbols)).canonicalRepresentative();
    }

    @Test
    void replayUsesTheRequestedBudgetForSingleAndBatchedLookup(@TempDir final Path directory) throws Exception {
        val pair = pair();
        final int required = KnownDiameters.MAX_BUDGET - 1, smaller = required - 1;
        org.rocksdb.RocksDB.loadLibrary();

        try (val options = new org.rocksdb.DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            val first = new org.rocksdb.ColumnFamilyOptions();
            val second = new org.rocksdb.ColumnFamilyOptions()) {
            val handles = new ArrayList<org.rocksdb.ColumnFamilyHandle>();

            try (val database = org.rocksdb.RocksDB.open(options,
                    directory.resolve("proof").toString(),
                    List.of(new org.rocksdb.ColumnFamilyDescriptor(org.rocksdb.RocksDB.DEFAULT_COLUMN_FAMILY, first),
                            new org.rocksdb.ColumnFamilyDescriptor("obstructions".getBytes(java.nio.charset.StandardCharsets.UTF_8), second)),
                    handles);
                val writes = new org.rocksdb.WriteOptions().setDisableWAL(true);
                val flush = new org.rocksdb.FlushOptions().setWaitForFlush(true)) {
                database.put(handles.get(0),
                        writes,
                        "td20-schema".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "eriksson-three-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                database.put(handles.get(0),
                        writes,
                        CertificateCodec.keyFor(pair),
                        CertificateCodec.resultFor(SortingCertificate.closure(List.of())));
                database.flush(flush, handles);
                handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
            }
        }

        try (val store = new CertificateStore(directory.resolve("proof"))) {
            assertTrue(store.find(pair, required).isPresent());
            assertTrue(store.find(pair, smaller).isEmpty());
            assertArrayEquals(new boolean[] { false }, store.findAll(List.of(pair), smaller));
            assertArrayEquals(new boolean[] { true }, store.findAll(List.of(pair), required));
        }
    }
}
