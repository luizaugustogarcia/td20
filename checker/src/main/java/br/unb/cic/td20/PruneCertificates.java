package br.unb.cic.td20;

import br.unb.cic.td20.certificate.CertificateStore;
import br.unb.cic.td20.certificate.RetainedCertificateWriter;
import br.unb.cic.td20.runtime.ProjectPaths;
import br.unb.cic.td20.verification.CertificateChecker;
import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;

/** Retains exactly the classes used by full verification, then verifies coverage and absence of surplus. */
public final class PruneCertificates {
    private PruneCertificates() { }
    @FunctionalInterface interface Verifier {
        void verify(CertificateStore store, Path work) throws Exception;
    }
    record Result(long sourceRecords, long retainedRecords, long sourceBytes, long retainedBytes) { }
    public static void main(final String[] args) throws Exception {
        final Path root = ProjectPaths.root(Path.of(""));
        Path source = root.resolve("certificates"), output = root.resolve("certificates-pruned");
        Path paper = root.resolve("docs/td20-reduction-count.tex");
        Path work = root.resolve("checker/target/certificate-pruning-" + UUID.randomUUID());
        boolean replace = false;
        for (final String arg : args) {
            if (arg.equals("--help")) {
                System.out.println("PruneCertificates [--source=PATH] [--output=NEW_PATH] [--paper=PATH] [--work=NEW_PATH] [--replace-source]");
                return;
            } else if (arg.startsWith("--source=")) source = Path.of(arg.substring(9));
            else if (arg.startsWith("--output=")) output = Path.of(arg.substring(9));
            else if (arg.startsWith("--paper=")) paper = Path.of(arg.substring(8));
            else if (arg.startsWith("--work=")) work = Path.of(arg.substring(7));
            else if (arg.equals("--replace-source")) replace = true;
            else throw new IllegalArgumentException("Unknown pruning argument: " + arg);
        }
        final Path sourcePaper = paper.toAbsolutePath().normalize();
        try {
            final var result = prune(source, output, work, replace,
                    (store, state) -> CertificateChecker.verifyAll(sourcePaper, store, state));
            System.out.printf("Verified pruned certificate: sourceRecords=%d retainedRecords=%d removedRecords=%d sourceBytes=%d retainedBytes=%d output=%s%n",
                    result.sourceRecords(), result.retainedRecords(), result.sourceRecords() - result.retainedRecords(),
                    result.sourceBytes(), result.retainedBytes(), (replace ? source : output).toAbsolutePath().normalize());
        } finally { DirectTwistedBraceletGenerator.shutdownExecutor(); }
    }
    static Result prune(final Path source, final Path output, final Path work, final Verifier verifier) throws Exception {
        return prune(source, output, work, false, verifier);
    }
    static Result prune(Path source, Path output, Path work, final boolean replace, final Verifier verifier) throws Exception {
        source = source.toRealPath();
        output = resolved(output); work = resolved(work);
        if (overlap(source, output) || overlap(source, work) || overlap(work, output)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS) || Files.exists(work, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Source, fresh output, and fresh work must be separate directories");
        Files.createDirectories(output.getParent()); Files.createDirectories(work.getParent());
        final Path staging = output.resolveSibling(output.getFileName() + ".partial-" + UUID.randomUUID());
        boolean published = false;
        try (final var sourceLock = new DatabaseLock(source)) {
            Files.createDirectory(work);
            try {
                final long sourceBytes = bytes(source);
                final long sourceCount;
                try (final var writer = new RetainedCertificateWriter(staging);
                     final var certificates = CertificateStore.forPruning(source, writer)) {
                    System.out.println("Verifying source and retaining accepted results: " + source);
                    verifier.verify(certificates, work.resolve("source-verification"));
                    sourceCount = certificates.resultCount();
                }
                try (final var outputLock = new DatabaseLock(staging)) {
                    final Path usedClasses = work.resolve("used-classes");
                    final long retainedCount;
                    // A fresh independent set audits every class in the persisted output.
                    try (final var used = new RetainedCertificateWriter(usedClasses);
                         final var certificates = CertificateStore.forAudit(staging, used)) {
                        System.out.println("Independently verifying coverage and absence of surplus: " + staging);
                        verifier.verify(certificates, work.resolve("output-verification"));
                        retainedCount = certificates.resultCount();
                    }
                    try (final var certificates = new CertificateStore(staging);
                         final var used = new CertificateStore(usedClasses)) {
                        certificates.requireSameResults(used);
                    }
                    remove(usedClasses);
                    if (retainedCount > sourceCount) throw new IllegalStateException("Retention introduced new records");
                    final var result = new Result(sourceCount, retainedCount, sourceBytes, bytes(staging));
                    Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
                    published = true;
                    if (replace) install(source, output);
                    Files.write(work.resolve("pruning-report-v1.txt"), java.util.List.of(
                            "td20-certificate-pruning-report-v1", "source=" + source,
                            "output=" + (replace ? source : output),
                            "sourceRecords=" + sourceCount, "retainedRecords=" + retainedCount,
                            "removedRecords=" + (sourceCount - retainedCount), "sourceBytes=" + sourceBytes,
                            "retainedBytes=" + result.retainedBytes(), "sourceVerification=complete",
                            "outputVerification=complete", "surplusClasses=0",
                            "replacedSource=" + replace, "complete=true"));
                    return result;
                }
            } finally {
                if (!published && Files.exists(staging)) remove(staging);
            }
        }
    }
    private static void install(final Path source, final Path output) throws Exception {
        final Path backup = source.resolveSibling(source.getFileName() + ".superseded-" + UUID.randomUUID());
        Files.move(source, backup, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(output, source, StandardCopyOption.ATOMIC_MOVE);
        } catch (final Exception failure) {
            try { Files.move(backup, source, StandardCopyOption.ATOMIC_MOVE); }
            catch (final Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        remove(backup);
    }
    private static boolean overlap(final Path first, final Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }
    private static Path resolved(final Path path) throws Exception {
        final Path absolute = path.toAbsolutePath().normalize();
        Path ancestor = absolute;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        return ancestor.toRealPath().resolve(ancestor.relativize(absolute));
    }
    private static long bytes(final Path directory) throws Exception {
        long total = 0;
        try (final var paths = Files.walk(directory)) {
            for (final var path : paths.filter(Files::isRegularFile).toList()) total = Math.addExact(total, Files.size(path));
        }
        return total;
    }
    private static void remove(final Path directory) throws Exception {
        try (final var paths = Files.walk(directory)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    /** Uses the same LOCK file as RocksDB writers; read-only verification remains possible. */
    private static final class DatabaseLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        DatabaseLock(final Path directory) throws Exception {
            channel = FileChannel.open(directory.resolve("LOCK"), StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
                if (lock == null) throw new IllegalStateException("Certificate database is in use by a writer: " + directory);
            } catch (final Exception | Error error) { channel.close(); throw error; }
        }
        @Override public void close() throws Exception {
            try { lock.close(); } finally { channel.close(); }
        }
    }
}
