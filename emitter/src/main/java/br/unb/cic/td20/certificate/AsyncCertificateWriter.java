package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.compat.LegacyCertificates;
import br.unb.cic.td20.avoidance.ReconstructionProgress;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.runtime.DatabaseWriteBatching;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.rocksdb.*;

/** Bounded asynchronous persistence of replayed witnesses and unresolved keys. */
public final class AsyncCertificateWriter implements AutoCloseable {
    static final byte[] OBSTRUCTIONS = "obstructions".getBytes(StandardCharsets.UTF_8);
    static final byte[] SCHEMA_KEY = "td20-schema".getBytes(StandardCharsets.UTF_8);
    static final byte[] SCHEMA_VALUE = "eriksson-three-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY = {};
    static { RocksDB.loadLibrary(); }
    private final DBOptions options;
    private final ColumnFamilyOptions witnessOptions, obstructionOptions;
    private final RocksDB database;
    private final ColumnFamilyHandle witnesses, obstructions;
    private final ConcurrentLinkedQueue<Entry> pending = new ConcurrentLinkedQueue<>();
    private final Semaphore pendingSlots;
    private final AtomicInteger publishers = new AtomicInteger();
    private static final int STOPPED = Integer.MIN_VALUE;
    private final int writeBatchEntries;
    private final LongAdder accepted = new LongAdder(), persisted = new LongAdder();
    private final ScheduledExecutorService executor;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean drainRequested = new AtomicBoolean();
    private final Object drainLock = new Object();
    private final java.util.concurrent.ExecutorService persistenceWorkers;
    private final int persistenceParallelism;
    private final Path tableDirectory;
    private volatile boolean closed;

    public AsyncCertificateWriter(Path directory, long flushSeconds) {
        if (flushSeconds <= 0) throw new IllegalArgumentException("Positive flush period required");
        int capacity = Integer.getInteger("td20.certificateQueueCapacity", 1048576);
        if (capacity < 1) throw new IllegalArgumentException("Positive certificate queue capacity required");
        writeBatchEntries = Math.min(DatabaseWriteBatching.entries(), capacity);
        pendingSlots = new Semaphore(capacity);
        persistenceParallelism = Integer.getInteger("td20.certificateWriters",
                Math.min(32, Runtime.getRuntime().availableProcessors()));
        if (persistenceParallelism < 1) throw new IllegalArgumentException("Positive certificate writer count required");
        persistenceWorkers = Executors.newFixedThreadPool(persistenceParallelism,
                Thread.ofPlatform().daemon(true).name("certificate-db-", 0).factory());
        Path absolute = directory.toAbsolutePath();
        tableDirectory = absolute.resolve("certificate-sst-staging");
        boolean existing = Files.isRegularFile(absolute.resolve("CURRENT"));
        if (existing) requireFamilies(absolute);
        else if (Files.exists(absolute)) {
            try (var files = Files.list(absolute)) {
                if (files.findAny().isPresent()) throw new IllegalStateException("Certificate directory is not empty");
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
                .setAllowConcurrentMemtableWrite(true)
                .setMaxBackgroundJobs(Math.max(2,Math.min(16,Runtime.getRuntime().availableProcessors())))
                .setMaxSubcompactions(Math.max(1,Math.min(8,Runtime.getRuntime().availableProcessors())));
        witnessOptions = new ColumnFamilyOptions().setWriteBufferSize(256L << 20).setMaxWriteBufferNumber(4);
        obstructionOptions = new ColumnFamilyOptions().setWriteBufferSize(256L << 20).setMaxWriteBufferNumber(4);
        var handles = new ArrayList<ColumnFamilyHandle>();
        RocksDB opened = null;
        try {
            opened = RocksDB.open(options, absolute.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, witnessOptions),
                    new ColumnFamilyDescriptor(OBSTRUCTIONS, obstructionOptions)), handles);
            if (existing && !Arrays.equals(opened.get(handles.get(0), SCHEMA_KEY), SCHEMA_VALUE))
                throw new IllegalStateException("Unsupported certificate database format");
            if (!existing) try (var writes=new WriteOptions().setDisableWAL(true);
                               var flush=new FlushOptions().setWaitForFlush(true)) {
                opened.put(handles.get(0),writes,SCHEMA_KEY,SCHEMA_VALUE);
                opened.flush(flush,List.of(handles.get(0)));
            }
            database = opened; witnesses = handles.get(0); obstructions = handles.get(1);
        } catch (RocksDBException | RuntimeException error) {
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            persistenceWorkers.shutdown();
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
        enqueue(LegacyPairs.fromLegacy(pair), PrimitiveCertificate.complete(LegacyCertificates.pack(moves)));
    }

    /** Replays and queues an intrinsic result without materializing permutation objects. */
    public void enqueue(PrimitivePair pair, PrimitiveCertificate result) {
        enqueue(pair, result, obligationBudget(pair.size()));
    }

    public void enqueue(PrimitivePair pair, PrimitiveCertificate result, int maximumMoves) {
        if (maximumMoves < 0 || maximumMoves > obligationBudget(pair.size()))
            throw new IllegalArgumentException("Invalid explicit certificate budget");
        int required = CertificateValidator.requiredBudget(pair, result);
        if (required > maximumMoves)
            throw new IllegalArgumentException("Sorting result does not close the requested move budget");
        offer(new Entry(CertificateCodec.keyFor(pair), CertificateCodec.resultFor(result), pair.size(), required));
    }

    public void enqueueObstruction(CyclicTargetPair pair, int maximumMoves) {
        enqueueObstruction(LegacyPairs.fromLegacy(pair), maximumMoves);
    }

    public void enqueueObstruction(PrimitivePair pair, int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > obligationBudget(pair.size()))
            throw new IllegalArgumentException("Invalid explicit obstruction budget");
        offer(new Entry(CertificateCodec.keyFor(pair), null, pair.size(), maximumMoves));
    }

