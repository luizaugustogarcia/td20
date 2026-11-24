package br.unb.cic.td20.verification;

import br.unb.cic.td20.runtime.BoundedTasks;
import lombok.val;
import org.rocksdb.Range;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.SizeApproximationFlag;
import org.rocksdb.Slice;
import org.rocksdb.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Complete, disjoint snapshot scans with worker-owned iterators and bounded batches.
 */
final class ParallelStateScanner {

    private static final Logger log = LoggerFactory.getLogger(ParallelStateScanner.class);
    private static final byte[] EMPTY = {};

    private record Interval(byte[] start, byte[] end, long estimatedBytes, int depth) {
    }

    private ParallelStateScanner() {
    }

    static long scan(final RocksDB database,
            final int workers,
            final int rangesPerWorker,
            final int batchKeys,
            final long expected,
            final String phase,
            final Consumer<List<byte[]>> consumer) {
        if (workers < 1 || rangesPerWorker < 1 || batchKeys < 1) {
            throw new IllegalArgumentException("Positive scan workers, ranges and batch size required");
        }
        if (expected < -1) {
            throw new IllegalArgumentException("A scan total is nonnegative or unknown (-1)");
        }

        Objects.requireNonNull(database);
        Objects.requireNonNull(consumer);

        final int targetRanges = Math.multiplyExact(workers, rangesPerWorker);
        val snapshot = database.getSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("Cannot acquire reconstruction scan snapshot");
        }

