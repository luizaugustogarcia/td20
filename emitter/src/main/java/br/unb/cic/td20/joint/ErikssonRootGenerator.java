package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Exhaustive active-route root generation with bounded parallel prefix scheduling.
 */
public final class ErikssonRootGenerator {

    private ErikssonRootGenerator() {
    }

    public record Counts(long unoriented, long oriented, long supportCleared, long prefixNodes, long realizableLeaves) {

        public long direct() {
            return Math.subtractExact(Math.addExact(unoriented, oriented), supportCleared);
        }

        public Counts plus(final Counts other) {
            return new Counts(Math.addExact(unoriented, other.unoriented),
                    Math.addExact(oriented, other.oriented),
                    Math.addExact(supportCleared, other.supportCleared),
                    Math.addExact(prefixNodes, other.prefixNodes),
                    Math.addExact(realizableLeaves, other.realizableLeaves));
        }
    }

    private record Move(int a, int b, int c) {

        int last() {
            return Math.max(a, Math.max(b, c));
        }
    }

    private record Pair(Move first, Move second) {
    }

    private record Group(Move first, Move second, Move[] thirds) {
    }

    private record Branch(int depth, int used, int[] position, int[] target, int[] partialInverse, int targetEdges) {
    }

    public static Counts count(final int[] partition, final int workers, final LongAdder progress) throws Exception {
        return generateConcurrently(partition, workers, progress, null, () -> {
        });
    }

    public static Counts generate(final int[] partition,
            final int workers,
            final Runnable cancellation,
            final Consumer<CyclicTargetPair> output) throws Exception {
        return generate(partition, workers, new LongAdder(), cancellation, output);
    }

    public static Counts generate(final int[] partition,
            final int workers,
            final LongAdder progress,
            final Runnable cancellation,
            final Consumer<CyclicTargetPair> output) throws Exception {
        return generateConcurrently(partition,
                workers,
                progress,
                images -> output.accept(decode(images)),
                cancellation);
    }

    /**
     * Production output: an owned compact state, without disjoint-cycle construction.
     */
    public static Counts generatePrimitive(final int[] partition,
            final int workers,
            final Runnable cancellation,
            final Consumer<PrimitivePair> output) throws Exception {
        return generatePrimitive(partition, workers, new LongAdder(), cancellation, output);
    }

    public static Counts generatePrimitive(final int[] partition,
            final int workers,
            final LongAdder progress,
            final Runnable cancellation,
            final Consumer<PrimitivePair> output) throws Exception {
        return generateConcurrently(partition,
                workers,
                progress,
                images -> {
                    val successors = new byte[images.length];
                    for (int x = 0; x < images.length; x++) {
                        successors[x] = (byte) images[x];
                    }

                    // This generator minimizes normalized image vectors, whereas the
                    // certificate convention minimizes auxiliary encoding words.
                    output.accept(PrimitivePair.fromNormalizedImages(successors, false));
                },
                cancellation);
    }

