package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import br.unb.cic.td20.runtime.CheckerParallelism;
import br.unb.cic.td20.runtime.RocksDbMemoryBudget;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/** Read-only intrinsic witness and unresolved-marker store. */
public final class CertificateStore implements AutoCloseable {
    public static final int FORMAT_VERSION = 2;
    private static final byte[] OBSTRUCTIONS = "obstructions".getBytes(StandardCharsets.UTF_8);
    static { RocksDB.loadLibrary(); }
    private final DBOptions options = new DBOptions().setMaxOpenFiles(CheckerParallelism.maxOpenFiles());
    private final RocksDbMemoryBudget.Lease memory = RocksDbMemoryBudget.acquire();
    private final LRUCache cache = memory.cache();
    private final ColumnFamilyOptions witnessOptions = familyOptions();
    private final ColumnFamilyOptions obstructionOptions = familyOptions();
    private final ReadOptions rootReads = memory.reads("td20.certificateRootFillCache", false);
    private final ReadOptions endpointReads = memory.reads("td20.certificateEndpointFillCache", false);
    private final ReadOptions reconstructionReads = memory.reads("td20.certificateReconstructionFillCache", true);
    private volatile ReadOptions reads = rootReads;
    private volatile String phase = "root";
    private final ArrayList<ColumnFamilyHandle> handles = new ArrayList<>();
    private final int workers = CheckerParallelism.workers();
    private final int readers = CheckerParallelism.readWorkers();
    private final Semaphore nativeReads = new Semaphore(readers);
    private final ExecutorService replayWorkers = pool(workers, "certificate-replay-");
    private final ExecutorService readWorkers = pool(readers, "certificate-read-");
    private final AtomicInteger activeReads = new AtomicInteger();
    private final LongAdder readKeys = new LongAdder();
    private boolean closed;
    private final RocksDB database;

    private ColumnFamilyOptions familyOptions() {
        return new ColumnFamilyOptions().setTableFormatConfig(memory.tables());
    }
    public void readPhase(String phase) {
        reads = switch (phase) {
            case "root" -> rootReads;
            case "endpoint" -> endpointReads;
            case "reconstruction" -> reconstructionReads;
            default -> throw new IllegalArgumentException("Unknown certificate read phase: " + phase);
        };
        this.phase = phase;
        System.out.printf("Certificate reads: phase=%s fillDataCache=%s readWorkers=%d lookupBatchKeys=%d %s%n",
                phase, reads.fillCache(), readers, CheckerParallelism.lookupBatchSize(), RocksDbMemoryBudget.progress());
    }

