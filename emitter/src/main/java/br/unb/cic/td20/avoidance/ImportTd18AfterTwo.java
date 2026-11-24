package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.PrimitiveCertificate;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.PrimitiveCanonicalizer;
import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Replays TD18 results into TD20 and atomically publishes the exact budget-nine residual set.
 */
public final class ImportTd18AfterTwo {

    static {
        RocksDB.loadLibrary();
    }

    private static final int SYMBOLS = ErikssonRoute.ROOT_SYMBOLS - 3;
    private static final int BUDGET = ErikssonRoute.ROOT_BUDGET - 2;
    public static final String REPORT = "td18-after-two-import-v1.txt";

    private ImportTd18AfterTwo() {
    }

    public record Settings(Path sourceDatabase, Path sourceWork, Path database, Path residualWork, long expectedSource, long expectedImported, long expectedResidual) {

        public Settings {
            sourceDatabase = absolute(sourceDatabase);
            sourceWork = absolute(sourceWork);
            database = absolute(database);
            residualWork = absolute(residualWork);
            if (expectedSource < 0
                    || expectedImported < 0
                    || expectedResidual < 0
                    || Math.addExact(expectedImported, expectedResidual) != expectedSource) {
                throw new IllegalArgumentException("Expected imported and residual counts must partition the source");
            }
        }

        private static Path absolute(final Path path) {
            return Objects.requireNonNull(path).toAbsolutePath().normalize();
        }

        public static Settings parse(final String[] args) {
            final Set<String> allowed = Set.of("source-db",
                    "source-work",
                    "database",
                    "residual-work",
                    "expected-source",
                    "expected-imported",
                    "expected-residual");
            val fields = new HashMap<String, String>();
            for (final String arg : args) {
                final int split = arg.indexOf('=');
                if (!arg.startsWith("--") || split < 3 || split == arg.length() - 1) {
                    throw new IllegalArgumentException("Expected --name=value import arguments");
                }

                final String key = arg.substring(2, split);
                if (!allowed.contains(key) || fields.put(key, arg.substring(split + 1)) != null) {
                    throw new IllegalArgumentException("Unknown or repeated import argument: " + key);
                }
            }
            if (!fields.keySet().equals(allowed)) {
                throw new IllegalArgumentException("All import paths and expected counts are required");
            }

            return new Settings(Path.of(fields.get("source-db")),
                    Path.of(fields.get("source-work")),
                    Path.of(fields.get("database")),
                    Path.of(fields.get("residual-work")),
                    Long.parseLong(fields.get("expected-source")),
                    Long.parseLong(fields.get("expected-imported")),
                    Long.parseLong(fields.get("expected-residual")));
        }
    }

    public record Result(long sourceStates, long imported, long missing, long longer, long residual) {
    }

    public static void main(final String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("ImportTd18AfterTwo --source-db=TD18_DB --source-work=SEALED_FULL_AFTER_TWO_WORK" + " --database=TD20_DB --residual-work=NEW_RESIDUAL_WORK" + " --expected-source=COUNT --expected-imported=COUNT --expected-residual=COUNT");
            return;
        }

