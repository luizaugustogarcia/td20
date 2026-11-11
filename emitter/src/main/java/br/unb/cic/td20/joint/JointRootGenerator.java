package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Direct constrained generation of every oriented joint residual class.
 *
 * <p>Disjoint algebraic cycles receive consecutive ranks, whose current-cycle
 * positions are assigned recursively. Cycle origins and equal-cycle ordering
 * remove auxiliary label choices. A largest algebraic cycle contains position
 * zero. Twisted dihedral comparisons among these permitted origins select one
 * numeric image-array representative per orbit; this representative need not
 * be the least fixed-content encoding used by certificate keys.</p>
 *
 * <p>Immediate gain-at-least-two moves, terminal gain-three second moves, and
 * gain-two-second/gain-at-least-two-third paths are rejected on safe prefixes.
 * Gain-one second paths, whose legality depends on the intermediate
 * totally-unoriented stop, are replayed exhaustively at full leaves. Forced proper
 * target cycles and strict smaller twisted images are rejected on prefixes.
 * Full leaves pass realizability, orientation and orbit comparisons before
 * emission. No sorting obstruction information is used.</p>
 */
public final class JointRootGenerator {
    private static final Runnable NO_CANCELLATION = () -> { };

    private JointRootGenerator() { }

    public record Counts(long jointResidualClasses, long supportClearingClasses,
                         long orientedClasses, long noQualifyingFirstMoveClasses,
                         long prefixNodes, long realizableLeaves) { }

    public static void generateResiduals(final int[] partition,
                                         final Consumer<CyclicTargetPair> consumer) {
        generateResiduals(partition, NO_CANCELLATION, consumer);
    }

    public static void generateResiduals(final int[] partition,
                                         final Runnable cancellationCheck,
                                         final Consumer<CyclicTargetPair> consumer) {
        new Generator(partition, Objects.requireNonNull(cancellationCheck),
                Objects.requireNonNull(consumer)).run();
    }

    /** Generates disjoint prefix subtrees on every CPU available to the JVM. The sink must be thread-safe. */
    public static Counts generateResidualsConcurrently(final int[] partition,
                                                       final Runnable cancellationCheck,
                                                       final Consumer<CyclicTargetPair> consumer) {
        return generateResidualsConcurrently(partition, Runtime.getRuntime().availableProcessors(),
                cancellationCheck, consumer);
    }

    static Counts generateResidualsConcurrently(final int[] partition, final int workers,
                                                final Runnable cancellationCheck,
                                                final Consumer<CyclicTargetPair> consumer) {
        return auditConcurrently(partition, workers, cancellationCheck, Objects.requireNonNull(consumer)).counts();
    }

    public record AuditCounts(Counts counts, java.util.Map<Integer,Long> clearingByLength,
                              long ignoredUnorientedLeaves) { }

    public static AuditCounts countConcurrently(final int[] partition) {
        return auditConcurrently(partition, Runtime.getRuntime().availableProcessors(), NO_CANCELLATION, null);
    }

    /** Per-partition count mode used by the TD18-style parallel partition coordinator. */
    public static AuditCounts countAudit(final int[] partition) {
        var generator = new Generator(partition, NO_CANCELLATION, null);
        generator.run();
        return generator.audit();
    }

