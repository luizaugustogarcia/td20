package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyWitnessSourceTest {

    @Test
    void replaysBeforeBudgetFiltering(@TempDir final Path directory) throws Exception {
        RocksDB.loadLibrary();

        val pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        val moves = new java.util.ArrayList<Cycle>();
        for (int i = 0; i < 11; i++) {
            moves.add(i % 2 == 0 ? Cycle.of(0, 1, 2) : Cycle.of(1, 0, 2));
        }

        try (val options = new Options().setCreateIfMissing(true);
            val database = RocksDB.open(options, directory.toString());
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            database.put(writes, CertificateCodec.keyFor(pair), CertificateCodec.valueFor(moves));
            database.flush(flush);
        }

        try (val source = new ReadOnlyWitnessSource(directory)) {
            assertTrue(source.find(pair, 10).isEmpty());
        }

        try (val options = new Options();
            val database = RocksDB.open(options, directory.toString());
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            database.put(writes,
                    CertificateCodec.keyFor(pair),
                    CertificateCodec.valueFor(List.of(Cycle.of(0, 1, 2), Cycle.of(0, 1, 2), Cycle.of(0, 1, 2))));
            database.flush(flush);
        }

        try (val source = new ReadOnlyWitnessSource(directory)) {
            assertThrows(IllegalArgumentException.class, () -> source.find(pair, 1));
        }
    }
}
