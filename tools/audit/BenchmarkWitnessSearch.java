import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.joint.JointRootGenerator;
import br.unb.cic.td20.joint.SupportClearing;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.search.WitnessSearch;
import br.unb.cic.td20.search.CpuPrefixSearch;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Sample benchmark only: no exhaustive-generation or proof-completion claim. */
public final class BenchmarkWitnessSearch {
    private static final class SampleComplete extends RuntimeException {
        SampleComplete() { super(null, null, false, false); }
    }

    public static void main(String[] args) throws Exception {
        int[] partition = args.length > 0
                ? Arrays.stream(args[0].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[]{9, 5, 5};
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
        String scope = args.length > 3 ? args[3] : "joint";
        String mode = args.length > 4 ? args[4] : "pipeline";
        if (samples < 1 || threads < 1 || Arrays.stream(partition).sum() != 21)
            throw new IllegalArgumentException("Use a 21-symbol root partition and positive samples/threads");
        if (args.length > 5 || !(scope.equals("joint") || scope.equals("unoriented"))
                || !(mode.equals("pipeline") || mode.equals("prefix")))
            throw new IllegalArgumentException("Expected PARTITION SAMPLES THREADS [joint|unoriented] [pipeline|prefix]");
        boolean prefixOnly = mode.equals("prefix");
        var pairs = new ArrayList<CyclicTargetPair>();
        long generationStarted = System.nanoTime();
        try {
            java.util.function.Consumer<CyclicTargetPair> collect = pair -> {
                pairs.add(pair.canonicalRepresentative());
                if (pairs.size() == samples) throw new SampleComplete();
            };
            if (scope.equals("unoriented"))
                JointRootGenerator.generateUnorientedResiduals(partition, collect);
            else JointRootGenerator.generateResiduals(partition, pair -> {
                if (!SupportClearing.closes(pair)) collect.accept(pair);
            });
        } catch (SampleComplete complete) { /* Deterministic prefix of this generation tree. */ }
        if (pairs.isEmpty()) throw new IllegalArgumentException("Partition has no sampled direct obligations");
        System.out.printf(java.util.Locale.ROOT, "scope=%s samples=%d generationSeconds=%.3f%n",
                scope, pairs.size(), (System.nanoTime() - generationStarted) / 1_000_000_000.0);
        var directory = prefixOnly ? null : Files.createTempDirectory("td20-search-benchmark-");
        int warmup = Math.min(2000, pairs.size() / 4);
        try (var writer = prefixOnly ? null : new AsyncCertificateWriter(directory.resolve("certificates"), 60)) {
            if (writer != null) WitnessSearch.installWriter(writer);
            try {
                for (int index = 0; index < warmup; index++) search(pairs.get(index), prefixOnly);
                if (writer != null) writer.flush();
                var next = new AtomicInteger(warmup);
                var settled = new AtomicInteger();
                var residual = new AtomicInteger();
                var depths = new AtomicIntegerArray(11);
                long started = System.nanoTime();
                try (var workers = Executors.newFixedThreadPool(threads)) {
                    var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                    for (int thread = 0; thread < threads; thread++) jobs.add(workers.submit(() -> {
                        for (int index; (index = next.getAndIncrement()) < pairs.size();) {
                            int depth = search(pairs.get(index), prefixOnly);
                            if (depth < 0) residual.incrementAndGet();
                            else { settled.incrementAndGet(); depths.incrementAndGet(depth); }
                        }
                    }));
                    for (var job : jobs) job.get();
                }
                if (writer != null) writer.flush();
                double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                System.out.printf(java.util.Locale.ROOT,
                        "partition=%s scope=%s mode=%s samples=%d warmup=%d threads=%d settled=%d residual=%d depths=%s seconds=%.3f throughput=%.3f/s database=%s%n",
                        Arrays.toString(partition), scope, mode, pairs.size(), warmup, threads, settled.get(), residual.get(), depths,
                        seconds, (pairs.size() - warmup) / seconds, directory);
            } finally { if (writer != null) WitnessSearch.uninstallWriter(writer); }
        } finally { WitnessSearch.shutdownGpuStats(); }
    }

    private static int search(CyclicTargetPair pair, boolean prefixOnly) {
        if (prefixOnly) {
            var result = CpuPrefixSearch.search(pair, 11);
            if (result.isEmpty()) return -1;
            var certificate = result.orElseThrow();
            CertificateValidator.validate(pair, certificate, 11);
            return certificate.moves().size();
        }
        var result = WitnessSearch.search(pair, 11);
        return result.disposition() == WitnessSearch.Disposition.RESIDUAL ? -1 : result.moves().size();
    }
}