        try {
            log.info("{}: planning disjoint snapshot ranges; workers={}, rangesPerWorker={}",
                    phase,
                    workers,
                    rangesPerWorker);

            val ranges = plan(database, snapshot, targetRanges);
            final int effectiveWorkers = Math.min(workers, Math.max(1, ranges.size()));
            val completed = new LongAdder();
            val finishedRanges = new AtomicLong();
            val result = new AtomicLong();
            log.info("{}: workers={}, ranges={}, batchKeys={}, snapshotSequence={}",
                    phase,
                    effectiveWorkers,
                    ranges.size(),
                    batchKeys,
                    snapshot.getSequenceNumber());

            try (val progress = new CheckerStateProgress(phase,
                    expected,
                    completed::sum,
                    () -> "workers=" + effectiveWorkers + ", rangesCompleted=" + finishedRanges.get() + "/" + ranges.size());
                val tasks = new BoundedTasks(effectiveWorkers, "reconstruction-scan-")) {
                for (val range : ranges) {
                    tasks.submit(() -> {
                        final long count = scanRange(database, snapshot, range, batchKeys, consumer, completed);
                        result.updateAndGet(value -> Math.addExact(value, count));
                        finishedRanges.incrementAndGet();
                    });
                }
            }

            final long count = result.get();
            if (finishedRanges.get() != ranges.size() || completed.sum() != count) {
                throw new IllegalStateException("Incomplete reconstruction scan ranges or callbacks");
            }
            if (expected >= 0 && count != expected) {
                throw new IllegalStateException("Reconstruction scan missed states: expected " + expected + ", scanned " + count);
            }

            log.info("{} complete: validatedKeys={}, rangesCompleted={}/{}",
                    phase,
                    count,
                    finishedRanges.get(),
                    ranges.size());

            return count;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot plan reconstruction scan ranges", failure);
        } finally {
            database.releaseSnapshot(snapshot);
        }
    }

    /**
     * Independently implements the emitter's partitioning over the entire snapshot keyspace.
     */
    private static List<Interval> plan(final RocksDB database, final Snapshot snapshot, final int target) throws RocksDBException {
        try (val reads = new ReadOptions().setSnapshot(snapshot).setFillCache(false);
            val probe = database.newIterator(reads)) {
            probe.seekToLast();
            if (!probe.isValid()) {
                probe.status();
                return List.of();
            }

            final byte[] last = probe.key();
            probe.status();

            // The empty lower bound includes an empty or malformed key. Appending
            // zero to the largest key is a strict upper bound on this snapshot.
            val start = new byte[0];
            final byte[] end = Arrays.copyOf(last, last.length + 1);
            val pending = new PriorityQueue<Interval>(Comparator.<Interval>comparingLong(Interval::estimatedBytes).reversed().// Equal estimates (including zero for memtables or small
            // blocks) split breadth-first instead of starving the tail.
            thenComparingInt(Interval::depth).thenComparing(Interval::start, Arrays::compareUnsigned));
            pending.add(new Interval(start, end, estimate(database, start, end), 0));

            val terminal = new ArrayList<Interval>();
            while (pending.size() + terminal.size() < target && !pending.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Reconstruction scan planning interrupted");
                }

                val range = pending.remove();
                final byte[] middle = split(probe, range);
                if (middle == null) {
                    terminal.add(range);
                    continue;
                }

                final long[] sizes = estimates(database, range.start(), middle, range.end());
                pending.add(new Interval(range.start(), middle, sizes[0], range.depth() + 1));
                pending.add(new Interval(middle, range.end(), sizes[1], range.depth() + 1));
            }

            terminal.addAll(pending);
            terminal.sort(Comparator.comparing(Interval::start, Arrays::compareUnsigned));

            return List.copyOf(terminal);
        }
    }

    private static byte[] split(final RocksIterator probe, final Interval range) throws RocksDBException {
        probe.seek(range.start());
        if (!probe.isValid()) {
            probe.status();
            return null;
        }

        final byte[] first = probe.key();
        if (Arrays.compareUnsigned(first, range.end()) >= 0) {
            probe.status();
            return null;
        }

        probe.seekForPrev(range.end());
        if (probe.isValid() && Arrays.equals(probe.key(), range.end())) {
            probe.prev();
        }
        if (!probe.isValid()) {
            probe.status();
            throw new IllegalStateException("Missing reconstruction range endpoint");
        }

        final byte[] last = probe.key();
        probe.status();

        final int order = Arrays.compareUnsigned(first, last);
        if (order > 0) {
            throw new IllegalStateException("Reversed reconstruction range endpoints");
        }
        if (order == 0) {
            return null;
        }

        final int prefix = Arrays.mismatch(first, last);
        final byte[] middle = Arrays.copyOf(last, prefix + 1);
        if (prefix < first.length) {
            middle[prefix] = (byte) (((first[prefix] & 255) + (last[prefix] & 255) + 1) / 2);
        }
        if (Arrays.compareUnsigned(first, middle) >= 0
                || Arrays.compareUnsigned(middle, last) > 0
                || Arrays.compareUnsigned(middle, range.start()) <= 0
                || Arrays.compareUnsigned(middle, range.end()) >= 0) {
            throw new IllegalStateException("Invalid reconstruction key-range boundary");
        }

        return middle;
    }

    private static long estimate(final RocksDB database, final byte[] start, final byte[] end) {
        try (val lower = new Slice(start);
            val upper = new Slice(end)) {
            return database.getApproximateSizes(List.of(new Range(lower, upper)),
                    SizeApproximationFlag.INCLUDE_FILES)[0];
        }
    }

    private static long[] estimates(final RocksDB database, final byte[] start, final byte[] middle, final byte[] end) {
        try (val lower = new Slice(start);
            val split = new Slice(middle);
            val upper = new Slice(end)) {
            return database.getApproximateSizes(List.of(new Range(lower, split), new Range(split, upper)),
                    SizeApproximationFlag.INCLUDE_FILES);
        }
    }

    private static long scanRange(final RocksDB database,
            final Snapshot snapshot,
            final Interval range,
            final int batchKeys,
            final Consumer<List<byte[]>> consumer,
            final LongAdder completed) {
        long count = 0;

        try (val lower = new Slice(range.start());
            val upper = new Slice(range.end());
            val reads = new ReadOptions().setSnapshot(snapshot).setFillCache(false).setIterateLowerBound(lower).setIterateUpperBound(upper);
            val iterator = database.newIterator(reads)) {
            val batch = new ArrayList<byte[]>(batchKeys);
            for (iterator.seek(range.start()); iterator.isValid(); iterator.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Reconstruction scan interrupted");
                }
                // RocksIterator.value(buffer) returns the full value length even
                // for an empty destination; avoid allocating one array per key.
                if (iterator.value(EMPTY) != 0) {
                    throw new IllegalStateException("Malformed reconstruction state");
                }

                batch.add(iterator.key());
                count = Math.addExact(count, 1);
                if (batch.size() == batchKeys) {
                    consume(batch, consumer, completed);
                    batch.clear();
                }
            }

            iterator.status();
            if (!batch.isEmpty()) {
                consume(batch, consumer, completed);
            }

            return count;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot scan reconstruction key range", failure);
        }
    }

    private static void consume(final List<byte[]> batch, final Consumer<List<byte[]>> consumer, final LongAdder completed) {
        val owned = List.copyOf(batch);
        consumer.accept(owned);
        completed.add(owned.size());
    }
}