    private static ExecutorService pool(int count, String name) {
        return new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(64, Math.multiplyExact(count, 4))),
                Thread.ofPlatform().daemon(true).name(name, 0).factory(),
                (task, executor) -> {
                    if (executor.isShutdown()) throw new RejectedExecutionException("Certificate worker pool is closed");
                    task.run();
                });
    }

    public CertificateStore(final Path directory) {
        RocksDB opened = null;
        try (final var listing = new Options()) {
            final var families = RocksDB.listColumnFamilies(listing, directory.toString());
            if (families.size() != 2 || families.stream().noneMatch(x -> Arrays.equals(x, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(x -> Arrays.equals(x, OBSTRUCTIONS)))
                throw new IllegalStateException("Unsupported certificate format: expected version-2 two-family schema");
            opened = RocksDB.openReadOnly(options, directory.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                    new ColumnFamilyDescriptor(OBSTRUCTIONS, obstructionOptions)), handles);
            database = opened;
            final var marker = database.get(handles.get(0), "td20-schema".getBytes(StandardCharsets.UTF_8));
            if (!Arrays.equals(marker, "eriksson-three-v1".getBytes(StandardCharsets.UTF_8)))
                throw new IllegalStateException("Missing or unsupported certificate format marker");
        } catch (final RocksDBException | RuntimeException failure) {
            shutdown(readWorkers); shutdown(replayWorkers);
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            closeOptions();
            throw new IllegalStateException("Cannot open certificate database " + directory, failure);
        }
    }
    public Optional<List<Cycle>> find(final CyclicTargetPair pair, final int maximumMoves) {
        if (maximumMoves < 0) throw new IllegalArgumentException("Negative move budget");
        final byte[] key = CertificateCodec.canonicalKeyFor(pair);
        final byte[] value = get(handles.get(0), key);
        if (value == null) return Optional.empty();
        final var result = CertificateCodec.decodeResult(value);
        // Replay stored data even when the derived bound exceeds the requested budget.
        final var canonical = pairForReplay(pair, key);
        final int required = CertificateValidator.validatedRequiredBudget(
                canonical, result, obligationBudget(canonical.getBeta().size()));
        return required <= maximumMoves ? Optional.of(result.moves()) : Optional.empty();
    }
    /** Position-preserving RocksDB multiGet followed by parallel independent replay of every hit. */
    public boolean[] findAll(final List<CyclicTargetPair> pairs, final int maximumMoves) {
        if (maximumMoves < 0) throw new IllegalArgumentException("Negative move budget");
        if (pairs.isEmpty()) return new boolean[0];
        final var keys = new byte[pairs.size()][];
        parallelRanges(pairs.size(), (first, last) -> {
            for (int i = first; i < last; i++) {
                keys[i] = CertificateCodec.canonicalKeyFor(pairs.get(i));
            }
        });
        final var encoded = new byte[pairs.size()][];
        parallelReadRanges(pairs.size(), (first, last) -> {
            final var keySlice = Arrays.asList(keys).subList(first, last);
            final List<byte[]> values = readNative(keySlice);
            if (values.size() != keySlice.size())
                throw new IllegalStateException("Incomplete certificate multiGet response");
            for (int i = first; i < last; i++) encoded[i] = values.get(i - first);
        });
        final var present = new boolean[pairs.size()];
        parallelRanges(pairs.size(), (first, last) -> {
            for (int i = first; i < last; i++) {
                final byte[] value = encoded[i];
                if (value == null) continue;
                final var result = CertificateCodec.decodeResult(value);
                final var pair = pairForReplay(pairs.get(i), keys[i]);
                final int required = CertificateValidator.validatedRequiredBudget(
                        pair, result, obligationBudget(pair.getBeta().size()));
                present[i] = required <= maximumMoves;
            }
        });
        return present;
    }
    /** Full result positions are preserved, including duplicates, misses and over-budget witnesses. */
    public boolean[] findAllPrimitive(final List<PrimitivePair> pairs, final int maximumMoves) {
        var future = findAllPrimitiveAsync(pairs, maximumMoves);
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    var result = future.get();
                    if (interrupted) throw new IllegalStateException("Certificate verification interrupted");
                    return result;
                }
                catch (InterruptedException error) { interrupted = true; }
            }
        } catch (ExecutionException error) { throw propagate(error.getCause()); }
        finally {
            // Native work must finish before the enclosing store can close its RocksDB handles.
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record Prepared(List<PrimitivePair> pairs, byte[][] keys) { }
    private record Loaded(Prepared prepared, List<byte[]> values) { }

    /** CPU encoding, native reads and independent replay overlap on separate bounded worker pools. */
    public CompletableFuture<boolean[]> findAllPrimitiveAsync(List<PrimitivePair> pairs, int maximumMoves) {
        if (maximumMoves < 0) throw new IllegalArgumentException("Negative move budget");
        if (pairs.isEmpty()) return CompletableFuture.completedFuture(new boolean[0]);
        var owned = List.copyOf(pairs);
        int batchSize = CheckerParallelism.lookupBatchSize();
        if (owned.size() > batchSize) {
            var futures = new ArrayList<CompletableFuture<boolean[]>>();
            for (int first = 0; first < owned.size(); first += batchSize)
                futures.add(findAllPrimitiveAsync(owned.subList(first, Math.min(owned.size(), first + batchSize)), maximumMoves));
            // Wait for every slice, including failed ones, before the parent result completes.
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                var result = new boolean[owned.size()]; int offset = 0;
                for (var future : futures) { var part = future.join(); System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
                return result;
            });
        }
        return CompletableFuture.supplyAsync(() -> {
            var keys = new byte[owned.size()][];
            for (int i = 0; i < keys.length; i++) keys[i] = owned.get(i).canonicalKey();
            return new Prepared(owned, keys);
        }, replayWorkers).thenApplyAsync(prepared ->
                new Loaded(prepared, readNative(Arrays.asList(prepared.keys()))), readWorkers)
            .thenApplyAsync(loaded -> {
                var prepared = loaded.prepared(); var present = new boolean[prepared.pairs().size()];
                if (loaded.values().size() != present.length) throw new IllegalStateException("Incomplete certificate multiGet response");
                for (int i = 0; i < present.length; i++) {
                    var value = loaded.values().get(i); if (value == null) continue;
                    var pair = pairForReplay(prepared.pairs().get(i), prepared.keys()[i]);
                    int required = CertificateValidator.validatedRequiredBudget(pair,
                            CertificateCodec.decodePrimitiveResult(value), obligationBudget(pair.size()));
                    present[i] = required <= maximumMoves;
                }
                return present;
            }, replayWorkers);
    }

    private List<byte[]> readNative(List<byte[]> keys) {
        boolean acquired = false;
        try {
            nativeReads.acquire(); acquired = true; activeReads.incrementAndGet();
            var values = database.multiGetAsList(reads, Collections.nCopies(keys.size(), handles.get(0)), keys);
            if (values.size() != keys.size()) throw new IllegalStateException("Incomplete certificate multiGet response");
            readKeys.add(keys.size());
            return values;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Certificate read interrupted", error);
        } catch (RocksDBException error) { throw new IllegalStateException("Cannot bulk-read certificates", error); }
        finally { if (acquired) { activeReads.decrementAndGet(); nativeReads.release(); } }
    }

    public String readProgress() {
        synchronized (cache) {
            return "readPhase=" + phase + " readWorkers=" + readers + " activeReads=" + activeReads.get() + " readKeys=" + readKeys.sum()
                    + (closed ? "" : " " + RocksDbMemoryBudget.progress());
        }
    }

    private static RuntimeException propagate(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Certificate verification failed", error);
    }
    public List<Optional<List<Cycle>>> find(final CyclicTargetPair[] pairs, final int maximumMoves) {
        final var result = new ArrayList<Optional<List<Cycle>>>(pairs.length);
        for (final var pair : pairs) result.add(find(pair, maximumMoves));
        return List.copyOf(result);
    }
    public List<Cycle> require(final CyclicTargetPair pair, final int maximumMoves) {
        return find(pair, maximumMoves).orElseThrow(() -> new IllegalStateException(
                "Missing sorting certificate: classKey=" + pair.extendedToricClassKey() + ", maximumMoves=" + maximumMoves));
    }
    public boolean hasObstruction(final CyclicTargetPair pair) {
        final byte[] value = get(handles.get(1), CertificateCodec.canonicalKeyFor(pair));
        if (value != null && value.length != 0) throw new IllegalStateException("An obstruction must have an empty value");
        return value != null;
    }
    public void forEachObstruction(final Consumer<byte[]> consumer) {
        try (final var iterator = database.newIterator(handles.get(1), reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                if (iterator.value().length != 0) throw new IllegalStateException("An obstruction must have an empty value");
                consumer.accept(iterator.key());
            }
            iterator.status();
        } catch (final RocksDBException failure) { throw new IllegalStateException("Cannot read obstruction keys", failure); }
    }
    private byte[] get(final ColumnFamilyHandle family, final byte[] key) {
        try { return database.get(family, reads, key); }
        catch (final RocksDBException failure) { throw new IllegalStateException("Cannot read certificate", failure); }
    }
    private static PrimitivePair pairForReplay(PrimitivePair pair, byte[] canonicalKey) {
        return CertificateCodec.matchesNormalizedPair(canonicalKey,pair)
                ? pair : PrimitivePair.fromCanonicalKey(canonicalKey,pair.size());
    }
    private static CyclicTargetPair pairForReplay(final CyclicTargetPair pair, final byte[] canonicalKey) {
        // A normalized persisted pair may already equal its computed orbit minimum.
        // Compare its actual images before reusing it; untrusted keys never confer this fact.
        return CertificateCodec.matchesNormalizedPair(canonicalKey, pair)
                ? pair : pair.canonicalRepresentative();
    }
    private static int obligationBudget(final int domain) {
        if (domain<1 || domain>br.unb.cic.td20.joint.KnownDiameters.ROOT_SYMBOLS)
            throw new IllegalArgumentException("Invalid certificate domain");
        return br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET;
    }
    private void parallelRanges(int size, RangeAction action) {
        int taskCount = Math.min(workers, size);
        ranges(replayWorkers, size, Math.ceilDiv(size, taskCount), action);
    }
    private void parallelReadRanges(int size, RangeAction action) {
        ranges(readWorkers, size, CheckerParallelism.lookupBatchSize(), action);
    }
    private static void ranges(ExecutorService pool, int size, int chunk, RangeAction action) {
        var futures = new ArrayList<Future<?>>(); Throwable problem = null; boolean interrupted = false;
        try {
            for (int first = 0; first < size; first += chunk) {
                int from = first, to = Math.min(size, first + chunk);
                futures.add(pool.submit(() -> action.run(from, to)));
            }
        } catch (Throwable error) { problem = error; }
        for (var future : futures) {
            for (;;) {
                try { future.get(); break; }
                catch (InterruptedException error) { interrupted = true; if (problem == null) problem = error; }
                catch (ExecutionException error) { if (problem == null) problem = error.getCause(); break; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (problem != null) throw propagate(problem);
    }
    @FunctionalInterface private interface RangeAction { void run(int first, int last); }
    private static void shutdown(ExecutorService pool) {
        pool.shutdown(); boolean interrupted = false;
        for (;;) {
            try { if (pool.awaitTermination(1, TimeUnit.SECONDS)) break; }
            catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Override public void close() {
        shutdown(readWorkers); shutdown(replayWorkers);
        handles.forEach(ColumnFamilyHandle::close); database.close(); closeOptions();
    }
    private void closeOptions() {
        synchronized (cache) {
            closed = true;
            rootReads.close(); endpointReads.close(); reconstructionReads.close();
            witnessOptions.close(); obstructionOptions.close(); options.close(); memory.close();
        }
    }
}