    private static Counts generateConcurrently(final int[] partition,
            final int workers,
            final LongAdder progress,
            final Consumer<int[]> output,
            final Runnable cancellation) throws Exception {
        if (workers < 1) {
            throw new IllegalArgumentException("Positive worker count required");
        }

        val template = new Generator(partition, output, progress);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        template.cancellation = () -> {
            if (failure.get() != null) {
                throw new CancellationException("Root generation failed: " + failure.get());
            }

            cancellation.run();
        };

        try (val pool = new ThreadPoolExecutor(workers,
                workers,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(256, workers * 16)),
                Thread.ofPlatform().daemon(true).name("eriksson-generation-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy())) {
            final List<Future<Counts>> jobs = new ArrayList<>();
            template.frontierDepth = Math.min(5, template.n - 1);
            template.frontier = branch -> jobs.add(pool.submit(() -> {
                try {
                    val generator = new Generator(template, branch);
                    generator.visit(branch.depth, branch.used);

                    return generator.result();
                } catch (final RuntimeException | Error error) {
                    failure.compareAndSet(null, error);
                    throw error;
                }
            }));
            template.position[0] = 0;
            template.visit(1, 1);

            Counts result = template.result();

            try {
                for (val job : jobs) {
                    result = result.plus(job.get());
                }
            } catch (final Exception | Error error) {
                jobs.forEach(job -> job.cancel(true));
                pool.shutdownNow();
                throw error;
            }

            return result;
        }
    }

    private static List<Move> fast(final int[] omega) {
        final List<Move> result = new ArrayList<>();
        for (int a = 0; a < omega.length; a++) {
            final int b = omega[a], c = omega[b];
            if (a == b || a == c || b == c || omega[c] == a && (a > b || a > c)) {
                continue;
            }

            result.add(new Move(a, b, c));
        }

        return result;
    }

    private static List<Move> firstCandidates(final int[] omega) {
        final List<Move> result = new ArrayList<>();
        for (int a = 0; a < omega.length - 2; a++) {
            for (int b = a + 1; b < omega.length - 1; b++) {
                for (int c = b + 1; c < omega.length; c++) {
                    for (int direction = 0; direction < 2; direction++) {
                        final Move move = direction == 0 ? new Move(a, b, c) : new Move(a, c, b);
                        if (gain(omega, move) < 2) {
                            result.add(move);
                        }
                    }
                }
            }
        }

        return result;
    }

    private static final class Generator {

        final int n;
        final int[] parts, omega, inverse, cycleStart, cycleLength, previousEqual;
        final int[] position, target, partialInverse, images, inverseImages;
        final boolean[] anchors;
        final byte[] order, after;
        final Move[][] immediate;
        final Group[][] groups;
        final Consumer<int[]> consumer;
        final LongAdder progress;
        Runnable cancellation = () -> {
        };
        int targetEdges, frontierDepth;
        long nodes, leaves, unoriented, oriented, cleared;
        Consumer<Branch> frontier;

        Generator(final int[] partition, final Consumer<int[]> consumer, final LongAdder progress) {
            parts = partition.clone();
            Arrays.sort(parts);
            for (int i = 0; i < parts.length / 2; i++) {
                final int value = parts[i];
                parts[i] = parts[parts.length - 1 - i];
                parts[parts.length - 1 - i] = value;
            }

            n = Arrays.stream(parts).sum();
            if (parts.length == 0
                    || parts[parts.length - 1] < 2
                    || n < 3
                    || n > KnownDiameters.ROOT_SYMBOLS
                    || Arrays.stream(parts).filter(k -> k % 2 == 0).count() % 2 != 0) {
                throw new IllegalArgumentException("Expected a fixed-point-free even root partition");
            }

            this.consumer = consumer;
            this.progress = progress;
            omega = new int[n];
            inverse = new int[n];
            cycleStart = new int[n];
            cycleLength = new int[n];
            previousEqual = new int[n];
            Arrays.fill(previousEqual, -1);

            int start = 0, previous = -1, previousLength = -1;
            for (final int length : parts) {
                if (length == previousLength) {
                    previousEqual[start] = previous;
                }
                for (int j = 0; j < length; j++) {
                    omega[start + j] = start + (j + 1) % length;
                    cycleStart[start + j] = start;
                    cycleLength[start + j] = length;
                }

                previous = start;
                previousLength = length;
                start += length;
            }
            for (int x = 0; x < n; x++) {
                inverse[omega[x]] = x;
            }

            position = new int[n];
            target = new int[n];
            partialInverse = new int[n];
            Arrays.fill(target, -1);
            Arrays.fill(partialInverse, -1);
            images = new int[n];
            inverseImages = new int[n];
            anchors = new boolean[n];
            order = new byte[n * n * n];
            after = new byte[n * n * n * n];
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    for (int c = 0; c < n; c++) {
                        final int index = index(a, b, c);
                        if (a == b || a == c || b == c || !ordered(a, b, c, n)) {
                            continue;
                        }

                        order[index] = 1;
                        for (int x = 0; x < n; x++) {
                            int d = (x - a + n) % n, db = (b - a + n) % n, dc = (c - a + n) % n;
                            after[index * n + x] = (byte) (d < db ? d : d >= dc ? db + d - dc : n - dc + d);
                        }
                    }
                }
            }

            final List<List<Move>> fastLists = new ArrayList<>();
            final List<LinkedHashMap<Pair, List<Move>>> grouped = new ArrayList<>();
            for (int d = 0; d < n; d++) {
                fastLists.add(new ArrayList<>());
                grouped.add(new LinkedHashMap<>());
            }
            for (final Move move : fast(omega)) {
                fastLists.get(move.last()).add(move);
            }
            for (final Move first : firstCandidates(omega)) {
                final int[] q = apply(omega, first);
                final int gain1 = gain(omega, first);
                for (final Move second : fast(q)) {
                    val pair = new Pair(first, second);
                    final int gain2 = gain(q, second), last = Math.max(first.last(), second.last());
                    if (gain1 + gain2 < 3) {
                        continue;
                    }
                    if (gain2 == 3 && gain1 == 1) {
                        grouped.get(last).computeIfAbsent(pair, ignored -> new ArrayList<>());
                        continue;
                    }

                    final int[] r = apply(q, second);
                    final List<Move> thirds = fast(r);
                    for (final Move third : thirds) {
                        grouped.get(Math.max(last, third.last())).computeIfAbsent(pair, ignored -> new ArrayList<>()).add(third);
                    }
                }
            }

            immediate = new Move[n][];
            groups = new Group[n][];
            for (int d = 0; d < n; d++) {
                immediate[d] = fastLists.get(d).toArray(Move[]::new);
                groups[d] = grouped.get(d).entrySet().stream().map(entry -> new Group(entry.getKey().first, entry.getKey().second, entry.getValue().toArray(Move[]::new))).toArray(Group[]::new);
            }
        }