    private static int obligationBudget(int domain) {
        if (domain<1 || domain>br.unb.cic.td20.joint.KnownDiameters.ROOT_SYMBOLS)
            throw new IllegalArgumentException("Invalid certificate domain");
        return br.unb.cic.td20.joint.KnownDiameters.MAX_BUDGET;
    }

    public void enqueueClosure(CyclicTargetPair pair, List<Cycle> moves, int maximumMoves) {
        enqueue(LegacyPairs.fromLegacy(pair), PrimitiveCertificate.closure(LegacyCertificates.pack(moves)),
                Math.min(maximumMoves, obligationBudget(pair.getBeta().size())));
    }

    public void enqueueClosure(PrimitivePair pair, PrimitiveCertificate result, int maximumMoves) {
        if (result.kind() != PrimitiveCertificate.Kind.DIAMETER_CLOSURE)
            throw new IllegalArgumentException("A diameter closure result is required");
        enqueue(pair, result, maximumMoves);
    }

    public Optional<List<Cycle>> find(CyclicTargetPair pair, int maximumMoves) {
        return findResult(pair, maximumMoves).map(SortingCertificate::moves);
    }

    public Optional<SortingCertificate> findResult(CyclicTargetPair pair, int maximumMoves) {
        return findPrimitiveResult(LegacyPairs.fromLegacy(pair), maximumMoves).map(LegacyCertificates::toLegacy);
    }

