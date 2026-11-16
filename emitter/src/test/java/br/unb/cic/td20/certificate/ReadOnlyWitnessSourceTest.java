package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;
import org.rocksdb.FlushOptions;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyWitnessSourceTest {
    @Test void replaysBeforeBudgetFiltering(@TempDir Path directory) throws Exception {
        RocksDB.loadLibrary();
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        var moves = new java.util.ArrayList<Cycle>();
        for (int i = 0; i < 11; i++) moves.add(i % 2 == 0 ? Cycle.of(0,1,2) : Cycle.of(1,0,2));
        try (var options = new Options().setCreateIfMissing(true); var database = RocksDB.open(options,directory.toString());
             var writes=new WriteOptions().setDisableWAL(true); var flush=new FlushOptions().setWaitForFlush(true)) {
            database.put(writes,CertificateCodec.keyFor(pair),CertificateCodec.valueFor(moves)); database.flush(flush);
        }
        try (var source = new ReadOnlyWitnessSource(directory)) {
            assertTrue(source.find(pair,10).isEmpty());
        }
        try (var options = new Options(); var database = RocksDB.open(options,directory.toString());
             var writes=new WriteOptions().setDisableWAL(true); var flush=new FlushOptions().setWaitForFlush(true)) {
            database.put(writes,CertificateCodec.keyFor(pair),CertificateCodec.valueFor(List.of(Cycle.of(0,1,2),Cycle.of(0,1,2),Cycle.of(0,1,2))));
            database.flush(flush);
        }
        try (var source = new ReadOnlyWitnessSource(directory)) {
            assertThrows(IllegalArgumentException.class, () -> source.find(pair,1));
        }
    }
}
