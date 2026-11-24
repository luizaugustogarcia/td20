package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.certificate.CertificateStore;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.runtime.BoundedTasks;
import br.unb.cic.td20.runtime.CheckerParallelism;
import br.unb.cic.td20.runtime.ProjectPaths;
import lombok.val;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Read-only verification of the copied after-two scope, not whole-endpoint or TD20 proof coverage.
 */
public final class ImportedTd18Verifier {

    static {
        RocksDB.loadLibrary();
    }

    public static final long EXPECTED_SOURCE = 5_032_950;
    public static final long EXPECTED_HITS = 4_062_619;
    public static final long EXPECTED_RESIDUALS = 970_331;
    public static final int SYMBOLS = 18, BUDGET = 9;
    public static final String REPORT_FORMAT = "td20-independent-imported-td18-scope-v1";

    private ImportedTd18Verifier() {
    }

    public record Scope(Path source, Path certificates, Path residuals, Path report, long sourceCount, long hitCount, long residualCount, boolean checkMarkers) {

        public Scope {
            java.util.Objects.requireNonNull(source);
            java.util.Objects.requireNonNull(certificates);
            java.util.Objects.requireNonNull(residuals);
            java.util.Objects.requireNonNull(report);
            if (sourceCount < 0 || hitCount < 0 || residualCount < 0 || Math.addExact(hitCount, residualCount) != sourceCount) {
                throw new IllegalArgumentException("The exact imported-scope counts must be nonnegative and account for every source class");
            }
        }
    }

    public record Result(long source, long hits, long residuals, boolean markersChecked) {
    }

    public static void main(final String[] arguments) throws Exception {
        if (Arrays.asList(arguments).contains("--help")) {
            usage();
            return;
        }

        final Result result = verify(parse(arguments, ProjectPaths.root(Path.of(""))));
        System.out.printf("Imported after-two scope independently verified: source=%d budgetNineHits=%d residuals=%d markersChecked=%s; whole-endpoint regeneration and full TD20 proof verification remain outside this audit.%n",
                result.source(),
                result.hits(),
                result.residuals(),
                result.markersChecked());
    }

    /**
     * Every input database remains read-only; a complete report is published only after both exact scans join.
     */
    public static Result verify(final Scope requested) throws IOException {
        final Scope scope = checkedPaths(requested);
        final int workers = Math.min(CheckerParallelism.workers(), Runtime.getRuntime().availableProcessors());
        final int batchKeys = Integer.getInteger("td20.importVerificationBatchKeys", 4096);
        if (batchKeys < 1) {
            throw new IllegalArgumentException("td20.importVerificationBatchKeys must be positive");
        }

        System.out.printf("Independent imported-scope audit: domain=%d budget=%d workers=%d batchKeys=%d source=%s certificates=%s residuals=%s report=%s%n",
                SYMBOLS,
                BUDGET,
                workers,
                batchKeys,
                scope.source(),
                scope.certificates(),
                scope.residuals(),
                scope.report());

        val hits = new LongAdder();
        val missing = new LongAdder();

        try (val source = new StateDatabase(scope.source());
            val residuals = new StateDatabase(scope.residuals());
            val certificates = new Certificates(scope.certificates())) {
            final long sourceCount = scan(source,
                    scope.sourceCount(),
                    workers,
                    batchKeys,
                    "imported source witness replay",
                    () -> "budgetNineHits=" + hits.sum() + ", residuals=" + missing.sum(),
                    keys -> {
                        val states = keys.stream().map(ImportedTd18Verifier::requireAfterTwoState).toList();
                        final List<byte[]> certificateKeys = keys.stream().map(key -> Arrays.copyOfRange(key, 3, key.length)).toList();
                        final List<byte[]> results = certificates.findAll(certificateKeys);
                        final List<byte[]> records = residuals.findAll(keys);
                        if (results.size() != keys.size() || records.size() != keys.size()) {
                            throw new IllegalStateException("Incomplete imported-scope batch lookup");
                        }
                        for (int i = 0; i < keys.size(); i++) {
                            final byte[] residual = records.get(i);
                            final byte[] encoded = results.get(i);
                            final boolean covered = encoded != null
                                    && CertificateValidator.validatedRequiredBudget(states.get(i).pair(), CertificateCodec.decodePrimitiveResult(encoded), KnownDiameters.MAX_BUDGET) <= BUDGET;
                            if (covered) {
                                if (residual != null) {
                                    throw new IllegalStateException("A replay-covered class remains in the residual state set");
                                }

                                hits.increment();
                            } else {
                                if (residual == null || residual.length != 0) {
                                    throw new IllegalStateException("Missing or malformed residual record for a class without a budget-nine result");
                                }
                                if (scope.checkMarkers() && !certificates.hasObstruction(certificateKeys.get(i))) {
                                    throw new IllegalStateException("Missing informational marker for an independently derived residual");
                                }

                                missing.increment();
                            }
                        }
                    });
            final long residualCount = scan(residuals,
                    scope.residualCount(),
                    workers,
                    batchKeys,
                    "residual inventory validation",
                    () -> "expectedResiduals=" + scope.residualCount(),
                    keys -> {
                        keys.forEach(ImportedTd18Verifier::requireAfterTwoState);

                        final List<byte[]> records = source.findAll(keys);
                        if (records.size() != keys.size()) {
                            throw new IllegalStateException("Incomplete source-membership lookup");
                        }
                        for (final byte[] record : records) {
                            if (record == null || record.length != 0) {
                                throw new IllegalStateException("A residual does not belong to the exact source state inventory");
                            }
                        }
                    });
            if (hits.sum() != scope.hitCount()
                    || missing.sum() != scope.residualCount()
                    || Math.addExact(hits.sum(), missing.sum()) != sourceCount
                    || missing.sum() != residualCount) {
                throw new IllegalStateException("Replayed imported-scope counts differ from the exact requested inventory: source=" + sourceCount + ", hits=" + hits.sum() + ", residuals=" + missing.sum());
            }

            val result = new Result(sourceCount, hits.sum(), residualCount, scope.checkMarkers());
            publish(scope, result, workers, batchKeys);

            return result;
        }
    }

