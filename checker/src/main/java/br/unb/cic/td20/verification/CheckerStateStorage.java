package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.joint.PrimitiveInverseReconstruction.RawConsumer;
import br.unb.cic.td20.runtime.CheckerParallelism;
import br.unb.cic.td20.runtime.RocksDbMemoryBudget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded disk deduplication of a regenerated wave of valid-pair classes. */
public final class CheckerStateStorage implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CheckerStateStorage.class);
    static final byte VERSION = 3;
    private static final byte[] EMPTY = {};
    private static final int MEMBERSHIP_BATCH_KEYS = 4096;
    private final int generatedBatchKeys = positiveProperty("td20.reconstructionGeneratedBatchKeys", 65536);
    private static final int ADMISSION_STOPPED = Integer.MIN_VALUE;
    static { RocksDB.loadLibrary(); }
    private final int bufferKeys = CheckerParallelism.reconstructionBufferKeys();
    private final int writeBatchEntries = CheckerParallelism.writeBatchEntries();
    private final Options options;
    private final BloomFilter membershipFilter;
    private final RocksDbMemoryBudget.Lease memory;
    private final Path stagingDirectory;
    private final RocksDB database;
    private final CheckerStateWriter[] writers;
    private final Consumer<List<byte[]>> generatedObserver;
    private final ConcurrentHashMap<Long, ArrayList<byte[]>> producerBatches = new ConcurrentHashMap<>();
    private final AtomicInteger admissions = new AtomicInteger();
    private final AtomicReference<Throwable> generationFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> persistenceFailure = new AtomicReference<>();
    private final LongAdder ingestedTables = new LongAdder(), ingestedKeys = new LongAdder(), duplicateKeys = new LongAdder();
    private long size = -1;
    private boolean scanPrepared;
    private volatile boolean closed;
    private final boolean readOnly;
    private final long checkpointCount;

    /** Successful persistence work; duplicate totals count occurrences, not distinct classes. */
    record WriteProgress(long ingestedKeys, long duplicateKeys, long ingestedTables) { }

    WriteProgress writeProgress() {
        return new WriteProgress(ingestedKeys.sum(), duplicateKeys.sum(), ingestedTables.sum());
    }

    public record State(PrimitivePair pair, int maximumMoves) {
        public State {
            java.util.Objects.requireNonNull(pair);
            if (maximumMoves<1 || maximumMoves>br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET)
                throw new IllegalArgumentException("Budget must be 1..11");
            if (!pair.isFixedPointFree()) throw new IllegalArgumentException("Reconstruction states must be fixed-point-free");
        }
        public State(CyclicTargetPair pair,int maximumMoves) { this(LegacyPairs.fromLegacy(pair),maximumMoves); }
    }

    public CheckerStateStorage(Path directory) {
        this(directory, false, -1, null);
    }

    /** Optional early-search observer; the complete persisted inventory remains authoritative. */
    public CheckerStateStorage(Path directory, Consumer<List<byte[]>> generatedObserver) {
        this(directory, false, -1, java.util.Objects.requireNonNull(generatedObserver));
    }

    /** Reopens a sealed checkpoint, checking every record and its exact inventory count. */
    private static CheckerStateStorage openSealed(Path directory, long expectedCount) {
        if (expectedCount < 0) throw new IllegalArgumentException("Nonnegative checkpoint count required");
        return new CheckerStateStorage(directory, true, expectedCount, null);
    }

    /**
     * Opens a checkpoint for mandatory processing. Its count remains an expected
     * count until that exhaustive scan validates every record, joins every
     * callback and checks the exact count. This avoids a duplicate traversal.
     */
    private static CheckerStateStorage openSealedForScan(Path directory, long expectedCount) {
        if (expectedCount < 0) throw new IllegalArgumentException("Nonnegative checkpoint count required");
        return new CheckerStateStorage(directory, true, expectedCount, null, false);
    }

    private CheckerStateStorage(Path directory, boolean readOnly, long expectedCount,
                         Consumer<List<byte[]>> generatedObserver) {
        this(directory, readOnly, expectedCount, generatedObserver, true);
    }

    private CheckerStateStorage(Path directory, boolean readOnly, long expectedCount,
                         Consumer<List<byte[]>> generatedObserver, boolean validateBeforeUse) {
        this.readOnly = readOnly;
        checkpointCount = readOnly ? expectedCount : -1;
        this.generatedObserver = generatedObserver;
        stagingDirectory = directory.resolve("sst-staging");
        int writerCount = readOnly ? 0 : Math.min(bufferKeys, positiveProperty("td20.reconstructionWriters",
                Math.min(8, Runtime.getRuntime().availableProcessors())));
        int queueBatches = readOnly ? 0 : positiveProperty("td20.reconstructionQueueBatches", 1024);
        long memtableMB = RocksDbMemoryBudget.positive("td20.rocksDbMemtableMB", 64);
        int memtables = positiveProperty("td20.rocksDbMaxWriteBuffers", 4);
        int openFiles = positiveProperty("td20.rocksDbScratchMaxOpenFiles", 128);
        memory = RocksDbMemoryBudget.acquire();
        membershipFilter = new BloomFilter(10);
        options = new Options().setCreateIfMissing(true).setWriteBufferSize(Math.multiplyExact(memtableMB, 1L << 20))
                .setWriteBufferManager(memory.writeBuffers())
                .setTableFormatConfig(memory.tables().setFilterPolicy(membershipFilter).setWholeKeyFiltering(true))
                .setMaxWriteBufferNumber(memtables).setMaxBackgroundJobs(Math.max(2, Math.min(16, 2 * writerCount)))
                .setMaxSubcompactions(Math.max(1, Math.min(8, writerCount)))
                .setMaxOpenFiles(openFiles);
        writers = new CheckerStateWriter[writerCount];
        RocksDB opened = null;
        try {
            if (readOnly) {
                if (!Files.isRegularFile(directory.resolve("CURRENT")))
                    throw new IllegalStateException("Missing checkpoint state set: " + directory);
                options.setCreateIfMissing(false);
                opened = RocksDB.openReadOnly(options, directory.toString());
            } else {
                Files.createDirectory(directory);
                Files.createDirectory(stagingDirectory);
                opened = RocksDB.open(options, directory.toString());
            }
            database = opened;
            for (int i = 0; i < writerCount; i++) {
                var pending = new HashSet<ByteBuffer>();
                int limit = Math.min(writeBatchEntries, ceilingDivide(bufferKeys, writerCount));
                writers[i] = new CheckerStateWriter(ceilingDivide(queueBatches, writerCount),
                        "reconstruction-writer-" + directory.getFileName() + "-" + i, keys -> {
                    for (byte[] key : keys) {
                        pending.add(ByteBuffer.wrap(key));
                        if (pending.size() >= limit) flushPending(pending);
                    }
                }, () -> flushPending(pending), persistenceFailure);
            }
            if (readOnly && validateBeforeUse && seal() != expectedCount)
                throw new IllegalStateException("Checkpoint state count disagrees at " + directory
                        + ": expected " + expectedCount + ", found " + size);
        } catch (IOException | RocksDBException | RuntimeException failure) {
            for (var writer : writers) if (writer != null) {
                try { writer.finish(); } catch (RuntimeException error) { failure.addSuppressed(error); }
            }
            if (opened != null) opened.close();
            options.close();
            membershipFilter.close();
            memory.close();
            throw new IllegalStateException("Cannot open reconstruction state set: " + directory, failure);
        }
    }

    boolean contains(State state) {
        if (knownSize() < 0) throw new IllegalStateException("Membership requires a sealed checker set");
        try { return database.get(encode(state)) != null; }
        catch (org.rocksdb.RocksDBException failure) { throw new IllegalStateException(failure); }
    }

    public void add(CyclicTargetPair pair, int maximumMoves) { add(LegacyPairs.fromLegacy(pair),maximumMoves); }

    public void add(PrimitivePair pair, int maximumMoves) {
        beginGeneration();
        try {
            submit(List.of(DirectStateKey.encode(pair, maximumMoves)));
        } catch (RuntimeException | Error failure) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        } finally { admissions.decrementAndGet(); }
    }

    /**
     * Inserts one synchronous generation into bounded, producer-thread key batches.
     * Canonicalization stays in producers; deterministic writer shards own all
     * deduplication and disk writes. Partial batches carry across generation
     * calls on the same thread and are drained after admissions stop, before
     * any writer finishes. The generator must not retain or concurrently use
     * its output callback. Callers must join all generators before sealing.
     * A failed generation makes the incomplete set unsealable.
     */
    public void addGenerated(int maximumMoves, Consumer<Consumer<CyclicTargetPair>> generator) {
        if (maximumMoves < 1 || maximumMoves > br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET) throw new IllegalArgumentException("Budget must be 1..11");
        java.util.Objects.requireNonNull(generator);
        beginGeneration();
        try {
            var batch = producerBatches.computeIfAbsent(Thread.currentThread().threadId(),
                    ignored -> new ArrayList<>(generatedBatchKeys));
            generator.accept(pair -> {
                batch.add(DirectStateKey.encode(pair, maximumMoves));
                if (batch.size() == generatedBatchKeys) {
                    submit(batch);
                    batch.clear();
                }
            });
        } catch (RuntimeException | Error failure) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        } finally { admissions.decrementAndGet(); }
    }

    /** Same bounded producer batching for immutable compact states. */
    public void addGeneratedPairs(int maximumMoves, Consumer<Consumer<PrimitivePair>> generator) {
        if (maximumMoves < 1 || maximumMoves > br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET) throw new IllegalArgumentException("Budget must be 1..11");
        java.util.Objects.requireNonNull(generator);
        beginGeneration();
        try {
            var batch = producerBatches.computeIfAbsent(Thread.currentThread().threadId(),
                    ignored -> new ArrayList<>(generatedBatchKeys));
            generator.accept(pair -> {
                batch.add(DirectStateKey.encode(pair, maximumMoves));
                if (batch.size() == generatedBatchKeys) {
                    submit(batch);
                    batch.clear();
                }
            });
        } catch (RuntimeException | Error failure) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        } finally { admissions.decrementAndGet(); }
    }

    /**
     * Mirrors emitter inverse persistence: the generator supplies valid contracted
     * pairs proved by forward replay. Borrowed arrays are packed before returning;
     * callbacks must not be retained or used concurrently. Failures prevent sealing.
     */
    public void addGeneratedPrimitive(int maximumMoves, Consumer<RawConsumer> generator) {
        if (maximumMoves < 1 || maximumMoves > br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET) throw new IllegalArgumentException("Budget must be 1..11");
        java.util.Objects.requireNonNull(generator);
        beginGeneration();
        try {
            var batch = producerBatches.computeIfAbsent(Thread.currentThread().threadId(),
                    ignored -> new ArrayList<>(generatedBatchKeys));
            generator.accept((omega,beta,n) -> {
                batch.add(DirectStateKey.encodeKnownPair(omega,beta,n,maximumMoves));
                if (batch.size() == generatedBatchKeys) {
                    submit(batch);
                    batch.clear();
                }
            });
        } catch (RuntimeException | Error failure) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        } finally { admissions.decrementAndGet(); }
    }

    private void submit(List<byte[]> keys) {
        if (generatedObserver != null) generatedObserver.accept(List.copyOf(keys));
        if (writers.length == 1) { writers[0].submit(keys); return; }
        var partitions = new ArrayList<List<byte[]>>(writers.length);
        for (int i = 0; i < writers.length; i++) partitions.add(new ArrayList<>());
        boolean powerOfTwo = (writers.length & (writers.length - 1)) == 0;
        for (byte[] key : keys) {
            int hash = Arrays.hashCode(key);
            hash ^= hash >>> 16;
            hash *= 0x7feb352d;
            hash ^= hash >>> 15;
            hash *= 0x846ca68b;
            hash ^= hash >>> 16;
            int shard = powerOfTwo ? hash & (writers.length - 1) : Math.floorMod(hash, writers.length);
            partitions.get(shard).add(key);
        }
        // Publish to every available shard before waiting on any full shard.
        // Otherwise a slow first shard prevents later writers receiving work.
        boolean[] submitted = new boolean[writers.length];
        for (int i = 0; i < writers.length; i++) submitted[i] = writers[i].trySubmit(partitions.get(i));
        for (int i = 0; i < writers.length; i++)
            if (!submitted[i]) writers[i].submit(partitions.get(i));
    }

    static byte[] encode(State state) {
        return DirectStateKey.encode(state.pair(), state.maximumMoves());
    }

    private void flushPending(HashSet<ByteBuffer> pending) {
        if (pending.isEmpty()) return;
        long started = System.nanoTime();
        log.debug("Reconstruction persistence: building sorted table, keys={}, directory={}",
                pending.size(), stagingDirectory);
        // Match RocksDB's unsigned bytewise comparator. Each shard constructs its
        // table independently, outside the database's shared memtable write path.
        byte[][] keys = new byte[pending.size()][];
        int index = 0;
        for (var key : pending) keys[index++] = key.array();
        Arrays.sort(keys, Arrays::compareUnsigned);
        try {
            int retained = retainAbsentKeys(keys);
            if (retained == 0) { pending.clear(); return; }
            keys = Arrays.copyOf(keys, retained);
        } catch (RocksDBException failure) {
            throw new IllegalStateException("Cannot check persisted reconstruction keys", failure);
        }
        try (var environment = new EnvOptions();
             var table = new SstFileWriter(environment, options);
             var ingest = new IngestExternalFileOptions().setMoveFiles(true)
                     .setAllowGlobalSeqNo(true).setAllowBlockingFlush(false)) {
            Path path = Files.createTempFile(stagingDirectory, "inverse-", ".sst");
            table.open(path.toString());
            for (byte[] key : keys) table.put(key, EMPTY);
            table.finish();
            long bytes = table.fileSize();
            // Each key belongs to one serial writer for this set's lifetime.
            // Its preceding ingest completes before the next membership check,
            // so absent keys cannot race with another writer of the same key.
            database.ingestExternalFile(List.of(path.toString()), ingest);
            Files.deleteIfExists(path);
            pending.clear();
            ingestedKeys.add(keys.length); ingestedTables.increment();
            log.debug("Reconstruction persistence: ingested table, keys={}, bytes={}, elapsed={} ms, directory={}",
                    keys.length, bytes, (System.nanoTime() - started) / 1_000_000, stagingDirectory);
        } catch (IOException | RocksDBException failure) {
            throw new IllegalStateException("Cannot persist reconstruction state table", failure);
        }
    }

    /** Exact reads only: a probabilistic membership positive cannot omit an obligation. */
    private int retainAbsentKeys(byte[][] keys) throws RocksDBException {
        int retained = 0;
        var sorted = Arrays.asList(keys);
        try (var reads = new ReadOptions().setFillCache(false)) {
            for (int from = 0; from < keys.length; from += MEMBERSHIP_BATCH_KEYS) {
                int end = Math.min(keys.length, from + MEMBERSHIP_BATCH_KEYS);
                var values = database.multiGetAsList(reads, sorted.subList(from, end));
                if (values.size() != end - from)
                    throw new IllegalStateException("Incomplete reconstruction membership lookup");
                for (int i = from; i < end; i++) {
                    byte[] value = values.get(i - from);
                    if (value == null) keys[retained++] = keys[i];
                    else if (value.length != 0)
                        throw new IllegalStateException("Malformed persisted reconstruction value");
                }
            }
        }
        duplicateKeys.add(keys.length - retained);
        return retained;
    }

    /** Drain persistence without a preliminary inventory scan. */
    public synchronized long prepareScan() {
        requireOpen();
        requireSuccessfulGeneration();
        if (!scanPrepared) {
            if (!readOnly) {
                stopAdmissions();
                finishWriters();
                requireSuccessfulGeneration();
            }
            scanPrepared = true;
        }
        return size >= 0 ? size : checkpointCount;
    }

    /** Unknown until an exhaustive iterator and its callbacks complete. */
    public synchronized long knownSize() { return size; }

    public synchronized long seal() {
        prepareScan();
        if (size >= 0) return size;
        // This set writes exclusively through completed external-SST ingests.
        // There is no memtable to flush; a synchronous flush can wait behind
        // compactions even though the complete inventory is already readable.
        size = scanEncodedConcurrently(Runtime.getRuntime().availableProcessors(), checkpointCount,
                "unique-class counting", keys -> {
                    for (byte[] key : keys) decode(key, EMPTY);
                    // Advisory work follows validation; the mandatory processing
                    // scan still visits every persisted record.
                    if (generatedObserver != null) generatedObserver.accept(keys);
                });
        return size;
    }

    public void forEach(Consumer<State> consumer) {
        long expected = prepareScan();
        long scanned = 0;
        var scannedCount = new AtomicLong();
        try (var progress = new CheckerStateProgress("State inventory " + stagingDirectory.getParent().getFileName() + " scan",
                     expected, scannedCount::get);
             var reads = new ReadOptions().setFillCache(false); var iterator = database.newIterator(reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                consumer.accept(decode(iterator.key(), iterator.value()));
                scanned = Math.addExact(scanned, 1);
                if ((scanned & 4095) == 0) scannedCount.set(scanned);
            }
            iterator.status();
            scannedCount.set(scanned);
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot scan reconstruction states", failure); }
        if (expected >= 0 && scanned != expected) throw new IllegalStateException("Reconstruction scan missed states");
        synchronized (this) { size = scanned; }
    }

    /** Reads every key with bounded parallel callbacks, without global callback ordering. */
    public void forEachConcurrently(int workers, Consumer<State> consumer) {
        forEachConcurrently(workers, null, state -> { }, consumer);
    }

    public static int lookupBatchSize() { return CheckerParallelism.lookupBatchSize(); }

    private static int positiveProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int ceilingDivide(int value, int divisor) {
        return Math.toIntExact(((long) value + divisor - 1) / divisor);
    }

    /** Deterministic bounded batches, including the final partial batch. */
    public void forEachBatch(Consumer<List<State>> consumer) {
        int limit = lookupBatchSize();
        var batch = new ArrayList<State>(limit);
        forEach(state -> {
            batch.add(state);
            if (batch.size() == limit) { consumer.accept(List.copyOf(batch)); batch.clear(); }
        });
        if (!batch.isEmpty()) consumer.accept(List.copyOf(batch));
    }

    /**
     * Workers decode bounded key batches, replay their bulk lookups, then process
     * misses directly. Each worker owns a snapshot iterator over a disjoint
     * range; a shared bounded task queue distributes ranges without a serial
     * iterator feeding all workers. There is no global callback ordering.
     * Callbacks and lookups must support concurrent calls. Every worker joins
     * before the exact inventory becomes available, including the final batch.
     */
    public void forEachConcurrently(int workers, Function<List<State>, boolean[]> lookup,
                                    Consumer<State> cached, Consumer<State> missing) {
        long expected = prepareScan();
        long scanned = scanEncodedConcurrently(workers, expected, "scan", keys -> {
            if (lookup == null) {
                for (byte[] key : keys) missing.accept(decode(key, EMPTY));
            } else {
                var batch = new ArrayList<State>(keys.size());
                for (byte[] key : keys) batch.add(decode(key, EMPTY));
                boolean[] hits = lookup.apply(List.copyOf(batch));
                if (hits == null || hits.length != batch.size())
                    throw new IllegalStateException("Incomplete reconstruction batch lookup");
                for (int i = 0; i < batch.size(); i++) {
                    if (hits[i]) cached.accept(batch.get(i));
                    else missing.accept(batch.get(i));
                }
            }
        });
        synchronized (this) { size = scanned; }
    }

    /** Worker-owned snapshot iterators feed bounded callbacks over disjoint key ranges. */
    private long scanEncodedConcurrently(int workers, long expected, String phase,
                                         Consumer<List<byte[]>> consumer) {
        if (workers < 1) throw new IllegalArgumentException("Positive scan worker count required");
        int batchKeys = Math.min(lookupBatchSize(), positiveProperty("td20.reconstructionScanBatchKeys", 4096));
        int parallelism = Math.min(workers, Math.min(CheckerParallelism.scanWorkers(),
                Runtime.getRuntime().availableProcessors()));
        // Small inverse inventories can have expensive callbacks. Keep their
        // lookup batches small; independent ranges distribute the callback work.
        if (expected > 0) {
            long targetTasks = 32L * parallelism;
            batchKeys = (int) Math.min(batchKeys, Math.max(1, Math.ceilDiv(expected, targetTasks)));
        }
        return ParallelStateScanner.scan(database, parallelism, CheckerParallelism.scanRangesPerWorker(),
                batchKeys, expected, "State inventory " + stagingDirectory.getParent().getFileName()
                        + " " + phase, consumer);
    }

    static State decode(byte[] key, byte[] value) {
        if (key.length < 4 || key[0] != VERSION || value.length != 0)
            throw new IllegalStateException("Malformed reconstruction state");
        return new State(CertificateCodec.primitivePairForKey(Arrays.copyOfRange(key, 3, key.length),
                Byte.toUnsignedInt(key[2])), Byte.toUnsignedInt(key[1]));
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("State set is closed"); }
    private void requireSuccessfulGeneration() {
        if (generationFailure.get() != null)
            throw new IllegalStateException("Reconstruction generation failed", generationFailure.get());
        if (persistenceFailure.get() != null)
            throw new IllegalStateException("Reconstruction writer failed", persistenceFailure.get());
    }
    private void beginGeneration() {
        requireOpen();
        requireSuccessfulGeneration();
        if (readOnly) throw new IllegalStateException("State set is sealed");
        for (;;) {
            int active = admissions.get();
            if (active < 0) throw new IllegalStateException("State set is sealed");
            if (active == Integer.MAX_VALUE) throw new IllegalStateException("Too many reconstruction producers");
            if (admissions.compareAndSet(active, active + 1)) return;
        }
    }
    private void stopAdmissions() {
        if (admissions.get() == ADMISSION_STOPPED) return;
        if (!admissions.compareAndSet(0, ADMISSION_STOPPED))
            throw new IllegalStateException("Join all reconstruction producers before sealing or closing");
    }
    private void finishWriters() {
        RuntimeException failure = null;
        // All producers have joined and admissions have stopped. Each list was
        // mutated by only its owner; drain every remaining list before END can
        // reach any shard. A drain failure still joins all native-resource users.
        for (var batch : producerBatches.values()) {
            try {
                if (!batch.isEmpty()) submit(batch);
                batch.clear();
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        producerBatches.clear();
        try (var progress = new CheckerStateProgress("State persistence " + stagingDirectory.getParent().getFileName() + " drain (writers)",
                writers.length, () -> Arrays.stream(writers).filter(writer -> writer != null && writer.finished()).count(), () -> {
                    long submitted = 0, consumed = 0;
                    for (var writer : writers) if (writer != null) {
                        submitted += writer.submittedKeys(); consumed += writer.consumedKeys();
                    }
                    return "encodedKeysConsumed=" + consumed + "/" + submitted + ", encodedKeysRemaining="
                            + (submitted - consumed) + ", ingestedTables=" + ingestedTables.sum()
                            + ", ingestedKeys=" + ingestedKeys.sum() + ", duplicateKeysSkipped=" + duplicateKeys.sum()
                            + ", uniqueTotal=unknown until counting";
                })) {
            // Signal every shard before joining any: their final tables must
            // build and ingest concurrently, rather than one final flush at a time.
            for (var writer : writers) if (writer != null) writer.requestFinish();
            for (var writer : writers) if (writer != null) {
                try { writer.finish(); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            generationFailure.compareAndSet(null, failure);
            throw failure;
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        stopAdmissions();
        closed = true;
        try { finishWriters(); }
        finally { database.close(); options.close(); membershipFilter.close(); memory.close(); }
    }
}
