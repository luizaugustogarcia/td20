package br.unb.cic.td20.experimental;

import br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction;
import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.PrimitiveCanonicalizer;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.runtime.BoundedTasks;
import com.sun.management.ThreadMXBean;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/** Read-only, bounded experiment. Its filtered outputs are never proof artifacts. */
public final class ReconstructionAlternativesBenchmark {
    private static final long SEED = 0x54443230414c5453L;
    private static volatile long blackhole;
    private ReconstructionAlternativesBenchmark() { }

    /**
     * Worker-owned consecutive-triple predicate. Input must be a valid pair
     * with every fixed point already contracted. A result encodes a,b,c in
     * five bits each, with bit 15 selecting the target endpoint; -1 is a miss.
     */
    public static final class ImmediateClosure {
        private final int[] position = new int[21], inverse = new int[21];
        private final int[] nextTarget = new int[21], targetPosition = new int[21];

        public int find(int[] omega, int[] beta, int n) {
            if (n < 3 || n > 21 || omega.length < n || beta.length < n)
                throw new IllegalArgumentException("Expected a fixed-point-free valid pair on 3..21 symbols");
            for (int i = 0; i < n; i++) {
                if (omega[i] == i) throw new IllegalArgumentException("Contract fixed points first");
                position[beta[i]] = i;
            }
            int move = findOn(omega, position, n);
            if (move >= 0) return move;
            for (int i = 0; i < n; i++) {
                inverse[omega[i]] = i;
                nextTarget[beta[i]] = omega[beta[i + 1 == n ? 0 : i + 1]];
            }
            int x = 0;
            for (int i = 0; i < n; i++) { targetPosition[x] = i; x = nextTarget[x]; }
            move = findOn(inverse, targetPosition, n);
            return move < 0 ? -1 : move | (1 << 15);
        }

        private static int findOn(int[] omega, int[] position, int n) {
            for (int a = 0; a < n; a++) {
                int b = omega[a], c = omega[b];
                if (a == b || a == c || b == c) continue;
                int pa = position[a], pb = position[b], pc = position[c];
                if ((pa < pb && pb < pc) || (pb < pc && pc < pa) || (pc < pa && pa < pb))
                    return a | (b << 5) | (c << 10);
            }
            return -1;
        }
    }

    private record Counts(long outputs, long closed, long allDigest, long survivorDigest, long replayed, long omittedSources) { }
    private record Measurement(double wallSeconds, double cpuSeconds, long allocated, Counts counts) { }
    private static final class Workspace {
        final PrimitiveInverseReconstruction inverse = new PrimitiveInverseReconstruction();
        final PrimitiveCanonicalizer canonical = new PrimitiveCanonicalizer();
        final ImmediateClosure closure = new ImmediateClosure();
        final InverseFamilyClosure family = new InverseFamilyClosure();
        final FamilyPrunedInverseReconstruction pruned = new FamilyPrunedInverseReconstruction();
    }
    private static final ThreadLocal<Workspace> WORK = ThreadLocal.withInitial(Workspace::new);

