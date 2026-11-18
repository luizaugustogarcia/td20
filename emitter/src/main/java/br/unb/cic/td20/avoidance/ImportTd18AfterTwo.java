package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
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
import org.rocksdb.*;

/** Replays TD18 results into TD20 and atomically publishes the exact budget-nine residual set. */
public final class ImportTd18AfterTwo {
    static { RocksDB.loadLibrary(); }
    private static final int SYMBOLS = ErikssonRoute.ROOT_SYMBOLS - 3;
    private static final int BUDGET = ErikssonRoute.ROOT_BUDGET - 2;
    public static final String REPORT = "td18-after-two-import-v1.txt";
    private ImportTd18AfterTwo() { }

    public record Settings(Path sourceDatabase, Path sourceWork, Path database, Path residualWork,
                           long expectedSource, long expectedImported, long expectedResidual) {
        public Settings {
            sourceDatabase = absolute(sourceDatabase); sourceWork = absolute(sourceWork);
            database = absolute(database); residualWork = absolute(residualWork);
            if (expectedSource < 0 || expectedImported < 0 || expectedResidual < 0
                    || Math.addExact(expectedImported, expectedResidual) != expectedSource)
                throw new IllegalArgumentException("Expected imported and residual counts must partition the source");
        }
        private static Path absolute(Path path) { return Objects.requireNonNull(path).toAbsolutePath().normalize(); }
        public static Settings parse(String[] args) {
            Set<String> allowed = Set.of("source-db", "source-work", "database", "residual-work",
                    "expected-source", "expected-imported", "expected-residual");
            var fields = new HashMap<String,String>();
            for (String arg : args) {
                int split = arg.indexOf('=');
                if (!arg.startsWith("--") || split < 3 || split == arg.length()-1)
                    throw new IllegalArgumentException("Expected --name=value import arguments");
                String key = arg.substring(2, split);
                if (!allowed.contains(key) || fields.put(key, arg.substring(split+1)) != null)
                    throw new IllegalArgumentException("Unknown or repeated import argument: "+key);
            }
            if (!fields.keySet().equals(allowed)) throw new IllegalArgumentException("All import paths and expected counts are required");
            return new Settings(Path.of(fields.get("source-db")), Path.of(fields.get("source-work")),
                    Path.of(fields.get("database")), Path.of(fields.get("residual-work")),
                    Long.parseLong(fields.get("expected-source")), Long.parseLong(fields.get("expected-imported")),
                    Long.parseLong(fields.get("expected-residual")));
        }
    }