    private static CheckerStateStorage.State requireAfterTwoState(final byte[] key) {
        val state = CheckerStateStorage.decode(key, new byte[0]);
        if (state.maximumMoves() != BUDGET || state.pair().size() != SYMBOLS) {
            throw new IllegalStateException("Imported scope requires fixed-point-free 18-symbol budget-nine states");
        }
        if (!Arrays.equals(key, DirectStateKey.encode(state.pair(), BUDGET))) {
            throw new IllegalStateException("Noncanonical or nondeterministic imported-scope state key");
        }

        return state;
    }

    private static long scan(final StateDatabase database,
            final long expected,
            final int workers,
            final int maximumBatchKeys,
            final String phase,
            final java.util.function.Supplier<String> details,
            final Consumer<List<byte[]>> consume) {
        final int batchKeys = (int) Math.min(maximumBatchKeys, Math.max(1, (expected + 32L * workers - 1) / (32L * workers)));
        val completed = new LongAdder();
        long scanned = 0;

        try (val progress = new CheckerStateProgress(phase, expected, completed::sum, details);
            val tasks = new BoundedTasks(workers, "import-scope-");
            val reads = new ReadOptions().setFillCache(false);
            val iterator = database.database.newIterator(reads)) {
            val batch = new ArrayList<byte[]>(batchKeys);
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                tasks.check();
                if (iterator.value().length != 0) {
                    throw new IllegalStateException("A v3 state record must have an empty value");
                }

                batch.add(iterator.key());
                scanned = Math.addExact(scanned, 1);
                if (scanned > expected) {
                    throw new IllegalStateException("The exact state inventory contains extra records");
                }
                if (batch.size() == batchKeys) {
                    submit(tasks, batch, consume, completed);
                    batch.clear();
                }
            }

            iterator.status();
            if (!batch.isEmpty()) {
                submit(tasks, batch, consume, completed);
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot exhaustively read imported-scope states", failure);
        }
        if (scanned != expected || completed.sum() != scanned) {
            throw new IllegalStateException("Missing or unprocessed imported-scope state records: expected=" + expected + ", scanned=" + scanned + ", completed=" + completed.sum());
        }

        return scanned;
    }

    private static void submit(final BoundedTasks tasks,
            final List<byte[]> batch,
            final Consumer<List<byte[]>> consume,
            final LongAdder completed) {
        final List<byte[]> keys = List.copyOf(batch);
        tasks.submit(() -> {
            consume.accept(keys);
            completed.add(keys.size());
        });
    }