        Generator(final Generator template, final Branch branch) {
            cancellation = template.cancellation;
            n = template.n;
            parts = template.parts;
            omega = template.omega;
            inverse = template.inverse;
            cycleStart = template.cycleStart;
            cycleLength = template.cycleLength;
            previousEqual = template.previousEqual;
            order = template.order;
            after = template.after;
            immediate = template.immediate;
            groups = template.groups;
            consumer = template.consumer;
            progress = template.progress;
            position = branch.position.clone();
            target = branch.target.clone();
            partialInverse = branch.partialInverse.clone();
            targetEdges = branch.targetEdges;
            images = new int[n];
            inverseImages = new int[n];
            anchors = new boolean[n];
        }

        Counts result() {
            return new Counts(unoriented, oriented, cleared, nodes, leaves);
        }

        int index(final int a, final int b, final int c) {
            return (a * n + b) * n + c;
        }

        int offset(final Move move) {
            return index(position[move.a], position[move.b], position[move.c]) * n;
        }

        int mappedIndex(final Move move, final int offset) {
            return index(after[offset + position[move.a]], after[offset + position[move.b]], after[offset + position[move.c]]);
        }

        boolean constraints(final int depth) {
            for (final Move move : immediate[depth]) {
                if (order[offset(move) / n] != 0) {
                    return false;
                }
            }
            for (final Group group : groups[depth]) {
                final int first = offset(group.first);
                if (order[first / n] == 0) {
                    continue;
                }

                final int second = mappedIndex(group.second, first);
                if (order[second] == 0) {
                    continue;
                }
                // A (1,3) diameter closure.
                if (group.thirds.length == 0) {
                    return false;
                }

                final int next = second * n;
                for (final Move third : group.thirds) {
                    if (order[index(after[next + after[first + position[third.a]]],
                            after[next + after[first + position[third.b]]],
                            after[next + after[first + position[third.c]]])] != 0) {
                        return false;
                    }
                }
            }

            return true;
        }

        // Known target edges form disjoint directed paths until the full
        // target cycle is completed. If a known path reaches b from a without
        // c, every completion has cyclic order (a,b,c). This can certify an
        // immediate target-side gain >=2 before the whole root is generated.
        boolean forcedTargetFast(final int depth) {
            for (int x = 0; x <= depth; x++) {
                final int y = inverse[x], z = inverse[y];
                if (y > depth || z > depth || x == y || x == z || y == z || inverse[z] == x && (x > y || x > z)) {
                    continue;
                }

                final int a = position[x], b = position[y], c = position[z];
                if (forcedBefore(a, b, c) || forcedBefore(b, c, a) || forcedBefore(c, a, b)) {
                    return true;
                }
            }

            return false;
        }

        boolean forcedBefore(final int a, final int b, final int c) {
            int next = target[a];
            while (next >= 0 && next != a && next != b && next != c) {
                next = target[next];
            }

            return next == b;
        }

        void visit(final int depth, final int used) {
            if (frontier != null && depth == frontierDepth) {
                cancellation.run();
                frontier.accept(new Branch(depth, used, position.clone(), target.clone(), partialInverse.clone(), targetEdges));

                return;
            }
            if ((++nodes & 1023) == 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Root generation interrupted");
                }

                progress.add(1024);
                cancellation.run();
            }
            if (depth == n) {
                leaf();
                return;
            }

