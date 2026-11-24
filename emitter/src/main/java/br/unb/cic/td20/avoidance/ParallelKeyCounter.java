package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.runtime.BoundedTasks;
import lombok.val;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Exact logical-key inventory over disjoint snapshot ranges; keys remain opaque.
 */
final class ParallelKeyCounter {

    private static final Logger log = LoggerFactory.getLogger(ParallelKeyCounter.class);

    private record Interval(byte[] start, byte[] end, long estimatedBytes) {
    }

    private ParallelKeyCounter() {
    }

    static long count(final RocksDB database, final int workers, final int rangesPerWorker, final long expected, final String phase) {
        val snapshot = database.getSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("Cannot acquire reconstruction inventory snapshot");
        }

        try {
            log.info("{}: planning disjoint key ranges; configuredWorkers={}, rangesPerWorker={}",
                    phase,
                    workers,
                    rangesPerWorker);

            val ranges = plan(database, snapshot, Math.multiplyExact(workers, rangesPerWorker));
            final int effectiveWorkers = Math.min(workers, Math.max(1, ranges.size()));
            val completed = new LongAdder();
            val finishedRanges = new AtomicLong();
            val result = new AtomicLong();
            log.info("{}: workers={}, ranges={}, snapshotSequence={}",
                    phase,
                    effectiveWorkers,
                    ranges.size(),
                    snapshot.getSequenceNumber());

            try (val progress = new ReconstructionProgress(phase,
                    expected,
                    completed::sum,
                    () -> "workers=" + effectiveWorkers + ", rangesCompleted=" + finishedRanges.get() + "/" + ranges.size());
                val tasks = new BoundedTasks(effectiveWorkers, "key-count-")) {
                for (val range : ranges) {
                    tasks.submit(() -> {
                        final long count = countRange(database, snapshot, range, completed);
                        result.updateAndGet(value -> Math.addExact(value, count));
                        finishedRanges.incrementAndGet();
                    });
                }
            }

            final long count = result.get();
            if (finishedRanges.get() != ranges.size() || count != completed.sum()) {
                throw new IllegalStateException("Incomplete reconstruction key-range count");
            }
            if (expected >= 0 && count != expected) {
                throw new IllegalStateException("Reconstruction scan missed states: expected " + expected + ", counted " + count);
            }

            log.info("{} complete: uniqueKeys={}, rangesCompleted={}/{}", phase, count, finishedRanges.get(), ranges.size());

            return count;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot count reconstruction state keys", failure);
        } finally {
            database.releaseSnapshot(snapshot);
        }
    }

    private static List<Interval> plan(final RocksDB database, final Snapshot snapshot, final int target) throws RocksDBException {
        byte[] last;

        try (val reads = new ReadOptions().setSnapshot(snapshot).setFillCache(false);
            val iterator = database.newIterator(reads)) {
            iterator.seekToLast();
            if (!iterator.isValid()) {
                iterator.status();
                return List.of();
            }

            last = iterator.key();
            iterator.status();
        }

        // Appending a zero byte is strictly above the largest snapshot key.
        val end = Arrays.copyOf(last, last.length + 1);
        val pending = new PriorityQueue<Interval>(Comparator.<Interval>comparingLong(Interval::estimatedBytes).reversed().thenComparing(Interval::start, Arrays::compareUnsigned));
        pending.add(new Interval(new byte[0], end, estimate(database, new byte[0], end)));

        val terminal = new ArrayList<Interval>();
        while (pending.size() + terminal.size() < target && !pending.isEmpty()) {
            val range = pending.remove();
            final byte[] split = split(database, snapshot, range);
            if (split == null) {
                terminal.add(range);
                continue;
            }

            final long[] estimates = estimates(database, range.start(), split, range.end());
            pending.add(new Interval(range.start(), split, estimates[0]));
            pending.add(new Interval(split, range.end(), estimates[1]));
        }

        terminal.addAll(pending);
        terminal.sort(Comparator.comparing(Interval::start, Arrays::compareUnsigned));

        return List.copyOf(terminal);
    }

    /**
     * Only range probes materialize keys; the full counting loop never does.
     */
    private static byte[] split(final RocksDB database, final Snapshot snapshot, final Interval range) throws RocksDBException {
        try (val reads = new ReadOptions().setSnapshot(snapshot).setFillCache(false);
            val iterator = database.newIterator(reads)) {
            iterator.seek(range.start());
            if (!iterator.isValid()) {
                iterator.status();
                return null;
            }

            final byte[] first = iterator.key();
            if (Arrays.compareUnsigned(first, range.end()) >= 0) {
                iterator.status();
                return null;
            }

            iterator.seekForPrev(range.end());
            if (iterator.isValid() && Arrays.equals(iterator.key(), range.end())) {
                iterator.prev();
            }
            if (!iterator.isValid()) {
                throw new IllegalStateException("Missing key-range endpoint");
            }

            final byte[] last = iterator.key();
            iterator.status();
            if (Arrays.compareUnsigned(first, last) >= 0) {
                return null;
            }

            final int prefix = Arrays.mismatch(first, last);
            final byte[] boundary = Arrays.copyOf(last, prefix + 1);
            if (prefix < first.length) {
                boundary[prefix] = (byte) (((first[prefix] & 255) + (last[prefix] & 255) + 1) / 2);
            }
            if (Arrays.compareUnsigned(boundary, range.start()) <= 0 || Arrays.compareUnsigned(boundary, range.end()) >= 0) {
                throw new IllegalStateException("Invalid disjoint key-range boundary");
            }

            return boundary;
        }
    }

    private static long estimate(final RocksDB database, final byte[] start, final byte[] end) {
        try (val lower = new Slice(start);
            val upper = new Slice(end)) {
            return database.getApproximateSizes(List.of(new Range(lower, upper)), SizeApproximationFlag.INCLUDE_FILES)[0];
        }
    }

    private static long[] estimates(final RocksDB database, final byte[] start, final byte[] middle, final byte[] end) {
        try (val lower = new Slice(start);
            val split = new Slice(middle);
            val upper = new Slice(end)) {
            return database.getApproximateSizes(List.of(new Range(lower, split), new Range(split, upper)), SizeApproximationFlag.INCLUDE_FILES);
        }
    }

    private static long countRange(final RocksDB database, final Snapshot snapshot, final Interval range, final LongAdder completed) {
        long count = 0, pending = 0;

        try (val lower = new Slice(range.start());
            val upper = new Slice(range.end());
            val reads = new ReadOptions().setSnapshot(snapshot).setFillCache(false).setIterateLowerBound(lower).setIterateUpperBound(upper);
            val iterator = database.newIterator(reads)) {
            for (iterator.seek(range.start()); iterator.isValid(); iterator.next()) {
                count = Math.addExact(count, 1);
                if (++pending == 4096) {
                    completed.add(pending);
                    pending = 0;
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Key-range count interrupted");
                    }
                }
            }

            iterator.status();
            completed.add(pending);

            return count;
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot count reconstruction key range", failure);
        }
    }
}
