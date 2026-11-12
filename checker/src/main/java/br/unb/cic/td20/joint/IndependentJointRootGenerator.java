package br.unb.cic.td20.joint;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.model.CyclicTargetPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Independent rank-placement regeneration of oriented or totally unoriented joint residuals.
 * Every forbidden predicate is derived by enumerating arbitrary supports in
 * both orientations, including every possible second support. Partial target
 * cycles and twisted canonical comparisons reject only irrevocable prefixes.
 */
public final class IndependentJointRootGenerator {
    private static final Runnable NO_CANCELLATION = () -> { };
    private IndependentJointRootGenerator() { }
    public static void generateResiduals(final int[] partition, final Consumer<CyclicTargetPair> consumer) {
        new Enumeration(partition, NO_CANCELLATION, Objects.requireNonNull(consumer)).run();
    }
    public static void generateUnorientedResiduals(final int[] partition, final Consumer<CyclicTargetPair> consumer) {
        new Enumeration(partition, NO_CANCELLATION, Objects.requireNonNull(consumer), true).run();
    }
    public static void generateUnorientedResidualsConcurrently(final int[] partition, final int workers,
                                                               final Runnable cancellationCheck,
                                                               final Consumer<CyclicTargetPair> consumer) {
        generateConcurrently(partition, workers, cancellationCheck, Objects.requireNonNull(consumer), true);
    }
    /** Generates disjoint rank-placement prefix subtrees on every requested CPU. */
    public static void generateResidualsConcurrently(final int[] partition, final int workers,
                                                     final Runnable cancellationCheck,
                                                     final Consumer<CyclicTargetPair> consumer) {
        generateConcurrently(partition, workers, cancellationCheck, Objects.requireNonNull(consumer), false);
    }
    /** Counts the same accepted leaves without decoding or retaining representatives. */
    public static long countUnorientedResiduals(final int[] partition) {
        final var enumeration = new Enumeration(partition, NO_CANCELLATION, null, true);
        enumeration.run();
        return enumeration.count;
    }
    public static long countUnorientedResidualsConcurrently(final int[] partition, final int workers,
                                                            final Runnable cancellationCheck) {
        return generateConcurrently(partition, workers, cancellationCheck, null, true);
    }
    private static long generateConcurrently(final int[] partition, final int workers,
                                             final Runnable cancellationCheck,
                                             final Consumer<CyclicTargetPair> consumer, final boolean unorientedOnly) {
        if (workers < 1) throw new IllegalArgumentException("Positive CPU worker count required");
        Objects.requireNonNull(cancellationCheck);
        final var failure = new AtomicReference<Throwable>();
        final Runnable cancel = () -> {
            final var error = failure.get();
            if (error != null) throw propagate(error);
            cancellationCheck.run();
        };
        final var template = new Enumeration(partition, cancel, consumer, unorientedOnly);
        final var pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(256, workers * 16)),
                Thread.ofPlatform().daemon(true).name("independent-joint-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        final List<Future<Long>> jobs = new ArrayList<>();
        try {
            // Depth five yields tens of thousands of disjoint N=21 subtrees, avoiding
            // the severe tail imbalance of the much larger shallow branches.
            template.frontierDepth = Math.min(5, template.n - 1);
            template.frontier = branch -> jobs.add(pool.submit(() -> {
                try {
                    final var enumeration = new Enumeration(template, branch);
                    enumeration.checkCancellation();
                    enumeration.place(branch.rank(), branch.used());
                    enumeration.checkCancellation();
                    return enumeration.count;
                } catch (final RuntimeException | Error error) {
                    failure.compareAndSet(null, error);
                    throw error;
                }
            }));
            template.run();
            long count = template.count;
            for (final var job : jobs) count = Math.addExact(count, job.get());
            cancel.run();
            return count;
        } catch (final ExecutionException error) {
            throw propagate(failure.get() == null ? error.getCause() : failure.get());
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Independent joint generation interrupted");
        } finally {
            pool.shutdownNow();
            boolean interrupted = Thread.interrupted();
            for (;;) {
                try { if (pool.awaitTermination(1, TimeUnit.SECONDS)) break; }
                catch (final InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    public static void generateResidualsConcurrently(final int[] partition,
                                                     final Runnable cancellationCheck,
                                                     final Consumer<CyclicTargetPair> consumer) {
        generateResidualsConcurrently(partition, br.unb.cic.td20.runtime.CheckerParallelism.workers(),
                cancellationCheck, consumer);
    }
    private static RuntimeException propagate(final Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) return error;
        return new IllegalStateException("Independent joint generation failed", failure);
    }
    private record Triple(int a, int b, int c) {
        int depth() { return Math.max(a, Math.max(b, c)); }
    }
    private record Rule(Triple first, List<Triple> seconds) { }
    private record Branch(int rank, int used, int[] position, int[] image, int[] inverse,
                          int[] target, int edges) { }
    private static final class Enumeration {
        final boolean unorientedOnly;
        final int n;
        final int[] parts, algebraic, predecessor, cycleOrigin, cycleLength, equalOrigin;
        final int[] position, image, inverse, target;
        final List<Triple>[] immediate;
        final List<Rule>[] joint;
        final byte[] orders, transformed;
        final Runnable cancellationCheck;
        final Consumer<CyclicTargetPair> consumer;
        int edges;
        long count;
        int frontierDepth;
        Consumer<Branch> frontier;
        @SuppressWarnings("unchecked")
        Enumeration(final int[] partition, final Runnable cancellationCheck,
                    final Consumer<CyclicTargetPair> consumer) {
            this(partition, cancellationCheck, consumer, false);
        }
        Enumeration(final int[] partition, final Runnable cancellationCheck,
                    final Consumer<CyclicTargetPair> consumer, final boolean unorientedOnly) {
            this.unorientedOnly = unorientedOnly;
            this.parts = partition.clone(); Arrays.sort(parts);
            for (int i = 0; i < parts.length / 2; i++) {
                final int value = parts[i]; parts[i] = parts[parts.length - 1 - i]; parts[parts.length - 1 - i] = value;
            }
            n = Arrays.stream(parts).sum();
            if (n < 3 || n > 21 || parts.length == 0 || parts[parts.length - 1] < 2
                    || Arrays.stream(parts).filter(x -> x % 2 == 0).count() % 2 != 0)
                throw new IllegalArgumentException("Expected an even fixed-point-free partition of 3..21 symbols");
            if (unorientedOnly) {
                // Keep the largest-cycle anchor. Placing the other even-length
                // cycles first makes cross-cycle joint rules available sooner.
                int next = 1;
                for (int i = 1; i < parts.length; i++) if (parts[i] % 2 == 0) {
                    final int value = parts[i];
                    System.arraycopy(parts, next, parts, next + 1, i - next);
                    parts[next++] = value;
                }
            }
            this.cancellationCheck = cancellationCheck; this.consumer = consumer;
            algebraic = new int[n]; predecessor = new int[n]; cycleOrigin = new int[n]; cycleLength = new int[n];
            equalOrigin = new int[n]; Arrays.fill(equalOrigin, -1);
            position = new int[n]; image = new int[n]; inverse = new int[n]; target = new int[n];
            Arrays.fill(image, -1); Arrays.fill(inverse, -1); Arrays.fill(target, -1);
            immediate = new List[n]; joint = new List[n];
            for (int i = 0; i < n; i++) { immediate[i] = new ArrayList<>(); joint[i] = new ArrayList<>(); }
            int origin = 0;
            final int[] precedingByLength = new int[n + 1];
            Arrays.fill(precedingByLength, -1);
            for (final int length : parts) {
                equalOrigin[origin] = precedingByLength[length];
                for (int r = 0; r < length; r++) {
                    algebraic[origin + r] = origin + (r + 1) % length;
                    cycleOrigin[origin + r] = origin; cycleLength[origin + r] = length;
                }
                precedingByLength[length] = origin;
                origin += length;
            }
            for (int i = 0; i < n; i++) predecessor[algebraic[i]] = i;
            final var supports = new ArrayList<Triple>();
            for (int a = 0; a < n - 2; a++) for (int b = a + 1; b < n - 1; b++)
                for (int c = b + 1; c < n; c++) { supports.add(new Triple(a,b,c)); supports.add(new Triple(a,c,b)); }
            final int odd = JointMoves.odd(algebraic);
            for (final var first : supports) {
                final int gain = JointMoves.gain(algebraic, first.a, first.b, first.c);
                if (gain >= 2) immediate[first.depth()].add(first);
                if (gain != 1) continue;
                final int[] q = JointMoves.apply(algebraic, first.a, first.b, first.c);
                if (JointMoves.odd(q) != odd + 2) continue;
                final List<Triple>[] byDepth = new List[n];
                for (int d = 0; d < n; d++) byDepth[d] = new ArrayList<>();
                for (final var second : supports) {
                    if (q[second.a] == second.a || q[second.b] == second.b || q[second.c] == second.c) continue;
                    if (JointMoves.gain(q, second.a, second.b, second.c) >= 2)
                        byDepth[Math.max(first.depth(), second.depth())].add(second);
                }
                for (int d = 0; d < n; d++) if (!byDepth[d].isEmpty()) joint[d].add(new Rule(first, List.copyOf(byDepth[d])));
            }
            orders = new byte[n * n * n]; transformed = new byte[n * n * n * n];
            for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int c = 0; c < n; c++) {
                final int key = triple(a,b,c);
                if (a == b || b == c || c == a || Math.floorMod(b-a,n) >= Math.floorMod(c-a,n)) continue;
                orders[key] = 1;
                // Build the actual current-cycle writing after the transposition.
                // Rotate to a, then swap the first two cut segments.
                final int ab = Math.floorMod(b-a,n), ac = Math.floorMod(c-a,n);
                final int[] writing = new int[n];
                int offset = 0;
                for (int i = ab; i < ac; i++) writing[offset++] = (a+i)%n;
                for (int i = 0; i < ab; i++) writing[offset++] = (a+i)%n;
                for (int i = ac; i < n; i++) writing[offset++] = (a+i)%n;
                for (int i = 0; i < n; i++) transformed[key*n + writing[i]] = (byte)i;
            }
        }
        Enumeration(final Enumeration template, final Branch branch) {
            unorientedOnly = template.unorientedOnly;
            n = template.n; parts = template.parts; algebraic = template.algebraic;
            predecessor = template.predecessor; cycleOrigin = template.cycleOrigin;
            cycleLength = template.cycleLength; equalOrigin = template.equalOrigin;
            immediate = template.immediate; joint = template.joint; orders = template.orders;
            transformed = template.transformed; cancellationCheck = template.cancellationCheck;
            consumer = template.consumer;
            position = branch.position().clone(); image = branch.image().clone();
            inverse = branch.inverse().clone(); target = branch.target().clone();
            edges = branch.edges();
        }
        int triple(final int a, final int b, final int c) { return (a*n+b)*n+c; }
        boolean ordered(final Triple t) { return orders[triple(position[t.a], position[t.b], position[t.c])] != 0; }
        boolean predicates(final int depth) {
            if (unorientedOnly && depth > cycleOrigin[depth] + 1) {
                final int start = cycleOrigin[depth];
                final int[] support = Arrays.copyOfRange(position, start, depth + 1);
                Arrays.sort(support);
                for (int rank = start; rank <= depth; rank++) {
                    final int at = Arrays.binarySearch(support, position[rank]);
                    final int nextAssigned = rank == depth ? start : rank + 1;
                    if (position[nextAssigned] != support[(at + support.length - 1) % support.length]) return false;
                }
            }
            for (final var first : immediate[depth]) if (ordered(first)) return false;
            for (final var rule : joint[depth]) {
                final var first = rule.first;
                final int key = triple(position[first.a], position[first.b], position[first.c]);
                if (orders[key] == 0) continue;
                final int offset = key*n;
                for (final var second : rule.seconds)
                    if (orders[triple(transformed[offset+position[second.a]], transformed[offset+position[second.b]],
                            transformed[offset+position[second.c]])] != 0) return false;
            }
            return true;
        }
        void run() { position[0] = 0; place(1,1); }
        void place(final int rank, final int used) {
            checkCancellation();
            if (frontier != null && rank == frontierDepth) {
                frontier.accept(new Branch(rank, used, position.clone(), image.clone(), inverse.clone(),
                        target.clone(), edges));
                return;
            }
            if (rank == n) {
                if (JointMoves.totallyUnoriented(image, JointMoves.identity(n)) != unorientedOnly) return;
                count = Math.incrementExact(count);
                if (consumer == null) return;
                final byte[] encoded = new byte[(5*n+7)/8];
                int bit = 0;
                for (final int value : image) for (int shift = 4; shift >= 0; shift--, bit++)
                    encoded[bit/8] |= (byte)(((value>>>shift)&1) << (7-bit%8));
                consumer.accept(CertificateCodec.pairForKey(encoded,n).canonicalRepresentative());
                return;
            }
            final int minimum = cycleOrigin[rank] != rank ? position[cycleOrigin[rank]]+1
                    : equalOrigin[rank] >= 0 ? position[equalOrigin[rank]]+1 : 1;
            for (int value = minimum; value < n; value++) {
                if ((used & (1<<value)) != 0) continue;
                position[rank] = value;
                final boolean firstEdge = algebraic[rank] <= rank;
                final boolean secondEdge = predecessor[rank] < rank;
                boolean okay = true, secondAdded = false;
                if (firstEdge) okay = addEdge(rank);
                if (okay && secondEdge) { okay = addEdge(predecessor[rank]); secondAdded = true; }
                if (okay && canonicalPrefix(rank) && predicates(rank)) place(rank+1, used|(1<<value));
                if (secondAdded) removeEdge(predecessor[rank]);
                if (firstEdge) removeEdge(rank);
            }
        }
        void checkCancellation() {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("Interrupted root regeneration");
            cancellationCheck.run();
        }
        boolean addEdge(final int rank) {
            final int from = position[rank], to = position[algebraic[rank]];
            image[from] = to; inverse[to] = from;
            final int source = (from+n-1)%n;
            target[source] = to; edges++;
            int cursor = to;
            while (cursor != source && target[cursor] >= 0) cursor = target[cursor];
            return cursor != source || edges == n;
        }
        void removeEdge(final int rank) {
            final int from = position[rank], to = position[algebraic[rank]];
            image[from] = -1; inverse[to] = -1; target[(from+n-1)%n] = -1; edges--;
        }
        boolean canonicalPrefix(final int depth) {
            for (int rank = 0; rank <= depth; rank++) {
                if (cycleLength[rank] != parts[0]) continue;
                final int anchor = position[rank];
                for (int reflection = 0; reflection < 2; reflection++) {
                    for (int i = 0; i < n; i++) {
                        if (image[i] < 0) break;
                        final int source = reflection == 0 ? (i+anchor)%n : Math.floorMod(anchor-i,n);
                        final int mapped = reflection == 0 ? image[source] : inverse[source];
                        if (mapped < 0) break;
                        final int value = reflection == 0 ? Math.floorMod(mapped-anchor,n) : Math.floorMod(anchor-mapped,n);
                        if (value < image[i]) return false;
                        if (value > image[i]) break;
                    }
                }
            }
            return true;
        }
    }
}