    public record Result(long sourceStates, long imported, long missing, long longer, long residual) { }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("ImportTd18AfterTwo --source-db=TD18_DB --source-work=SEALED_FULL_AFTER_TWO_WORK"
                    + " --database=TD20_DB --residual-work=NEW_RESIDUAL_WORK"
                    + " --expected-source=COUNT --expected-imported=COUNT --expected-residual=COUNT");
            return;
        }
        System.out.println(run(Settings.parse(args)));
    }

    public static Result run(Settings settings) throws IOException { return run(settings, System.out::println); }

    public static Result run(Settings settings, Consumer<String> progress) throws IOException {
        Objects.requireNonNull(progress);
        requirePaths(settings);
        var sourceCheckpoint = ErikssonCheckpoint.read(settings.sourceWork());
        if (sourceCheckpoint.stage() != ErikssonCheckpoint.Stage.AFTER_TWO
                || sourceCheckpoint.mode() != ErikssonCheckpoint.Mode.FULL_SEARCH
                || sourceCheckpoint.afterTwo() != settings.expectedSource())
            throw new IllegalArgumentException("Import requires the complete original TD20 after-two checkpoint");
        Files.createDirectories(settings.residualWork().getParent());
        Path staging = Files.createTempDirectory(settings.residualWork().getParent(),
                "."+settings.residualWork().getFileName()+"-");
        int workers = Runtime.getRuntime().availableProcessors();
        var imported = new LongAdder(); var missing = new LongAdder(); var longer = new LongAdder();
        var processed = new AtomicLong(); var phase = new AtomicReference<>("replaying TD18 results");
        long residualCount;
        var result = new Result[1];
        try (var reporter = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("td18-import-progress").factory());
             var source = new VersionedDatabase(settings.sourceDatabase(), true);
             var inputs = DiskStateSet.openSealedForScan(settings.sourceWork().resolve("after-two"), settings.expectedSource());
             var writer = new AsyncCertificateWriter(settings.database(), 5);
             var residuals = new DiskStateSet(staging.resolve("after-two"))) {
            reporter.scheduleWithFixedDelay(() -> progress.accept("TD18 import: phase="+phase.get()
                    + " processed="+processed.get()+"/"+settings.expectedSource()+" imported="+imported.sum()
                    + " residual="+(missing.sum()+longer.sum())+" workers="+workers), 0, 10, TimeUnit.SECONDS);
            inputs.forEachConcurrently(workers, state -> {
                CyclicTargetPair pair = requireInput(state);
                var replayed = source.find(pair);
                if (replayed == null || replayed.required() > BUDGET) {
                    if (replayed == null) missing.increment(); else longer.increment();
                    writer.enqueueObstruction(pair, BUDGET); residuals.add(pair, BUDGET);
                } else {
                    if (replayed.result().kind() == SortingCertificate.Kind.COMPLETE)
                        writer.enqueue(pair, replayed.result().moves());
                    else writer.enqueueClosure(pair, replayed.result().moves(), BUDGET);
                    imported.increment();
                }
                processed.incrementAndGet();
            });
            if (processed.get() != settings.expectedSource() || imported.sum() != settings.expectedImported()
                    || missing.sum()+longer.sum() != settings.expectedResidual())
                throw new IllegalStateException("Replayed TD18 partition differs from the expected complete inventory");
            writer.flush();
            phase.set("sealing residuals");
            residualCount = residuals.seal();
            if (residualCount != settings.expectedResidual()) throw new IllegalStateException("Residual persistence lost or duplicated a class");
            phase.set("verifying copied TD20 results"); processed.set(0);
            try (var destination = new VersionedDatabase(settings.database(), false)) {
                var covered = new LongAdder(); var uncovered = new LongAdder();
                inputs.forEachConcurrently(workers, states -> destination.withinBudget(states),
                        state -> { covered.increment(); processed.incrementAndGet(); },
                        state -> { uncovered.increment(); processed.incrementAndGet(); });
                if (covered.sum() != settings.expectedImported() || uncovered.sum() != residualCount
                        || processed.get() != settings.expectedSource())
                    throw new IllegalStateException("Copied certificate inventory does not match the imported partition");
                phase.set("verifying residual obstruction markers");
                residuals.forEachConcurrently(workers, states -> destination.withinBudget(states),
                        state -> { throw new IllegalStateException("Published residual already has a budget-nine result"); },
                        state -> destination.requireObstruction(requireInput(state)));
            }
            result[0] = new Result(processed.get(), imported.sum(), missing.sum(), longer.sum(), residualCount);
        }
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, sourceCheckpoint.endpoints(),
                residualCount, -1, -1, ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(staging);
        Files.write(staging.resolve(REPORT), List.of("td20-td18-after-two-import-v1",
                "route="+ErikssonRoute.ID, "mode=RESIDUAL_EXPANSION", "rootSymbols="+ErikssonRoute.ROOT_SYMBOLS,
                "rootBudget="+ErikssonRoute.ROOT_BUDGET, "symbols="+SYMBOLS, "budget="+BUDGET,
                "informationalEndpoints="+sourceCheckpoint.endpoints(), "sourceStates="+result[0].sourceStates(),
                "importedCertificates="+result[0].imported(), "missingWitnesses="+result[0].missing(),
                "overBudgetWitnesses="+result[0].longer(), "activeResiduals="+result[0].residual(),
                "sourceDatabase="+settings.sourceDatabase(), "sourceWork="+settings.sourceWork(),
                "database="+settings.database(), "workers="+workers, "replay=source-and-destination",
                "status=complete"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(staging, settings.residualWork(), StandardCopyOption.ATOMIC_MOVE);
        progress.accept("TD18 import complete: "+result[0]+" residualWork="+settings.residualWork());
        return result[0];
    }

    private static CyclicTargetPair requireInput(DiskStateSet.State state) {
        if (state.maximumMoves() != BUDGET || state.pair().getBeta().size() != SYMBOLS)
            throw new IllegalArgumentException("Import requires fixed-point-free 18-symbol budget-nine states");
        var pair = state.pair().canonicalRepresentative();
        if (!Arrays.equals(CertificateCodec.keyFor(state.pair()), CertificateCodec.keyFor(pair)))
            throw new IllegalArgumentException("Import state key is not canonical");
        return pair;
    }

    private static void requirePaths(Settings settings) throws IOException {
        if (Files.exists(settings.residualWork())) throw new IllegalArgumentException("Residual publication requires a fresh directory");
        Path source = settings.sourceDatabase().toRealPath();
        Path work = settings.sourceWork().toRealPath();
        Path database = Files.exists(settings.database()) ? settings.database().toRealPath() : settings.database();
        Path residual = settings.residualWork();
        for (Path input : List.of(source, work)) for (Path output : List.of(database, residual))
            if (input.startsWith(output) || output.startsWith(input)) throw new IllegalArgumentException("Import input and output directories overlap");
        if (database.startsWith(residual) || residual.startsWith(database)) throw new IllegalArgumentException("Certificate and residual directories overlap");
    }

    private record Replayed(SortingCertificate result, int required) { }

    /** Read-only, strict version-two source or destination; obstruction markers never select work. */
    private static final class VersionedDatabase implements AutoCloseable {
        private static final byte[] OBSTRUCTIONS = bytes("obstructions");
        private final DBOptions options = new DBOptions().setCreateIfMissing(false).setMaxOpenFiles(128);
        private final ColumnFamilyOptions first = new ColumnFamilyOptions(), second = new ColumnFamilyOptions();
        private final ReadOptions reads = new ReadOptions().setFillCache(false);
        private final List<ColumnFamilyHandle> handles = new ArrayList<>();
        private final RocksDB database;
        private final int maximumBudget;
        private VersionedDatabase(Path directory, boolean td18) {
            RocksDB.loadLibrary();
            maximumBudget = td18 ? 10 : ErikssonRoute.ROOT_BUDGET;
            RocksDB opened = null;
            try (var listing = new Options()) {
                if (!Files.isRegularFile(directory.resolve("CURRENT"))) throw new IllegalArgumentException("Missing certificate database: "+directory);
                var families = RocksDB.listColumnFamilies(listing, directory.toString());
                if (families.size() != 2 || families.stream().noneMatch(x -> Arrays.equals(x, RocksDB.DEFAULT_COLUMN_FAMILY))
                        || families.stream().noneMatch(x -> Arrays.equals(x, OBSTRUCTIONS)))
                    throw new IllegalArgumentException("Expected version-two witness and obstruction column families");
                opened = RocksDB.openReadOnly(options, directory.toString(), List.of(
                        new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                        new ColumnFamilyDescriptor(OBSTRUCTIONS, second)), handles);
                if (!Arrays.equals(opened.get(handles.getFirst(), bytes(td18 ? "td18-schema" : "td20-schema")),
                        bytes(td18 ? "joint-selection-v3" : "eriksson-three-v1")))
                    throw new IllegalArgumentException("Missing or incompatible certificate database schema");
                database = opened;
            } catch (RocksDBException | RuntimeException error) {
                handles.forEach(ColumnFamilyHandle::close);
                if (opened != null) opened.close();
                reads.close(); first.close(); second.close(); options.close();
                throw new IllegalStateException("Cannot open versioned certificate database "+directory, error);
            }
        }
        private Replayed find(CyclicTargetPair pair) {
            try { return replay(pair, database.get(handles.getFirst(), reads, CertificateCodec.keyFor(pair))); }
            catch (RocksDBException error) { throw new IllegalStateException("Cannot read certificate", error); }
        }
        private Replayed replay(CyclicTargetPair pair, byte[] value) {
            if (value == null) return null;
            var result = CertificateCodec.decodeResult(value);
            int required = CertificateValidator.requiredBudget(pair, result);
            if (result.moves().size() > maximumBudget || required > maximumBudget)
                throw new IllegalArgumentException("Certificate exceeds its source project budget");
            return new Replayed(result, required);
        }
        private boolean[] withinBudget(List<DiskStateSet.State> states) {
            var pairs = states.stream().map(ImportTd18AfterTwo::requireInput).toList();
            try {
                var values = database.multiGetAsList(Collections.nCopies(pairs.size(), handles.getFirst()),
                        pairs.stream().map(CertificateCodec::keyFor).toList());
                if (values.size() != pairs.size()) throw new IllegalStateException("Incomplete certificate bulk lookup");
                var hits = new boolean[pairs.size()];
                for (int i = 0; i < pairs.size(); i++) {
                    var replayed = replay(pairs.get(i), values.get(i));
                    hits[i] = replayed != null && replayed.required() <= BUDGET;
                }
                return hits;
            } catch (RocksDBException error) { throw new IllegalStateException("Cannot verify copied certificates", error); }
        }
        private void requireObstruction(CyclicTargetPair pair) {
            try {
                byte[] value = database.get(handles.get(1), reads, CertificateCodec.keyFor(pair));
                if (value == null || value.length != 0) throw new IllegalStateException("Missing or malformed persisted residual obstruction");
            } catch (RocksDBException error) { throw new IllegalStateException("Cannot verify obstruction marker", error); }
        }
        private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
        @Override public void close() { handles.forEach(ColumnFamilyHandle::close); database.close(); reads.close(); first.close(); second.close(); options.close(); }
    }
}