    public Optional<PrimitiveCertificate> findPrimitiveResult(PrimitivePair pair, int maximumMoves) {
        throwIfFailed();
        if (closed) throw new IllegalStateException("Certificate writer is closed");
        if (maximumMoves < 0 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 0..11");
        try {
            return checkedResult(pair, maximumMoves, database.get(witnesses, CertificateCodec.keyFor(pair)));
        } catch (RocksDBException error) { throw new IllegalStateException("Cannot read sorting result", error); }
    }

    /** Position-preserving bulk lookup; every hit is replayed against its actual obligation budget. */
    public List<Optional<SortingCertificate>> findResults(List<CyclicTargetPair> pairs, int maximumMoves) {
        return findPrimitiveResults(pairs.stream().map(LegacyPairs::fromLegacy).toList(), maximumMoves)
                .stream().map(result -> result.map(LegacyCertificates::toLegacy)).toList();
    }

    /** One native multiGet retains missing, duplicate and over-budget request positions. */
    public List<Optional<PrimitiveCertificate>> findPrimitiveResults(List<PrimitivePair> pairs, int maximumMoves) {
        throwIfFailed();
        if (closed) throw new IllegalStateException("Certificate writer is closed");
        if (maximumMoves < 0 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 0..11");
        if (pairs.isEmpty()) return List.of();
        var keys = pairs.stream().map(CertificateCodec::keyFor).toList();
        try {
            var encoded = database.multiGetAsList(Collections.nCopies(keys.size(), witnesses), keys);
            if (encoded.size() != pairs.size()) throw new IllegalStateException("Incomplete certificate multiGet response");
            var results = new ArrayList<Optional<PrimitiveCertificate>>(pairs.size());
            for (int i = 0; i < pairs.size(); i++)
                results.add(checkedResult(pairs.get(i), maximumMoves, encoded.get(i)));
            return List.copyOf(results);
        } catch (RocksDBException error) { throw new IllegalStateException("Cannot bulk-read sorting results", error); }
    }

    private static Optional<PrimitiveCertificate> checkedResult(PrimitivePair pair, int maximumMoves, byte[] encoded) {
        if (encoded == null) return Optional.empty();
        var result = CertificateCodec.decodePrimitiveResult(encoded);
        int required = CertificateValidator.requiredBudget(pair, result);
        if (required > obligationBudget(pair.size()))
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
        for (;;) {
            int active=publishers.get();
            if (active<0 || closed) throw new IllegalStateException("Certificate writer is closed");
            if (active==Integer.MAX_VALUE) throw new IllegalStateException("Too many certificate publishers");
            if (publishers.compareAndSet(active,active+1)) break;
        }
        try {
            for (;;) {
                throwIfFailed();
                if (closed) throw new IllegalStateException("Certificate writer is closed");
                if (!pendingSlots.tryAcquire()) {
                    if (drainRequested.compareAndSet(false, true)) executor.execute(this::drainBackground);
                    if (!pendingSlots.tryAcquire(100,TimeUnit.MILLISECONDS)) continue;
                }
                try {
                    if (closed) throw new IllegalStateException("Certificate writer is closed");
                    pending.add(entry); accepted.increment(); return;
                } catch (RuntimeException | Error error) { pendingSlots.release(); throw error; }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted certificate enqueue", error);
        } finally { publishers.decrementAndGet(); }
    }

    private void drainBackground() {
        // Background writes avoid a forced SST flush on every timer tick.
        // WAL is disabled: explicit flush/close persist phase boundaries.
        try { drain(false); } catch (Throwable error) { failure.compareAndSet(null, error); }
        finally { drainRequested.set(false); }
    }

    public void throwIfFailed() {
        if (failure.get() != null) throw new IllegalStateException("Certificate writer failed", failure.get());
    }

    public void flush() {
        throwIfFailed();
        try (var progress=new ReconstructionProgress("Certificate persistence (entries)",accepted.sum(),persisted::sum)) {
            drain(true);
        } catch (RocksDBException error) { throw new IllegalStateException(error); }
        throwIfFailed();
    }

    private void drain(boolean flush) throws RocksDBException {
        synchronized (drainLock) {
            var entries = new ArrayList<Entry>(writeBatchEntries);
            for (;;) {
                for (int i=0;i<writeBatchEntries;i++) {
                    var entry=pending.poll(); if (entry==null) break; entries.add(entry);
                }
                if (entries.isEmpty()) break;
                pendingSlots.release(entries.size());
                persistInParallel(entries);
                persisted.add(entries.size());
                entries.clear();
            }
            var finished=new java.util.concurrent.atomic.AtomicLong();
            if (flush) try (var progress=new ReconstructionProgress("Certificate memtable flush (barrier)",1,finished::get);
                           var options = new FlushOptions().setWaitForFlush(true)) {
                database.flush(options, List.of(witnesses, obstructions));
                finished.set(1);
            }
        }
    }

    /**
     * One coordinator orders batches. Equal keys always enter the same shard;
     * every shard joins before the next batch or a flush barrier can proceed.
     * Each native WriteBatch atomically updates witnesses and obstructions.
     */
    private void persistInParallel(List<Entry> entries) {
        int count = Math.min(persistenceParallelism, Math.max(1, (entries.size() + 4095) / 4096));
        var shards = new ArrayList<List<Entry>>(count);
        for (int i = 0; i < count; i++) shards.add(new ArrayList<>());
        for (var entry : entries) {
            int hash = Arrays.hashCode(entry.key);
            hash ^= hash >>> 16;
            shards.get(Math.floorMod(hash, count)).add(entry);
        }
        var futures = new ArrayList<java.util.concurrent.Future<?>>(count);
        Throwable error = null;
        try {
            for (var shard : shards) if (!shard.isEmpty()) futures.add(persistenceWorkers.submit(() -> {
                try { persistShard(shard); }
                catch (RocksDBException failure) { throw new IllegalStateException("Certificate shard write failed", failure); }
            }));
        } catch (RuntimeException | Error submissionFailure) { error = submissionFailure; }
        boolean interrupted = false;
        // Never cancel native writes or close their handles while they run.
        for (var future : futures) for (;;) {
            try { future.get(); break; }
            catch (InterruptedException failure) { interrupted = true; }
            catch (java.util.concurrent.ExecutionException failure) {
                if (error == null) error = failure.getCause();
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            if (error == null) error = new InterruptedException("Interrupted certificate persistence barrier");
        }
        if (error != null) throw new IllegalStateException("Parallel certificate persistence failed", error);
    }

    private void persistShard(List<Entry> entries) throws RocksDBException {
        // Equal keys have one owner for this batch, and earlier batches have
        // joined. Reads cannot race with writes to those same keys. Keep the
        // strongest discharge: inverse coverage may already depend on it.
        var best = new LinkedHashMap<ByteBuffer,Entry>();
        for (Entry entry : entries) if (entry.value != null) {
            var key = ByteBuffer.wrap(entry.key);
            var previous = best.get(key);
            if (previous == null || entry.budget < previous.budget
                    || entry.budget == previous.budget && Arrays.compareUnsigned(entry.value,previous.value) < 0) best.put(key,entry);
        }
        var certificates = new ArrayList<>(best.values());
        if (!certificates.isEmpty()) {
            var keys = certificates.stream().map(Entry::key).toList();
            var lookupKeys = new ArrayList<>(keys); lookupKeys.addAll(keys);
            var families = new ArrayList<>(Collections.nCopies(keys.size(), witnesses));
            families.addAll(Collections.nCopies(keys.size(), obstructions));
            var stored = database.multiGetAsList(families, lookupKeys);
            if (stored.size() != lookupKeys.size()) throw new IllegalStateException("Incomplete certificate persistence lookup");
            var certifiedKeys = new HashSet<ByteBuffer>();
            var tables = new ArrayList<Entry>();
            var atomic = new ArrayList<Entry>();
            for (int i = 0; i < certificates.size(); i++) {
                Entry entry = certificates.get(i);
                certifiedKeys.add(ByteBuffer.wrap(entry.key));
                byte[] previous = stored.get(i), marker = stored.get(i+keys.size());
                if (previous != null) {
                    var pair = CertificateCodec.primitivePairForKey(entry.key,entry.domain);
                    int required = CertificateValidator.requiredBudget(pair,CertificateCodec.decodePrimitiveResult(previous));
                    if (required > obligationBudget(entry.domain))
                        throw new IllegalArgumentException("Stored sorting result exceeds the maximum move budget");
                    if (required < entry.budget || required == entry.budget && Arrays.compareUnsigned(previous,entry.value) <= 0) {
                        if (marker == null) continue;
                        entry = new Entry(entry.key,previous,entry.domain,required);
                    }
                }
                if (marker == null) tables.add(entry); else atomic.add(entry);
            }
            for (Entry entry : entries)
                if (entry.value == null && !certifiedKeys.contains(ByteBuffer.wrap(entry.key))) atomic.add(entry);
            // Only the keys with existing markers need a cross-family atomic
            // put/delete. A few markers must not force an entire shard back
            // through the serialized per-key memtable path.
            if (!tables.isEmpty()) persistCertificateTable(tables);
            if (atomic.isEmpty()) return;
            entries = atomic;
        }
        var certified = new HashSet<ByteBuffer>();
        for (Entry entry : entries) if (entry.value != null) certified.add(ByteBuffer.wrap(entry.key));
        var markerKeys = new ArrayList<byte[]>();
        for (Entry entry : entries)
            if (entry.value == null && !certified.contains(ByteBuffer.wrap(entry.key))) markerKeys.add(entry.key);
        var markerWitnesses = markerKeys.isEmpty() ? List.<byte[]>of()
                : database.multiGetAsList(Collections.nCopies(markerKeys.size(), witnesses), markerKeys);
        if (markerWitnesses.size() != markerKeys.size())
            throw new IllegalStateException("Incomplete obstruction coverage lookup");
        try (var batch = new WriteBatch(); var writes = new WriteOptions().setDisableWAL(true)) {
            int markerIndex = 0;
            for (Entry entry : entries) {
                if (entry.value != null) {
                    batch.put(witnesses, entry.key, entry.value);
                    batch.delete(obstructions, entry.key);
                } else if (!certified.contains(ByteBuffer.wrap(entry.key))) {
                    byte[] value = markerWitnesses.get(markerIndex++);
                    boolean covered = false;
                    if (value != null) {
                        var pair = CertificateCodec.primitivePairForKey(entry.key, entry.domain);
                        var result = CertificateCodec.decodePrimitiveResult(value);
                        int required = CertificateValidator.requiredBudget(pair, result);
                        if (required > obligationBudget(entry.domain))
                            throw new IllegalArgumentException("Stored sorting result exceeds the maximum move budget");
                        covered = required <= entry.budget;
                    }
                    if (!covered) batch.put(obstructions, entry.key, EMPTY);
                }
            }
            database.write(writes, batch);
        }
    }

    /** Exact sorted certificate keys; no WAL or per-key memtable insertion. */
    private void persistCertificateTable(List<Entry> entries) throws RocksDBException {
        entries.sort((left, right) -> Arrays.compareUnsigned(left.key, right.key));
        Path path = null;
        try {
            Files.createDirectories(tableDirectory);
            path = Files.createTempFile(tableDirectory, "certificates-", ".sst");
            try (var environment = new EnvOptions();
                 var tableOptions = new Options();
                 var table = new SstFileWriter(environment, tableOptions);
                 var ingest = new IngestExternalFileOptions().setMoveFiles(true)
                         .setAllowGlobalSeqNo(true).setAllowBlockingFlush(true)) {
                table.open(path.toString());
                for (int i = 0; i < entries.size();) {
                    int last = i;
                    while (last + 1 < entries.size()
                            && Arrays.equals(entries.get(i).key, entries.get(last + 1).key)) last++;
                    // Shard selection already retains the strongest queued certificate.
                    Entry entry = entries.get(last);
                    table.put(entry.key, entry.value);
                    i = last + 1;
                }
                table.finish();
                database.ingestExternalFile(witnesses, List.of(path.toString()), ingest);
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot build certificate table", error);
        } finally {
            if (path != null) try { Files.deleteIfExists(path); }
            catch (java.io.IOException error) {
                failure.compareAndSet(null, error);
                throw new IllegalStateException("Cannot clean certificate table", error);
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) { throwIfFailed(); return; }
        closed = true;
        publishers.getAndUpdate(count -> count | STOPPED);
        boolean interrupted=false;
        while (publishers.get()!=STOPPED) {
            if (Thread.interrupted()) interrupted=true;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        executor.shutdown();
        try {
            for (;;) {
                try { if (executor.awaitTermination(1,TimeUnit.SECONDS)) break; }
                catch (InterruptedException error) { interrupted=true; }
            }
            drain(true); throwIfFailed();
        } catch (RocksDBException error) { throw new IllegalStateException(error); }
        finally {
            persistenceWorkers.shutdown();
            obstructions.close(); witnesses.close(); database.close(); obstructionOptions.close(); witnessOptions.close(); options.close();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private record Entry(byte[] key, byte[] value, int domain, int budget) {}
}
