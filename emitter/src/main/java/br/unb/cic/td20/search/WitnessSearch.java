package br.unb.cic.td20.search;

import br.unb.cic.td20.base.GPUSortingSearch;
import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.PrimitiveCertificate;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.OneLinePermutation;
import lombok.val;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * The only route from a sorting search result to a persisted certificate.
 */
public final class WitnessSearch {

    private static final Logger log = LoggerFactory.getLogger(WitnessSearch.class);
    private static volatile AsyncCertificateWriter certificateWriter;
    private static volatile boolean gpuInitialized;

    private WitnessSearch() {
    }

    public static synchronized void installWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != null) {
            throw new IllegalStateException("A proof-scoped certificate writer is already installed");
        }

        certificateWriter = Objects.requireNonNull(writer);
    }

    public static synchronized void uninstallWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != writer) {
            throw new IllegalStateException("Writer scope mismatch");
        }

        certificateWriter = null;
    }

    public static void shutdownGpuStats() {
        CpuPrefixSearchStats.shutdown();
        if (gpuInitialized) {
            GPUSortingSearch.shutdownStatsReporter();
        }
    }

    private static GPUSortingSearch gpu() {
        val devices = GpuDeviceLayout.parseDevices(System.getProperty("td20.gpuDevices", "0"));
        final int slots = Integer.parseInt(System.getProperty("td20.gpuSlots", "8"));
        final long budget = Long.parseLong(System.getProperty("td20.gpuBudgetMB", "768"));
        GPUSortingSearch.init(devices, slots, Math.multiplyExact(budget, 1024L * 1024L));
        gpuInitialized = true;

        return GPUSortingSearch.getInstance();
    }

    /**
     * Production search: owned primitive state through lookup, CPU, CUDA, replay, and persistence.
     */
    public static PrimitiveSearchResult search(final PrimitivePair input, final int maximumMoves) {
        return search(input, maximumMoves, WitnessSearch::dispatchPrimitiveGpu);
    }

    static PrimitiveSearchResult search(final PrimitivePair input, final int maximumMoves, final PrimitiveGpuSearch dispatcher) {
        return search(input, maximumMoves, CpuPrefixSearch.DEFAULT_DEPTH, Long.MAX_VALUE, dispatcher);
    }

    /**
     * Direct generation already emits unique classes, so no redundant database lookup is needed.
     */
    public static PrimitiveSearchResult searchFresh(final PrimitivePair input, final int maximumMoves) {
        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                Long.MAX_VALUE,
                WitnessSearch::dispatchPrimitiveGpu,
                false);
    }

    /**
     * GPU stage after the reconstruction CPU stage; reuses checked stored results.
     */
    public static PrimitiveSearchResult searchGpu(final PrimitivePair input, final int maximumMoves) {
        return searchReconstructedGpu(input, maximumMoves, WitnessSearch::dispatchPrimitiveGpu);
    }

    /**
     * GPU stage for a bulk-lookup and CPU-prefix miss, preserving the full obligation budget.
     */
    public static PrimitiveSearchResult searchGpuMiss(final PrimitivePair input, final int maximumMoves) {
        return searchReconstructedGpu(input, maximumMoves, WitnessSearch::dispatchPrimitiveGpu, false);
    }

    public static PrimitiveSearchResult searchGpuPrefetch(final PrimitivePair input, final int maximumMoves) {
        return searchGpuPrefetch(input, maximumMoves, WitnessSearch::dispatchPrimitiveGpu);
    }

    public static PrimitiveSearchResult searchGpuPrefetchMiss(final PrimitivePair input, final int maximumMoves) {
        return searchGpuPrefetch(input, maximumMoves, WitnessSearch::dispatchPrimitiveGpu, false);
    }

    public static PrimitiveSearchResult searchWithCpuPrefixTimeout(final PrimitivePair input,
            final int maximumMoves,
            final long maximumMillis) {
        return searchWithCpuPrefixTimeout(input, maximumMoves, maximumMillis, WitnessSearch::dispatchPrimitiveGpu);
    }

    public static PrimitiveSearchResult searchCpu(final PrimitivePair input, final int maximumMoves, final long maximumMillis) {
        if (maximumMillis < 0) {
            throw new IllegalArgumentException("CPU prefix timeout cannot be negative");
        }

        return search(input, maximumMoves, 5, Math.multiplyExact(maximumMillis, 1_000_000L), null);
    }

    public static PrimitiveSearchResult searchCpuMiss(final PrimitivePair input, final int maximumMoves, final long maximumMillis) {
        if (maximumMillis < 0) {
            throw new IllegalArgumentException("CPU prefix timeout cannot be negative");
        }

        return search(input, maximumMoves, 5, Math.multiplyExact(maximumMillis, 1_000_000L), null, false);
    }

    @FunctionalInterface
    interface PrimitiveGpuSearch {

        GPUSortingSearch.PrimitiveSearchOutcome search(final PrimitivePair pair, final int maximumMoves);
    }

    @FunctionalInterface
    interface PrimitiveTimedGpuSearch {

        GPUSortingSearch.PrimitiveSearchOutcome search(final PrimitivePair pair,
                final int maximumMoves,
                final long maximumMillis,
                final int maximumSearchDepth);
    }

    static PrimitiveSearchResult searchReconstructedGpu(final PrimitivePair input,
            final int maximumMoves,
            final PrimitiveTimedGpuSearch dispatcher) {
        return searchReconstructedGpu(input, maximumMoves, dispatcher, true);
    }

    private static PrimitiveSearchResult searchReconstructedGpu(final PrimitivePair input,
            final int maximumMoves,
            final PrimitiveTimedGpuSearch dispatcher,
            final boolean lookup) {
        final long maximumMillis = reconstructionGpuMillis(maximumMoves);
        final int maximumSearchDepth = reconstructionGpuDepth(maximumMoves);

        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                -1,
                (pair, budget) -> dispatcher.search(pair, budget, maximumMillis, maximumSearchDepth),
                lookup);
    }

    static PrimitiveSearchResult searchGpuPrefetch(final PrimitivePair input,
            final int maximumMoves,
            final PrimitiveTimedGpuSearch dispatcher) {
        return searchGpuPrefetch(input, maximumMoves, dispatcher, true);
    }

    static PrimitiveSearchResult searchGpuPrefetch(final PrimitivePair input,
            final int maximumMoves,
            final PrimitiveTimedGpuSearch dispatcher,
            final boolean lookup) {
        final long configuredMillis = Long.parseLong(System.getProperty("td20.rootPrefetchGpuMillis", "100").trim());
        if (configuredMillis <= 0) {
            throw new IllegalArgumentException("Root-prefetch GPU time slice must be positive");
        }

        final long maximumMillis = new GPUSortingSearch.SearchLimits(configuredMillis).maximumMillis();

        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                -1,
                (pair, budget) -> dispatcher.search(pair, budget, maximumMillis, budget),
                lookup);
    }

    static PrimitiveSearchResult searchGpu(final PrimitivePair input, final int maximumMoves, final PrimitiveGpuSearch dispatcher) {
        return search(input, maximumMoves, CpuPrefixSearch.DEFAULT_DEPTH, -1, dispatcher);
    }

    static PrimitiveSearchResult searchWithCpuPrefixTimeout(final PrimitivePair input,
            final int maximumMoves,
            final long maximumMillis,
            final PrimitiveGpuSearch dispatcher) {
        if (maximumMillis < 0) {
            throw new IllegalArgumentException("CPU prefix timeout cannot be negative");
        }

        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                Math.multiplyExact(maximumMillis, 1_000_000L),
                dispatcher);
    }

    private static PrimitiveSearchResult search(final PrimitivePair input,
            final int maximumMoves,
            final int defaultCpuPrefixDepth,
            final long cpuPrefixNanos,
            final PrimitiveGpuSearch dispatcher) {
        return search(input, maximumMoves, defaultCpuPrefixDepth, cpuPrefixNanos, dispatcher, true);
    }

    private static PrimitiveSearchResult search(final PrimitivePair input,
            final int maximumMoves,
            final int defaultCpuPrefixDepth,
            final long cpuPrefixNanos,
            final PrimitiveGpuSearch dispatcher,
            final boolean lookup) {
        Objects.requireNonNull(input);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET) {
            throw new IllegalArgumentException("Sorting budget must lie between 1 and 11");
        }

        val pair = input.canonicalRepresentative();
        val writer = certificateWriter;
        if (writer == null) {
            throw new IllegalStateException("Sorting requires a proof-scoped certificate writer");
        }
        if (lookup) {
            val existing = writer.findPrimitiveResult(pair, maximumMoves);
            if (existing.isPresent()) {
                return checked(existing.orElseThrow());
            }
        }
        if (cpuPrefixNanos >= 0) {
            val cpu = cpuPrefixNanos == Long.MAX_VALUE ? CpuPrefixSearch.search(pair, maximumMoves) : CpuPrefixSearch.searchWithinNanos(pair, maximumMoves, defaultCpuPrefixDepth, cpuPrefixNanos);
            if (cpu.isPresent()) {
                return persist(pair, cpu.orElseThrow(), maximumMoves, writer);
            }
        }
        if (dispatcher == null) {
            return PrimitiveSearchResult.residual();
        }

        val outcome = dispatcher.search(pair, maximumMoves);
        if (outcome.disposition() == GPUSortingSearch.Disposition.RESIDUAL
                || outcome.disposition() == GPUSortingSearch.Disposition.TIMED_OUT
                || outcome.disposition() == GPUSortingSearch.Disposition.DEPTH_LIMIT) {
            return PrimitiveSearchResult.residual();
        }

        val result = outcome.disposition() == GPUSortingSearch.Disposition.COMPLETE ? PrimitiveCertificate.complete(outcome.packedMoves()) : PrimitiveCertificate.closure(outcome.packedMoves());

        return persist(pair, result, maximumMoves, writer);
    }

    private static GPUSortingSearch.PrimitiveSearchOutcome dispatchPrimitiveGpu(final PrimitivePair pair, final int maximumMoves) {
        return dispatchPrimitiveGpu(pair, maximumMoves, 0, maximumMoves);
    }

    private static GPUSortingSearch.PrimitiveSearchOutcome dispatchPrimitiveGpu(final PrimitivePair pair,
            final int maximumMoves,
            final long maximumMillis,
            final int maximumSearchDepth) {
        return gpu().search(pair.betaBytes(), pair.omegaBytes(), maximumMoves, true, maximumMillis, maximumSearchDepth);
    }

    /**
     * Batch lookup replays every hit before any cache miss enters the CPU sorting queue.
     */
    public static boolean[] lookupPrimitiveBatch(final List<PrimitivePair> inputs, final int maximumMoves) {
        val writer = certificateWriter;
        if (writer == null) {
            throw new IllegalStateException("Sorting requires a proof-scoped certificate writer");
        }

        val pairs = inputs.stream().map(PrimitivePair::canonicalRepresentative).toList();
        val results = writer.findPrimitiveResults(pairs, maximumMoves);
        val covered = new boolean[results.size()];
        long hits = 0;
        for (int i = 0; i < covered.length; i++) {
            if (results.get(i).isPresent()) {
                covered[i] = true;
                hits++;
            }
        }

        log.debug("Certificate multiGet: classes={}, reusable={}, budget={}", covered.length, hits, maximumMoves);

        return covered;
    }

    private static PrimitiveSearchResult persist(final PrimitivePair pair,
            final PrimitiveCertificate result,
            final int maximumMoves,
            final AsyncCertificateWriter writer) {
        CertificateValidator.validate(pair, result, maximumMoves);
        writer.enqueue(pair, result, maximumMoves);
        writer.throwIfFailed();

        return checked(result);
    }

    private static PrimitiveSearchResult checked(final PrimitiveCertificate result) {
        return new PrimitiveSearchResult(result.kind() == PrimitiveCertificate.Kind.COMPLETE ? Disposition.CERTIFICATE : Disposition.DIAMETER_CLOSURE,
                result);
    }

    public record PrimitiveSearchResult(Disposition disposition, PrimitiveCertificate certificate) {

        public PrimitiveSearchResult {
            Objects.requireNonNull(disposition);
            if (disposition == Disposition.RESIDUAL) {
                if (certificate != null) {
                    throw new IllegalArgumentException("A residual cannot contain a certificate");
                }
            } else {
                Objects.requireNonNull(certificate);
                if ((disposition == Disposition.CERTIFICATE) != (certificate.kind() == PrimitiveCertificate.Kind.COMPLETE)) {
                    throw new IllegalArgumentException("Search disposition disagrees with certificate kind");
                }
            }
        }

        public static PrimitiveSearchResult residual() {
            return new PrimitiveSearchResult(Disposition.RESIDUAL, null);
        }
    }

    /**
     * Searches the full budget, accepting identity or a replayed known-diameter closure at any depth.
     */
    public static SearchResult search(final CyclicTargetPair input, final int maximumMoves) {
        return search(input, maximumMoves, WitnessSearch::dispatchGpu);
    }

    static SearchResult search(final CyclicTargetPair input, final int maximumMoves, final GpuSearch dispatcher) {
        return search(input, maximumMoves, CpuPrefixSearch.DEFAULT_DEPTH, Long.MAX_VALUE, dispatcher);
    }

    /**
     * Direct root/endpoint generation supplies unique classes; search without a certificate lookup.
     */
    public static SearchResult searchFresh(final CyclicTargetPair input, final int maximumMoves) {
        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                Long.MAX_VALUE,
                WitnessSearch::dispatchGpu,
                false);
    }

    /**
     * Reuses stored certificates and bounds intermediate CUDA work; final roots have no time cutoff.
     */
    public static SearchResult searchGpu(final CyclicTargetPair input, final int maximumMoves) {
        return searchReconstructedGpu(input, maximumMoves, WitnessSearch::dispatchGpu);
    }

    /**
     * Searches a reconstruction bulk-lookup miss without another database read.
     */
    public static SearchResult searchGpuMiss(final CyclicTargetPair input, final int maximumMoves) {
        return searchReconstructedGpu(input, maximumMoves, WitnessSearch::dispatchGpu, false);
    }

    /**
     * Advisory full-depth GPU slice; incomplete work remains for the mandatory final scan.
     */
    public static SearchResult searchGpuPrefetch(final CyclicTargetPair input, final int maximumMoves) {
        return searchGpuPrefetch(input, maximumMoves, WitnessSearch::dispatchGpu);
    }

    /**
     * Searches a prepared bulk-lookup miss without issuing a second random database read.
     */
    public static SearchResult searchGpuPrefetchMiss(final CyclicTargetPair input, final int maximumMoves) {
        return searchGpuPrefetch(input, maximumMoves, WitnessSearch::dispatchGpu, false);
    }

    /**
     * Gives the CPU prefix a wall-clock budget, then sends the untouched obligation to CUDA.
     */
    public static SearchResult searchWithCpuPrefixTimeout(final CyclicTargetPair input, final int maximumMoves, final long maximumMillis) {
        return searchWithCpuPrefixTimeout(input, maximumMoves, maximumMillis, WitnessSearch::dispatchGpu);
    }

    /**
     * Replays stored results and searches reconstruction prefixes to depth five by default, without GPU dispatch.
     */
    public static SearchResult searchCpu(final CyclicTargetPair input, final int maximumMoves, final long maximumMillis) {
        if (maximumMillis < 0) {
            throw new IllegalArgumentException("CPU prefix timeout cannot be negative");
        }

        return search(input, maximumMoves, 5, Math.multiplyExact(maximumMillis, 1_000_000L), null);
    }

    /**
     * CPU stage for a reconstruction bulk-lookup miss; the original budget is preserved.
     */
    public static SearchResult searchCpuMiss(final CyclicTargetPair input, final int maximumMoves, final long maximumMillis) {
        if (maximumMillis < 0) {
            throw new IllegalArgumentException("CPU prefix timeout cannot be negative");
        }

        return search(input, maximumMoves, 5, Math.multiplyExact(maximumMillis, 1_000_000L), null, false);
    }

    @FunctionalInterface
    interface GpuSearch {

        GPUSortingSearch.SearchOutcome search(final CyclicTargetPair pair, final int maximumMoves);
    }

    @FunctionalInterface
    interface TimedGpuSearch {

        GPUSortingSearch.SearchOutcome search(final CyclicTargetPair pair,
                final int maximumMoves,
                final long maximumMillis,
                final int maximumSearchDepth);
    }

    static SearchResult searchReconstructedGpu(final CyclicTargetPair input, final int maximumMoves, final TimedGpuSearch dispatcher) {
        return searchReconstructedGpu(input, maximumMoves, dispatcher, true);
    }

    private static SearchResult searchReconstructedGpu(final CyclicTargetPair input,
            final int maximumMoves,
            final TimedGpuSearch dispatcher,
            final boolean lookup) {
        final long maximumMillis = reconstructionGpuMillis(maximumMoves);
        final int maximumSearchDepth = reconstructionGpuDepth(maximumMoves);

        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                -1,
                (pair, budget) -> dispatcher.search(pair, budget, maximumMillis, maximumSearchDepth),
                lookup);
    }

    static SearchResult searchGpuPrefetch(final CyclicTargetPair input, final int maximumMoves, final TimedGpuSearch dispatcher) {
        return searchGpuPrefetch(input, maximumMoves, dispatcher, true);
    }

    static SearchResult searchGpuPrefetch(final CyclicTargetPair input,
            final int maximumMoves,
            final TimedGpuSearch dispatcher,
            final boolean lookup) {
        final long configuredMillis = Long.parseLong(System.getProperty("td20.rootPrefetchGpuMillis", "100").trim());
        if (configuredMillis <= 0) {
            throw new IllegalArgumentException("Root-prefetch GPU time slice must be positive");
        }

        final long maximumMillis = new GPUSortingSearch.SearchLimits(configuredMillis).maximumMillis();

        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                -1,
                (pair, budget) -> dispatcher.search(pair, budget, maximumMillis, budget),
                lookup);
    }

    static int reconstructionGpuDepth(final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET) {
            throw new IllegalArgumentException("Sorting budget must lie between 1 and 11");
        }

        return maximumMoves;
    }

    static long reconstructionGpuMillis(final int maximumMoves) {
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET) {
            throw new IllegalArgumentException("Sorting budget must lie between 1 and 11");
        }
        if (maximumMoves == KnownDiameters.MAX_BUDGET) {
            return 0;
        }

        final long maximumMillis = Long.parseLong(System.getProperty("td20.reconstructionGpuMillis", "0").trim());

        return new GPUSortingSearch.SearchLimits(maximumMillis).maximumMillis();
    }

    static SearchResult searchGpu(final CyclicTargetPair input, final int maximumMoves, final GpuSearch dispatcher) {
        return search(input, maximumMoves, CpuPrefixSearch.DEFAULT_DEPTH, -1, dispatcher);
    }

    static SearchResult searchWithCpuPrefixTimeout(final CyclicTargetPair input,
            final int maximumMoves,
            final long maximumMillis,
            final GpuSearch dispatcher) {
        if (maximumMillis < 0) {
            throw new IllegalArgumentException("CPU prefix timeout cannot be negative");
        }

        return search(input,
                maximumMoves,
                CpuPrefixSearch.DEFAULT_DEPTH,
                Math.multiplyExact(maximumMillis, 1_000_000L),
                dispatcher);
    }

    private static SearchResult search(final CyclicTargetPair input,
            final int maximumMoves,
            final int defaultCpuPrefixDepth,
            final long cpuPrefixNanos,
            final GpuSearch dispatcher) {
        return search(input, maximumMoves, defaultCpuPrefixDepth, cpuPrefixNanos, dispatcher, true);
    }

    private static SearchResult search(final CyclicTargetPair input,
            final int maximumMoves,
            final int defaultCpuPrefixDepth,
            final long cpuPrefixNanos,
            final GpuSearch dispatcher,
            final boolean lookup) {
        Objects.requireNonNull(input);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET) {
            throw new IllegalArgumentException("Sorting budget must lie between 1 and 11");
        }

        val pair = input.canonicalRepresentative();
        val writer = certificateWriter;
        if (writer == null) {
            throw new IllegalStateException("Sorting requires a proof-scoped certificate writer");
        }
        if (lookup) {
            val existing = writer.findResult(pair, maximumMoves);
            if (existing.isPresent()) {
                return checked(existing.orElseThrow());
            }
        }
        if (cpuPrefixNanos >= 0) {
            val cpu = cpuPrefixNanos == Long.MAX_VALUE ? CpuPrefixSearch.search(pair, maximumMoves) : CpuPrefixSearch.searchWithinNanos(pair, maximumMoves, defaultCpuPrefixDepth, cpuPrefixNanos);
            if (cpu.isPresent()) {
                return persist(pair, cpu.orElseThrow(), maximumMoves, writer);
            }
        }
        if (dispatcher == null) {
            return SearchResult.residual();
        }

        val outcome = dispatcher.search(pair, maximumMoves);
        if (outcome.disposition() == GPUSortingSearch.Disposition.RESIDUAL
                || outcome.disposition() == GPUSortingSearch.Disposition.TIMED_OUT
                || outcome.disposition() == GPUSortingSearch.Disposition.DEPTH_LIMIT) {
            return SearchResult.residual();
        }

        val moves = outcome.moves().stream().map(move -> Cycle.of(move[0], move[1], move[2])).toList();
        val result = outcome.disposition() == GPUSortingSearch.Disposition.COMPLETE ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);

        return persist(pair, result, maximumMoves, writer);
    }

    private static GPUSortingSearch.SearchOutcome dispatchGpu(final CyclicTargetPair pair, final int maximumMoves) {
        return dispatchGpu(pair, maximumMoves, 0, maximumMoves);
    }

    private static GPUSortingSearch.SearchOutcome dispatchGpu(final CyclicTargetPair pair,
            final int maximumMoves,
            final long maximumMillis,
            final int maximumSearchDepth) {
        val beta = new byte[pair.getBeta().size()];
        for (int i = 0; i < beta.length; i++) {
            beta[i] = (byte) pair.getBeta().get(i);
        }

        return gpu().search(beta,
                new OneLinePermutation(pair.getOmega().getOneLineNotation()),
                maximumMoves,
                true,
                maximumMillis,
                maximumSearchDepth);
    }

    /**
     * Replays a large batch before any of its cache misses enter a sorting queue.
     */
    public static boolean[] lookupBatch(final List<CyclicTargetPair> inputs, final int maximumMoves) {
        val writer = certificateWriter;
        if (writer == null) {
            throw new IllegalStateException("Sorting requires a proof-scoped certificate writer");
        }

        val pairs = inputs.stream().map(CyclicTargetPair::canonicalRepresentative).toList();
        val results = writer.findResults(pairs, maximumMoves);
        val covered = new boolean[results.size()];
        long hits = 0;
        for (int i = 0; i < covered.length; i++) {
            if (results.get(i).isPresent()) {
                covered[i] = true;
                hits++;
            }
        }

        log.debug("Certificate multiGet: classes={}, reusable={}, budget={}", covered.length, hits, maximumMoves);

        return covered;
    }

    private static SearchResult persist(final CyclicTargetPair pair,
            final SortingCertificate result,
            final int maximumMoves,
            final AsyncCertificateWriter writer) {
        CertificateValidator.validate(pair, result, maximumMoves);
        if (result.kind() == SortingCertificate.Kind.COMPLETE) {
            writer.enqueue(pair, result.moves());
        } else {
            writer.enqueueClosure(pair, result.moves(), maximumMoves);
        }

        writer.throwIfFailed();

        return checked(result);
    }

    private static SearchResult checked(final SortingCertificate result) {
        return new SearchResult(result.kind() == SortingCertificate.Kind.COMPLETE ? Disposition.CERTIFICATE : Disposition.DIAMETER_CLOSURE,
                result.moves());
    }

    public enum Disposition {

        CERTIFICATE, DIAMETER_CLOSURE, RESIDUAL
    }

    public record SearchResult(Disposition disposition, List<Cycle> moves) {

        public SearchResult {
            Objects.requireNonNull(disposition);
            moves = List.copyOf(moves);
            if (disposition == Disposition.RESIDUAL && !moves.isEmpty()) {
                throw new IllegalArgumentException("A residual cannot contain moves");
            }
        }

        public static SearchResult residual() {
            return new SearchResult(Disposition.RESIDUAL, List.of());
        }
    }
}