    public static void run() throws Exception {
        Path source = Path.of(System.getProperty("td20.bench.afterOne",
                "local-runtime/work/emitter-20261118T024413Z-706373/after-one")).toAbsolutePath();
        int workers = positive("td20.bench.workers", Runtime.getRuntime().availableProcessors());
        int ranges = positive("td20.bench.seekRanges", 32);
        int perRange = positive("td20.bench.samplesPerSeek", 4);
        int warmups = positive("td20.bench.warmups", 2), repetitions = positive("td20.bench.repetitions", 3);
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean threads)
                || !threads.isThreadAllocatedMemorySupported() || !threads.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException("Per-thread CPU and allocation measurement unavailable");
        threads.setThreadAllocatedMemoryEnabled(true); threads.setThreadCpuTimeEnabled(true);
        System.out.printf(Locale.ROOT,
                "source=%s,java=%s,workers=%d,seed=%d,seek_ranges=%d,samples_per_seek=%d,warmups=%d,repetitions=%d%n",
                source, System.getProperty("java.version"), workers, SEED, ranges, perRange, warmups, repetitions);
        System.out.println("NONUNIFORM deterministic key-seek samples; rates and rejection fractions are not population estimates.");
        System.out.println("Timing excludes sample reads, proof persistence, RocksDB deduplication, certificate lookup, and GPU work.");
        System.out.println("Filtering requires a separately specified proof/checker mode before production use.");
        RocksDB.loadLibrary();
        try (Options options = new Options().setCreateIfMissing(false);
             RocksDB database = RocksDB.openReadOnly(options, source.toString())) {
            for (int n : new int[]{20, 21}) {
                List<PrimitivePair> samples = samples(database, n, ranges, perRange);
                familyStudy(samples, n, workers);
                Counts expected = measure(samples, n, workers, threads, 2).counts;
                System.out.printf(Locale.ROOT,
                        "validation domain=%d,sources=%d,outputs=%d,immediate_closed=%d,survivors=%d,closure_replays=%d%n",
                        n, samples.size(), expected.outputs, expected.closed, expected.outputs - expected.closed, expected.replayed);
                for (int i = 0; i < warmups; i++) {
                    check(measure(samples, n, workers, threads, 0).counts, expected, false);
                    check(measure(samples, n, workers, threads, 1).counts, expected, true);
                    checkFamily(measure(samples, n, workers, threads, 3).counts, expected);
                    checkFamily(measure(samples, n, workers, threads, 4).counts, expected);
                }
                Measurement[] baseline = new Measurement[repetitions], filtered = new Measurement[repetitions];
                Measurement[] families = new Measurement[repetitions];
                Measurement[] pruned = new Measurement[repetitions];
                for (int i = 0; i < repetitions; i++) {
                    if ((i & 1) == 0) {
                        baseline[i] = measure(samples, n, workers, threads, 0);
                        filtered[i] = measure(samples, n, workers, threads, 1);
                    } else {
                        filtered[i] = measure(samples, n, workers, threads, 1);
                        baseline[i] = measure(samples, n, workers, threads, 0);
                    }
                    check(baseline[i].counts, expected, false); check(filtered[i].counts, expected, true);
                    families[i] = measure(samples, n, workers, threads, 3);
                    checkFamily(families[i].counts, expected);
                    pruned[i] = measure(samples, n, workers, threads, 4);
                    checkFamily(pruned[i].counts, expected);
                }
                double baseWall = median(baseline, false), filterWall = median(filtered, false);
                double baseCpu = median(baseline, true), filterCpu = median(filtered, true);
                System.out.printf(Locale.ROOT,
                        "domain=%d,gain=%d,sources=%d,outputs=%d,survivors=%d,all_keys_wall_s=%.6f,filtered_wall_s=%.6f,wall_speedup=%.3f,all_keys_cpu_s=%.6f,filtered_cpu_s=%.6f,cpu_speedup=%.3f,all_keys_bytes_per_output=%.2f,filtered_bytes_per_output=%.2f,filtered_sources_per_s=%.3f%n",
                        n, 21 - n, samples.size(), expected.outputs, expected.outputs - expected.closed, baseWall, filterWall,
                        baseWall / filterWall, baseCpu, filterCpu, baseCpu / filterCpu,
                        medianBytes(baseline) / (double) expected.outputs, medianBytes(filtered) / (double) expected.outputs,
                        samples.size() / filterWall);
                System.out.printf(Locale.ROOT,
                        "family_then_filter domain=%d,fully_closed_sources=%d,enumerated_outputs=%d,survivors=%d,wall_s=%.6f,cpu_s=%.6f,wall_speedup_vs_all_keys=%.3f,cpu_speedup_vs_all_keys=%.3f,sources_per_s=%.3f%n",
                        n, families[0].counts.omittedSources, families[0].counts.outputs,
                        families[0].counts.outputs - families[0].counts.closed, median(families, false),
                        median(families, true), baseWall / median(families, false),
                        baseCpu / median(families, true), samples.size() / median(families, false));
                System.out.printf(Locale.ROOT,
                        "support_family_then_filter domain=%d,enumerated_outputs=%d,survivors=%d,wall_s=%.6f,cpu_s=%.6f,wall_speedup_vs_all_keys=%.3f,cpu_speedup_vs_all_keys=%.3f,sources_per_s=%.3f%n",
                        n, pruned[0].counts.outputs, pruned[0].counts.outputs - pruned[0].counts.closed,
                        median(pruned, false), median(pruned, true), baseWall / median(pruned, false),
                        baseCpu / median(pruned, true), samples.size() / median(pruned, false));
            }
        }
        System.out.println("Bounded reconstruction alternatives benchmark completed; no database was modified.");
    }

    private static int positive(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static List<PrimitivePair> samples(RocksDB db, int n, int ranges, int perRange) throws Exception {
        var result = new ArrayList<PrimitivePair>();
        var seen = new HashSet<ByteBuffer>();
        var random = new SplittableRandom(SEED + n);
        try (RocksIterator iterator = db.newIterator()) {
            for (int range = 0; range < ranges; range++) {
                byte[] anchor = new byte[3 + (5 * n + 7) / 8];
                anchor[0] = 3; anchor[1] = 10; anchor[2] = (byte) n;
                for (int i = 3; i < anchor.length; i++) anchor[i] = (byte) random.nextInt(256);
                // Stratify the first image; subsequent bits choose a deterministic seek, not a random class.
                int image = range == 0 ? 0 : 1 + (int) ((long) (range - 1) * (n - 1) / (ranges - 1));
                anchor[3] = (byte) ((image << 3) | (anchor[3] & 7));
                if (range == 0) Arrays.fill(anchor, 3, anchor.length, (byte) 0);
                iterator.seek(anchor);
                int accepted = 0;
                for (int inspected = 0; iterator.isValid() && accepted < perRange && inspected < perRange * ranges;
                     iterator.next(), inspected++) {
                    byte[] key = iterator.key();
                    if (key.length < 4 || key[0] != 3 || key[1] != 10 || Byte.toUnsignedInt(key[2]) != n) break;
                    if (iterator.value().length != 0) throw new IllegalStateException("Unexpected state value");
                    if (!seen.add(ByteBuffer.wrap(key))) continue;
                    PrimitivePair pair = PrimitivePair.fromCanonicalKey(Arrays.copyOfRange(key, 3, key.length), n);
                    if (!pair.isFixedPointFree()) throw new IllegalStateException("Uncontracted source state");
                    result.add(pair); accepted++;
                }
                iterator.status();
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("No sampled states for domain " + n);
        return List.copyOf(result);
    }

    /** Modes: 0 all keys; 1 immediate; 2 cross-check; 3 whole-source family; 4 support-family pruning. */
    private static Measurement measure(List<PrimitivePair> sources, int n, int workers, ThreadMXBean threads, int mode) {
        long[] cpus = new long[sources.size()], allocations = new long[sources.size()];
        Counts[] outputs = new Counts[sources.size()];
        long started = System.nanoTime();
        try (var tasks = new BoundedTasks(workers, "reconstruction-benchmark-")) {
            for (int index = 0; index < sources.size(); index++) {
                final int slot = index;
                tasks.submit(() -> {
                    Workspace work = WORK.get();
                    long thread = Thread.currentThread().threadId();
                    long bytes = threads.getThreadAllocatedBytes(thread), cpu = threads.getCurrentThreadCpuTime();
                    long[] counts = new long[6];
                    boolean familyClosed = false;
                    if (mode == 2 || mode == 3) {
                        work.family.prepare(sources.get(slot), false);
                        boolean currentClosed = work.family.closesEverySupport(21 - n);
                        work.family.prepare(sources.get(slot), true);
                        familyClosed = currentClosed && work.family.closesEverySupport(21 - n);
                    }
                    final boolean provedFamily = familyClosed;
                    if (mode == 3 && familyClosed) counts[5]++;
                    else {
                        PrimitiveInverseReconstruction.RawConsumer consume = (omega, beta, length) -> {
                            counts[0]++;
                            int move = mode == 0 ? -1 : work.closure.find(omega, beta, length);
                            if (mode == 2 && provedFamily && move < 0)
                                throw new IllegalStateException("Family predicate omitted a parent without an immediate closure");
                            if (move >= 0) {
                                counts[1]++;
                                if (mode == 2 && counts[4] < 8) { replay(omega, beta, length, move); counts[4]++; }
                            }
                            if (mode == 0 || mode == 2 || move < 0) {
                                byte[] key = work.canonical.keyForKnownPair(omega, beta, length, 3);
                                key[0] = 3; key[1] = 11; key[2] = (byte) length;
                                int hash = Arrays.hashCode(key);
                                counts[2] += hash;
                                if (move < 0) counts[3] += hash;
                            }
                        };
                        if (mode == 4) work.pruned.preimages(sources.get(slot), 21 - n, consume::accept);
                        else work.inverse.preimages(sources.get(slot), 21 - n, consume);
                    }
                    cpus[slot] = threads.getCurrentThreadCpuTime() - cpu;
                    allocations[slot] = threads.getThreadAllocatedBytes(thread) - bytes;
                    outputs[slot] = new Counts(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
                });
            }
        }
        double wall = (System.nanoTime() - started) / 1e9;
        long count = 0, closed = 0, all = 0, survivor = 0, replayed = 0, omitted = 0;
        for (Counts output : outputs) {
            count += output.outputs; closed += output.closed; all += output.allDigest;
            survivor += output.survivorDigest; replayed += output.replayed;
            omitted += output.omittedSources;
        }
        blackhole = all + survivor;
        return new Measurement(wall, Arrays.stream(cpus).sum() / 1e9, Arrays.stream(allocations).sum(),
                new Counts(count, closed, all, survivor, replayed, omitted));
    }

    private static void familyStudy(List<PrimitivePair> sources, int n, int workers) {
        long[] currentRemaining = new long[sources.size()], targetRemaining = new long[sources.size()];
        try (var tasks = new BoundedTasks(workers, "family-benchmark-")) {
            for (int i = 0; i < sources.size(); i++) {
                final int slot = i;
                tasks.submit(() -> {
                    var family = WORK.get().family;
                    family.prepare(sources.get(slot), false);
                    currentRemaining[slot] = family.unresolvedOldSupports(21 - n);
                    family.prepare(sources.get(slot), true);
                    targetRemaining[slot] = family.unresolvedOldSupports(21 - n);
                });
            }
        }
        long sourceSidesClosed = Arrays.stream(currentRemaining).filter(x -> x == 0).count()
                + Arrays.stream(targetRemaining).filter(x -> x == 0).count();
        long remaining = Arrays.stream(currentRemaining).sum() + Arrays.stream(targetRemaining).sum();
        long perSide = n == 20 ? (long) n * (n - 1) / 2 : (long) n * (n - 1) * (n - 2) / 6;
        System.out.printf(Locale.ROOT,
                "family_supports domain=%d,source_sides=%d,fully_closed_sides=%d,old_supports=%d,remaining_old_supports=%d,remaining_support_gap_candidates=%d%n",
                n, 2 * sources.size(), sourceSidesClosed, 2 * sources.size() * perSide, remaining, remaining * (n == 20 ? n : 1));
    }

    public static void replay(int[] omega, int[] beta, int n, int packed) {
        int[] algebraic = Arrays.copyOf(omega, n), current = Arrays.copyOf(beta, n);
        if ((packed & (1 << 15)) != 0) {
            PrimitivePair pair = PrimitivePair.of(algebraic, current);
            for (int i = 0; i < n; i++) { algebraic[omega[i]] = i; current[i] = pair.rhoAt(i); }
        }
        var move = new CyclicTargetMoves.Move(packed & 31, (packed >>> 5) & 31, (packed >>> 10) & 31);
        int[] parentTarget = target(algebraic, current);
        int[] movedOmega = CyclicTargetMoves.applyToOmega(algebraic, move);
        int[] movedBeta = CyclicTargetMoves.applyToBeta(current, move);
        if (!Arrays.equals(parentTarget, target(movedOmega, movedBeta)))
            throw new IllegalStateException("Closure changed its sorting target");
        int fixed = 0;
        for (int i = 0; i < n; i++) if (movedOmega[i] == i) fixed++;
        if (fixed < 2 || fixed != CyclicTargetMoves.gain(algebraic, move))
            throw new IllegalStateException("Closure did not replay its actual fixed-symbol gain");
        if (n == 21 && KnownDiameters.knownDiameter(n - fixed) > 10)
            throw new IllegalStateException("Closure exceeded the final-root budget");
        CyclicTargetMoves.Contracted contracted = CyclicTargetMoves.contractFixedPoints(movedOmega, movedBeta);
        if (!CyclicTargetMoves.isFullTargetCycle(contracted.omega(), contracted.beta()))
            throw new IllegalStateException("Contracted closure is not a cyclic-target pair");
    }

    private static int[] target(int[] omega, int[] beta) {
        int[] result = new int[beta.length];
        for (int i = 0; i < beta.length; i++) result[beta[i]] = omega[beta[(i + 1) % beta.length]];
        return result;
    }
    private static void check(Counts actual, Counts expected, boolean filtered) {
        if (actual.outputs != expected.outputs || (filtered
                ? actual.closed != expected.closed || actual.survivorDigest != expected.survivorDigest
                : actual.allDigest != expected.allDigest))
            throw new IllegalStateException("Benchmark output count or digest changed");
    }
    private static void checkFamily(Counts actual, Counts expected) {
        if (actual.outputs - actual.closed != expected.outputs - expected.closed
                || actual.survivorDigest != expected.survivorDigest)
            throw new IllegalStateException("Family filtering changed the surviving outputs");
    }
    private static double median(Measurement[] values, boolean cpu) {
        return Arrays.stream(values).mapToDouble(v -> cpu ? v.cpuSeconds : v.wallSeconds).sorted().toArray()[values.length / 2];
    }
    private static long medianBytes(Measurement[] values) {
        return Arrays.stream(values).mapToLong(Measurement::allocated).sorted().toArray()[values.length / 2];
    }
}