    private static AuditCounts auditConcurrently(final int[] partition, final int workers,
                                                 final Runnable cancellationCheck,
                                                 final Consumer<CyclicTargetPair> consumer) {
        if (workers < 1) throw new IllegalArgumentException("Positive CPU worker count required");
        Objects.requireNonNull(cancellationCheck);
        final var failure = new AtomicReference<Throwable>();
        final Runnable cancel = () -> {
            if (failure.get() != null) throw propagate(failure.get());
            cancellationCheck.run();
        };
        final var template = new Generator(partition, cancel, consumer);
        final var pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256),
                Thread.ofPlatform().daemon(true).name("joint-generation-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        // At depth three there are at most (N-1)(N-2) disjoint jobs, with N <= 21.
        final List<Future<AuditCounts>> jobs = new ArrayList<>();
        try {
            template.frontierDepth = Math.min(3, template.n - 1);
            template.frontier = branch -> jobs.add(pool.submit(() -> {
                try {
                    final var generator = new Generator(template, branch);
                    generator.checkCancellation();
                    generator.visit(branch.depth, branch.used);
                    generator.checkCancellation();
                    return generator.audit();
                } catch (RuntimeException | Error error) {
                    failure.compareAndSet(null, error);
                    throw error;
                }
            }));
            template.checkCancellation();
            template.position[0] = 0;
            template.visit(1, 1);
            AuditCounts result = template.audit();
            for (final var job : jobs) result = addAudit(result, job.get());
            cancel.run();
            return result;
        } catch (ExecutionException error) {
            throw propagate(failure.get() == null ? error.getCause() : failure.get());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Joint generation interrupted");
        } finally {
            pool.shutdownNow();
            boolean interrupted = Thread.interrupted();
            for (;;) {
                try {
                    if (pool.awaitTermination(1, TimeUnit.SECONDS)) break;
                } catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static AuditCounts addAudit(final AuditCounts a, final AuditCounts b) {
        var lengths = new java.util.TreeMap<Integer,Long>(a.clearingByLength());
        b.clearingByLength().forEach((length,count) -> lengths.merge(length,count,Math::addExact));
        return new AuditCounts(add(a.counts(),b.counts()),java.util.Map.copyOf(lengths),
                Math.addExact(a.ignoredUnorientedLeaves(),b.ignoredUnorientedLeaves()));
    }

    private static RuntimeException propagate(final Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) return error;
        return new IllegalStateException("Joint generation failed", failure);
    }

    private static Counts add(final Counts a, final Counts b) {
        return new Counts(Math.addExact(a.jointResidualClasses, b.jointResidualClasses),
                Math.addExact(a.supportClearingClasses, b.supportClearingClasses),
                Math.addExact(a.orientedClasses, b.orientedClasses),
                Math.addExact(a.noQualifyingFirstMoveClasses, b.noQualifyingFirstMoveClasses),
                Math.addExact(a.prefixNodes, b.prefixNodes), Math.addExact(a.realizableLeaves, b.realizableLeaves));
    }

    /** Count mode avoids decoding pair objects and retains no representative set. */
    public static Counts count(final int[] partition) {
        return count(partition, NO_CANCELLATION);
    }

    public static Counts count(final int[] partition, final Runnable cancellationCheck) {
        return new Generator(partition, Objects.requireNonNull(cancellationCheck), null).run();
    }

    public static long countResiduals(final int[] partition) {
        return count(partition).jointResidualClasses();
    }

    private record Triple(int a, int b, int c) { }
    private record Continuation(Triple second, boolean terminal, Triple[] thirds) { }
    private record Rule(Triple first, Continuation[] continuations) { }
    private static final class PendingContinuation {
        private boolean terminal;
        private final List<Triple> thirds = new ArrayList<>();
    }
    private record Branch(int depth, int used, int[] position, int[] target,
                          int[] partialInverse, int targetEdges) { }

    private static final class Generator {
        private final int n;
        private final int[] parts;
        private final int[] omega;
        private final int[] inverse;
        private final int[] position;
        private final int[] cycleStart;
        private final int[] cycleLength;
        private final int[] previousEqual;
        private final int[] target;
        private final int[] partialInverse;
        private final int[] images;
        private final int[] inverseImages;
        private final boolean[] anchors;
        private final Triple[][] fast;
        private final Rule[][] joint;
        private final Triple[] firsts;
        private final byte[] cyclicOrder;
        private final byte[] afterPositions;
        private final Runnable cancellationCheck;
        private final Consumer<CyclicTargetPair> consumer;
        private int targetEdges;
        private long nodes;
        private long leaves;
        private long classes;
        private long cleared;
        private long noQualifying;
        private Consumer<Branch> frontier;
        private int frontierDepth;
        private long ignoredUnoriented;
        private final long[] clearingByLength = new long[4];

        private Generator(final Generator template, final Branch branch) {
            n = template.n; parts = template.parts; omega = template.omega; inverse = template.inverse;
            cycleStart = template.cycleStart; cycleLength = template.cycleLength; previousEqual = template.previousEqual;
            fast = template.fast; joint = template.joint; firsts = template.firsts;
            cyclicOrder = template.cyclicOrder; afterPositions = template.afterPositions;
            cancellationCheck = template.cancellationCheck; consumer = template.consumer;
            position = branch.position.clone(); target = branch.target.clone();
            partialInverse = branch.partialInverse.clone(); targetEdges = branch.targetEdges;
            images = new int[n]; inverseImages = new int[n]; anchors = new boolean[n];
        }

        @SuppressWarnings("unchecked")
        private Generator(final int[] partition, final Runnable cancellationCheck,
                          final Consumer<CyclicTargetPair> consumer) {
            Objects.requireNonNull(partition);
            parts = partition.clone();
            Arrays.sort(parts);
            for (int i = 0; i < parts.length / 2; i++) {
                final int value = parts[i];
                parts[i] = parts[parts.length - 1 - i];
                parts[parts.length - 1 - i] = value;
            }
            int symbols = 0;
            int evenParts = 0;
            for (final int length : parts) {
                if (length < 2 || length > KnownDiameters.ROOT_SYMBOLS) {
                    throw new IllegalArgumentException("Expected a fixed-point-free partition");
                }
                symbols = Math.addExact(symbols, length);
                if (length % 2 == 0) evenParts++;
            }
            n = symbols;
            if (n < 3 || n > KnownDiameters.ROOT_SYMBOLS || evenParts % 2 != 0)
                throw new IllegalArgumentException(
                        "Expected an even fixed-point-free partition of 3 through 21 symbols");
            this.consumer = consumer;
            this.cancellationCheck = cancellationCheck;
            omega = new int[n];
            inverse = new int[n];
            position = new int[n];
            cycleStart = new int[n];
            cycleLength = new int[n];
            previousEqual = new int[n];
            target = new int[n];
            partialInverse = new int[n];
            images = new int[n];
            inverseImages = new int[n];
            anchors = new boolean[n];
            Arrays.fill(previousEqual, -1);
            Arrays.fill(target, -1);
            Arrays.fill(partialInverse, -1);
            int start = 0;
            int previous = -1;
            int previousLength = -1;
            for (final int length : parts) {
                if (length == previousLength) previousEqual[start] = previous;
                for (int j = 0; j < length; j++) {
                    omega[start + j] = start + (j + 1) % length;
                    cycleStart[start + j] = start;
                    cycleLength[start + j] = length;
                }
                previous = start;
                previousLength = length;
                start += length;
            }
            for (int x = 0; x < n; x++) inverse[omega[x]] = x;
            final List<Triple>[] fastLists = new List[n];
            final List<Rule>[] rules = new List[n];
            for (int d = 0; d < n; d++) {
                fastLists[d] = new ArrayList<>();
                rules[d] = new ArrayList<>();
            }
            for (int x = 0; x < n; x++) {
                final int y = omega[x];
                final int z = omega[y];
                if (x == z || omega[z] == x && (x > y || x > z)) continue;
                fastLists[Math.max(x, Math.max(y, z))].add(new Triple(x, y, z));
            }
            final List<Triple> qualifying = new ArrayList<>();
            final List<Triple> supports = new ArrayList<>();
            for (int a = 0; a < n - 2; a++) for (int b = a + 1; b < n - 1; b++)
                for (int c = b + 1; c < n; c++) {
                    supports.add(new Triple(a, b, c));
                    supports.add(new Triple(a, c, b));
                }
            final int odd = oddCycles(omega);
            for (final Triple first : supports) {
                            if (gain(omega, first) != 1) continue;
                            final int[] next = apply(omega, first);
                            if (oddCycles(next) != odd + 2) continue;
                            qualifying.add(first);
                            @SuppressWarnings("unchecked")
                            final LinkedHashMap<Triple, PendingContinuation>[] byDepth = new LinkedHashMap[n];
                            for (int d = 0; d < n; d++) byDepth[d] = new LinkedHashMap<>();
                            for (final Triple second : supports) {
                                if (next[second.a] == second.a || next[second.b] == second.b
                                        || next[second.c] == second.c) continue;
                                final int secondGain = gain(next, second);
                                if (secondGain < 2) continue;
                                final int[] successor = apply(next, second);
                                if (oddCycles(successor) != oddCycles(next) + 2) continue;
                                if (secondGain >= 3) {
                                    final int depth = Math.max(depth(first), depth(second));
                                    byDepth[depth].computeIfAbsent(second,
                                            ignored -> new PendingContinuation()).terminal = true;
                                    continue;
                                }
                                for (int x = 0; x < n; x++) {
                                    final int y = successor[x], z = successor[y];
                                    if (x == y || x == z || y == z
                                            || successor[z] == x && (x > y || x > z)) continue;
                                    final Triple third = new Triple(x, y, z);
                                    final int depth = Math.max(Math.max(depth(first), depth(second)), depth(third));
                                    byDepth[depth].computeIfAbsent(second,
                                            ignored -> new PendingContinuation()).thirds.add(third);
                                }
                            }
                            for (int d = 0; d < n; d++) {
                                if (byDepth[d].isEmpty()) continue;
                                final List<Continuation> continuations = new ArrayList<>();
                                for (final var entry : byDepth[d].entrySet()) {
                                    continuations.add(new Continuation(entry.getKey(),
                                            entry.getValue().terminal,
                                            entry.getValue().thirds.toArray(Triple[]::new)));
                                }
                                rules[d].add(new Rule(first, continuations.toArray(Continuation[]::new)));
                            }
            }
            firsts = qualifying.toArray(Triple[]::new);
            fast = new Triple[n][];
            joint = new Rule[n][];
            for (int d = 0; d < n; d++) {
                fast[d] = fastLists[d].toArray(Triple[]::new);
                joint[d] = rules[d].toArray(Rule[]::new);
            }
            cyclicOrder = new byte[n * n * n];
            afterPositions = new byte[n * n * n * n];
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    for (int c = 0; c < n; c++) {
                        final int index = index(a, b, c);
                        if (a == b || a == c || b == c || !ordered(a, b, c)) continue;
                        cyclicOrder[index] = 1;
                        for (int x = 0; x < n; x++) {
                            afterPositions[index * n + x] = (byte) afterPosition(x, a, b, c);
                        }
                    }
                }
            }
        }

        private Counts run() {
            checkCancellation();
            position[0] = 0;
            visit(1, 1);
            checkCancellation();
            return result();
        }

        private Counts result() {
            return new Counts(classes, cleared, classes - cleared, noQualifying, nodes, leaves);
        }
        private AuditCounts audit() {
            return new AuditCounts(result(),java.util.Map.of(9,clearingByLength[0],11,clearingByLength[1],
                    13,clearingByLength[2],17,clearingByLength[3]),ignoredUnoriented);
        }

        private void checkCancellation() {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException(
                    "Joint root generation interrupted");
            cancellationCheck.run();
        }

        private void visit(final int depth, final int used) {
            if (frontier != null && depth == frontierDepth) {
                checkCancellation();
                frontier.accept(new Branch(depth, used, position.clone(), target.clone(),
                        partialInverse.clone(), targetEdges));
                return;
            }
            if ((++nodes & 1023) == 0) checkCancellation();
            if (depth == n) {
                leaf();
                return;
            }
            int minimum = 1;
            if (cycleStart[depth] != depth) minimum = position[cycleStart[depth]] + 1;
            else if (previousEqual[depth] >= 0) minimum = position[previousEqual[depth]] + 1;
            for (int x = minimum; x < n; x++) {
                if ((used & (1 << x)) != 0) continue;
                position[depth] = x;
                final boolean edgeA = omega[depth] <= depth;
                final boolean edgeB = inverse[depth] < depth;
                boolean okay = true;
                boolean addedB = false;
                if (edgeA) okay = addTarget(depth);
                if (okay && edgeB) {
                    okay = addTarget(inverse[depth]);
                    addedB = true;
                }
                if (okay && canonicalPrefix(depth) && constraints(depth)) {
                    visit(depth + 1, used | (1 << x));
                }
                if (addedB) removeTarget(inverse[depth]);
                if (edgeA) removeTarget(depth);
            }
        }

        private boolean constraints(final int depth) {
            for (final Triple triple : fast[depth]) {
                if (cyclicOrder[index(position[triple.a], position[triple.b],
                        position[triple.c])] != 0) return false;
            }
            for (final Rule group : joint[depth]) {
                final Triple first = group.first;
                final int index = index(position[first.a], position[first.b], position[first.c]);
                if (cyclicOrder[index] == 0) continue;
                final int offset = index * n;
                for (final Continuation continuation : group.continuations) {
                    final Triple second = continuation.second;
                    final int secondA = afterPositions[offset + position[second.a]];
                    final int secondB = afterPositions[offset + position[second.b]];
                    final int secondC = afterPositions[offset + position[second.c]];
                    final int secondIndex = index(secondA, secondB, secondC);
                    if (cyclicOrder[secondIndex] == 0) continue;
                    if (continuation.terminal) return false;
                    final int secondOffset = secondIndex * n;
                    for (final Triple third : continuation.thirds) {
                        final int thirdA = afterPositions[secondOffset
                                + afterPositions[offset + position[third.a]]];
                        final int thirdB = afterPositions[secondOffset
                                + afterPositions[offset + position[third.b]]];
                        final int thirdC = afterPositions[secondOffset
                                + afterPositions[offset + position[third.c]]];
                        if (cyclicOrder[index(thirdA, thirdB, thirdC)] != 0) return false;
                    }
                }
            }
            return true;
        }

        private boolean addTarget(final int rank) {
            final int source = (position[rank] + n - 1) % n;
            final int destination = position[omega[rank]];
            target[source] = destination;
            partialInverse[destination] = position[rank];
            targetEdges++;
            int x = destination;
            while (x != source && target[x] >= 0) x = target[x];
            return x != source || targetEdges == n;
        }

        private void removeTarget(final int rank) {
            partialInverse[position[omega[rank]]] = -1;
            target[(position[rank] + n - 1) % n] = -1;
            targetEdges--;
        }

        private boolean canonicalPrefix(final int depth) {
            for (int rank = 0; rank <= depth; rank++) {
                if (cycleLength[rank] != parts[0]) continue;
                final int anchor = position[rank];
                for (int reflection = 0; reflection < 2; reflection++) {
                    if (reflection == 0 && anchor == 0) continue;
                    for (int j = 0; j < n; j++) {
                        final int original = target[(j + n - 1) % n];
                        if (original < 0) break;
                        final int image = reflection == 1
                                ? partialInverse[(anchor - j + n) % n]
                                : target[(j + anchor + n - 1) % n];
                        if (image < 0) break;
                        final int value = reflection == 1
                                ? (anchor - image + n) % n : (image - anchor + n) % n;
                        if (value < original) return false;
                        if (value > original) break;
                    }
                }
            }
            return true;
        }

        private void leaf() {
            leaves++;
            for (int rank = 0; rank < n; rank++) {
                images[position[rank]] = position[omega[rank]];
                anchors[position[rank]] = cycleLength[rank] == parts[0];
            }
            if (unoriented(images)) { ignoredUnoriented++; return; }
            if (!canonical() || !joint3Residual(images)) return;
            classes++;
            boolean qualifying = false;
            for (final Triple first : firsts) {
                if (ordered(position[first.a], position[first.b], position[first.c])) {
                    qualifying = true;
                    break;
                }
            }
            if (!qualifying) noQualifying++;
            if (n == KnownDiameters.ROOT_SYMBOLS) {
                int length = SupportClearing.closingLengthCanonicalUnchecked(images);
                if (length != 0) {
                    cleared++;
                    clearingByLength[switch(length) { case 9 -> 0; case 11 -> 1; case 13 -> 2; case 17 -> 3;
                        default -> throw new IllegalStateException("Unknown support clearing"); }]++;
                }
            }
            if (consumer != null) consumer.accept(decode(images));
        }

        private boolean canonical() {
            for (int x = 0; x < n; x++) inverseImages[images[x]] = x;
            for (int anchor = 0; anchor < n; anchor++) {
                if (!anchors[anchor]) continue;
                for (int reflection = 0; reflection < 2; reflection++) {
                    for (int j = 0; j < n; j++) {
                        final int value = reflection == 1
                                ? (anchor - inverseImages[(anchor - j + n) % n] + n) % n
                                : (images[(j + anchor) % n] - anchor + n) % n;
                        if (value < images[j]) return false;
                        if (value > images[j]) break;
                    }
                }
            }
            return true;
        }

        private int index(final int a, final int b, final int c) { return (a * n + b) * n + c; }

        private boolean ordered(final int a, final int b, final int c) {
            return (b - a + n) % n < (c - a + n) % n;
        }

        private int afterPosition(final int x, final int a, final int b, final int c) {
            final int d = (x - a + n) % n;
            final int db = (b - a + n) % n;
            final int dc = (c - a + n) % n;
            if (d < db) return d;
            return d >= dc ? db + d - dc : n - dc + d;
        }
    }

