package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.Objects;
import br.unb.cic.td20.runtime.CheckerParallelism;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/** Read-only TD(20) joint-2 witness/obstruction schema, with independent replay. */
public final class CertificateStore implements AutoCloseable {
    public static final int FORMAT_VERSION = 2;
    private static final int MAX_RESULT_MOVES = 11;
    public static final String SCHEMA_KEY = "td20-schema";
    public static final String SCHEMA_VALUE = "joint2-selection-v2";
    private static final String PREVIOUS_SCHEMA_VALUE = "joint2-selection-v1";
    private static final byte[] OBSTRUCTIONS = "obstructions".getBytes(StandardCharsets.UTF_8);
    static { RocksDB.loadLibrary(); }
    private final DBOptions options = new DBOptions().setMaxOpenFiles(256);
    private final ColumnFamilyOptions witnessOptions = new ColumnFamilyOptions();
    private final ColumnFamilyOptions obstructionOptions = new ColumnFamilyOptions();
    private final ReadOptions reads = new ReadOptions().setFillCache(false);
    private final ArrayList<ColumnFamilyHandle> handles = new ArrayList<>();
    private final int workers = CheckerParallelism.workers();
    private final ExecutorService replayWorkers = Executors.newFixedThreadPool(workers,
            Thread.ofPlatform().daemon(true).name("certificate-replay-", 0).factory());
    private final RocksDB database;
    private final BiConsumer<byte[], byte[]> retained;

