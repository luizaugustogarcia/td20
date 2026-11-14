package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.rocksdb.*;

/** Bounded asynchronous persistence of replayed witnesses and unresolved keys. */
public final class AsyncCertificateWriter implements AutoCloseable {
    private static final byte[] OBSTRUCTIONS = "obstructions".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SCHEMA_KEY = "td20-schema".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SCHEMA_VALUE = "joint2-selection-v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = {};
    static { RocksDB.loadLibrary(); }
    private final DBOptions options;
    private final ColumnFamilyOptions witnessOptions, obstructionOptions;
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses, obstructions;
    private final ArrayBlockingQueue<Entry> pending;
    private final ScheduledExecutorService executor;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean drainRequested = new AtomicBoolean();
    private final Object drainLock = new Object();
    private volatile boolean closed;

    public AsyncCertificateWriter(Path directory, long flushSeconds) {
        if (flushSeconds <= 0) throw new IllegalArgumentException("Positive flush period required");
        int capacity = Integer.getInteger("td20.certificateQueueCapacity", 16384);
        if (capacity < 1) throw new IllegalArgumentException("Positive certificate queue capacity required");
        pending = new ArrayBlockingQueue<>(capacity);
        Path absolute = directory.toAbsolutePath();
        boolean existing = Files.isRegularFile(absolute.resolve("CURRENT"));
        if (existing) requireFamilies(absolute);
        else if (Files.exists(absolute)) {
            try (var files = Files.list(absolute)) {
                if (files.findAny().isPresent()) throw new IllegalStateException("Certificate directory is not empty");
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true).setMaxBackgroundJobs(4);
        witnessOptions = new ColumnFamilyOptions();
        obstructionOptions = new ColumnFamilyOptions();
        var handles = new ArrayList<ColumnFamilyHandle>();
        RocksDB opened = null;
        try {
            opened = RocksDB.open(options, absolute.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                    new ColumnFamilyDescriptor(OBSTRUCTIONS, obstructionOptions)), handles);
            if (existing && !Arrays.equals(opened.get(handles.get(0), SCHEMA_KEY), SCHEMA_VALUE))
                throw new IllegalStateException("Unsupported certificate database format");
            if (!existing) opened.put(handles.get(0), SCHEMA_KEY, SCHEMA_VALUE);
            database = opened; witnesses = handles.get(0); obstructions = handles.get(1);
        } catch (RocksDBException | RuntimeException error) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            witnessOptions.close(); obstructionOptions.close(); options.close();
            throw new IllegalStateException("Cannot open certificate database", error);
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("certificate-writer").factory());
        executor.scheduleWithFixedDelay(this::drainBackground, flushSeconds, flushSeconds, TimeUnit.SECONDS);
    }

    private static void requireFamilies(Path directory) {
        try (var listing = new Options()) {
            var families = RocksDB.listColumnFamilies(listing, directory.toString());
            if (families.size() != 2 || families.stream().noneMatch(x -> Arrays.equals(x, RocksDB.DEFAULT_COLUMN_FAMILY))
                    || families.stream().noneMatch(x -> Arrays.equals(x, OBSTRUCTIONS)))
                throw new IllegalStateException("Expected witness and obstruction column families");
        } catch (RocksDBException error) { throw new IllegalStateException(error); }
    }

    public void enqueue(CyclicTargetPair pair, List<Cycle> moves) {
        CertificateValidator.validate(pair, moves, obligationBudget(pair.getBeta().size()));
        offer(new Entry(CertificateCodec.keyFor(pair), CertificateCodec.resultFor(SortingCertificate.complete(moves)), pair.getBeta().size(), 0));
    }

    public void enqueueObstruction(CyclicTargetPair pair, int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > obligationBudget(pair.getBeta().size()))
            throw new IllegalArgumentException("Invalid explicit obstruction budget");
        offer(new Entry(CertificateCodec.keyFor(pair), null, pair.getBeta().size(), maximumMoves));
    }

    private static int obligationBudget(int domain) {
        if (domain<1 || domain>br.unb.cic.td20.joint.KnownDiameters.ROOT_SYMBOLS)
            throw new IllegalArgumentException("Invalid certificate domain");
        return br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET;
    }

    public void enqueueClosure(CyclicTargetPair pair, List<Cycle> moves, int maximumMoves) {
        var result = SortingCertificate.closure(moves);
        CertificateValidator.validate(pair, result,
                Math.min(maximumMoves, obligationBudget(pair.getBeta().size())));
        offer(new Entry(CertificateCodec.keyFor(pair), CertificateCodec.resultFor(result), pair.getBeta().size(), 0));
    }

    public Optional<List<Cycle>> find(CyclicTargetPair pair, int maximumMoves) {
        return findResult(pair, maximumMoves).map(SortingCertificate::moves);
    }

    public Optional<SortingCertificate> findResult(CyclicTargetPair pair, int maximumMoves) {
        throwIfFailed();
        if (closed) throw new IllegalStateException("Certificate writer is closed");
        if (maximumMoves < 0 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 0..11");
        try {
            return checkedResult(pair, maximumMoves, database.get(witnesses, CertificateCodec.keyFor(pair)));
        } catch (RocksDBException error) { throw new IllegalStateException("Cannot read sorting result", error); }
    }

    /** Position-preserving bulk lookup; every hit is replayed against its actual obligation budget. */
    public List<Optional<SortingCertificate>> findResults(List<CyclicTargetPair> pairs, int maximumMoves) {
        throwIfFailed();
        if (closed) throw new IllegalStateException("Certificate writer is closed");
        if (maximumMoves < 0 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 0..11");
        if (pairs.isEmpty()) return List.of();
        var keys = pairs.stream().map(CertificateCodec::keyFor).toList();
        try {
            var encoded = database.multiGetAsList(Collections.nCopies(keys.size(), witnesses), keys);
            if (encoded.size() != pairs.size()) throw new IllegalStateException("Incomplete certificate multiGet response");
            var results = new ArrayList<Optional<SortingCertificate>>(pairs.size());
            for (int i = 0; i < pairs.size(); i++)
                results.add(checkedResult(pairs.get(i), maximumMoves, encoded.get(i)));
            return List.copyOf(results);
        } catch (RocksDBException error) { throw new IllegalStateException("Cannot bulk-read sorting results", error); }
    }

    private static Optional<SortingCertificate> checkedResult(CyclicTargetPair pair, int maximumMoves, byte[] encoded) {
        if (encoded == null) return Optional.empty();
        var result = CertificateCodec.decodeResult(encoded);
        int required = CertificateValidator.requiredBudget(pair, result);
        if (required > obligationBudget(pair.getBeta().size()))
            throw new IllegalArgumentException("Sorting result does not close its obligation budget");
        return required <= maximumMoves ? Optional.of(result) : Optional.empty();
    }

    public void forEachObstruction(Consumer<byte[]> consumer) {
        flush();
        try (var reads = new ReadOptions().setFillCache(false); var iterator = database.newIterator(obstructions, reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                if (iterator.value().length != 0) throw new IllegalStateException("Obstruction value must be empty");
                consumer.accept(iterator.key());
            }
            iterator.status();
        } catch (RocksDBException error) { throw new IllegalStateException(error); }
    }

    private void offer(Entry entry) {
        try {
            for (;;) {
                throwIfFailed();
                if (closed) throw new IllegalStateException("Certificate writer is closed");
                synchronized (pending) {
                    if (closed) throw new IllegalStateException("Certificate writer is closed");
                    if (pending.offer(entry)) return;
                }
                if (drainRequested.compareAndSet(false, true)) executor.execute(this::drainBackground);
                Thread.sleep(10);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted certificate enqueue", error);
        }
    }

    private void drainBackground() {
        try { drain(true); } catch (Throwable error) { failure.compareAndSet(null, error); }
        finally { drainRequested.set(false); }
    }

    public void throwIfFailed() {
        if (failure.get() != null) throw new IllegalStateException("Certificate writer failed", failure.get());
    }

    public void flush() {
        throwIfFailed();
        try { drain(true); } catch (RocksDBException error) { throw new IllegalStateException(error); }
        throwIfFailed();
    }

    private void drain(boolean flush) throws RocksDBException {
        synchronized (drainLock) {
            var entries = new ArrayList<Entry>(4096);
            while (pending.drainTo(entries, 4096) != 0) {
                var certified = new HashSet<ByteBuffer>();
                for (Entry entry : entries) if (entry.value != null) certified.add(ByteBuffer.wrap(entry.key));
                try (var batch = new WriteBatch(); var writes = new WriteOptions()) {
                    for (Entry entry : entries) {
                        if (entry.value != null) {
                            batch.put(witnesses, entry.key, entry.value);
                            batch.delete(obstructions, entry.key);
                        } else if (!certified.contains(ByteBuffer.wrap(entry.key))) {
                            byte[] value = database.get(witnesses, entry.key);
                            boolean covered = false;
                            if (value != null) {
                                var pair = CertificateCodec.pairForKey(entry.key, entry.domain);
                                var result = CertificateCodec.decodeResult(value);
                                CertificateValidator.validate(pair, result, obligationBudget(entry.domain));
                                covered = CertificateValidator.requiredBudget(pair, result) <= entry.budget;
                            }
                            if (!covered) batch.put(obstructions, entry.key, EMPTY);
                        }
                    }
                    database.write(writes, batch);
                }
                entries.clear();
            }
            if (flush) try (var options = new FlushOptions().setWaitForFlush(true)) {
                database.flush(options, List.of(witnesses, obstructions));
            }
        }
    }

    @Override public void close() {
        if (closed) { throwIfFailed(); return; }
        synchronized (pending) { closed = true; }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Writer thread did not stop");
            }
            drain(true); throwIfFailed();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        catch (RocksDBException error) { throw new IllegalStateException(error); }
        finally { obstructions.close(); witnesses.close(); database.close(); obstructionOptions.close(); witnessOptions.close(); options.close(); }
    }
    private record Entry(byte[] key, byte[] value, int domain, int budget) {}
}