    private static int gain(final int[] omega, final Triple triple) {
        return (omega[triple.a] == triple.b ? 1 : 0)
                + (omega[triple.b] == triple.c ? 1 : 0)
                + (omega[triple.c] == triple.a ? 1 : 0);
    }

    private static int depth(final Triple triple) {
        return Math.max(triple.a, Math.max(triple.b, triple.c));
    }

    private static int[] apply(final int[] omega, final Triple triple) {
        final int[] next = omega.clone();
        next[triple.a] = omega[triple.c];
        next[triple.b] = omega[triple.a];
        next[triple.c] = omega[triple.b];
        return next;
    }

    private static int oddCycles(final int[] omega) {
        int seen = 0;
        int count = 0;
        for (int first = 0; first < omega.length; first++) {
            if ((seen & (1 << first)) != 0) continue;
            int length = 0;
            int x = first;
            do {
                seen |= 1 << x;
                length++;
                x = omega[x];
            } while (x != first);
            count += length & 1;
        }
        return count;
    }

    private static boolean unoriented(final int[] omega) {
        int seen = 0;
        for (int first = 0; first < omega.length; first++) {
            if ((seen & (1 << first)) != 0) continue;
            int x = first;
            int length = 0;
            int increases = 0;
            do {
                seen |= 1 << x;
                length++;
                if (omega[x] > x) increases++;
                x = omega[x];
            } while (x != first);
            if (length >= 3 && increases != 1) return false;
        }
        return true;
    }