    public CertificateStore(final Path directory) {
        this(directory, null, false);
    }
    /** Replays compatible stored results for a new certificate; never imports route counts. */
    public static CertificateStore forPruning(final Path directory, final BiConsumer<byte[], byte[]> retained) {
        return new CertificateStore(directory, Objects.requireNonNull(retained), true);
    }
    /** Strict current-schema reader that records the classes used by an independent audit. */
    public static CertificateStore forAudit(final Path directory, final BiConsumer<byte[], byte[]> retained) {
        return new CertificateStore(directory, Objects.requireNonNull(retained), false);
    }
    private CertificateStore(final Path directory, final BiConsumer<byte[], byte[]> retained,
                             final boolean acceptPreviousResults) {
        this.retained = retained;
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
            final var marker = database.get(handles.get(0), SCHEMA_KEY.getBytes(StandardCharsets.UTF_8));
            if (!Arrays.equals(marker, SCHEMA_VALUE.getBytes(StandardCharsets.UTF_8))
                    && !(acceptPreviousResults && Arrays.equals(marker, PREVIOUS_SCHEMA_VALUE.getBytes(StandardCharsets.UTF_8))))
                throw new IllegalStateException("Missing or unsupported certificate format marker");
        } catch (final RocksDBException | RuntimeException failure) {
            replayWorkers.shutdownNow();
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            closeOptions();
            throw new IllegalStateException("Cannot open certificate database " + directory, failure);
        }
    }
    public Optional<List<Cycle>> find(final CyclicTargetPair pair, final int maximumMoves) {
        if (maximumMoves < 0) throw new IllegalArgumentException("Negative move budget");
        final var canonical = pair.canonicalRepresentative();
        final byte[] key = CertificateCodec.keyFor(canonical);
        final byte[] value = get(handles.get(0), key);
        if (value == null) return Optional.empty();
        final var result = CertificateCodec.decodeResult(value);
        // Replay stored data even when the derived bound exceeds the requested budget.
        if (storedBudget(canonical, result) > maximumMoves) return Optional.empty();
        if (retained != null) retained.accept(key, value);
        return Optional.of(result.moves());
    }
    /** Position-preserving RocksDB multiGet followed by parallel independent replay of every hit. */
    public boolean[] findAll(final List<CyclicTargetPair> pairs, final int maximumMoves) {
        if (maximumMoves < 0) throw new IllegalArgumentException("Negative move budget");
        if (pairs.isEmpty()) return new boolean[0];
        final var canonical = new CyclicTargetPair[pairs.size()];
        final var keys = new byte[pairs.size()][];
        parallelRanges(pairs.size(), (first, last) -> {
            for (int i = first; i < last; i++) {
                canonical[i] = pairs.get(i).canonicalRepresentative();
                keys[i] = CertificateCodec.keyFor(canonical[i]);
            }
        });
        final var encoded = new byte[pairs.size()][];
        parallelRanges(pairs.size(), (first, last) -> {
            final var keySlice = Arrays.asList(keys).subList(first, last);
            final List<byte[]> values;
            try {
                values = database.multiGetAsList(
                        Collections.nCopies(keySlice.size(), handles.get(0)), keySlice);
            } catch (final RocksDBException failure) {
                throw new IllegalStateException("Cannot bulk-read certificates", failure);
            }
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
                final var pair = canonical[i];
                present[i] = storedBudget(pair, result) <= maximumMoves;
                if (present[i] && retained != null) retained.accept(keys[i], value);
            }
        });
        return present;
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
        final byte[] value = get(handles.get(1), CertificateCodec.keyFor(pair.canonicalRepresentative()));
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
    public long resultCount() {
        long count = 0;
        final byte[] marker = SCHEMA_KEY.getBytes(StandardCharsets.UTF_8);
        try (final var iterator = database.newIterator(handles.get(0), reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next())
                if (!Arrays.equals(iterator.key(), marker)) count = Math.incrementExact(count);
            iterator.status();
            return count;
        } catch (final RocksDBException failure) { throw new IllegalStateException("Cannot count stored results", failure); }
    }
    /** Checks exact keys and values, including the schema, with bounded memory. */
    public void requireSameResults(final CertificateStore used) {
        try (final var stored = database.newIterator(handles.get(0), reads);
             final var accepted = used.database.newIterator(used.handles.get(0), used.reads)) {
            stored.seekToFirst(); accepted.seekToFirst();
            while (stored.isValid() && accepted.isValid()) {
                if (!Arrays.equals(stored.key(), accepted.key()) || !Arrays.equals(stored.value(), accepted.value()))
                    throw new IllegalStateException("Persisted certificate differs from exactly the results used by verification");
                stored.next(); accepted.next();
            }
            stored.status(); accepted.status();
            if (stored.isValid() || accepted.isValid())
                throw new IllegalStateException("Persisted certificate contains surplus or missing results");
        } catch (final RocksDBException failure) { throw new IllegalStateException("Cannot audit retained results", failure); }
    }
    private byte[] get(final ColumnFamilyHandle family, final byte[] key) {
        try { return database.get(family, reads, key); }
        catch (final RocksDBException failure) { throw new IllegalStateException("Cannot read certificate", failure); }
    }
    private static int storedBudget(final CyclicTargetPair pair, final SortingCertificate result) {
        final int budget = CertificateValidator.requiredBudget(pair, result);
        if (budget > MAX_RESULT_MOVES) throw new IllegalArgumentException("Sorting result exceeds the certificate move budget");
        return budget;
    }
    private void parallelRanges(final int size, final RangeAction action) {
        final int taskCount = Math.min(workers, size);
        final int chunk = (size + taskCount - 1) / taskCount;
        final var futures = new ArrayList<Future<?>>(taskCount);
        for (int first = 0; first < size; first += chunk) {
            final int from = first, to = Math.min(size, first + chunk);
            futures.add(replayWorkers.submit(() -> action.run(from, to)));
        }
        try {
            for (final var future : futures) future.get();
        } catch (final InterruptedException failure) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Certificate replay interrupted", failure);
        } catch (final ExecutionException failure) {
            futures.forEach(future -> future.cancel(true));
            final var cause = failure.getCause();
            if (cause instanceof Error fatal) throw fatal;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Certificate replay failed", cause);
        }
    }
    @FunctionalInterface private interface RangeAction { void run(int first, int last); }
    @Override public void close() {
        replayWorkers.shutdown();
        boolean interrupted = false;
        for (;;) {
            try { if (replayWorkers.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) break; }
            catch (final InterruptedException failure) { interrupted = true; }
        }
        handles.forEach(ColumnFamilyHandle::close); database.close(); closeOptions();
        if (interrupted) Thread.currentThread().interrupt();
    }
    private void closeOptions() { reads.close(); witnessOptions.close(); obstructionOptions.close(); options.close(); }
}
