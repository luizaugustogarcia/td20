package br.unb.cic.td20.certificate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.rocksdb.*;

/** Bounded persistence of exactly the raw results accepted by independent replay. */
public final class RetainedCertificateWriter implements BiConsumer<byte[], byte[]>, AutoCloseable {
    static { RocksDB.loadLibrary(); }
    private static final int BATCH_SIZE = 8192;
    private record Entry(byte[] key, byte[] value) { }
    private final DBOptions options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
    private final ColumnFamilyOptions first = new ColumnFamilyOptions(), second = new ColumnFamilyOptions();
    private final WriteOptions writes = new WriteOptions().setDisableWAL(true);
    private final List<ColumnFamilyHandle> handles = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("retained-certificates-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final RocksDB database;
    private ArrayList<Entry> pending = new ArrayList<>(BATCH_SIZE);
    private boolean closed;

    public RetainedCertificateWriter(final Path path) throws Exception {
        RocksDB opened = null;
        try {
            Files.createDirectory(path);
            opened = RocksDB.open(options, path.toString(), List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                    new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), second)), handles);
            database = opened;
            database.put(handles.get(0), CertificateStore.SCHEMA_KEY.getBytes(StandardCharsets.UTF_8),
                    CertificateStore.SCHEMA_VALUE.getBytes(StandardCharsets.UTF_8));
        } catch (final Exception | Error error) {
            pool.shutdownNow();
            handles.forEach(ColumnFamilyHandle::close);
            if (opened != null) opened.close();
            writes.close(); first.close(); second.close(); options.close();
            throw error;
        }
    }
    @Override public synchronized void accept(final byte[] key, final byte[] value) {
        check();
        if (closed) throw new IllegalStateException("Retention writer is closed");
        pending.add(new Entry(key.clone(), value.clone()));
        if (pending.size() == BATCH_SIZE) submit();
    }
    private void submit() {
        if (pending.isEmpty()) return;
        final var batch = pending;
        pending = new ArrayList<>(BATCH_SIZE);
        pool.execute(() -> {
            try (final var writeBatch = new WriteBatch()) {
                for (final var entry : batch) writeBatch.put(handles.get(0), entry.key(), entry.value());
                database.write(writes, writeBatch);
            } catch (final Exception | Error error) { failure.compareAndSet(null, error); }
        });
        check();
    }
    private void check() {
        final var error = failure.get();
        if (error != null) throw new IllegalStateException("Cannot retain certificate results", error);
    }
    @Override public void close() throws Exception {
        synchronized (this) {
            if (closed) return;
            closed = true;
            try { submit(); }
            catch (final RuntimeException | Error error) { failure.compareAndSet(null, error); }
            finally { pool.shutdown(); }
        }
        boolean interrupted = false;
        try {
            for (;;) {
                try { if (pool.awaitTermination(1, TimeUnit.SECONDS)) break; }
                catch (final InterruptedException error) { interrupted = true; }
            }
            check();
            try (final var flush = new FlushOptions().setWaitForFlush(true)) { database.flush(flush, handles); }
            database.compactRange(handles.get(0));
        } finally {
            handles.forEach(ColumnFamilyHandle::close); database.close();
            writes.close(); first.close(); second.close(); options.close();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