    private static boolean totallyUnoriented(final int[] omega, final int[] beta) {
        final int[] at = new int[omega.length];
        for (int i = 0; i < beta.length; i++) at[beta[i]] = i;
        final int[] positioned = new int[omega.length];
        for (int x = 0; x < omega.length; x++) positioned[at[x]] = at[omega[x]];
        return unoriented(positioned);
    }

    private static int[] transpose(final int[] beta, final int first,
                                   final int second, final int third) {
        final int[] result = beta.clone();
        System.arraycopy(beta, second, result, first, third - second);
        System.arraycopy(beta, first, result, first + third - second, second - first);
        return result;
    }

    /** Exhaustive leaf replay keeps the optimized prefix predicates honest. */
    private static boolean joint3Residual(final int[] root) {
        final int n = root.length;
        final int[] beta = new int[n];
        Arrays.setAll(beta, i -> i);
        final int initialOdd = oddCycles(root);
        for (int i = 0; i < n - 2; i++) for (int j = i + 1; j < n - 1; j++)
            for (int k = j + 1; k < n; k++) {
                final Triple first = new Triple(beta[i], beta[j], beta[k]);
                final int firstGain = gain(root, first);
                final int[] q = apply(root, first);
                if (oddCycles(q) != initialOdd + 2) continue;
                if (firstGain >= 2) return false;
                if (firstGain != 1) continue;
                final int[] beta1 = transpose(beta, i, j, k);
                if (totallyUnoriented(q, beta1)) continue;
                final int qOdd = initialOdd + 2;
                for (int r = 0; r < n - 2; r++) for (int s = r + 1; s < n - 1; s++)
                    for (int t = s + 1; t < n; t++) {
                        final Triple second = new Triple(beta1[r], beta1[s], beta1[t]);
                        if (q[second.a] == second.a || q[second.b] == second.b
                                || q[second.c] == second.c) continue;
                        final int secondGain = gain(q, second);
                        if (secondGain < 1) continue;
                        final int[] successor = apply(q, second);
                        if (oddCycles(successor) != qOdd + 2) continue;
                        if (secondGain >= 3) return false;
                        final int[] beta2 = transpose(beta1, r, s, t);
                        if (totallyUnoriented(successor, beta2)) continue;
                        final int successorOdd = qOdd + 2;
                        for (int u = 0; u < n - 2; u++) for (int v = u + 1; v < n - 1; v++)
                            for (int w = v + 1; w < n; w++) {
                                final Triple third = new Triple(beta2[u], beta2[v], beta2[w]);
                                if (successor[third.a] == third.a || successor[third.b] == third.b
                                        || successor[third.c] == third.c) continue;
                                final int thirdGain = gain(successor, third);
                                if (secondGain == 1 && thirdGain != 3
                                        || secondGain == 2 && thirdGain < 2) continue;
                                if (oddCycles(apply(successor, third)) == successorOdd + 2) return false;
                            }
                    }
            }
        return true;
    }

    private static CyclicTargetPair decode(final int[] images) {
        final int n = images.length;
        final MulticyclePermutation omega = new MulticyclePermutation();
        int seen = 0;
        for (int first = 0; first < n; first++) {
            if ((seen & (1 << first)) != 0) continue;
            int length = 0;
            int x = first;
            do { length++; x = images[x]; } while (x != first);
            final int[] symbols = new int[length];
            for (int i = 0; i < length; i++) {
                symbols[i] = x;
                seen |= 1 << x;
                x = images[x];
            }
            omega.add(Cycle.of(symbols));
        }
        final int[] beta = new int[n];
        for (int x = 0; x < n; x++) beta[x] = x;
        return CyclicTargetPair.ofOwned(omega, Cycle.of(beta));
    }
}
