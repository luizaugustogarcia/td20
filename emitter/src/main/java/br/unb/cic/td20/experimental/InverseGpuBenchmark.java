package br.unb.cic.td20.experimental;

import br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction;
import br.unb.cic.td20.model.PrimitiveCanonicalizer;
import br.unb.cic.td20.model.PrimitivePair;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

/**
 * Isolated CPU/CUDA experiment, launched with tools/bench/InverseGpu.jsh.
 * Reports transfer-inclusive GPU wall time and all-core Java wall time.
 * No emitted key is persisted and no proof obligation is discharged here.
 */
public final class InverseGpuBenchmark {
    private static final int STRIDE = 17;
    private static volatile long blackhole;
    private InverseGpuBenchmark() { }

    private static PrimitivePair randomPair(int n, Random random) {
        for (;;) {
            int[] rho = new int[n], omega = new int[n], beta = new int[n];
            for (int i = 0; i < n; i++) rho[i] = beta[i] = i;
            for (int i = n - 1; i > 0; i--) { int j = random.nextInt(i + 1), t = rho[i]; rho[i] = rho[j]; rho[j] = t; }
            for (int i = 0; i < n; i++) omega[(rho[i] + 1) % n] = rho[(i + 1) % n];
            var result = PrimitivePair.of(omega, beta);
            if (result.isFixedPointFree()) return result;
        }
    }

    private static List<byte[]> cpuKeys(PrimitivePair child, int gain) {
        var keys = new ArrayList<byte[]>();
        var canonical = new PrimitiveCanonicalizer();
        new PrimitiveInverseReconstruction().preimages(child, gain, (omega, beta, n) -> {
            byte[] key = Arrays.copyOf(canonical.keyForKnownPair(omega, beta, n, 3), STRIDE);
            key[0] = 3; key[1] = 11; key[2] = (byte) n; keys.add(key);
        });
        keys.sort(Arrays::compareUnsigned);
        return keys;
    }

    private static void validate(PrimitivePair child, int gain) {
        var expected = cpuKeys(child, gain);
        var actual = new ArrayList<byte[]>();
        byte[] gpu = GpuInverseReconstruction.expand(List.of(child), gain, 11);
        for (int offset = 0; offset < gpu.length; offset += STRIDE) {
            if (gpu[offset] == 0) {
                for (int i = 1; i < STRIDE; i++) if (gpu[offset + i] != 0)
                    throw new IllegalStateException("Nonzero rejected GPU slot");
            } else {
                if (gpu[offset] != 3 || gpu[offset + 1] != 11 || gpu[offset + 2] != child.size() + gain)
                    throw new IllegalStateException("Malformed GPU inverse state");
                actual.add(Arrays.copyOfRange(gpu, offset, offset + STRIDE));
            }
        }
        actual.sort(Arrays::compareUnsigned);
        if (expected.size() != actual.size()) throw new IllegalStateException("GPU inverse multiset cardinality differs");
        for (int i = 0; i < expected.size(); i++) if (!Arrays.equals(expected.get(i), actual.get(i)))
            throw new IllegalStateException("GPU inverse multiset differs at " + i + " for domain " + child.size() + " gain " + gain);
    }

    private record Digest(long outputs, long hash) {
        Digest plus(Digest other) { return new Digest(outputs + other.outputs, hash + other.hash); }
    }

    private static Digest cpu(PrimitivePair child, int gain) {
        var canonical = new PrimitiveCanonicalizer();
        long[] digest = new long[2];
        new PrimitiveInverseReconstruction().preimages(child, gain, (omega, beta, n) -> {
            byte[] key = canonical.keyForKnownPair(omega, beta, n, 3);
            key[0] = 3; key[1] = 11; key[2] = (byte) n;
            int hash = Arrays.hashCode(key);
            for (int i = key.length; i < STRIDE; i++) hash *= 31;
            digest[0]++; digest[1] += hash;
        });
        return new Digest(digest[0], digest[1]);
    }

    private static Digest gpu(byte[] keys) {
        long outputs = 0, digest = 0;
        for (int offset = 0; offset < keys.length; offset += STRIDE) if (keys[offset] != 0) {
            int hash = 1;
            for (int i = 0; i < STRIDE; i++) hash = 31 * hash + keys[offset + i];
            outputs++; digest += hash;
        }
        return new Digest(outputs, digest);
    }

