package br.unb.cic.td20.verification;

import br.unb.cic.td20.joint.JointInverseReconstruction;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.runtime.BoundedTasks;
import br.unb.cic.td20.runtime.CheckerParallelism;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * Fresh checker-owned disk set; never opens or resumes an emitter work database.
 * Keys contain version, budget, domain and the canonical five-bit instance.
 * Sealing ends writes before the checker streams the resulting set.
 */
final class CheckerStateSet implements AutoCloseable {
    private static final byte VERSION = 3;
    private static final byte[] EMPTY = {};

    static {
        RocksDB.loadLibrary();
    }

    private final int bufferKeys = CheckerParallelism.reconstructionBufferKeys();
    private final Options options = new Options().setCreateIfMissing(true)
            .setWriteBufferSize(128L << 20).setMaxWriteBufferNumber(4)
            .setMaxBackgroundJobs(Math.min(8, CheckerParallelism.workers())).setMaxOpenFiles(512);
    private final WriteOptions writes = new WriteOptions().setDisableWAL(true);
    private final RocksDB database;
    private final Set<ByteBuffer> pending = new HashSet<>();
    private Inventory sealed;

    record Inventory(long count) { }

    CheckerStateSet(final Path directory) {
        try {
            // Refuse even an empty pre-existing directory: only this invocation
            // may populate proof reconstruction scratch space.
            Files.createDirectory(directory);
            database = RocksDB.open(options, directory.toString());
        } catch (final IOException | RocksDBException | RuntimeException failure) {
            writes.close();
            options.close();
            throw new IllegalStateException("Checker requires a fresh state directory: "
                    + directory, failure);
        }
    }

    void add(final JointInverseReconstruction.SearchState state) {
        final var canonical = state.pair().canonicalRepresentative();
        final byte[] raw = CertificateCodec.keyFor(canonical);
        final byte[] key = new byte[3 + raw.length];
        key[0] = VERSION;
        key[1] = (byte) state.maximumMoves();
        key[2] = (byte) canonical.getBeta().size();
        System.arraycopy(raw, 0, key, 3, raw.length);
        synchronized (this) {
            if (sealed != null) {
                throw new IllegalStateException("Cannot add to a sealed checker set");
            }
            pending.add(ByteBuffer.wrap(key));
            if (pending.size() >= bufferKeys) {
                flushPending();
            }
        }
    }

    private void flushPending() {
        try (final var batch = new WriteBatch()) {
            for (final var key : pending) {
                batch.put(key.array(), EMPTY);
            }
            database.write(writes, batch);
            pending.clear();
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot persist checker states", failure);
        }
    }

    boolean contains(final JointInverseReconstruction.SearchState state) {
        if (sealed == null) {
            throw new IllegalStateException("Membership requires a sealed checker set");
        }
        final var pair = state.pair().canonicalRepresentative();
        final byte[] raw = CertificateCodec.keyFor(pair);
        final byte[] key = ByteBuffer.allocate(3 + raw.length).put(VERSION)
                .put((byte) state.maximumMoves()).put((byte) pair.getBeta().size())
                .put(raw).array();
        try {
            return database.get(key) != null;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot query checker states", failure);
        }
    }

    synchronized Inventory seal() {
        if (sealed != null) {
            return sealed;
        }
        flushPending();
        long count = 0;
        try (final var reads = new ReadOptions().setFillCache(false);
             final var iterator = database.newIterator(reads)) {
            for (iterator.seek(new byte[]{VERSION}); iterator.isValid(); iterator.next()) {
                final byte[] key = iterator.key();
                requireKey(key, iterator.value());
                count++;
            }
            iterator.status();
            try (final var flush = new FlushOptions().setWaitForFlush(true)) {
                database.flush(flush);
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot seal checker states", failure);
        }
        sealed = new Inventory(count);
        return sealed;
    }

    void forEachBatch(final Consumer<List<JointInverseReconstruction.SearchState>> consumer) {
        final Inventory expected = seal();
        long count = 0;
        final int batchSize = CheckerParallelism.lookupBatchSize();
        try (final var reads = new ReadOptions().setFillCache(false);
             final var iterator = database.newIterator(reads)) {
            var batch = new ArrayList<JointInverseReconstruction.SearchState>(batchSize);
            for (iterator.seek(new byte[]{VERSION}); iterator.isValid(); iterator.next()) {
                final byte[] key = iterator.key();
                requireKey(key, iterator.value());
                count++;
                batch.add(new JointInverseReconstruction.SearchState(
                        Byte.toUnsignedInt(key[1]), CertificateCodec.pairForKey(
                        Arrays.copyOfRange(key, 3, key.length), Byte.toUnsignedInt(key[2]))));
                if (batch.size() == batchSize) {
                    consumer.accept(List.copyOf(batch));
                    batch = new ArrayList<>(batchSize);
                }
            }
            iterator.status();
            if (!batch.isEmpty()) {
                consumer.accept(List.copyOf(batch));
            }
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot scan checker states", failure);
        }
        if (count != expected.count()) {
            throw new IllegalStateException("Checker scan did not visit every generated state");
        }
    }

    /** Streams all sealed states through bounded parallel callbacks. */
    void forEachConcurrently(final int workers,
                             final Consumer<JointInverseReconstruction.SearchState> consumer) {
        try (final var tasks = new BoundedTasks(workers, "checker-reconstruction-")) {
            forEachBatch(batch -> {
                tasks.check();
                for (final var state : batch) tasks.submit(() -> consumer.accept(state));
            });
        }
    }

    /** Performs one bulk lookup per large batch, then parallelizes every missing-state callback. */
    void forEachConcurrently(final int workers,
                             final Function<List<JointInverseReconstruction.SearchState>, boolean[]> lookup,
                             final Consumer<JointInverseReconstruction.SearchState> present,
                             final Consumer<JointInverseReconstruction.SearchState> missing) {
        try (final var tasks = new BoundedTasks(workers, "checker-reconstruction-")) {
            forEachBatch(batch -> {
                tasks.check();
                final boolean[] hits = lookup.apply(batch);
                if (hits == null || hits.length != batch.size())
                    throw new IllegalStateException("Incomplete reconstruction batch lookup");
                for (int i = 0; i < hits.length; i++) {
                    final var state = batch.get(i);
                    if (hits[i]) present.accept(state);
                    else tasks.submit(() -> missing.accept(state));
                }
            });
        }
    }

    private static void requireKey(final byte[] key, final byte[] value) {
        if (key.length < 4 || key[0] != VERSION || key[1] < 9 || key[1] > 11
                || key[2] < 1 || key[2] > 21 || value.length != 0) {
            throw new IllegalStateException("Malformed checker state");
        }
    }

    @Override
    public void close() {
        database.close();
        writes.close();
        options.close();
    }
}
