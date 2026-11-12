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
 * Direct constrained generation of oriented or totally unoriented joint residual classes.
 *
 * <p>Disjoint algebraic cycles receive consecutive ranks, whose current-cycle
 * positions are assigned recursively. Cycle origins and equal-cycle ordering
 * remove auxiliary label choices. A largest algebraic cycle contains position
 * zero. Twisted dihedral comparisons among these permitted origins select one
 * numeric image-array representative per orbit; this representative need not
 * be the least fixed-content encoding used by certificate keys.</p>
 *
 * <p>Every gain-two move and every qualifying gain-one/second-gain-two pair is
 * rejected as soon as its at most six positions are known. Deleting the first
 * fixed point does not change cyclic order on a second support. Forced proper
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

    /** The U residual stream uses the same joint predicates and symmetry scheduler. */
    public static void generateUnorientedResiduals(final int[] partition,
                                                   final Consumer<CyclicTargetPair> consumer) {
        new Generator(partition, NO_CANCELLATION, Objects.requireNonNull(consumer), true).run();
    }

    public static long countUnorientedResiduals(final int[] partition) {
        return new Generator(partition, NO_CANCELLATION, null, true).run().jointResidualClasses();
    }

    public static long generateUnorientedResidualsConcurrently(final int[] partition,
                                                               final Runnable cancellationCheck,
                                                               final Consumer<CyclicTargetPair> consumer) {
        return generateUnorientedResidualsConcurrently(partition, Runtime.getRuntime().availableProcessors(),
                cancellationCheck, consumer);
    }

    static long generateUnorientedResidualsConcurrently(final int[] partition, final int workers,
                                                        final Runnable cancellationCheck,
                                                        final Consumer<CyclicTargetPair> consumer) {
        return generateConcurrently(partition, workers, cancellationCheck, Objects.requireNonNull(consumer), true);
    }

    public static long countUnorientedResidualsConcurrently(final int[] partition) {
        return generateConcurrently(partition, Runtime.getRuntime().availableProcessors(), NO_CANCELLATION, null, true);
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

    /** Sequential audit count, useful for checking the parallel prefix scheduler. */
    public static AuditCounts countAudit(final int[] partition) {
        var generator = new Generator(partition, NO_CANCELLATION, null);
        generator.run();
        return generator.audit();
    }

    /**
     * Picks the shallowest frontier depth whose disjoint subtree count comfortably exceeds the worker
     * pool, so no single giant branch can strand the remaining CPUs during the tail of generation.
     * An explicit {@code td20.jointFrontierDepth} overrides the choice (tuning/benchmarking only).
     * Counting inspects only the top of the tree and stops as soon as the target is met, so it is
     * negligible beside full generation, and it runs on a disposable copy so the template is untouched.
     */
    private static int chooseFrontierDepth(final Generator template, final int workers) {
        final int maxDepth = Math.max(1, template.n - 1);
        final Integer forced = Integer.getInteger("td20.jointFrontierDepth");
        if (forced != null) return Math.max(1, Math.min(forced, maxDepth));
        final long target = 32L * workers;
        int depth = Math.min(3, maxDepth);
        while (depth < maxDepth && template.countFrontierAtLeast(depth, target) < target) depth++;
        return depth;
    }

    private static long generateConcurrently(final int[] partition, final int workers,
                                             final Runnable cancellationCheck,
                                             final Consumer<CyclicTargetPair> consumer, final boolean unorientedOnly) {
        return auditConcurrently(partition, workers, cancellationCheck, consumer, unorientedOnly).counts().jointResidualClasses();
    }

    private static AuditCounts auditConcurrently(final int[] partition, final int workers,
                                                 final Runnable cancellationCheck,
                                                 final Consumer<CyclicTargetPair> consumer) {
        return auditConcurrently(partition, workers, cancellationCheck, consumer, false);
    }

    private static AuditCounts auditConcurrently(final int[] partition, final int workers,
                                                 final Runnable cancellationCheck,
                                                 final Consumer<CyclicTargetPair> consumer, final boolean unorientedOnly) {
        if (workers < 1) throw new IllegalArgumentException("Positive CPU worker count required");
        Objects.requireNonNull(cancellationCheck);
        final var failure = new AtomicReference<Throwable>();
        final Runnable cancel = () -> {
            if (failure.get() != null) throw propagate(failure.get());
            cancellationCheck.run();
        };
        final var template = new Generator(partition, cancel, consumer, unorientedOnly);
        final var pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1 << 16),
                Thread.ofPlatform().daemon(true).name("joint-generation-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        // Cut the parallel frontier deep enough that even a single large cycle yields many more
        // disjoint subtrees than workers. A fixed depth-three cut leaves a few enormous branches
        // whose subtrees dominate the tail: they pin one CPU each for minutes while every other core
        // sits idle (observed on the TD(20) single-cycle partitions). chooseFrontierDepth deepens the
        // cut until the subtree count comfortably exceeds the pool.
        final List<Future<AuditCounts>> jobs = new ArrayList<>();
        try {
            template.frontierDepth = chooseFrontierDepth(template, workers);
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
    private record ConstraintGroup(Triple first, Triple[] seconds) { }
    private record Branch(int depth, int used, int[] position, int[] target,
                          int[] partialInverse, int targetEdges) { }

    /** Stackless sentinel used to abandon frontier counting once the target is reached. */
    private static final class StopCounting extends RuntimeException {
        private StopCounting() { super(null, null, false, false); }
    }
    private static final StopCounting STOP_COUNTING = new StopCounting();

    private static final class Generator {
        private final int n;
        private final boolean unorientedOnly;
        private final int[] parts;
        private final int[] omega;
        private final int[] inverse;
        private final int[] position;
        private final int[] cycleStart;
        private final int[] cycleLength;
        private final int[] previousEqual;
        private final int orientationDepth;
        private final int[] target;
        private final int[] partialInverse;
        private final int[] images;
        private final int[] inverseImages;
        private final boolean[] anchors;
        private final Triple[][] fast;
        private final ConstraintGroup[][] pairs;
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
            unorientedOnly = template.unorientedOnly;
            n = template.n; parts = template.parts; omega = template.omega; inverse = template.inverse;
            cycleStart = template.cycleStart; cycleLength = template.cycleLength; previousEqual = template.previousEqual;
            orientationDepth = template.orientationDepth;
            fast = template.fast; pairs = template.pairs; firsts = template.firsts;
            cyclicOrder = template.cyclicOrder; afterPositions = template.afterPositions;
            cancellationCheck = template.cancellationCheck; consumer = template.consumer;
            position = branch.position.clone(); target = branch.target.clone();
            partialInverse = branch.partialInverse.clone(); targetEdges = branch.targetEdges;
            images = new int[n]; inverseImages = new int[n]; anchors = new boolean[n];
        }

        private Generator(final int[] partition, final Runnable cancellationCheck,
                          final Consumer<CyclicTargetPair> consumer) {
            this(partition, cancellationCheck, consumer, false);
        }

        @SuppressWarnings("unchecked")
        private Generator(final int[] partition, final Runnable cancellationCheck,
                          final Consumer<CyclicTargetPair> consumer, final boolean unorientedOnly) {
            this.unorientedOnly = unorientedOnly;
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
            // An oriented 3-cycle has an immediate gain-three move, already forbidden
            // by fast[]. Thus only cycles of length at least four can orient a residual.
            orientationDepth = Arrays.stream(parts).filter(length -> length >= 4).sum();
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
            final LinkedHashMap<Triple, List<Triple>>[] grouped = new LinkedHashMap[n];
            for (int d = 0; d < n; d++) {
                fastLists[d] = new ArrayList<>();
                grouped[d] = new LinkedHashMap<>();
            }
            for (int x = 0; x < n; x++) {
                final int y = omega[x];
                final int z = omega[y];
                if (x == z || omega[z] == x && (x > y || x > z)) continue;
                fastLists[Math.max(x, Math.max(y, z))].add(new Triple(x, y, z));
            }
            final List<Triple> qualifying = new ArrayList<>();
            final int odd = oddCycles(omega);
            for (int a = 0; a < n - 2; a++) {
                for (int b = a + 1; b < n - 1; b++) {
                    for (int c = b + 1; c < n; c++) {
                        for (int reverse = 0; reverse < 2; reverse++) {
                            final Triple first = reverse == 0
                                    ? new Triple(a, b, c) : new Triple(a, c, b);
                            if (gain(omega, first) != 1) continue;
                            final int[] next = apply(omega, first);
                            if (oddCycles(next) != odd + 2) continue;
                            qualifying.add(first);
                            for (int x = 0; x < n; x++) {
                                final int y = next[x];
                                final int z = next[y];
                                if (x == y || x == z || y == z
                                        || next[z] == x && (x > y || x > z)) continue;
                                final int depth = Math.max(c, Math.max(x, Math.max(y, z)));
                                grouped[depth].computeIfAbsent(first, ignored -> new ArrayList<>())
                                        .add(new Triple(x, y, z));
                            }
                        }
                    }
                }
            }
            firsts = qualifying.toArray(Triple[]::new);
            fast = new Triple[n][];
            pairs = new ConstraintGroup[n][];
            for (int d = 0; d < n; d++) {
                fast[d] = fastLists[d].toArray(Triple[]::new);
                pairs[d] = grouped[d].entrySet().stream()
                        .map(entry -> new ConstraintGroup(entry.getKey(),
                                entry.getValue().toArray(Triple[]::new)))
                        .toArray(ConstraintGroup[]::new);
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
            // U is counted and generated separately. Once all longer cycles are assigned,
            // an unoriented prefix cannot acquire orientation from the remaining 2-/3-cycles.
            if (!unorientedOnly && (orientationDepth == 0
                    || depth == orientationDepth && !hasAssignedOrientation())) return;
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
                // With its least position anchored, an unoriented cycle's remaining
                // algebraic ranks must occupy strictly decreasing positions.
                if (unorientedOnly && depth > cycleStart[depth] + 1
                        && x >= position[depth - 1]) continue;
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

        private boolean hasAssignedOrientation() {
            for (int start = 0; start < orientationDepth; start += cycleLength[start]) {
                int increases = 0;
                for (int rank = start; rank < start + cycleLength[start]; rank++) {
                    if (position[omega[rank]] > position[rank] && ++increases > 1) return true;
                }
            }
            return false;
        }

        /**
         * Counts the frontier nodes this prefix would spawn at {@code depth}, stopping once {@code limit}
         * is reached. Runs on a disposable copy seeded from this generator's current (root) state, so it
         * never disturbs the caller even though the early stop abandons the partial walk.
         */
        private long countFrontierAtLeast(final int depth, final long limit) {
            final var probe = new Generator(this, new Branch(1, 1,
                    position.clone(), target.clone(), partialInverse.clone(), targetEdges));
            probe.frontierDepth = depth;
            final long[] seen = {0};
            probe.frontier = branch -> { if (++seen[0] >= limit) throw STOP_COUNTING; };
            probe.position[0] = 0;
            try {
                probe.visit(1, 1);
            } catch (StopCounting reachedTarget) {
                // Target met; no need to enumerate the rest of the frontier.
            }
            return seen[0];
        }

        private boolean constraints(final int depth) {
            if (unorientedOnly && depth == cycleStart[depth] + cycleLength[depth] - 1
                    && cycleLength[depth] >= 3) {
                int increases = 0;
                for (int rank = cycleStart[depth]; rank <= depth; rank++)
                    if (position[omega[rank]] > position[rank]) increases++;
                if (increases != 1) return false;
            }
            for (final Triple triple : fast[depth]) {
                if (cyclicOrder[index(position[triple.a], position[triple.b],
                        position[triple.c])] != 0) return false;
            }
            for (final ConstraintGroup group : pairs[depth]) {
                final Triple first = group.first;
                final int index = index(position[first.a], position[first.b], position[first.c]);
                if (cyclicOrder[index] == 0) continue;
                final int offset = index * n;
                for (final Triple second : group.seconds) {
                    if (cyclicOrder[index(afterPositions[offset + position[second.a]],
                            afterPositions[offset + position[second.b]],
                            afterPositions[offset + position[second.c]])] != 0) return false;
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
            if (unoriented(images) != unorientedOnly) { ignoredUnoriented++; return; }
            if (!canonical()) return;
            classes++;
            boolean qualifying = false;
            for (final Triple first : firsts) {
                if (ordered(position[first.a], position[first.b], position[first.c])) {
                    qualifying = true;
                    break;
                }
            }
            if (!qualifying) noQualifying++;
            if (!unorientedOnly && n == KnownDiameters.ROOT_SYMBOLS) {
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
