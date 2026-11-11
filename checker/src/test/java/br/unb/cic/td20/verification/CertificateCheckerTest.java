package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateStore;
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

class CertificateCheckerTest {
    @Test void missingEndpointWitnessRegeneratesAvoidanceWithoutAnyObstructionMarker(@TempDir final Path path) throws Exception {
        RocksDB.loadLibrary();
        final var directory=path.resolve("certificate");
        try(final var options=new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            final var first=new ColumnFamilyOptions();final var second=new ColumnFamilyOptions()) {
            final var handles=new ArrayList<ColumnFamilyHandle>();
            try(final var database=RocksDB.open(options,directory.toString(),List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY,first),
                    new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8),second)),handles)) {
                database.put(handles.get(0),"td20-schema".getBytes(StandardCharsets.UTF_8),"joint3-selection-v1".getBytes(StandardCharsets.UTF_8));
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
        final var pair=CyclicTargetPair.of(new MulticyclePermutation(List.of(
                Cycle.of(0,1,2,10,4,12,6,14,8),Cycle.of(3,15,13,11,9,7,5))),
                Cycle.of(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15)).canonicalRepresentative();
        try(final var store=new CertificateStore(directory);final var residuals=new CheckerStateSet(path.resolve("regenerated"))) {
            assertFalse(store.hasObstruction(pair));
            assertTrue(CertificateChecker.checkEndpointPair(pair,store,residuals));
            assertEquals(1,residuals.seal().count());
        }
    }
    @Test void partialRangesCannotBeReportedAsComplete() throws Exception {
        final var options=CertificateChecker.Options.parse(new String[]{"--structural-only","--phase=all","--partitions=2-83"},Path.of("."));
        assertFalse(options.completeScope());
        assertThrows(IllegalArgumentException.class,()->CertificateChecker.Options.parse(
                new String[]{"--structural-only","--endpoints=0-104"},Path.of(".")));
    }
}
