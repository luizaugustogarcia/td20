package br.unb.cic.td20.experimental;

import br.unb.cic.td20.avoidance.ErikssonInverseReconstruction;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

/** Four small CPU fixtures, launched through tools/bench/PrimitivePermutations.jsh. */
public final class PrimitivePermutationBenchmark {
    private static volatile long blackhole;
    private PrimitivePermutationBenchmark() { }

    private record Fixture(int domain, int shift, int gain) {
        CyclicTargetPair pair() {
            int[] beta = IntStream.range(0, domain).toArray();
            int[] omega = new int[domain];
            for (int i = 0; i < domain; i++) omega[i] = (i + shift) % domain;
            var permutation = new MulticyclePermutation();
            int visited = 0;
            for (int start = 0; start < domain; start++) {
                if ((visited & (1 << start)) != 0) continue;
                int[] cycle = new int[domain];
                int x = start, size = 0;
                do { visited |= 1 << x; cycle[size++] = x; x = omega[x]; } while (x != start);
                permutation.add(Cycle.of(Arrays.copyOf(cycle, size)));
            }
            return CyclicTargetPair.ofOwned(permutation, Cycle.of(beta));
        }
    }

    private record Measurement(double wallMillis, double cpuMillis, long allocatedBytes) { }
    private record Sample(int[] omega, int[] beta, byte[] key) { }
    private static final class SampleComplete extends RuntimeException {
        SampleComplete() { super(null, null, false, false); }
    }

    private static CyclicTargetPair materialize(Sample sample) {
        var permutation = new MulticyclePermutation();
        int[] omega = sample.omega;
        int visited = 0;
        for (int start = 0; start < omega.length; start++) {
            if ((visited & (1 << start)) != 0) continue;
            int[] cycle = new int[omega.length];
            int x = start, size = 0;
            do { visited |= 1 << x; cycle[size++] = x; x = omega[x]; } while (x != start);
            permutation.add(Cycle.of(Arrays.copyOf(cycle, size)));
        }
        return CyclicTargetPair.ofOwned(permutation, Cycle.of(sample.beta.clone()));
    }

    private static List<Sample> samples(CyclicTargetPair child, int gain,
                                         PrimitiveInverseReconstruction inverse,
                                         PrimitiveCanonicalizer canonicalizer) {
        List<Sample> samples = new ArrayList<>();
        Set<ByteBuffer> seen = new HashSet<>();
        try {
            inverse.preimages(child, gain, (omega, beta, n) -> {
                byte[] key = canonicalizer.keyFor(omega, beta, n);
                if (seen.add(ByteBuffer.wrap(key))) {
                    Sample sample = new Sample(Arrays.copyOf(omega, n), Arrays.copyOf(beta, n), key);
                    if (!Arrays.equals(key, CertificateCodec.keyFor(materialize(sample).canonicalRepresentative())))
                        throw new IllegalStateException("Sample canonical key disagrees with the existing implementation");
                    samples.add(sample);
                    if (samples.size() == 16) throw new SampleComplete();
                }
            });
        } catch (SampleComplete complete) {
            // This is a deliberately bounded benchmark population, never a
            // generator or persistence interface used by proof computation.
        }
        if (samples.isEmpty()) throw new IllegalStateException("Empty benchmark population");
        return samples;
    }