    private static List<PrimitivePair> persistedSample(Path directory, int n, int count) {
        RocksDB.loadLibrary();
        var sample = new ArrayList<PrimitivePair>();
        var seen = new HashSet<ByteBuffer>();
        var random = new Random(0x54443230L + n);
        try (var options = new Options().setCreateIfMissing(false).setMaxOpenFiles(32);
             var database = RocksDB.openReadOnly(options, directory.toString());
             var iterator = database.newIterator()) {
            for (int attempt = 0; attempt < count * 4 && sample.size() < count; attempt++) {
                byte[] seek = new byte[3 + (5 * n + 7) / 8];
                random.nextBytes(seek); seek[0] = 3; seek[1] = 10; seek[2] = (byte) n;
                seek[3] = (byte) (((attempt % n) << 3) | (seek[3] & 7));
                // These are bounded deterministic seeks, not a random or complete inventory.
                iterator.seek(seek);
                if (!iterator.isValid() || iterator.key()[2] != n) iterator.seek(new byte[]{3, 10, (byte) n});
                if (!iterator.isValid()) throw new IllegalStateException("Missing benchmark source stratum");
                byte[] key = iterator.key();
                if (key[0] != 3 || key[1] != 10 || key[2] != n || iterator.value().length != 0)
                    throw new IllegalStateException("Incompatible reconstruction benchmark source");
                for (int adjacent = 0; adjacent < 4 && sample.size() < count && iterator.isValid(); adjacent++, iterator.next()) {
                    key = iterator.key();
                    if (key[0] != 3 || key[1] != 10 || key[2] != n) break;
                    if (iterator.value().length != 0) throw new IllegalStateException("Unexpected source value");
                    if (seen.add(ByteBuffer.wrap(key)))
                        sample.add(PrimitivePair.fromCanonicalKey(Arrays.copyOfRange(key, 3, key.length), n));
                }
            }
            iterator.status();
        } catch (org.rocksdb.RocksDBException error) { throw new IllegalStateException(error); }
        if (sample.size() != count) throw new IllegalStateException("Could not obtain the requested distinct source sample");
        return sample;
    }

    public static void run() {
        int workers = Runtime.getRuntime().availableProcessors();
        int batchSize = Integer.getInteger("td20.inverseBenchmarkBatch", Math.max(64, workers));
        int repeats = Integer.getInteger("td20.inverseBenchmarkRepeats", 3);
        if (batchSize < workers || repeats < 1) throw new IllegalArgumentException("Use at least one source per available CPU worker");
        var random = new Random(20261119);
        int checked = 0;
        for (int n : new int[]{3, 5, 6, 8, 18, 20, 21}) for (int gain = 0; gain <= 3 && n + gain <= 21; gain++) {
            for (int i = 0; i < 2; i++) { validate(randomPair(n, random), gain); checked++; }
        }
        System.out.printf(Locale.ROOT, "exact_multisets_checked=%d, both_endpoints=true, gains=0/1/2/3%n", checked);
        String path = System.getProperty("td20.inverseBenchmarkSource", "");
        System.out.printf(Locale.ROOT, "java=%s,cpu_workers=%d,batch=%d,repeats=%d,source=%s%n",
                System.getProperty("java.version"), workers, batchSize, repeats, path.isEmpty() ? "synthetic" : path);
        System.out.println("domain,gain,outputs,all_core_cpu_ms,gpu_transfer_inclusive_ms,speedup,gpu_outputs_per_second");
        System.out.println("Persisted sources use distinct, nonuniform deterministic key seeks; they do not estimate the population.");
        try (var pool = Executors.newFixedThreadPool(workers)) {
            for (int[] fixture : new int[][]{{18, 2}, {18, 3}, {20, 1}, {21, 0}}) {
                int n = fixture[0], gain = fixture[1];
                var sources = new ArrayList<PrimitivePair>();
                if (!path.isEmpty() && n >= 20) sources.addAll(persistedSample(Path.of(path), n, batchSize));
                else for (int i = 0; i < batchSize; i++) sources.add(randomPair(n, random));
                for (int i = 0; i < Math.min(4, sources.size()); i++) validate(sources.get(i), gain);
                long[] cpuNanos = new long[repeats], gpuNanos = new long[repeats];
                Digest expected = null;
                for (int iteration = -2; iteration < repeats; iteration++) {
                    long started = System.nanoTime();
                    var jobs = new ArrayList<Future<Digest>>();
                    for (var source : sources) jobs.add(pool.submit(() -> cpu(source, gain)));
                    Digest cpu = new Digest(0, 0);
                    for (var job : jobs) cpu = cpu.plus(job.get());
                    long cpuElapsed = System.nanoTime() - started;
                    started = System.nanoTime();
                    byte[] keys = GpuInverseReconstruction.expand(sources, gain, 11);
                    long gpuElapsed = System.nanoTime() - started;
                    Digest gpu = gpu(keys);
                    if (!cpu.equals(gpu)) throw new IllegalStateException("CPU/GPU timed batch digest differs");
                    expected = cpu; blackhole = cpu.hash;
                    if (iteration >= 0) { cpuNanos[iteration] = cpuElapsed; gpuNanos[iteration] = gpuElapsed; }
                }
                Arrays.sort(cpuNanos); Arrays.sort(gpuNanos);
                double cpuMs = cpuNanos[repeats / 2] / 1e6, gpuMs = gpuNanos[repeats / 2] / 1e6;
                System.out.printf(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%.0f%n", n, gain, expected.outputs,
                        cpuMs, gpuMs, cpuMs / gpuMs, expected.outputs * 1000 / gpuMs);
            }
        } catch (Exception error) { throw new IllegalStateException(error); }
        System.out.println("GPU timing includes allocation, transfers, generation, canonicalization, and returned Java bytes; excludes disk persistence.");
        System.out.println("This benchmark establishes no proof coverage or full-run deadline.");
    }
}
