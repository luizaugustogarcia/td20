package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import lombok.val;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Append-only, disk-backed handoff. Recovery regenerates it from the sealed source wave.
 */
final class DiskStateQueue implements AutoCloseable {

    private static final int KEY_BYTES = 3 + (5 * ErikssonRoute.ROOT_SYMBOLS + 7) / 8;
    private static final int RECORD_BYTES = 1 + KEY_BYTES;
    private final FileChannel file;
    private final int headerBytes;
    private long appended, claimed;
    private boolean finished;
    private Throwable failure;

    DiskStateQueue(final Path path) {
        FileChannel opened = null;

        try {
            val bytes = new ByteArrayOutputStream();

            try (val header = new DataOutputStream(bytes)) {
                header.writeUTF("td-reconstruction-queue-v1");
                header.writeUTF(ErikssonRoute.ID);
                header.writeInt(ErikssonRoute.ROOT_SYMBOLS);
                header.writeInt(ErikssonRoute.ROOT_BUDGET);
                header.writeInt(RECORD_BYTES);
                // Final record count, written only when production completes.
                header.writeLong(-1);
            }

            headerBytes = bytes.size();
            opened = FileChannel.open(path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            file = opened;
            write(ByteBuffer.wrap(bytes.toByteArray()), 0);
        } catch (final IOException | RuntimeException error) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (final IOException close) {
                    error.addSuppressed(close);
                }
            }
            throw new IllegalStateException("Cannot create reconstruction queue: " + path, error);
        }
    }

    void add(final DiskStateSet.State state) {
        final byte[] key = DiskStateSet.encode(state);
        final int length = key.length;
        if (length > KEY_BYTES) {
            throw new IllegalArgumentException("Reconstruction key exceeds queue domain");
        }

        val record = ByteBuffer.allocate(RECORD_BYTES);
        record.put((byte) length).put(key).rewind();
        synchronized (this) {
            check();
            if (finished) {
                throw new IllegalStateException("Reconstruction queue is sealed");
            }

            try {
                write(record, offset(appended));
                appended++;
                notifyAll();
            } catch (final IOException error) {
                abort(error);
                throw new IllegalStateException("Cannot append reconstruction queue", error);
            }
        }
    }

    DiskStateSet.State take() {
        long index;
        synchronized (this) {
            while (claimed == appended && !finished && failure == null) {
                try {
                    wait();
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Reconstruction queue interrupted", error);
                }
            }

            check();
            if (claimed == appended) {
                return null;
            }

            index = claimed++;
        }

        val record = ByteBuffer.allocate(RECORD_BYTES);

        try {
            long position = offset(index);
            while (record.hasRemaining()) {
                final int read = file.read(record, position);
                if (read <= 0) {
                    throw new IOException("Missing or truncated reconstruction queue record " + index);
                }

                position += read;
            }

            final byte[] bytes = record.array();
            final int length = Byte.toUnsignedInt(bytes[0]);
            if (length < 4 || length > KEY_BYTES) {
                throw new IOException("Malformed reconstruction queue length");
            }
            for (int i = 1 + length; i < bytes.length; i++) {
                if (bytes[i] != 0) {
                    throw new IOException("Malformed reconstruction queue padding");
                }
            }

            return DiskStateSet.decode(Arrays.copyOfRange(bytes, 1, 1 + length), new byte[0]);
        } catch (final IOException error) {
            throw new IllegalStateException("Cannot read reconstruction queue", error);
        }
    }

    synchronized void finish() {
        check();
        if (finished) {
            return;
        }

        try {
            if (file.size() != offset(appended)) {
                throw new IOException("Reconstruction queue length mismatch");
            }

            write(ByteBuffer.allocate(Long.BYTES).putLong(appended).flip(), headerBytes - Long.BYTES);
            finished = true;
            notifyAll();
        } catch (final IOException error) {
            abort(error);
            throw new IllegalStateException("Cannot seal reconstruction queue", error);
        }
    }

    synchronized void abort(final Throwable error) {
        if (failure == null) {
            failure = error;
        }

        notifyAll();
    }

    synchronized void check() {
        if (failure != null) {
            throw new IllegalStateException("Reconstruction queue failed", failure);
        }
    }

    synchronized long appended() {
        return appended;
    }

    private long offset(final long index) {
        return Math.addExact(headerBytes, Math.multiplyExact(index, RECORD_BYTES));
    }

    private void write(final ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            final int written = file.write(buffer, position);
            if (written <= 0) {
                throw new IOException("Incomplete reconstruction queue write");
            }

            position += written;
        }
    }

    @Override
    public void close() {
        try {
            file.close();
        } catch (final IOException error) {
            throw new IllegalStateException("Cannot close reconstruction queue", error);
        }
    }
}
