package br.unb.cic.td20.base;

import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.permutation.OneLinePermutation;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GPUSortingSearch {

    static {
        System.loadLibrary("td20_jni");
    }

    private static volatile GPUSortingSearch instance;

    private final GpuDeviceLayout layout;
    private final int totalSlots;

    @Getter
    private final int queueCapacity;

    private final int pendingCapacity;
    private final ArrayBlockingQueue<SearchJob> pendingSearches;
    private final ScheduledExecutorService statsReporter;
    private final GpuSearchStats stats;
    private final long statsPeriodSeconds;

    private GPUSortingSearch(final int[] devices, final int slotsPerDevice, final long queueBytesBudget) {
        if (queueBytesBudget <= 0) {
            throw new IllegalArgumentException("queueBytesBudget must be positive");
        }

        this.layout = new GpuDeviceLayout(devices, slotsPerDevice);
        this.totalSlots = layout.totalSlots();
        this.queueCapacity = parseQueueCapacity();

        try {
            this.pendingCapacity = Math.addExact(totalSlots, queueCapacity);
        } catch (final ArithmeticException e) {
            throw new IllegalArgumentException("GPU pending-search capacity is too large", e);
        }

        this.pendingSearches = new ArrayBlockingQueue<>(pendingCapacity);
        this.statsPeriodSeconds = parseStatsPeriodSeconds();
        if (statsPeriodSeconds > 0) {
            this.statsReporter = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("gpu-search-stats-", 0).factory());
            this.stats = new GpuSearchStats(layout, System.nanoTime());
        } else {
            this.statsReporter = null;
            this.stats = null;
        }

        initSlots(totalSlots, layout.devices(), queueBytesBudget);
        log.info("Initialized GPU sorting search: devices={}, slots/device={}, total slots={}, " + "pending capacity={}, additional queue={}, queue budget/slot={} MiB",
                Arrays.toString(layout.devices()),
                layout.slotsPerDevice(),
                totalSlots,
                pendingCapacity,
                queueCapacity,
                queueBytesBudget / (1024L * 1024L));
        startSlotWorkers();
        if (statsReporter != null) {
            startStatsReporter();
        }
    }

    private static long parseStatsPeriodSeconds() {
        val value = System.getProperty("td20.gpuStatsPeriodSeconds", "").trim();
        final long period = value.isEmpty() ? 10 : Long.parseLong(value);
        if (period < 0) {
            throw new IllegalArgumentException("td20.gpuStatsPeriodSeconds must not be negative");
        }

        return period;
    }

    private static int parseQueueCapacity() {
        val value = System.getProperty("td20.gpuQueueCapacity", "").trim();
        final int capacity = value.isEmpty() ? 0 : Integer.parseInt(value);
        if (capacity < 0) {
            throw new IllegalArgumentException("td20.gpuQueueCapacity must not be negative");
        }

        return capacity;
    }

    /**
     * Initializes the singleton instance with the given parameters.
     * Must be called at least once before {@link #getInstance()}.
     * Subsequent calls are ignored (the first initialization wins).
     */
    public static synchronized void init(final int[] devices, final int slotsPerDevice, final long queueBytesBudget) {
        if (instance != null) {
            return;
        }

        instance = new GPUSortingSearch(devices, slotsPerDevice, queueBytesBudget);
    }

    /**
     * Returns the singleton instance.
     *
     * @throws IllegalStateException if {@link #init} has not been called
     */
    public static GPUSortingSearch getInstance() {
        if (instance == null) {
            throw new IllegalStateException("GPUSortingSearch has not been initialized. Call init() first.");
        }

        return instance;
    }

    /**
     * Stops the optional periodic statistics reporter.
     */
    public static synchronized void shutdownStatsReporter() {
        if (instance != null && instance.statsReporter != null) {
            instance.statsReporter.shutdownNow();
        }
    }

    private static native void initSlots(final int totalSlots, final int[] devices, final long queueBytesBudget);

    private static final int[] DIAMETERS = java.util.stream.IntStream.rangeClosed(0, KnownDiameters.ROOT_SYMBOLS).map(KnownDiameters::knownDiameter).toArray();

    private static native int[][] searchForSortingSeqWithLimits(final byte[] beta,
            final byte[] omega,
            final int maxMoves,
            final boolean allowDiameterClosure,
            final int maximumSearchDepth,
            final long maximumNanos,
            final int[] diameters,
            final int slot);

    private void startStatsReporter() {
        statsReporter.scheduleAtFixedRate(this::logWindowStats, statsPeriodSeconds, statsPeriodSeconds, TimeUnit.SECONDS);
    }

    private void startSlotWorkers() {
        for (int slot = 0; slot < totalSlots; slot++) {
            final int assignedSlot = slot;
            final int device = layout.deviceForSlot(slot);
            Thread.ofPlatform().daemon(true).name("gpu-device-" + device + "-slot-" + slot).start(() -> runSlotWorker(assignedSlot));
        }
    }

    private void runSlotWorker(final int slot) {
        while (!Thread.currentThread().isInterrupted()) {
            SearchJob job = null;
            var failed = true;
            int kind = 0;

            try {
                job = pendingSearches.take();
                if (stats != null) {
                    stats.acquired(slot, job.queuedAtNanos(), System.nanoTime());
                }

                final int[][] result = searchForSortingSeqWithLimits(job.beta(),
                        job.omega(),
                        job.maxMoves(),
                        job.allowDiameterClosure(),
                        job.maximumSearchDepth(),
                        job.maximumNanos(),
                        DIAMETERS,
                        slot);
                kind = SearchOutcome.requireNativeResult(result, job.maxMoves(), job.beta().length).ordinal();
                failed = false;
                job.result().complete(result);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Throwable throwable) {
                if (job != null) {
                    job.result().completeExceptionally(throwable);
                } else {
                    log.error("GPU slot worker {} failed before acquiring a search", slot, throwable);
                }
            } finally {
                if (job != null && stats != null) {
                    stats.released(slot, System.nanoTime(), failed, kind);
                }
            }
        }
    }

    private void logWindowStats() {
        val snapshot = stats.snapshot(System.nanoTime());
        final double elapsedSeconds = snapshot.elapsedNanos() / 1_000_000_000.0;
        final double throughput = snapshot.completed() / elapsedSeconds;
        final double occupancy = snapshot.busyNanos() / (snapshot.elapsedNanos() * (double) snapshot.totalSlots());
        final double averageWaitMillis = snapshot.started() == 0 ? 0 : snapshot.queueWaitNanos() / 1_000_000.0 / snapshot.started();
        log.info("GPU search stats (last {}s): started={}, completed={}, throughput={}/s, " + "complete witnesses={}, diameter closures={}, misses={}, timeouts={}, slot occupancy={}, avg dispatch wait={} ms, pending={}/{}, failures={}",
                format(elapsedSeconds),
                snapshot.started(),
                snapshot.completed(),
                format(throughput),
                snapshot.completeWitnesses(),
                snapshot.diameterClosures(),
                snapshot.misses(),
                snapshot.timeouts(),
                formatPercent(occupancy),
                format(averageWaitMillis),
                pendingSearches.size(),
                pendingCapacity,
                snapshot.failed());

        for (val device : snapshot.devices()) {
            final double deviceThroughput = device.completed() / elapsedSeconds;
            final double deviceOccupancy = device.busyNanos() / (snapshot.elapsedNanos() * (double) layout.slotsPerDevice());
            final double deviceAverageWaitMillis = device.started() == 0 ? 0 : device.queueWaitNanos() / 1_000_000.0 / device.started();
            log.info("  CUDA device {}: started={}, completed={}, throughput={}/s, " + "complete witnesses={}, diameter closures={}, misses={}, timeouts={}, slot occupancy={}, avg dispatch wait={} ms, failures={}",
                    device.device(),
                    device.started(),
                    device.completed(),
                    format(deviceThroughput),
                    device.completeWitnesses(),
                    device.diameterClosures(),
                    device.misses(),
                    device.timeouts(),
                    formatPercent(deviceOccupancy),
                    format(deviceAverageWaitMillis),
                    device.failed());
        }
    }

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    public enum Disposition {

        RESIDUAL, COMPLETE, DIAMETER_CLOSURE, TIMED_OUT, DEPTH_LIMIT
    }

    /**
     * Per-search CUDA execution allowance; zero leaves the search without a time cutoff.
     */
    public record SearchLimits(long maximumMillis) {

        public SearchLimits {
            if (maximumMillis < 0) {
                throw new IllegalArgumentException("GPU timeout must not be negative");
            }

            try {
                Math.multiplyExact(maximumMillis, 1_000_000L);
            } catch (final ArithmeticException error) {
                throw new IllegalArgumentException("GPU timeout is too large", error);
            }
        }

        public long maximumNanos() {
            return maximumMillis * 1_000_000L;
        }
    }

    public record SearchOutcome(Disposition disposition, List<int[]> moves) {

        public SearchOutcome {
            Objects.requireNonNull(disposition);
            moves = moves.stream().map(int[]::clone).toList();
            if ((disposition == Disposition.RESIDUAL
                    || disposition == Disposition.TIMED_OUT
                    || disposition == Disposition.DEPTH_LIMIT)
                    && !moves.isEmpty()) {
                throw new IllegalArgumentException("An unresolved search cannot contain moves");
            }
        }

        @Override
        public List<int[]> moves() {
            return moves.stream().map(int[]::clone).toList();
        }

        /**
         * Validate the native status and every positional move before counting a completed result.
         */
        static Disposition requireNativeResult(final int[][] result, final int maximumMoves, final int domain) {
            if (result == null
                    || result.length < 1
                    || result[0] == null
                    || result[0].length != 1
                    || result[0][0] < 0
                    || result[0][0] >= Disposition.values().length
                    || result.length - 1 > maximumMoves) {
                throw new IllegalStateException("Malformed native sorting result");
            }

            val disposition = Disposition.values()[result[0][0]];
            if ((disposition == Disposition.RESIDUAL
                    || disposition == Disposition.TIMED_OUT
                    || disposition == Disposition.DEPTH_LIMIT)
                    && result.length != 1) {
                throw new IllegalStateException("An unresolved native result contains moves");
            }
            for (int index = 1; index < result.length; index++) {
                val move = result[index];
                if (move == null || move.length != 3 || !(0 <= move[0] && move[0] < move[1] && move[1] < move[2] && move[2] < domain)) {
                    throw new IllegalStateException("Inapplicable native positional move");
                }
            }

            return disposition;
        }
    }

    /**
     * Immutable packed transpositions returned by the production byte-array bridge.
     */
    public record PrimitiveSearchOutcome(Disposition disposition, int[] packedMoves) {

        public PrimitiveSearchOutcome {
            Objects.requireNonNull(disposition);
            packedMoves = packedMoves.clone();
            if (packedMoves.length > KnownDiameters.MAX_BUDGET) {
                throw new IllegalArgumentException("GPU result exceeds the maximum move budget");
            }
            if ((disposition == Disposition.RESIDUAL
                    || disposition == Disposition.TIMED_OUT
                    || disposition == Disposition.DEPTH_LIMIT)
                    && packedMoves.length != 0) {
                throw new IllegalArgumentException("An unresolved search cannot contain moves");
            }
            for (final int move : packedMoves) {
                final int a = move & 31, b = (move >>> 5) & 31, c = (move >>> 10) & 31;
                if (move < 0 || move >= (1 << 15) || a == b || b == c || a == c) {
                    throw new IllegalArgumentException("GPU result requires packed distinct-symbol triples");
                }
            }
        }

        @Override
        public int[] packedMoves() {
            return packedMoves.clone();
        }

        public int moveCount() {
            return packedMoves.length;
        }

        public int moveAt(final int index) {
            return packedMoves[index];
        }

        @Override
        public boolean equals(final Object object) {
            return object instanceof PrimitiveSearchOutcome other
                    && disposition == other.disposition
                    && Arrays.equals(packedMoves, other.packedMoves);
        }

        @Override
        public int hashCode() {
            return 31 * disposition.hashCode() + Arrays.hashCode(packedMoves);
        }
    }

    public PrimitiveSearchOutcome search(final byte[] beta, final byte[] omega, final int maxMoves, final boolean allowDiameterClosure) {
        return search(beta, omega, maxMoves, allowDiameterClosure, 0, maxMoves);
    }

    public PrimitiveSearchOutcome search(final byte[] beta,
            final byte[] omega,
            final int maxMoves,
            final boolean allowDiameterClosure,
            final long maximumMillis) {
        return search(beta, omega, maxMoves, allowDiameterClosure, maximumMillis, maxMoves);
    }

    public SearchOutcome search(final byte[] beta, final OneLinePermutation omega, final int maxMoves, final boolean allowDiameterClosure) {
        return search(beta, omega, maxMoves, allowDiameterClosure, 0);
    }

    /**
     * A timeout returns an unresolved result; successful prefixes keep the full move budget.
     */
    @SneakyThrows
    public SearchOutcome search(final byte[] beta,
            final OneLinePermutation omega,
            final int maxMoves,
            final boolean allowDiameterClosure,
            final long maximumMillis) {
        return search(beta, omega, maxMoves, allowDiameterClosure, maximumMillis, maxMoves);
    }

    /**
     * Exploration depth is independent of the full budget used for diameter closures.
     */
    @SneakyThrows
    public SearchOutcome search(final byte[] beta,
            final OneLinePermutation omega,
            final int maxMoves,
            final boolean allowDiameterClosure,
            final long maximumMillis,
            final int maximumSearchDepth) {
        val outcome = search(beta,
                omega.getOneLine(),
                maxMoves,
                allowDiameterClosure,
                maximumMillis,
                maximumSearchDepth);
        val moves = new ArrayList<int[]>(outcome.moveCount());
        for (int index = 0; index < outcome.moveCount(); index++) {
            final int move = outcome.moveAt(index);
            moves.add(new int[] { move & 31, (move >>> 5) & 31, (move >>> 10) & 31 });
        }

        return new SearchOutcome(outcome.disposition(), moves);
    }

    /**
     * Production bridge: bounded owned byte inputs, packed output, unchanged CUDA ABI.
     */
    @SneakyThrows
    public PrimitiveSearchOutcome search(final byte[] beta,
            final byte[] omega,
            final int maxMoves,
            final boolean allowDiameterClosure,
            final long maximumMillis,
            final int maximumSearchDepth) {
        Objects.requireNonNull(beta);
        Objects.requireNonNull(omega);
        if (maximumSearchDepth < 1 || maximumSearchDepth > maxMoves) {
            throw new IllegalArgumentException("GPU search depth must lie between 1 and the move budget");
        }

        final long maximumNanos = new SearchLimits(maximumMillis).maximumNanos();
        final byte[] images = omega.clone();
        if (maxMoves < 1 || maxMoves > 11 || beta.length != images.length || beta.length < 1 || beta.length > 21) {
            throw new IllegalArgumentException("Invalid bounded GPU search domain or budget");
        }

        requirePermutation(beta);
        requirePermutation(images);

        final byte[] inputBeta = beta.clone();
        val resultFuture = new CompletableFuture<int[][]>();
        val job = new SearchJob(inputBeta,
                images,
                maxMoves,
                allowDiameterClosure,
                maximumSearchDepth,
                maximumNanos,
                System.nanoTime(),
                resultFuture);
        pendingSearches.put(job);

        final int[][] result;

        try {
            result = resultFuture.get();
        } catch (final ExecutionException e) {
            throw e.getCause();
        }

        val disposition = SearchOutcome.requireNativeResult(result, maxMoves, inputBeta.length);
        val currentBeta = inputBeta.clone();
        val moves = new int[result.length - 1];
        val scratch = new byte[currentBeta.length];
        for (int index = 1; index < result.length; index++) {
            val posMove = result[index];
            if (posMove == null || posMove.length != 3) {
                throw new IllegalStateException("Malformed native move");
            }

            final int i = posMove[0], j = posMove[1], k = posMove[2];
            if (!(0 <= i && i < j && j < k && k < currentBeta.length)) {
                throw new IllegalStateException("Inapplicable native positional move");
            }

            moves[index - 1] = Byte.toUnsignedInt(currentBeta[i]) | (Byte.toUnsignedInt(currentBeta[j]) << 5) | (Byte.toUnsignedInt(currentBeta[k]) << 10);
            applyTransposition(currentBeta, scratch, i, j, k);
        }

        return new PrimitiveSearchOutcome(disposition, moves);
    }

    private static void requirePermutation(final byte[] values) {
        int seen = 0;
        for (final byte value : values) {
            final int symbol = Byte.toUnsignedInt(value);
            if (symbol >= values.length || (seen & (1 << symbol)) != 0) {
                throw new IllegalArgumentException("GPU inputs must be permutations of their full domain");
            }

            seen |= 1 << symbol;
        }
    }

    private static String formatPercent(final double fraction) {
        return format(Math.clamp(fraction, 0, 1) * 100) + "%";
    }

    private record SearchJob(byte[] beta, byte[] omega, int maxMoves, boolean allowDiameterClosure, int maximumSearchDepth, long maximumNanos, long queuedAtNanos, CompletableFuture<int[][]> result) {
    }

    private static void applyTransposition(final byte[] beta, final byte[] temp, final int i, final int j, final int k) {
        val len = k - i;
        System.arraycopy(beta, i, temp, 0, len);
        System.arraycopy(temp, j - i, beta, i, k - j);
        System.arraycopy(temp, 0, beta, i + (k - j), j - i);
    }
}