    private static Scope checkedPaths(final Scope scope) throws IOException {
        final Path source = scope.source().toRealPath(), certificates = scope.certificates().toRealPath(), residuals = scope.residuals().toRealPath(), report = scope.report().toAbsolutePath().normalize();
        for (final Path input : List.of(source, certificates, residuals)) {
            if (!Files.isRegularFile(input.resolve("CURRENT"))) {
                throw new IllegalArgumentException("Missing RocksDB input: " + input);
            }
        }
        if (source.equals(residuals) || source.equals(certificates) || residuals.equals(certificates)) {
            throw new IllegalArgumentException("Source, certificate and residual databases must be distinct");
        }
        for (final Path input : List.of(source, certificates, residuals)) {
            if (report.startsWith(input)) {
                throw new IllegalArgumentException("The output report must remain outside every read-only input database");
            }
        }
        if (Files.exists(report)) {
            throw new IllegalArgumentException("A fresh audit report path is required: " + report);
        }

        return new Scope(source,
                certificates,
                residuals,
                report,
                scope.sourceCount(),
                scope.hitCount(),
                scope.residualCount(),
                scope.checkMarkers());
    }

    private static void publish(final Scope scope, final Result result, final int workers, final int batchKeys) throws IOException {
        Files.createDirectories(scope.report().getParent());

        final Path temporary = Files.createTempFile(scope.report().getParent(), ".import-scope-", ".tmp");

        try {
            Files.write(temporary,
                    List.of(REPORT_FORMAT,
                            "route=" + ErikssonRoute.ID,
                            "status=complete",
                            "scope=persisted-after-two-import-inventory",
                            "wholeEndpointRegeneration=false",
                            "fullTd20ProofVerification=false",
                            "domain=" + SYMBOLS,
                            "budget=" + BUDGET,
                            "stateVersion=3",
                            "certificateVersion=" + CertificateStore.FORMAT_VERSION,
                            "source=" + scope.source(),
                            "certificates=" + scope.certificates(),
                            "residuals=" + scope.residuals(),
                            "sourceClasses=" + result.source(),
                            "budgetNineHits=" + result.hits(),
                            "residualClasses=" + result.residuals(),
                            "residualSetEqualsMissingClasses=true",
                            "markersChecked=" + result.markersChecked(),
                            "workers=" + workers,
                            "maximumBatchKeys=" + batchKeys,
                            "unprocessed=0"));
            Files.move(temporary, scope.report(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Scope parse(final String[] arguments, final Path root) {
        final Path run = root.resolve("local-runtime/import-audit");
        Path source = run.resolve("reconstruction/waves/after-two"), certificates = root.resolve("certificates"), residuals = run.resolve("td18-after-two-reuse-v1/residuals/after-two"), report = run.resolve("td18-after-two-reuse-v1/independent-import-scope-v1.txt");
        long sourceCount = EXPECTED_SOURCE, hitCount = EXPECTED_HITS, residualCount = EXPECTED_RESIDUALS;
        boolean markers = false;
        for (final String argument : arguments) {
            if (argument.startsWith("--source=")) {
                source = Path.of(argument.substring(9));
            } else if (argument.startsWith("--certificates=")) {
                certificates = Path.of(argument.substring(15));
            } else if (argument.startsWith("--residuals=")) {
                residuals = Path.of(argument.substring(12));
            } else if (argument.startsWith("--report=")) {
                report = Path.of(argument.substring(9));
            } else if (argument.startsWith("--expected-source=")) {
                sourceCount = Long.parseLong(argument.substring(18));
            } else if (argument.startsWith("--expected-hits=")) {
                hitCount = Long.parseLong(argument.substring(16));
            } else if (argument.startsWith("--expected-residuals=")) {
                residualCount = Long.parseLong(argument.substring(21));
            } else if (argument.equals("--check-markers")) {
                markers = true;
            } else {
                throw new IllegalArgumentException("Unknown imported-scope audit argument: " + argument);
            }
        }

        return new Scope(source, certificates, residuals, report, sourceCount, hitCount, residualCount, markers);
    }

    private static void usage() {
        System.out.println("ImportedTd18Verifier [--source=PATH] [--certificates=PATH] [--residuals=PATH] [--report=PATH]" + " [--expected-source=N] [--expected-hits=N] [--expected-residuals=N] [--check-markers]");
        System.out.println("This verifies only the supplied persisted after-two import scope. Full TD20 checking remains tools/td20 verify --phase=all.");
    }

    private static final class StateDatabase implements AutoCloseable {

        final Options options = new Options().setCreateIfMissing(false).setMaxOpenFiles(128);
        final RocksDB database;

        StateDatabase(final Path directory) {
            RocksDB opened = null;

            try {
                final List<byte[]> families = RocksDB.listColumnFamilies(options, directory.toString());
                if (families.size() != 1 || !Arrays.equals(families.getFirst(), RocksDB.DEFAULT_COLUMN_FAMILY)) {
                    throw new IllegalStateException("A v3 state inventory must have exactly one default column family");
                }

                opened = RocksDB.openReadOnly(options, directory.toString());
                database = opened;
            } catch (final RocksDBException | RuntimeException failure) {
                if (opened != null) {
                    opened.close();
                }

                options.close();
                throw new IllegalStateException("Cannot open read-only imported-scope states: " + directory, failure);
            }
        }

        List<byte[]> findAll(final List<byte[]> keys) {
            try {
                return database.multiGetAsList(keys);
            } catch (final RocksDBException failure) {
                throw new IllegalStateException("Cannot bulk-read imported-scope membership", failure);
            }
        }

        @Override
        public void close() {
            database.close();
            options.close();
        }
    }

    /**
     * Raw bulk reads use independently validated canonical keys; all witness replay belongs to checker workers.
     */
    private static final class Certificates implements AutoCloseable {

        private static final byte[] MARKERS = "obstructions".getBytes(StandardCharsets.UTF_8);
        final DBOptions options = new DBOptions().setMaxOpenFiles(256);
        final ColumnFamilyOptions witnesses = new ColumnFamilyOptions(), markers = new ColumnFamilyOptions();
        final ArrayList<ColumnFamilyHandle> handles = new ArrayList<>();
        final RocksDB database;

        Certificates(final Path directory) {
            RocksDB opened = null;

            try (val listing = new Options()) {
                final List<byte[]> families = RocksDB.listColumnFamilies(listing, directory.toString());
                if (families.size() != 2
                        || families.stream().noneMatch(key -> Arrays.equals(key, RocksDB.DEFAULT_COLUMN_FAMILY))
                        || families.stream().noneMatch(key -> Arrays.equals(key, MARKERS))) {
                    throw new IllegalStateException("Unsupported version-two TD20 certificate column families");
                }

                opened = RocksDB.openReadOnly(options,
                        directory.toString(),
                        List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, witnesses),
                                new ColumnFamilyDescriptor(MARKERS, markers)),
                        handles);
                database = opened;
                if (!Arrays.equals(database.get(handles.getFirst(), "td20-schema".getBytes(StandardCharsets.UTF_8)),
                        "eriksson-three-v1".getBytes(StandardCharsets.UTF_8))) {
                    throw new IllegalStateException("Missing or incompatible TD20 certificate schema marker");
                }
            } catch (final RocksDBException | RuntimeException failure) {
                handles.forEach(ColumnFamilyHandle::close);
                if (opened != null) {
                    opened.close();
                }

                witnesses.close();
                markers.close();
                options.close();
                throw new IllegalStateException("Cannot open read-only imported TD20 certificates: " + directory, failure);
            }
        }

        List<byte[]> findAll(final List<byte[]> keys) {
            try {
                return database.multiGetAsList(Collections.nCopies(keys.size(), handles.getFirst()), keys);
            } catch (final RocksDBException failure) {
                throw new IllegalStateException("Cannot bulk-read imported TD20 certificates", failure);
            }
        }

        boolean hasObstruction(final byte[] key) {
            try {
                final byte[] value = database.get(handles.get(1), key);
                if (value != null && value.length != 0) {
                    throw new IllegalStateException("An informational obstruction marker must have an empty value");
                }

                return value != null;
            } catch (final RocksDBException failure) {
                throw new IllegalStateException("Cannot read informational obstruction marker", failure);
            }
        }

        @Override
        public void close() {
            handles.forEach(ColumnFamilyHandle::close);
            database.close();
            witnesses.close();
            markers.close();
            options.close();
        }
    }
}
