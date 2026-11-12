package br.unb.cic.td20.search;

import br.unb.cic.td20.base.GPUSortingSearch;
import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.OneLinePermutation;
import br.unb.cic.td20.joint.KnownDiameters;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The only route from a sorting search result to a persisted certificate. */
public final class WitnessSearch {
    private static final Logger log = LoggerFactory.getLogger(WitnessSearch.class);
    private static volatile AsyncCertificateWriter certificateWriter;
    private static volatile boolean gpuInitialized;

    private WitnessSearch() {
    }

    public static synchronized void installWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != null) {
            throw new IllegalStateException(
                    "A proof-scoped certificate writer is already installed");
        }
        certificateWriter = Objects.requireNonNull(writer);
    }

    public static synchronized void uninstallWriter(final AsyncCertificateWriter writer) {
        if (certificateWriter != writer) throw new IllegalStateException("Writer scope mismatch");
        certificateWriter = null;
    }

    public static void shutdownGpuStats() {
        CpuPrefixSearchStats.shutdown();
        if (gpuInitialized) GPUSortingSearch.shutdownStatsReporter();
    }

    private static GPUSortingSearch gpu() {
        final var devices = GpuDeviceLayout.parseDevices(System.getProperty("td20.gpuDevices", "0"));
        final int slots = Integer.parseInt(System.getProperty("td20.gpuSlots", "7"));
        final long budget = Long.parseLong(System.getProperty("td20.gpuBudgetMB", "1024"));
        GPUSortingSearch.init(devices, slots, Math.multiplyExact(budget, 1024L * 1024L));
        gpuInitialized = true;
        return GPUSortingSearch.getInstance();
    }

    /** Searches the full budget, accepting identity or a replayed known-diameter closure at any depth. */
    public static SearchResult search(final CyclicTargetPair input, final int maximumMoves) {
        return search(input, maximumMoves, WitnessSearch::dispatchGpu);
    }

    static SearchResult search(CyclicTargetPair input, int maximumMoves, GpuSearch dispatcher) {
        return search(input, maximumMoves, Long.MAX_VALUE, dispatcher);
    }

    /** Reuses stored certificates, then sends an unresolved reconstructed class directly to CUDA. */
    public static SearchResult searchGpu(final CyclicTargetPair input, final int maximumMoves) {
        return searchGpu(input, maximumMoves, WitnessSearch::dispatchGpu);
    }

    /** Gives the CPU prefix a wall-clock budget, then sends the untouched obligation to CUDA. */
    public static SearchResult searchWithCpuPrefixTimeout(final CyclicTargetPair input,
                                                          final int maximumMoves,
                                                          final long maximumMillis) {
        return searchWithCpuPrefixTimeout(input, maximumMoves, maximumMillis, WitnessSearch::dispatchGpu);
    }

    @FunctionalInterface
    interface GpuSearch { GPUSortingSearch.SearchOutcome search(CyclicTargetPair pair, int maximumMoves); }

    static SearchResult searchGpu(CyclicTargetPair input, int maximumMoves, GpuSearch dispatcher) {
        return search(input, maximumMoves, -1, dispatcher);
    }

    static SearchResult searchWithCpuPrefixTimeout(CyclicTargetPair input, int maximumMoves,
                                                   long maximumMillis, GpuSearch dispatcher) {
        if (maximumMillis < 0) throw new IllegalArgumentException("CPU prefix timeout cannot be negative");
        return search(input, maximumMoves, Math.multiplyExact(maximumMillis, 1_000_000L), dispatcher);
    }

    private static SearchResult search(CyclicTargetPair input, int maximumMoves,
                                       long cpuPrefixNanos, GpuSearch dispatcher) {
        Objects.requireNonNull(input);
        if (maximumMoves < 1 || maximumMoves > KnownDiameters.MAX_BUDGET)
            throw new IllegalArgumentException("Sorting budget must lie between 1 and 11");
        final var pair = input.canonicalRepresentative();
        final var writer = certificateWriter;
        if (writer == null) throw new IllegalStateException("Sorting requires a proof-scoped certificate writer");
        final var existing = writer.findResult(pair, maximumMoves);
        if (existing.isPresent()) return checked(existing.orElseThrow());
        if (cpuPrefixNanos >= 0) {
            final var cpu = cpuPrefixNanos == Long.MAX_VALUE
                    ? CpuPrefixSearch.search(pair, maximumMoves)
                    : CpuPrefixSearch.searchWithinNanos(pair, maximumMoves, cpuPrefixNanos);
            if (cpu.isPresent()) return persist(pair, cpu.orElseThrow(), maximumMoves, writer);
        }
        final var outcome = dispatcher.search(pair, maximumMoves);
        if (outcome.disposition() == GPUSortingSearch.Disposition.RESIDUAL) return SearchResult.residual();
        final var moves = outcome.moves().stream().map(move -> Cycle.of(move[0], move[1], move[2])).toList();
        final var result = outcome.disposition() == GPUSortingSearch.Disposition.COMPLETE
                ? SortingCertificate.complete(moves)
                : SortingCertificate.closure(moves);
        return persist(pair, result, maximumMoves, writer);
    }

    private static GPUSortingSearch.SearchOutcome dispatchGpu(CyclicTargetPair pair, int maximumMoves) {
        final byte[] beta = new byte[pair.getBeta().size()];
        for (int i = 0; i < beta.length; i++) beta[i] = (byte) pair.getBeta().get(i);
        return gpu().search(beta, new OneLinePermutation(pair.getOmega().getOneLineNotation()), maximumMoves, true);
    }

    /** Replays a large batch before any of its cache misses enter a sorting queue. */
    public static boolean[] lookupBatch(List<CyclicTargetPair> inputs, int maximumMoves) {
        var writer = certificateWriter;
        if (writer == null) throw new IllegalStateException("Sorting requires a proof-scoped certificate writer");
        var pairs = inputs.stream().map(CyclicTargetPair::canonicalRepresentative).toList();
        var results = writer.findResults(pairs, maximumMoves);
        var covered = new boolean[results.size()];
        long hits = 0;
        for (int i = 0; i < covered.length; i++) if (results.get(i).isPresent()) { covered[i] = true; hits++; }
        log.info("Certificate multiGet: classes={}, reusable={}, budget={}", covered.length, hits, maximumMoves);
        return covered;
    }

    private static SearchResult persist(CyclicTargetPair pair, SortingCertificate result,
                                        int maximumMoves, AsyncCertificateWriter writer) {
        CertificateValidator.validate(pair, result, maximumMoves);
        if (result.kind() == SortingCertificate.Kind.COMPLETE) writer.enqueue(pair, result.moves());
        else writer.enqueueClosure(pair, result.moves(), maximumMoves);
        writer.throwIfFailed();
        return checked(result);
    }

    private static SearchResult checked(SortingCertificate result) {
        return new SearchResult(result.kind() == SortingCertificate.Kind.COMPLETE
                ? Disposition.CERTIFICATE : Disposition.DIAMETER_CLOSURE, result.moves());
    }

    public enum Disposition { CERTIFICATE, DIAMETER_CLOSURE, RESIDUAL }
    public record SearchResult(Disposition disposition, List<Cycle> moves) {
        public SearchResult {
            Objects.requireNonNull(disposition);
            moves = List.copyOf(moves);
            if (disposition == Disposition.RESIDUAL && !moves.isEmpty())
                throw new IllegalArgumentException("A residual cannot contain moves");
        }
        public static SearchResult residual() { return new SearchResult(Disposition.RESIDUAL, List.of()); }
    }
}
