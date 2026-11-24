package br.unb.cic.td20.experimental;

import br.unb.cic.td20.avoidance.DiskStateSet;
import br.unb.cic.td20.avoidance.PrimitiveInverseReconstruction;
import br.unb.cic.td20.model.PrimitiveCanonicalizer;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.runtime.BoundedTasks;
import com.sun.management.OperatingSystemMXBean;
import lombok.val;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded real-source reconstruction benchmark including decoding, exact
 * canonical residual persistence, writer drain and the final inventory scan.
 * Source windows are deterministic and disjoint, but deliberately nonuniform.
 * It never alters the source, certificates or production checkpoints.
 */
public final class LongReconstructionBenchmark {

    private static final long SEED = 0x544432304C4F4E47L;
    private static final byte[] EMPTY = {};
    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private LongReconstructionBenchmark() {
    }

    private record Window(int domain, int index, byte[] lower, byte[] upper) {
    }

    private record WindowResult(long sources, byte[] lastKey) {
    }

    private record Result(int domain, long sources, long occurrences, long uniqueRoots, double wallSeconds, double cpuSeconds, long gcMillis) {
    }

    private record Oracle(long sources, long occurrences, long distinct) {
    }

    public static void run() throws Exception {
        final int available = Runtime.getRuntime().availableProcessors();
        final int workers = positive("td20.bench.workers", available);
        if (workers > available) {
            throw new IllegalArgumentException("Workers exceed the available CPU allocation");
        }

        final int windows = positive("td20.bench.longWindows", Math.max(256, workers * 16));
        final int windowSources = positive("td20.bench.longSourcesPerWindow", 16_384);
        final int batchSources = positive("td20.bench.longBatchSources", 512);
        final int oracleWindows = positive("td20.bench.longOracleWindows", 64);
        final int oracleNeighbors = positive("td20.bench.longOracleNeighbors", 4);
        final Path source = Path.of(System.getProperty("td20.bench.afterOne",
                "local-runtime/work/benchmark-source/after-one")).toRealPath();
        if (!Files.isRegularFile(source.resolve("CURRENT"))) {
            throw new IllegalArgumentException("Missing completed after-one database");
        }

        Path work = Path.of(System.getProperty("td20.bench.longWork",
                "local-runtime/work/reconstruction-long-benchmark-" + System.currentTimeMillis() + "-" + ProcessHandle.current().pid())).toAbsolutePath().normalize();
        Files.createDirectories(work.getParent());
        work = work.getParent().toRealPath().resolve(work.getFileName());
        if (work.startsWith(source) || source.startsWith(work) || Files.exists(work)) {
            throw new IllegalArgumentException("Benchmark work must be fresh and outside its source");
        }

        Files.createDirectory(work);
        System.out.printf(Locale.ROOT,
                "long_benchmark_start utc=%s,pid=%d,java=%s,workers=%d,seed=%d,windows_per_domain=%d,max_sources_per_window=%d,batch_sources=%d,source=%s,work=%s%n",
                Instant.now(),
                ProcessHandle.current().pid(),
                System.getProperty("java.version"),
                workers,
                SEED,
                windows,
                windowSources,
                batchSources,
                source,
                work);
        System.out.println("Scope: disjoint nonuniform key windows on both domains; source keys, decoding, generation, residual persistence, writer drain and counted scan are timed.");
        System.out.println("Source database is read-only. Certificate search and independent proof regeneration are excluded. No population ETA or proof conclusion is produced.");

        val report = new ArrayList<String>();
        report.add("td20-long-reconstruction-benchmark-v1");
        report.add("source=" + source);
        report.add("seed=" + SEED);
        report.add("workers=" + workers);
        report.add("windowsPerDomain=" + windows);
        report.add("maxSourcesPerWindow=" + windowSources);
        report.add("batchSources=" + batchSources);

        val windowReport = new ArrayList<String>();
        windowReport.add("td20-benchmark-source-windows-v1");
        windowReport.add("domain\twindow\tlowerInclusive\tupperExclusive\tsources\tlastKey");
        RocksDB.loadLibrary();

        try (val options = new Options().setCreateIfMissing(false).setMaxOpenFiles(128);
            val database = RocksDB.openReadOnly(options, source.toString())) {
            for (final int domain : new int[] { 20, 21 }) {
                val scopes = windows(domain, windows);
                final Oracle oracle = validate(database, scopes, oracleWindows, oracleNeighbors, work.resolve("oracle-" + domain));
                System.out.printf(Locale.ROOT,
                        "exact_oracle domain=%d,sources=%d,survivor_occurrences=%d,distinct_survivors=%d,production_multiset_equal=true,persisted_inventory_equal=true%n",
                        domain,
                        oracle.sources,
                        oracle.occurrences,
                        oracle.distinct);
                report.add("oracle" + domain + "Sources=" + oracle.sources);
                report.add("oracle" + domain + "Occurrences=" + oracle.occurrences);
                report.add("oracle" + domain + "Distinct=" + oracle.distinct);

                final Result result = measure(database,
                        scopes,
                        windowSources,
                        batchSources,
                        workers,
                        work.resolve("residual-roots-" + domain),
                        windowReport);
                System.out.printf(Locale.ROOT,
                        "long_benchmark_result domain=%d,sources=%d,residual_occurrences=%d,distinct_residual_roots=%d,wall_seconds=%.6f,process_cpu_seconds=%.6f,average_busy_cores=%.3f,allocated_cpu_percent=%.2f,gc_millis=%d,sources_per_second=%.3f%n",
                        domain,
                        result.sources,
                        result.occurrences,
                        result.uniqueRoots,
                        result.wallSeconds,
                        result.cpuSeconds,
                        result.cpuSeconds / result.wallSeconds,
                        100 * result.cpuSeconds / (result.wallSeconds * workers),
                        result.gcMillis,
                        result.sources / result.wallSeconds);
                report.add("domain" + domain + "Sources=" + result.sources);
                report.add("domain" + domain + "ResidualOccurrences=" + result.occurrences);
                report.add("domain" + domain + "DistinctResidualRoots=" + result.uniqueRoots);
                report.add("domain" + domain + "WallSeconds=" + result.wallSeconds);
                report.add("domain" + domain + "ProcessCpuSeconds=" + result.cpuSeconds);
                report.add("domain" + domain + "GcMillis=" + result.gcMillis);
            }
        }

        report.add("status=complete");
        publish(work.resolve("source-windows-v1.tsv"), windowReport);
        publish(work.resolve("long-reconstruction-benchmark-v1.txt"), report);
        System.out.println("Long reconstruction benchmark completed; exact oracle comparisons passed, every selected window joined, and residual inventories are sealed. Production/source databases were not modified.");
    }

