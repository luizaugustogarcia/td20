package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RetainedCertificateWriterTest {
    @Test void concurrentBatchesCopyBuffersAndDeduplicateByClass(@TempDir final Path root) throws Exception {
        final var pair=CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        final byte[] key=CertificateCodec.keyFor(pair);
        final byte[] value=CertificateCodec.resultFor(SortingCertificate.closure(List.of()));
        final var writer=new RetainedCertificateWriter(root.resolve("db"));
        try(writer) {
            IntStream.range(0,150_000).parallel().forEach(i->{
                final byte[] mutableKey=key.clone(),mutableValue=value.clone();
                writer.accept(mutableKey,mutableValue);
                java.util.Arrays.fill(mutableKey,(byte)99); java.util.Arrays.fill(mutableValue,(byte)99);
            });
        }
        writer.close();
        assertThrows(IllegalStateException.class,()->writer.accept(key,value));
        try(final var store=new CertificateStore(root.resolve("db"))) {
            assertEquals(1,store.resultCount()); assertTrue(store.find(pair,0).isEmpty()); assertTrue(store.find(pair,1).isPresent());
        }
    }
}
