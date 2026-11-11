package br.unb.cic.td20.base;

import br.unb.cic.td20.permutation.OneLinePermutation;
import br.unb.cic.td20.joint.KnownDiameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

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

    private GPUSortingSearch(int[] devices, int slotsPerDevice, long queueBytesBudget) {
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
            this.statsReporter = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("gpu-search-stats-", 0).factory());
            this.stats = new GpuSearchStats(layout, System.nanoTime());
        } else {
            this.statsReporter = null;
            this.stats = null;
        }
        initSlots(totalSlots, layout.devices(), queueBytesBudget);
        log.info("Initialized GPU sorting search: devices={}, slots/device={}, total slots={}, "
                        + "pending capacity={}, additional queue={}, queue budget/slot={} MiB",
                Arrays.toString(layout.devices()), layout.slotsPerDevice(), totalSlots,
                pendingCapacity, queueCapacity, queueBytesBudget / (1024L * 1024L));
        startSlotWorkers();
        if (statsReporter != null) {
            startStatsReporter();
        }
    }

    private static long parseStatsPeriodSeconds() {
        final var value = System.getProperty("td20.gpuStatsPeriodSeconds", "").trim();
        final long period = value.isEmpty() ? 10 : Long.parseLong(value);
        if (period < 0) {
            throw new IllegalArgumentException("td20.gpuStatsPeriodSeconds must not be negative");
        }
        return period;
    }

    private static int parseQueueCapacity() {
        final var value = System.getProperty("td20.gpuQueueCapacity", "").trim();
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
    public static synchronized void init(int[] devices, int slotsPerDevice, long queueBytesBudget) {
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

    /** Stops the optional periodic statistics reporter. */
    public static synchronized void shutdownStatsReporter() {
        if (instance != null && instance.statsReporter != null) {
            instance.statsReporter.shutdownNow();
        }
    }

    private static native void initSlots(int totalSlots, int[] devices, long queueBytesBudget);

    private static final int[] DIAMETERS = java.util.stream.IntStream.rangeClosed(0, KnownDiameters.ROOT_SYMBOLS)
            .map(KnownDiameters::knownDiameter).toArray();
    private static native int[][] searchForSortingSeq(byte[] beta, byte[] omega, int maxMoves,
                                                     boolean allowDiameterClosure, int[] diameters, int slot);

    private void startStatsReporter() {
        statsReporter.scheduleAtFixedRate(this::logWindowStats, statsPeriodSeconds, statsPeriodSeconds, TimeUnit.SECONDS);
    }

    private void startSlotWorkers() {
        for (int slot = 0; slot < totalSlots; slot++) {
            final int assignedSlot = slot;
            final int device = layout.deviceForSlot(slot);
            Thread.ofPlatform()
                    .daemon(true)
                    .name("gpu-device-" + device + "-slot-" + slot)
                    .start(() -> runSlotWorker(assignedSlot));
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
                final int[][] result = searchForSortingSeq(
                        job.beta(), job.omega(), job.maxMoves(), job.allowDiameterClosure(), DIAMETERS, slot);
                if (result == null || result.length == 0 || result[0] == null || result[0].length != 1
                        || result[0][0] < 0 || result[0][0] > 2) throw new IllegalStateException("Malformed native result status");
                kind = result[0][0];
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
        final var snapshot = stats.snapshot(System.nanoTime());
        final double elapsedSeconds = snapshot.elapsedNanos() / 1_000_000_000.0;
        final double throughput = snapshot.completed() / elapsedSeconds;
        final double occupancy = snapshot.busyNanos()
                / (snapshot.elapsedNanos() * (double) snapshot.totalSlots());
        final double averageWaitMillis = snapshot.started() == 0
                ? 0
                : snapshot.queueWaitNanos() / 1_000_000.0 / snapshot.started();
        log.info("GPU search stats (last {}s): started={}, completed={}, throughput={}/s, "
                        + "complete witnesses={}, diameter closures={}, misses={}, slot occupancy={}, avg dispatch wait={} ms, pending={}/{}, failures={}",
                format(elapsedSeconds), snapshot.started(), snapshot.completed(),
                format(throughput), snapshot.completeWitnesses(), snapshot.diameterClosures(), snapshot.misses(),
                formatPercent(occupancy), format(averageWaitMillis),
                pendingSearches.size(), pendingCapacity,
                snapshot.failed());

        for (final var device : snapshot.devices()) {
            final double deviceThroughput = device.completed() / elapsedSeconds;
            final double deviceOccupancy = device.busyNanos()
                    / (snapshot.elapsedNanos() * (double) layout.slotsPerDevice());
            final double deviceAverageWaitMillis = device.started() == 0
                    ? 0
                    : device.queueWaitNanos() / 1_000_000.0 / device.started();
            log.info("  CUDA device {}: started={}, completed={}, throughput={}/s, "
                            + "complete witnesses={}, diameter closures={}, misses={}, slot occupancy={}, avg dispatch wait={} ms, failures={}",
                    device.device(), device.started(), device.completed(),
                    format(deviceThroughput), device.completeWitnesses(), device.diameterClosures(), device.misses(), formatPercent(deviceOccupancy),
                    format(deviceAverageWaitMillis), device.failed());
        }
    }

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    public enum Disposition { RESIDUAL, COMPLETE, DIAMETER_CLOSURE }

    public record SearchOutcome(Disposition disposition, List<int[]> moves) {
        public SearchOutcome {
            moves = moves.stream().map(int[]::clone).toList();
            if (disposition == Disposition.RESIDUAL && !moves.isEmpty())
                throw new IllegalArgumentException("A search miss cannot contain moves");
        }
        @Override public List<int[]> moves() { return moves.stream().map(int[]::clone).toList(); }
    }

    @SneakyThrows
    public SearchOutcome search(final byte[] beta, final OneLinePermutation omega,
                                final int maxMoves, final boolean allowDiameterClosure) {
        final byte[] images = omega.getOneLine().clone();
        if (maxMoves < 1 || maxMoves > 11 || beta.length != images.length || beta.length < 1 || beta.length > 21)
            throw new IllegalArgumentException("Invalid bounded GPU search domain or budget");
        requirePermutation(beta); requirePermutation(images);
        final byte[] inputBeta = beta.clone();
        final var resultFuture = new CompletableFuture<int[][]>();
        final var job = new SearchJob(inputBeta, images, maxMoves, allowDiameterClosure, System.nanoTime(), resultFuture);
        pendingSearches.put(job);
        final int[][] result;
        try { result = resultFuture.get(); }
        catch (final ExecutionException e) { throw e.getCause(); }
        if (result == null || result.length < 1 || result[0] == null || result[0].length != 1
                || result[0][0] < 0 || result[0][0] > 2 || result.length - 1 > maxMoves)
            throw new IllegalStateException("Malformed native sorting result");
        final var disposition = Disposition.values()[result[0][0]];
        final var currentBeta = inputBeta.clone();
        final var moves = new ArrayList<int[]>(result.length - 1);
        for (int index = 1; index < result.length; index++) {
            final var posMove = result[index];
            if (posMove == null || posMove.length != 3) throw new IllegalStateException("Malformed native move");
            int i = posMove[0], j = posMove[1], k = posMove[2];
            if (!(0 <= i && i < j && j < k && k < currentBeta.length))
                throw new IllegalStateException("Inapplicable native positional move");
            moves.add(new int[]{currentBeta[i] & 0xFF, currentBeta[j] & 0xFF, currentBeta[k] & 0xFF});
            applyTransposition(currentBeta, i, j, k);
        }
        return new SearchOutcome(disposition, moves);
    }

    private static void requirePermutation(byte[] values) {
        int seen = 0;
        for (byte value : values) {
            int symbol = Byte.toUnsignedInt(value);
            if (symbol >= values.length || (seen & (1 << symbol)) != 0)
                throw new IllegalArgumentException("GPU inputs must be permutations of their full domain");
            seen |= 1 << symbol;
        }
    }

    private static String formatPercent(final double fraction) {
        return format(Math.clamp(fraction, 0, 1) * 100) + "%";
    }

    private record SearchJob(
            byte[] beta,
            byte[] omega,
            int maxMoves,
            boolean allowDiameterClosure,
            long queuedAtNanos,
            CompletableFuture<int[][]> result
    ) {
    }

    private static void applyTransposition(final byte[] beta, final int i, final int j, final int k) {
        val len = k - i;
        val temp = new byte[len];
        System.arraycopy(beta, i, temp, 0, len);
        System.arraycopy(temp, j - i, beta, i, k - j);
        System.arraycopy(temp, 0, beta, i + (k - j), j - i);
    }

}