    private static List<Window> windows(final int domain, final int count) {
        val random = new SplittableRandom(SEED + domain);
        val anchors = new ArrayList<byte[]>();
        for (int index = 0; index < count; index++) {
            val anchor = new byte[3 + (5 * domain + 7) / 8];
            anchor[0] = 3;
            anchor[1] = 10;
            anchor[2] = (byte) domain;
            for (int i = 3; i < anchor.length; i++) {
                anchor[i] = (byte) random.nextInt(256);
            }

            final int image = index == 0 ? 0 : 1 + (int) ((long) (index - 1) * (domain - 1) / Math.max(1, count - 1));
            anchor[3] = (byte) ((image << 3) | (anchor[3] & 7));
            if (index == 0) {
                Arrays.fill(anchor, 3, anchor.length, (byte) 0);
            }

            anchors.add(anchor);
        }

        anchors.sort(Arrays::compareUnsigned);
        for (int i = 1; i < anchors.size(); i++) {
            if (Arrays.equals(anchors.get(i - 1), anchors.get(i))) {
                throw new IllegalStateException("Duplicate deterministic benchmark anchor");
            }
        }

        val result = new ArrayList<Window>();
        for (int i = 0; i < anchors.size(); i++) {
            result.add(new Window(domain,
                    i,
                    anchors.get(i),
                    i + 1 < anchors.size() ? anchors.get(i + 1) : new byte[] { 3, 10, (byte) (domain + 1) }));
        }

        return List.copyOf(result);
    }

