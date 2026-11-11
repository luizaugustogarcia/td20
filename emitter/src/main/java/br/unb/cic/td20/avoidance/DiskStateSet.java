package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.runtime.BoundedTasks;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.rocksdb.*;

/** Bounded disk deduplication of a regenerated wave of valid-pair classes. */
public final class DiskStateSet implements AutoCloseable {
    private static final byte VERSION = 1;
    private static final byte[] EMPTY = {};
    static { RocksDB.loadLibrary(); }
    private final int bufferKeys = positiveProperty("td20.reconstructionBufferKeys", 1048576);
    private final Options options = new Options().setCreateIfMissing(true)
            .setWriteBufferSize(32L << 20).setMaxWriteBufferNumber(2).setMaxBackgroundJobs(2).setMaxOpenFiles(128);
    private final WriteOptions writes = new WriteOptions().setDisableWAL(true);
    private final RocksDB database;
    private final HashSet<ByteBuffer> pending = new HashSet<>();
    private long size = -1;
    private boolean closed;
    private final boolean readOnly;

    public record State(CyclicTargetPair pair, int maximumMoves) {
        public State {
            if (maximumMoves < 1 || maximumMoves > 11) throw new IllegalArgumentException("Budget must be 1..11");
            for (int symbol : pair.getBeta().getSymbols()) {
                if (pair.getOmega().image(symbol) == symbol)
                    throw new IllegalArgumentException("Reconstruction states must be fixed-point-free");
            }
        }
    }

    public DiskStateSet(Path directory) {
        this(directory, false, -1);
    }

    /** Reopens a sealed checkpoint, checking every record and its exact inventory count. */
    public static DiskStateSet openSealed(Path directory, long expectedCount) {
        if (expectedCount < 0) throw new IllegalArgumentException("Nonnegative checkpoint count required");
        return new DiskStateSet(directory, true, expectedCount);
    }

    private DiskStateSet(Path directory, boolean readOnly, long expectedCount) {
        this.readOnly = readOnly;
        RocksDB opened = null;
        try {
            if (readOnly) {
                if (!Files.isRegularFile(directory.resolve("CURRENT")))
                    throw new IllegalStateException("Missing checkpoint state set: " + directory);
                options.setCreateIfMissing(false);
                opened = RocksDB.openReadOnly(options, directory.toString());
            } else {
                Files.createDirectory(directory);
                opened = RocksDB.open(options, directory.toString());
            }
            database = opened;
            if (readOnly && seal() != expectedCount)
                throw new IllegalStateException("Checkpoint state count disagrees at " + directory
                        + ": expected " + expectedCount + ", found " + size);
        } catch (IOException | RocksDBException | RuntimeException failure) {
            if (opened != null) opened.close();
            writes.close(); options.close();
            throw new IllegalStateException("Cannot open reconstruction state set: " + directory, failure);
        }
    }

    public void add(CyclicTargetPair pair, int maximumMoves) {
        // Orbit minimization is CPU work and must not serialize all producers on the disk lock.
        var state = new State(pair.canonicalRepresentative(), maximumMoves);
        byte[] raw = CertificateCodec.keyFor(state.pair());
        byte[] key = ByteBuffer.allocate(raw.length + 3).put(VERSION).put((byte) maximumMoves)
                .put((byte) pair.getBeta().size()).put(raw).array();
        synchronized (this) {
            requireOpen();
            if (readOnly || size >= 0) throw new IllegalStateException("State set is sealed");
            pending.add(ByteBuffer.wrap(key));
            if (pending.size() >= bufferKeys) flushPending();
        }
    }

    private void flushPending() {
        try (var batch = new WriteBatch()) {
            for (var key : pending) batch.put(key.array(), EMPTY);
            database.write(writes, batch);
            pending.clear();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot persist reconstruction states", failure); }
    }

    public synchronized long seal() {
        requireOpen();
        if (size >= 0) return size;
        if (!readOnly) flushPending();
        long count = 0;
        try (var reads = new ReadOptions().setFillCache(false); var iterator = database.newIterator(reads);
             var flush = new FlushOptions().setWaitForFlush(true)) {
            if (!readOnly) database.flush(flush);
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                decode(iterator.key(), iterator.value());
                count = Math.addExact(count, 1);
            }
            iterator.status();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot seal reconstruction states", failure); }
        size = count;
        return count;
    }

    public void forEach(Consumer<State> consumer) {
        long expected = seal();
        long scanned = 0;
        try (var reads = new ReadOptions().setFillCache(false); var iterator = database.newIterator(reads)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                consumer.accept(decode(iterator.key(), iterator.value()));
                scanned = Math.addExact(scanned, 1);
            }
            iterator.status();
        } catch (RocksDBException failure) { throw new IllegalStateException("Cannot scan reconstruction states", failure); }
        if (scanned != expected) throw new IllegalStateException("Reconstruction scan missed states");
    }

    /** Reads deterministic keys with bounded parallel callbacks, waiting for all work to finish. */
    public void forEachConcurrently(int workers, Consumer<State> consumer) {
        try (var tasks = new BoundedTasks(workers, "reconstruction-")) {
            forEach(state -> tasks.submit(() -> consumer.accept(state)));
        }
    }

    public static int lookupBatchSize() { return positiveProperty("td20.certificateLookupBatchSize", 262144); }

    private static int positiveProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
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

    /** Bulk replay precedes bounded submission; cached classes never enter the worker queue. */
    public void forEachConcurrently(int workers, Function<List<State>, boolean[]> lookup,
                                    Consumer<State> cached, Consumer<State> missing) {
        if (lookup == null) { forEachConcurrently(workers, missing); return; }
        try (var tasks = new BoundedTasks(workers, "reconstruction-")) {
            forEachBatch(batch -> {
                tasks.check();
                boolean[] hits = lookup.apply(batch);
                if (hits == null || hits.length != batch.size())
                    throw new IllegalStateException("Incomplete reconstruction batch lookup");
                for (int i = 0; i < batch.size(); i++) if (hits[i]) cached.accept(batch.get(i));
                for (int i = 0; i < batch.size(); i++) {
                    if (hits[i]) continue;
                    State state = batch.get(i);
                    tasks.submit(() -> missing.accept(state));
                }
            });
        }
    }

    private static State decode(byte[] key, byte[] value) {
        if (key.length < 4 || key[0] != VERSION || value.length != 0)
            throw new IllegalStateException("Malformed reconstruction state");
        return new State(CertificateCodec.pairForKey(Arrays.copyOfRange(key, 3, key.length),
                Byte.toUnsignedInt(key[2])), Byte.toUnsignedInt(key[1]));
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("State set is closed"); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; database.close(); writes.close(); options.close();
    }
}
