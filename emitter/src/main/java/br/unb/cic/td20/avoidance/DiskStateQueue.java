package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Append-only, disk-backed handoff. Recovery regenerates it from the sealed source wave. */
final class DiskStateQueue implements AutoCloseable {
    private static final int KEY_BYTES = 3 + (5 * ErikssonRoute.ROOT_SYMBOLS + 7) / 8;
    private static final int RECORD_BYTES = 1 + KEY_BYTES;
    private final FileChannel file;
    private final int headerBytes;
    private long appended, claimed;
    private boolean finished;
    private Throwable failure;

    DiskStateQueue(Path path) {
        FileChannel opened = null;
        try {
            var bytes = new ByteArrayOutputStream();
            try (var header = new DataOutputStream(bytes)) {
                header.writeUTF("td-reconstruction-queue-v1");
                header.writeUTF(ErikssonRoute.ID);
                header.writeInt(ErikssonRoute.ROOT_SYMBOLS);
                header.writeInt(ErikssonRoute.ROOT_BUDGET);
                header.writeInt(RECORD_BYTES);
                header.writeLong(-1); // Final record count, written only when production completes.
            }
            headerBytes = bytes.size();
            opened = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            file = opened;
            write(ByteBuffer.wrap(bytes.toByteArray()), 0);
        } catch (IOException | RuntimeException error) {
            if (opened != null) try { opened.close(); } catch (IOException close) { error.addSuppressed(close); }
            throw new IllegalStateException("Cannot create reconstruction queue: " + path, error);
        }
    }

    synchronized void add(DiskStateSet.State state) {
        check();
        if (finished) throw new IllegalStateException("Reconstruction queue is sealed");
        byte[] key = DiskStateSet.encode(state);
        int length = key.length;
        if (length > KEY_BYTES) throw new IllegalArgumentException("Reconstruction key exceeds queue domain");
        var record = ByteBuffer.allocate(RECORD_BYTES);
        record.put((byte) length).put(key).rewind();
        try {
            write(record, offset(appended));
            appended++;
            notifyAll();
        } catch (IOException error) {
            abort(error);
            throw new IllegalStateException("Cannot append reconstruction queue", error);
        }
    }

    DiskStateSet.State take() {
        long index;
        synchronized (this) {
            while (claimed == appended && !finished && failure == null) {
                try { wait(); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Reconstruction queue interrupted", error);
                }
            }
            check();
            if (claimed == appended) return null;
            index = claimed++;
        }
        var record = ByteBuffer.allocate(RECORD_BYTES);
        try {
            long position = offset(index);
            while (record.hasRemaining()) {
                int read = file.read(record, position);
                if (read <= 0) throw new IOException("Missing or truncated reconstruction queue record " + index);
                position += read;
            }
            byte[] bytes = record.array();
            int length = Byte.toUnsignedInt(bytes[0]);
            if (length < 4 || length > KEY_BYTES) throw new IOException("Malformed reconstruction queue length");
            for (int i = 1 + length; i < bytes.length; i++)
                if (bytes[i] != 0) throw new IOException("Malformed reconstruction queue padding");
            return DiskStateSet.decode(Arrays.copyOfRange(bytes, 1, 1 + length), new byte[0]);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read reconstruction queue", error);
        }
    }

    synchronized void finish() {
        check();
        if (finished) return;
        try {
            if (file.size() != offset(appended)) throw new IOException("Reconstruction queue length mismatch");
            write(ByteBuffer.allocate(Long.BYTES).putLong(appended).flip(), headerBytes - Long.BYTES);
            finished = true;
            notifyAll();
        } catch (IOException error) {
            abort(error);
            throw new IllegalStateException("Cannot seal reconstruction queue", error);
        }
    }

    synchronized void abort(Throwable error) {
        if (failure == null) failure = error;
        notifyAll();
    }

    synchronized void check() {
        if (failure != null) throw new IllegalStateException("Reconstruction queue failed", failure);
    }

    synchronized long appended() { return appended; }

    private long offset(long index) {
        return Math.addExact(headerBytes, Math.multiplyExact(index, RECORD_BYTES));
    }

    private void write(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int written = file.write(buffer, position);
            if (written <= 0) throw new IOException("Incomplete reconstruction queue write");
            position += written;
        }
    }

    @Override public void close() {
        try { file.close(); }
        catch (IOException error) { throw new IllegalStateException("Cannot close reconstruction queue", error); }
    }
}