    private static Oracle validate(final RocksDB source,
            final List<Window> windows,
            final int limit,
            final int neighbors,
            final Path destination) throws Exception {
        val expected = new HashMap<ByteBuffer, Long>();
        val actual = new HashMap<ByteBuffer, Long>();
        val inverse = new PrimitiveInverseReconstruction();
        val predicate = new ReconstructionAlternativesBenchmark.ImmediateClosure();
        val canonical = new PrimitiveCanonicalizer();
        val recording = new AtomicBoolean(true);
        long sources = 0;

        try (val residuals = new DiskStateSet(destination,
                keys -> {
                    if (recording.get()) {
                        for (final byte[] key : keys) actual.merge(ByteBuffer.wrap(key), 1L, Long::sum);
                    }
                });
            val reads = new ReadOptions().setFillCache(false);
            val iterator = source.newIterator(reads)) {
            final int selected = Math.min(limit, windows.size());
            for (int ordinal = 0; ordinal < selected; ordinal++) {
                final Window window = windows.get((int) ((long) ordinal * windows.size() / selected));
                iterator.seek(window.lower);
                for (int neighbor = 0; neighbor < neighbors && inside(iterator, window); neighbor++, iterator.next()) {
                    final PrimitivePair pair = decode(iterator.key(), iterator.value(), window.domain);
                    inverse.preimages(pair,
                            21 - window.domain,
                            (omega, beta, n) -> {
                                if (predicate.find(omega, beta, n) >= 0) {
                                    return;
                                }

                                final byte[] key = canonical.keyForKnownPair(omega, beta, n, 3);
                                key[0] = 3;
                                key[1] = 11;
                                key[2] = (byte) n;
                                expected.merge(ByteBuffer.wrap(key), 1L, Long::sum);
                            });
                    residuals.addFinalInverseGenerated(pair);
                    sources++;
                }
            }

            iterator.status();
            residuals.prepareScan();
            // The inventory scan may republish unique keys to advisory observers.
            recording.set(false);

            final long distinct = residuals.seal();
            if (!expected.equals(actual) || distinct != expected.size()) {
                throw new IllegalStateException("Production family inverse differs from the complete inverse residual multiset");
            }

            val pending = new HashMap<>(expected);
            residuals.forEach(state -> {
                final PrimitivePair pair = state.primitivePair();
                if (pair.size() != 21 || state.maximumMoves() != 11) {
                    throw new IllegalStateException("Wrong persisted residual domain/budget");
                }

                final byte[] key = canonical.keyForKnownPair(pair.omegaImages(), pair.betaSymbols(), pair.size(), 3);
                key[0] = 3;
                key[1] = 11;
                key[2] = 21;
                if (pending.remove(ByteBuffer.wrap(key)) == null) {
                    throw new IllegalStateException("Unexpected or duplicate persisted residual key");
                }
            });
            if (!pending.isEmpty()) {
                throw new IllegalStateException("Missing persisted oracle residuals");
            }
            if (sources == 0) {
                throw new IllegalStateException("Empty oracle source scope");
            }

            return new Oracle(sources, expected.values().stream().mapToLong(Long::longValue).sum(), distinct);
        }
    }

    private static Result measure(final RocksDB source,
            final List<Window> windows,
            final int limit,
            final int batchSources,
            final int workers,
            final Path destination,
            final List<String> windowReport) throws Exception {
        val sources = new LongAdder();
        val completed = new LongAdder();
        val results = new WindowResult[windows.size()];
        val windowSources = new LongAdder[windows.size()];
        val pending = new AtomicInteger[windows.size()];
        for (int index = 0; index < windows.size(); index++) {
            windowSources[index] = new LongAdder();
            pending[index] = new AtomicInteger(1);
        }

        final int domain = windows.getFirst().domain;
        final long started = System.nanoTime(), initialCpu = OS.getProcessCpuTime(), initialGc = gcMillis();
        long unique, occurrences;

        try (val residuals = new DiskStateSet(destination);
            val progress = new Progress(domain, workers, windows.size(), limit, sources, completed, residuals)) {
            try (val tasks = new BoundedTasks(workers, "long-inverse-");
                val reads = new ReadOptions().setFillCache(false);
                val iterator = source.newIterator(reads)) {
                for (final Window window : windows) {
                    long seen = 0;
                    byte[] last = null;
                    val batch = new ArrayList<byte[]>(batchSources);
                    iterator.seek(window.lower);
                    for (; seen < limit && iterator.isValid(); iterator.next()) {
                        tasks.check();

                        final byte[] key = iterator.key();
                        if (Arrays.compareUnsigned(key, window.upper) >= 0) {
                            break;
                        }
                        if (iterator.value().length != 0) {
                            throw new IllegalStateException("Unexpected source value");
                        }

                        batch.add(key);
                        last = key;
                        seen++;
                        if (batch.size() == batchSources) {
                            submit(tasks,
                                    residuals,
                                    window,
                                    batch,
                                    sources,
                                    windowSources[window.index],
                                    pending[window.index],
                                    completed);
                            batch.clear();
                        }
                    }

                    iterator.status();
                    if (!batch.isEmpty()) {
                        submit(tasks,
                                residuals,
                                window,
                                batch,
                                sources,
                                windowSources[window.index],
                                pending[window.index],
                                completed);
                    }

                    results[window.index] = new WindowResult(seen, last);
                    if (pending[window.index].decrementAndGet() == 0) {
                        completed.increment();
                    }
                }
            }

            unique = residuals.seal();
            occurrences = residuals.writeProgress().generatedKeyOccurrences();
            if (completed.sum() != windows.size()) {
                throw new IllegalStateException("Missing completed benchmark windows");
            }
        }

        // Opening the completed output again verifies that the counted entries survived the persistence boundary.
        try (val sealed = DiskStateSet.openSealed(destination, unique)) {
            if (sealed.knownSize() != unique) {
                throw new IllegalStateException("Reopened benchmark inventory differs");
            }
        }
        for (final Window window : windows) {
            final WindowResult result = results[window.index];
            if (result == null) {
                throw new IllegalStateException("Missing window result");
            }
            if (windowSources[window.index].sum() != result.sources) {
                throw new IllegalStateException("Source callbacks did not cover the selected window");
            }

            windowReport.add(domain + "\t" + window.index + "\t" + HexFormat.of().formatHex(window.lower) + "\t" + HexFormat.of().formatHex(window.upper) + "\t" + result.sources + "\t" + (result.lastKey == null ? "none" : HexFormat.of().formatHex(result.lastKey)));
        }
        if (sources.sum() == 0) {
            throw new IllegalStateException("Empty benchmark domain");
        }

        return new Result(domain,
                sources.sum(),
                occurrences,
                unique,
                (System.nanoTime() - started) / 1e9,
                (OS.getProcessCpuTime() - initialCpu) / 1e9,
                gcMillis() - initialGc);
    }