            int minimum = 1;
            if (cycleStart[depth] != depth) {
                minimum = position[cycleStart[depth]] + 1;
            } else if (previousEqual[depth] >= 0) {
                minimum = position[previousEqual[depth]] + 1;
            }
            for (int x = minimum; x < n; x++) {
                if ((used & (1 << x)) != 0) {
                    continue;
                }

                position[depth] = x;

                boolean edgeA = omega[depth] <= depth, edgeB = inverse[depth] < depth, okay = true, addedB = false;
                if (edgeA) {
                    okay = addTarget(depth);
                }
                if (okay && edgeB) {
                    okay = addTarget(inverse[depth]);
                    addedB = true;
                }
                if (okay && canonicalPrefix(depth) && !forcedTargetFast(depth) && constraints(depth)) {
                    visit(depth + 1, used | (1 << x));
                }
                if (addedB) {
                    removeTarget(inverse[depth]);
                }
                if (edgeA) {
                    removeTarget(depth);
                }
            }
        }

        boolean addTarget(final int rank) {
            final int source = (position[rank] + n - 1) % n, destination = position[omega[rank]];
            target[source] = destination;
            partialInverse[destination] = position[rank];
            targetEdges++;

            int x = destination;
            while (x != source && target[x] >= 0) {
                x = target[x];
            }

            return x != source || targetEdges == n;
        }

        void removeTarget(final int rank) {
            partialInverse[position[omega[rank]]] = -1;
            target[(position[rank] + n - 1) % n] = -1;
            targetEdges--;
        }

        boolean canonicalPrefix(final int depth) {
            for (int rank = 0; rank <= depth; rank++) {
                if (cycleLength[rank] != parts[0]) {
                    continue;
                }

                final int anchor = position[rank];
                for (int reflection = 0; reflection < 2; reflection++) {
                    if (reflection == 0 && anchor == 0) {
                        continue;
                    }
                    for (int j = 0; j < n; j++) {
                        final int original = target[(j + n - 1) % n];
                        if (original < 0) {
                            break;
                        }

                        final int image = reflection == 1 ? partialInverse[(anchor - j + n) % n] : target[(j + anchor + n - 1) % n];
                        if (image < 0) {
                            break;
                        }

                        final int value = reflection == 1 ? (anchor - image + n) % n : (image - anchor + n) % n;
                        if (value < original) {
                            return false;
                        }
                        if (value > original) {
                            break;
                        }
                    }
                }
            }

            return true;
        }

        boolean canonical() {
            for (int x = 0; x < n; x++) {
                inverseImages[images[x]] = x;
            }
            for (int anchor = 0; anchor < n; anchor++) {
                if (!anchors[anchor]) {
                    continue;
                }
                for (int reflection = 0; reflection < 2; reflection++) {
                    for (int j = 0; j < n; j++) {
                        final int value = reflection == 1 ? (anchor - inverseImages[(anchor - j + n) % n] + n) % n : (images[(j + anchor) % n] - anchor + n) % n;
                        if (value < images[j]) {
                            return false;
                        }
                        if (value > images[j]) {
                            break;
                        }
                    }
                }
            }

            return true;
        }

        void leaf() {
            leaves++;
            for (int rank = 0; rank < n; rank++) {
                images[position[rank]] = position[omega[rank]];
                anchors[position[rank]] = cycleLength[rank] == parts[0];
            }
            if (!canonical()) {
                return;
            }

            val beta = new int[n];
            for (int x = 0; x < n; x++) {
                beta[x] = x;
            }
            if (ErikssonThirdMoveAnalysis.closesOrHasPrefixUnchecked(images, beta, true)) {
                return;
            }

            final boolean totally = unoriented(images);
            if (totally) {
                unoriented++;
            } else {
                oriented++;
            }
            if (!totally && n == KnownDiameters.ROOT_SYMBOLS && SupportClearing.closingLengthCanonicalUnchecked(images) != 0) {
                cleared++;
            }
            // All internal sinks copy into owned output before the workspace is reused.
            if (consumer != null) {
                consumer.accept(images);
            }
        }
    }

    private static int gain(final int[] omega, final Move move) {
        return (omega[move.a] == move.b ? 1 : 0) + (omega[move.b] == move.c ? 1 : 0) + (omega[move.c] == move.a ? 1 : 0);
    }

    private static int[] apply(final int[] omega, final Move move) {
        final int[] q = omega.clone();
        q[move.a] = omega[move.c];
        q[move.b] = omega[move.a];
        q[move.c] = omega[move.b];

        return q;
    }

    private static boolean ordered(final int a, final int b, final int c, final int n) {
        return (b - a + n) % n < (c - a + n) % n;
    }

    private static boolean unoriented(final int[] omega) {
        int seen = 0;
        for (int x = 0; x < omega.length; x++) {
            if ((seen & (1 << x)) == 0) {
                int length = 0, increases = 0, y = x;
                do {
                    seen |= 1 << y;
                    length++;
                    if (omega[y] > y) {
                        increases++;
                    }

                    y = omega[y];
                } while (y != x);
                if (length >= 3 && increases != 1) {
                    return false;
                }
            }
        }

        return true;
    }

    private static CyclicTargetPair decode(final int[] images) {
        val omega = new MulticyclePermutation();
        val seen = new boolean[images.length];
        for (int x = 0; x < images.length; x++) {
            if (!seen[x]) {
                val symbols = new int[images.length];
                int size = 0, y = x;
                do {
                    symbols[size++] = y;
                    seen[y] = true;
                    y = images[y];
                } while (y != x);

                omega.add(Cycle.of(Arrays.copyOf(symbols, size)));
            }
        }

        val beta = new int[images.length];
        Arrays.setAll(beta, i -> i);

        return CyclicTargetPair.ofOwned(omega, Cycle.of(beta));
    }
}