    public static void run() {
        long benchmarkStarted = System.nanoTime();
        var management = ManagementFactory.getThreadMXBean();
        if (!(management instanceof ThreadMXBean threads)
                || !threads.isThreadAllocatedMemorySupported() || !threads.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException(
                    "Benchmark requires per-thread CPU time and allocated-byte measurements");
        threads.setThreadAllocatedMemoryEnabled(true);
        threads.setThreadCpuTimeEnabled(true);
        System.out.printf(Locale.ROOT,
                "java=%s, processors=%d, warmup=2, measured=3, source_classes=4, sample_limit=16, GPU=unused%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        System.out.println("domain,gain,outputs,sampled_classes,stage,legacy_wall_ms,primitive_wall_ms,"
                + "legacy_cpu_ms,primitive_cpu_ms,cpu_speedup,legacy_bytes_per_output,primitive_bytes_per_output");
        for (var fixture : new Fixture[]{new Fixture(18, 4, 2), new Fixture(18, 4, 3),
                new Fixture(20, 2, 1), new Fixture(21, 1, 0)}) {
            var child = fixture.pair();
            var inverse = new PrimitiveInverseReconstruction();
            var canonicalizer = new PrimitiveCanonicalizer();
            List<Sample> population = samples(child, fixture.gain, inverse, canonicalizer);
            for (boolean keys : new boolean[]{false, true}) {
                long[] legacyDigest = new long[2], primitiveDigest = new long[2];
                Runnable legacy = () -> {
                    Arrays.fill(legacyDigest, 0);
                    if (keys) {
                        for (Sample sample : population) {
                            legacyDigest[0]++;
                            legacyDigest[1] += Arrays.hashCode(
                                    CertificateCodec.keyFor(materialize(sample).canonicalRepresentative()));
                        }
                    } else ErikssonInverseReconstruction.preimages(child, fixture.gain, parent -> {
                        legacyDigest[0]++;
                        legacyDigest[1] += parent.getOmega().image(0) + parent.getBeta().get(0);
                    });
                    blackhole = legacyDigest[1];
                };
                Runnable primitive = () -> {
                    Arrays.fill(primitiveDigest, 0);
                    if (keys) {
                        for (Sample sample : population) {
                            primitiveDigest[0]++;
                            primitiveDigest[1] += Arrays.hashCode(canonicalizer.keyForKnownPair(
                                    sample.omega, sample.beta, sample.beta.length));
                        }
                    } else inverse.preimages(child, fixture.gain, (omega, beta, n) -> {
                        primitiveDigest[0]++;
                        primitiveDigest[1] += omega[0] + beta[0];
                    });
                    blackhole = primitiveDigest[1];
                };
                for (int i = 0; i < 2; i++) { legacy.run(); primitive.run(); }
                long count = legacyDigest[0];
                if (count == 0 || !Arrays.equals(legacyDigest, primitiveDigest))
                    throw new IllegalStateException(
                            "Benchmark outputs disagree for " + fixture + " keys=" + keys);
                Measurement[] baseline = new Measurement[3], experiment = new Measurement[3];
                for (int i = 0; i < 3; i++) {
                    // Alternate ordering to reduce systematic scheduling/JIT bias.
                    if ((i & 1) == 0) {
                        baseline[i] = measure(legacy, threads); experiment[i] = measure(primitive, threads);
                    } else {
                        experiment[i] = measure(primitive, threads); baseline[i] = measure(legacy, threads);
                    }
                    if (!Arrays.equals(legacyDigest, primitiveDigest) || legacyDigest[0] != count)
                        throw new IllegalStateException("Measured outputs disagree for " + fixture);
                }
                double baselineCpu = medianCpu(baseline), experimentCpu = medianCpu(experiment);
                System.out.printf(Locale.ROOT,
                        "%d,%d,%d,%d,%s,%.3f,%.3f,%.3f,%.3f,%.2f,%.1f,%.1f%n",
                        fixture.domain, fixture.gain, count, population.size(),
                        keys ? "canonical_key_from_raw" : "inverse",
                        medianWall(baseline), medianWall(experiment), baselineCpu, experimentCpu,
                        baselineCpu / experimentCpu, medianBytes(baseline) / (double) count,
                        medianBytes(experiment) / (double) count);
            }
        }
        System.out.println("Every sampled canonical key and every measured output agree.");
        System.out.printf(Locale.ROOT, "benchmark_total_seconds=%.3f%n",
                (System.nanoTime() - benchmarkStarted) / 1_000_000_000.0);
    }

    private static Measurement measure(Runnable operation, ThreadMXBean threads) {
        long thread = Thread.currentThread().threadId();
        long bytes = threads.getThreadAllocatedBytes(thread);
        long cpu = threads.getCurrentThreadCpuTime(), wall = System.nanoTime();
        operation.run();
        return new Measurement((System.nanoTime() - wall) / 1_000_000.0,
                (threads.getCurrentThreadCpuTime() - cpu) / 1_000_000.0,
                threads.getThreadAllocatedBytes(thread) - bytes);
    }
    private static double medianCpu(Measurement[] values) {
        return Arrays.stream(values).mapToDouble(Measurement::cpuMillis).sorted().toArray()[values.length / 2];
    }
    private static double medianWall(Measurement[] values) {
        return Arrays.stream(values).mapToDouble(Measurement::wallMillis).sorted().toArray()[values.length / 2];
    }
    private static long medianBytes(Measurement[] values) {
        return Arrays.stream(values).mapToLong(Measurement::allocatedBytes).sorted().toArray()[values.length / 2];
    }
}