    private static void submit(final BoundedTasks tasks,
            final DiskStateSet residuals,
            final Window window,
            final List<byte[]> batch,
            final LongAdder sources,
            final LongAdder windowSources,
            final AtomicInteger pending,
            final LongAdder completed) {
        final List<byte[]> owned = List.copyOf(batch);
        pending.incrementAndGet();
        tasks.submit(() -> {
            try {
                for (final byte[] key : owned) {
                    residuals.addFinalInverseGenerated(decode(key, EMPTY, window.domain));
                }

                sources.add(owned.size());
                windowSources.add(owned.size());
            } finally {
                if (pending.decrementAndGet() == 0) {
                    completed.increment();
                }
            }
        });
    }

    private static boolean inside(final RocksIterator iterator, final Window window) {
        return iterator.isValid() && Arrays.compareUnsigned(iterator.key(), window.upper) < 0;
    }

    private static PrimitivePair decode(final byte[] key, final byte[] value, final int domain) {
        if (key.length != 3 + (5 * domain + 7) / 8
                || key[0] != 3
                || key[1] != 10
                || Byte.toUnsignedInt(key[2]) != domain
                || value.length != 0) {
            throw new IllegalStateException("Incompatible after-one benchmark state");
        }

        final PrimitivePair pair = PrimitivePair.fromCanonicalKey(Arrays.copyOfRange(key, 3, key.length), domain);
        if (!pair.isFixedPointFree()) {
            throw new IllegalStateException("Uncontracted source state");
        }

        return pair;
    }

    private static int positive(final String key, final int fallback) {
        final int value = Integer.getInteger(key, fallback);
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be positive");
        }

        return value;
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    private static void publish(final Path path, final List<String> lines) throws Exception {
        final Path temporary = Files.createTempFile(path.getParent(), ".benchmark-", ".tmp");

        try {
            Files.write(temporary, lines);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class Progress implements AutoCloseable {

        private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("long-benchmark-progress").factory());

        Progress(final int domain,
                final int workers,
                final int windows,
                final int perWindow,
                final LongAdder sources,
                final LongAdder completed,
                final DiskStateSet residuals) {
            val previousCpu = new AtomicLong(OS.getProcessCpuTime());
            val previousTime = new AtomicLong(System.nanoTime());
            val previousSources = new AtomicLong();
            executor.scheduleAtFixedRate(() -> {
                        final long now = System.nanoTime(), cpu = OS.getProcessCpuTime(), count = sources.sum();
                        final double seconds = (now - previousTime.getAndSet(now)) / 1e9;
                        final double cpuSeconds = (cpu - previousCpu.getAndSet(cpu)) / 1e9;
                        final long delta = count - previousSources.getAndSet(count);
                        val writes = residuals.writeProgress();
                        System.out.printf(Locale.ROOT,
                                "long_benchmark_progress domain=%d,windows=%d/%d,completed_sources=%d,max_selected_sources=%d,exact_source_total=unknown_until_windows_finish,interval_sources_per_second=%.3f,interval_busy_cores=%.3f,allocated_cpu_percent=%.2f,residual_occurrences=%d,queued_occurrences=%d,ingested_occurrences=%d,unique_residuals=unknown_until_seal%n",
                                domain,
                                completed.sum(),
                                windows,
                                count,
                                (long) windows * perWindow,
                                delta / seconds,
                                cpuSeconds / seconds,
                                100 * cpuSeconds / (seconds * workers),
                                writes.generatedKeyOccurrences(),
                                writes.queuedKeyOccurrences(),
                                writes.ingestedKeyOccurrences());
                    },
                    10,
                    10,
                    TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
