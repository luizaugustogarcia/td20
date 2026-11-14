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

class ErikssonCertificateCheckerTest {
    @Test void missingEndpointWitnessRegeneratesAvoidanceWithoutAnyObstructionMarker(@TempDir final Path path) throws Exception {
        final var directory=path.resolve("certificate");
        emptyDatabase(directory);
        final var endpoint=new br.unb.cic.td20.joint.ErikssonInventory.Endpoint(1,new int[]{3,2,2},new boolean[3]);
        long expected=br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(),endpoint.orientedByPart());
        try(final var store=new CertificateStore(directory);final var residuals=new CheckerStateSet(path.resolve("regenerated"));
            final var progress=new VerificationProgress()) {
            ErikssonCertificateChecker.verifyEndpoint(endpoint,store,residuals,progress);
            assertTrue(expected>0);
            assertEquals(endpoint.elementaryClosure()?0:expected,residuals.seal().count());
        }
    }
    @Test void partialRangesCannotBeReportedAsComplete() throws Exception {
        final var options=ErikssonCertificateChecker.Options.parse(new String[]{"--structural-only","--phase=all","--partitions=2-"+br.unb.cic.td20.joint.ErikssonRoute.ROOT_PARTITIONS},Path.of("."));
        assertFalse(options.completeScope());
        assertThrows(IllegalArgumentException.class,()->ErikssonCertificateChecker.Options.parse(
                new String[]{"--structural-only","--endpoints=0-1"},Path.of(".")));
    }
    @Test void missingDirectRootIsRejectedWithoutAMarker(@TempDir Path path) throws Exception {
        Path directory=path.resolve("empty-root-database");emptyDatabase(directory);
        int[] partition={7};
        var counts=br.unb.cic.td20.joint.ErikssonRootGenerator.count(partition,2,new java.util.concurrent.atomic.LongAdder());
        assertTrue(counts.direct()>0);
        var row=new br.unb.cic.td20.joint.ErikssonInventory.Root(1,partition,counts.unoriented(),counts.oriented(),counts.supportCleared());
        try(var store=new CertificateStore(directory);var progress=new VerificationProgress()) {
            assertThrows(IllegalStateException.class,()->ErikssonCertificateChecker.verifyRoot(row,store,progress));
        }
    }
    private static void emptyDatabase(Path directory) throws Exception {
        RocksDB.loadLibrary();
        try(final var options=new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            final var first=new ColumnFamilyOptions();final var second=new ColumnFamilyOptions()) {
            final var handles=new ArrayList<ColumnFamilyHandle>();
            try(final var database=RocksDB.open(options,directory.toString(),List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY,first),
                    new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8),second)),handles)) {
                database.put(handles.get(0),"td20-schema".getBytes(StandardCharsets.UTF_8),"joint2-selection-v2".getBytes(StandardCharsets.UTF_8));
                handles.forEach(ColumnFamilyHandle::close);
            }
        }
    }
}
