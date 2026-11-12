package br.unb.cic.td20;

import br.unb.cic.td20.certificate.*;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PruneCertificatesTest {
    private static final CyclicTargetPair THREE = pair(3), FIVE = pair(5), SEVEN = pair(7);
    private static CyclicTargetPair pair(final int n) {
        final int[] symbols = java.util.stream.IntStream.range(0, n).toArray();
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(symbols)), Cycle.of(symbols)).canonicalRepresentative();
    }
    private static Path source(final Path root) throws Exception {
        final Path source = root.resolve("source");
        try (final var writer = new RetainedCertificateWriter(source)) {
            writer.accept(CertificateCodec.keyFor(THREE), CertificateCodec.resultFor(SortingCertificate.complete(List.of(Cycle.of(0,1,2)))));
            writer.accept(CertificateCodec.keyFor(FIVE), CertificateCodec.resultFor(SortingCertificate.closure(List.of())));
            // A surplus class need never be decoded to be safely removed.
            writer.accept(CertificateCodec.keyFor(SEVEN), new byte[]{99, 99});
        }
        return source;
    }
    private static void verify(final CertificateStore store, final Path work) throws Exception {
        Files.createDirectory(work);
        assertEquals(1, store.require(THREE, 1).size());
        assertArrayEquals(new boolean[]{true,true,true}, store.findAll(List.of(FIVE,THREE,FIVE), 3));
    }
    @Test void dropsEveryUnusedClassAndPreservesCompleteAndClosureBytes(@TempDir final Path root) throws Exception {
        final Path source = source(root), output = root.resolve("output"), work = root.resolve("work");
        final var result = PruneCertificates.prune(source, output, work, PruneCertificatesTest::verify);
        assertEquals(3, result.sourceRecords()); assertEquals(2, result.retainedRecords());
        try (final var input = new CertificateStore(source); final var retained = new CertificateStore(output)) {
            assertEquals(3, input.resultCount()); assertEquals(2, retained.resultCount());
            assertTrue(retained.find(SEVEN,10).isEmpty());
            assertTrue(retained.find(FIVE,0).isEmpty()); assertTrue(retained.find(FIVE,3).isPresent());
        }
        assertTrue(Files.readString(work.resolve("pruning-report-v1.txt")).contains("surplusClasses=0"));
    }
    @Test void replacesSourceAndDeletesTheOldDatabaseOnlyAfterBothChecks(@TempDir final Path root) throws Exception {
        final Path source = source(root), output = root.resolve("output");
        PruneCertificates.prune(source, output, root.resolve("work"), true, PruneCertificatesTest::verify);
        assertFalse(Files.exists(output));
        try (final var store = new CertificateStore(source)) { assertEquals(2, store.resultCount()); }
        try (final var children = Files.list(root)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().contains("superseded")));
        }
    }
    @Test void missingRequiredResultCannotPublishOrChangeSource(@TempDir final Path root) throws Exception {
        final Path source = source(root), output = root.resolve("output");
        assertThrows(IllegalStateException.class, () -> PruneCertificates.prune(source,output,root.resolve("work"),true,
                (store, work) -> store.require(pair(9),10)));
        assertFalse(Files.exists(output));
        try (final var store = new CertificateStore(source)) { assertEquals(3,store.resultCount()); }
        try (final var children = Files.list(root)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().contains("partial")));
        }
    }
    @Test void secondVerificationMustUseEveryRetainedClass(@TempDir final Path root) throws Exception {
        final Path source = source(root), output = root.resolve("output"); final var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> PruneCertificates.prune(source,output,root.resolve("work"),true,
                (store, work) -> {
                    store.require(THREE,1);
                    if (calls.incrementAndGet()==1) store.require(FIVE,3);
                }));
        assertEquals(2,calls.get()); assertFalse(Files.exists(output));
        try (final var store = new CertificateStore(source)) { assertEquals(3,store.resultCount()); }
    }
    @Test void rejectsExistingOverlappingAndSymlinkPathsWithoutDeletingThem(@TempDir final Path root) throws Exception {
        final Path source = source(root), existing = Files.createDirectory(root.resolve("existing"));
        final Path sentinel = Files.writeString(existing.resolve("keep"),"keep");
        assertThrows(IllegalArgumentException.class, () -> PruneCertificates.prune(source,existing,root.resolve("work"),PruneCertificatesTest::verify));
        assertEquals("keep",Files.readString(sentinel));
        assertThrows(IllegalArgumentException.class, () -> PruneCertificates.prune(source,source.resolve("out"),root.resolve("work"),PruneCertificatesTest::verify));
        final Path alias = Files.createSymbolicLink(root.resolve("alias"),source);
        assertThrows(IllegalArgumentException.class, () -> PruneCertificates.prune(source,alias.resolve("out"),root.resolve("work"),PruneCertificatesTest::verify));
        assertFalse(Files.exists(source.resolve("out")));
        assertThrows(IllegalArgumentException.class, () -> PruneCertificates.prune(source,root.resolve("out"),source.resolve("nested/work"),PruneCertificatesTest::verify));
        assertFalse(Files.exists(source.resolve("nested")));
    }
    @Test void rejectsLockedSource(@TempDir final Path root) throws Exception {
        final Path source = source(root);
        try (final var channel = FileChannel.open(source.resolve("LOCK"),StandardOpenOption.WRITE); final var lock = channel.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    () -> PruneCertificates.prune(source,root.resolve("out"),root.resolve("work"),PruneCertificatesTest::verify));
        }
        assertFalse(Files.exists(root.resolve("out")));
    }
}