        System.out.println(run(Settings.parse(args)));
    }

    public static Result run(final Settings settings) throws IOException {
        return run(settings, System.out::println);
    }

    public static Result run(final Settings settings, final Consumer<String> progress) throws IOException {
        Objects.requireNonNull(progress);
        requirePaths(settings);

        val sourceCheckpoint = ErikssonCheckpoint.read(settings.sourceWork());
        if (sourceCheckpoint.stage() != ErikssonCheckpoint.Stage.AFTER_TWO
                || sourceCheckpoint.mode() != ErikssonCheckpoint.Mode.FULL_SEARCH
                || sourceCheckpoint.afterTwo() != settings.expectedSource()) {
            throw new IllegalArgumentException("Import requires the complete original TD20 after-two checkpoint");
        }

        Files.createDirectories(settings.residualWork().getParent());

        final Path staging = Files.createTempDirectory(settings.residualWork().getParent(),
                "." + settings.residualWork().getFileName() + "-");
        final int workers = Runtime.getRuntime().availableProcessors();
        val imported = new LongAdder();
        val missing = new LongAdder();
        val longer = new LongAdder();
        val processed = new AtomicLong();
        val phase = new AtomicReference<>("replaying TD18 results");
        long residualCount;
        val result = new Result[1];

        try (val reporter = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("td18-import-progress").factory());
            val source = new VersionedDatabase(settings.sourceDatabase(), true);
            val inputs = DiskStateSet.openSealedForScan(settings.sourceWork().resolve("after-two"), settings.expectedSource());
            val writer = new AsyncCertificateWriter(settings.database(), 5);
            val residuals = new DiskStateSet(staging.resolve("after-two"))) {
            reporter.scheduleWithFixedDelay(() -> progress.accept("TD18 import: phase=" + phase.get() + " processed=" + processed.get() + "/" + settings.expectedSource() + " imported=" + imported.sum() + " residual=" + (missing.sum() + longer.sum()) + " workers=" + workers),
                    0,
                    10,
                    TimeUnit.SECONDS);
            inputs.forEachConcurrently(workers,
                    states -> {
                        val pairs = states.stream().map(ImportTd18AfterTwo::requireInput).toList();
                        val replayed = source.findAll(pairs);
                        val hits = new boolean[pairs.size()];
                        for (int i = 0; i < pairs.size(); i++) {
                            val pair = pairs.get(i);
                            val record = replayed[i];
                            if (record == null || record.required() > BUDGET) {
                                if (record == null) {
                                    missing.increment();
                                } else {
                                    longer.increment();
                                }

                                writer.enqueueObstruction(pair, BUDGET);
                                residuals.add(pair, BUDGET);
                            } else {
                                writer.enqueue(pair, record.result(), BUDGET);
                                hits[i] = true;
                            }
                        }

                        return hits;
                    },
                    state -> {
                        imported.increment();
                        processed.incrementAndGet();
                    },
                    state -> processed.incrementAndGet());
            if (processed.get() != settings.expectedSource()
                    || imported.sum() != settings.expectedImported()
                    || missing.sum() + longer.sum() != settings.expectedResidual()) {
                throw new IllegalStateException("Replayed TD18 partition differs from the expected complete inventory");
            }

            writer.flush();
            phase.set("sealing residuals");
            residualCount = residuals.seal();
            if (residualCount != settings.expectedResidual()) {
                throw new IllegalStateException("Residual persistence lost or duplicated a class");
            }

            phase.set("verifying copied TD20 results");
            processed.set(0);

            try (val destination = new VersionedDatabase(settings.database(), false)) {
                val covered = new LongAdder();
                val uncovered = new LongAdder();
                inputs.forEachConcurrently(workers,
                        states -> destination.withinBudget(states),
                        state -> {
                            covered.increment();
                            processed.incrementAndGet();
                        },
                        state -> {
                            uncovered.increment();
                            processed.incrementAndGet();
                        });
                if (covered.sum() != settings.expectedImported()
                        || uncovered.sum() != residualCount
                        || processed.get() != settings.expectedSource()) {
                    throw new IllegalStateException("Copied certificate inventory does not match the imported partition");
                }

                phase.set("verifying residual obstruction markers");
                residuals.forEachConcurrently(workers,
                        states -> destination.residualsWithinBudget(states),
                        state -> {
                            throw new IllegalStateException("Published residual already has a budget-nine result");
                        },
                        state -> {
                        });
            }

            result[0] = new Result(processed.get(), imported.sum(), missing.sum(), longer.sum(), residualCount);
        }

        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,
                sourceCheckpoint.endpoints(),
                residualCount,
                -1,
                -1,
                ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(staging);
        Files.write(staging.resolve(REPORT),
                List.of("td20-td18-after-two-import-v1",
                        "route=" + ErikssonRoute.ID,
                        "mode=RESIDUAL_EXPANSION",
                        "rootSymbols=" + ErikssonRoute.ROOT_SYMBOLS,
                        "rootBudget=" + ErikssonRoute.ROOT_BUDGET,
                        "symbols=" + SYMBOLS,
                        "budget=" + BUDGET,
                        "informationalEndpoints=" + sourceCheckpoint.endpoints(),
                        "sourceStates=" + result[0].sourceStates(),
                        "importedCertificates=" + result[0].imported(),
                        "missingWitnesses=" + result[0].missing(),
                        "overBudgetWitnesses=" + result[0].longer(),
                        "activeResiduals=" + result[0].residual(),
                        "sourceDatabase=" + settings.sourceDatabase(),
                        "sourceWork=" + settings.sourceWork(),
                        "database=" + settings.database(),
                        "workers=" + workers,
                        "replay=source-and-destination",
                        "status=complete"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.move(staging, settings.residualWork(), StandardCopyOption.ATOMIC_MOVE);
        progress.accept("TD18 import complete: " + result[0] + " residualWork=" + settings.residualWork());

        return result[0];
    }

    private static PrimitivePair requireInput(final DiskStateSet.State state) {
        val pair = state.primitivePair();
        if (state.maximumMoves() != BUDGET || pair.size() != SYMBOLS) {
            throw new IllegalArgumentException("Import requires fixed-point-free 18-symbol budget-nine states");
        }

        val work = INPUT_CANONICALIZER.get();
        pair.copyOmega(work.omega);
        pair.copyBeta(work.beta);
        if (!Arrays.equals(CertificateCodec.keyFor(pair), work.canonicalizer.keyForKnownPair(work.omega, work.beta, pair.size()))) {
            throw new IllegalArgumentException("Import state key is not canonical");
        }

        return pair;
    }

    private static final class InputWorkspace {

        final PrimitiveCanonicalizer canonicalizer = new PrimitiveCanonicalizer();
        final int[] omega = new int[ErikssonRoute.ROOT_SYMBOLS], beta = new int[ErikssonRoute.ROOT_SYMBOLS];
    }

    private static final ThreadLocal<InputWorkspace> INPUT_CANONICALIZER = ThreadLocal.withInitial(InputWorkspace::new);

    private static void requirePaths(final Settings settings) throws IOException {
        if (Files.exists(settings.residualWork())) {
            throw new IllegalArgumentException("Residual publication requires a fresh directory");
        }

        final Path source = settings.sourceDatabase().toRealPath();
        final Path work = settings.sourceWork().toRealPath();
        final Path database = Files.exists(settings.database()) ? settings.database().toRealPath() : settings.database();
        final Path residual = settings.residualWork();
        for (final Path input : List.of(source, work)) {
            for (final Path output : List.of(database, residual)) {
                if (input.startsWith(output) || output.startsWith(input)) {
                    throw new IllegalArgumentException("Import input and output directories overlap");
                }
            }
        }
        if (database.startsWith(residual) || residual.startsWith(database)) {
            throw new IllegalArgumentException("Certificate and residual directories overlap");
        }
    }

    private record Replayed(PrimitiveCertificate result, int required) {
    }

    /**
     * Read-only, strict version-two source or destination; obstruction markers never select work.
     */
    private static final class VersionedDatabase implements AutoCloseable {

        private static final byte[] OBSTRUCTIONS = bytes("obstructions");
        private final DBOptions options = new DBOptions().setCreateIfMissing(false).setMaxOpenFiles(128);
        private final ColumnFamilyOptions first = new ColumnFamilyOptions(), second = new ColumnFamilyOptions();
        private final ReadOptions reads = new ReadOptions().setFillCache(false);
        private final List<ColumnFamilyHandle> handles = new ArrayList<>();
        private final RocksDB database;
        private final int maximumBudget;

        private VersionedDatabase(final Path directory, final boolean td18) {
            RocksDB.loadLibrary();
            maximumBudget = td18 ? 10 : ErikssonRoute.ROOT_BUDGET;

            RocksDB opened = null;

            try (val listing = new Options()) {
                if (!Files.isRegularFile(directory.resolve("CURRENT"))) {
                    throw new IllegalArgumentException("Missing certificate database: " + directory);
                }

                val families = RocksDB.listColumnFamilies(listing, directory.toString());
                if (families.size() != 2
                        || families.stream().noneMatch(x -> Arrays.equals(x, RocksDB.DEFAULT_COLUMN_FAMILY))
                        || families.stream().noneMatch(x -> Arrays.equals(x, OBSTRUCTIONS))) {
                    throw new IllegalArgumentException("Expected version-two witness and obstruction column families");
                }

                opened = RocksDB.openReadOnly(options,
                        directory.toString(),
                        List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                                new ColumnFamilyDescriptor(OBSTRUCTIONS, second)),
                        handles);
                if (!Arrays.equals(opened.get(handles.getFirst(), bytes(td18 ? "td18-schema" : "td20-schema")),
                        bytes(td18 ? "joint-selection-v3" : "eriksson-three-v1"))) {
                    throw new IllegalArgumentException("Missing or incompatible certificate database schema");
                }

                database = opened;
            } catch (final RocksDBException | RuntimeException error) {
                handles.forEach(ColumnFamilyHandle::close);
                if (opened != null) {
                    opened.close();
                }

                reads.close();
                first.close();
                second.close();
                options.close();
                throw new IllegalStateException("Cannot open versioned certificate database " + directory, error);
            }
        }

        private Replayed[] findAll(final List<PrimitivePair> pairs) {
            if (pairs.isEmpty()) {
                return new Replayed[0];
            }

            try {
                val values = database.multiGetAsList(Collections.nCopies(pairs.size(), handles.getFirst()),
                        pairs.stream().map(CertificateCodec::keyFor).toList());
                if (values.size() != pairs.size()) {
                    throw new IllegalStateException("Incomplete certificate bulk lookup");
                }

                val results = new Replayed[pairs.size()];
                for (int i = 0; i < pairs.size(); i++) {
                    results[i] = replay(pairs.get(i), values.get(i));
                }

                return results;
            } catch (final RocksDBException error) {
                throw new IllegalStateException("Cannot bulk-read certificates", error);
            }
        }

        private Replayed replay(final PrimitivePair pair, final byte[] value) {
            if (value == null) {
                return null;
            }

            val result = CertificateCodec.decodePrimitiveResult(value);
            final int required = CertificateValidator.requiredBudget(pair, result);
            if (result.moveCount() > maximumBudget || required > maximumBudget) {
                throw new IllegalArgumentException("Certificate exceeds its source project budget");
            }

            return new Replayed(result, required);
        }

        private boolean[] withinBudget(final List<DiskStateSet.State> states) {
            val pairs = states.stream().map(ImportTd18AfterTwo::requireInput).toList();
            val replayed = findAll(pairs);
            val hits = new boolean[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                hits[i] = replayed[i] != null && replayed[i].required() <= BUDGET;
            }

            return hits;
        }

        private boolean[] residualsWithinBudget(final List<DiskStateSet.State> states) {
            val pairs = states.stream().map(ImportTd18AfterTwo::requireInput).toList();
            val replayed = findAll(pairs);

            try {
                val markers = database.multiGetAsList(Collections.nCopies(pairs.size(), handles.get(1)),
                        pairs.stream().map(CertificateCodec::keyFor).toList());
                if (markers.size() != pairs.size()) {
                    throw new IllegalStateException("Incomplete obstruction bulk lookup");
                }

                val hits = new boolean[pairs.size()];
                for (int i = 0; i < pairs.size(); i++) {
                    hits[i] = replayed[i] != null && replayed[i].required() <= BUDGET;
                    if (!hits[i] && (markers.get(i) == null || markers.get(i).length != 0)) {
                        throw new IllegalStateException("Missing or malformed persisted residual obstruction");
                    }
                }

                return hits;
            } catch (final RocksDBException error) {
                throw new IllegalStateException("Cannot bulk-verify obstruction markers", error);
            }
        }

        private static byte[] bytes(final String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            handles.forEach(ColumnFamilyHandle::close);
            database.close();
            reads.close();
            first.close();
            second.close();
            options.close();
        }
    }
}
