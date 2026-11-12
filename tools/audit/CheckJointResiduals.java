import br.unb.cic.td20.model.CyclicTargetCandidate;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.joint.JointReductionAnalysis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Independent small-domain generation audit for joint-reduction residuals.
 * Exhausts all omega permutations with canonical beta; the production bracelet
 * generator and the native constrained generation tree are not used. Only
 * validated cyclic-target pairs receive ExtendedToricClassKey values.
 *
 * <p>Usage: java -cp emitter/target/classes:&lt;dependencies&gt;
 * tools/audit/CheckJointResiduals.java --domain=8 [--partition=5,3]
 * [--read=/tmp/native.txt] [--write=/tmp/java.txt]. Domains above ten require
 * --verify-only --read=...; that mode verifies every listed representative but
 * explicitly does not certify completeness. Reports and representative files
 * are generated artifacts and must remain untracked.</p>
 *
 * <p>The residual scope excludes totally unoriented roots, which remain
 * separate obligations. An oriented residual has no immediate gain-at-least-two
 * move and no favorable joint-two prefix: gain pattern (1,3) or (1,2).
 * Totally unoriented states receive no structural reduction at either depth. Structural availability is not a sorting certificate. Neither this
 * audit nor an empty residual family proves TD(20).</p>
 */
public final class CheckJointResiduals {
    private static final String MAGIC = "TD20-JOINT2-RESIDUALS";
    private static final int VERSION = 1;
    private static final int MAXIMUM_EXHAUSTIVE_DOMAIN = 10;

    private CheckJointResiduals() { }

    private record Header(int domain, int[] partition) {
        private Header { partition = partition == null ? null : partition.clone(); }
        private String partitionText() {
            return partition == null ? "all" : String.join(",",
                    Arrays.stream(partition).mapToObj(Integer::toString).toList());
        }
    }

    private record ReadResult(Header header,
                              Map<ExtendedToricClassKey, CyclicTargetPair> representatives,
                              EnumMap<JointReductionAnalysis.Route, Long> classRoutes) { }

    private static final class Exhaustion {
        private long permutations;
        private long fixedPointFreePermutations;
        private long validPairs;
        private long residualRawPairs;
        private final EnumMap<JointReductionAnalysis.Route, Long> routes =
                new EnumMap<>(JointReductionAnalysis.Route.class);
        private final Map<ExtendedToricClassKey, CyclicTargetPair> representatives = new HashMap<>();
    }

    public static void main(final String[] args) throws IOException {
        Integer requestedDomain = null;
        int[] requestedPartition = null;
        boolean partitionSpecified = false;
        Path input = null;
        Path output = null;
        boolean verifyOnly = false;
        final Set<String> options = new HashSet<>();
        for (final String argument : args) {
            final String option = argument.contains("=")
                    ? argument.substring(0, argument.indexOf('=')) : argument;
            if (!options.add(option)) throw new IllegalArgumentException("Duplicate option " + option);
            if (argument.startsWith("--domain=")) {
                requestedDomain = parseDomain(argument.substring("--domain=".length()));
            } else if (argument.startsWith("--partition=")) {
                requestedPartition = parsePartition(argument.substring("--partition=".length()));
                partitionSpecified = true;
            } else if (argument.startsWith("--read=")) {
                input = Path.of(argument.substring("--read=".length()));
            } else if (argument.startsWith("--write=")) {
                output = Path.of(argument.substring("--write=".length()));
            } else if (argument.equals("--verify-only")) {
                verifyOnly = true;
            } else {
                throw new IllegalArgumentException("Unknown argument " + argument);
            }
        }
        if (verifyOnly && input == null) throw new IllegalArgumentException(
                "--verify-only requires a representative file supplied with --read");
        final ReadResult read = input == null ? null : readRepresentatives(input);
        if (read != null) {
            if (requestedDomain != null && requestedDomain != read.header.domain) {
                throw new IllegalArgumentException("CLI and representative-file domains differ");
            }
            if (partitionSpecified && read.header.partition != null
                    && !Arrays.equals(requestedPartition, read.header.partition)) {
                throw new IllegalArgumentException("CLI and representative-file partitions differ");
            }
            requestedDomain = read.header.domain;
            if (partitionSpecified && requestedPartition != null && read.header.partition == null) {
                // A native per-partition file may omit partition metadata. An
                // explicit CLI partition then adds a checked restriction; it
                // must never silently relabel an out-of-scope representative.
                for (final CyclicTargetPair pair : read.representatives.values()) {
                    final int[] omega = new int[requestedDomain];
                    for (int x = 0; x < omega.length; x++) omega[x] = pair.getOmega().image(x);
                    if (!matchesPartition(omega, requestedPartition)) {
                        throw new IllegalArgumentException(
                                "Representative is outside the requested cycle partition");
                    }
                }
            } else {
                requestedPartition = read.header.partition;
            }
        }
        if (requestedDomain == null) throw new IllegalArgumentException(
                "Specify --domain or a representative file with --read");
        final Header header = checkedHeader(requestedDomain, requestedPartition);
        if (!verifyOnly && header.domain > MAXIMUM_EXHAUSTIVE_DOMAIN) {
            throw new IllegalArgumentException(
                    "Exhaustive omega enumeration is limited to N<=10; use --verify-only"
                            + " for larger representative files (without a completeness claim)");
        }

        final Exhaustion exhaustion = verifyOnly ? null : exhaust(header);
        if (read != null && exhaustion != null
                && !read.representatives.keySet().equals(exhaustion.representatives.keySet())) {
            final Set<ExtendedToricClassKey> missing = new HashSet<>(exhaustion.representatives.keySet());
            missing.removeAll(read.representatives.keySet());
            final Set<ExtendedToricClassKey> extra = new HashSet<>(read.representatives.keySet());
            extra.removeAll(exhaustion.representatives.keySet());
            throw new IllegalStateException("Residual class sets differ: missing=" + missing.size()
                    + ", extra=" + extra.size());
        }
        final Map<ExtendedToricClassKey, CyclicTargetPair> representatives =
                exhaustion == null ? read.representatives : exhaustion.representatives;
        if (output != null) writeRepresentatives(output, header, representatives.values());
        printReport(header, exhaustion, read, representatives.size());
    }

    private static Exhaustion exhaust(final Header header) {
        final Exhaustion result = new Exhaustion();
        final int[] beta = IntStream.range(0, header.domain).toArray();
        permutations(beta.clone(), 0, omega -> {
            result.permutations++;
            if (!fixedPointFree(omega) || !matchesPartition(omega, header.partition)) return;
            result.fixedPointFreePermutations++;
            if (!fullTargetCycle(omega)) return;
            final CyclicTargetPair pair = validatedPair(omega, beta);
            result.validPairs++;
            final var analysis = JointReductionAnalysis.analyze(omega, beta);
            result.routes.merge(analysis.route(), 1L, Math::addExact);
            if (analysis.route() == JointReductionAnalysis.Route.TOTALLY_UNORIENTED
                    || analysis.hasFavorablePrefix()) return;
            result.residualRawPairs++;
            result.representatives.computeIfAbsent(pair.extendedToricClassKey(),
                    ignored -> pair.canonicalRepresentative());
        });
        long expected = 1;
        for (int k = 2; k <= header.domain; k++) expected = Math.multiplyExact(expected, k);
        if (result.permutations != expected) throw new IllegalStateException(
                "Incomplete independent permutation enumeration");
        return result;
    }

    private static ReadResult readRepresentatives(final Path path) throws IOException {
        try (final BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            final String first = reader.readLine();
            if (first == null) throw new IllegalArgumentException("Missing representative-file header");
            final Header header = parseHeader(first);
            final int[] beta = IntStream.range(0, header.domain).toArray();
            final Map<ExtendedToricClassKey, CyclicTargetPair> representatives = new HashMap<>();
            final EnumMap<JointReductionAnalysis.Route, Long> classRoutes =
                    new EnumMap<>(JointReductionAnalysis.Route.class);
            long rows = 0;
            String line;
            boolean completed = false;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("COUNT ")) {
                    final String[] fields = line.split(" ", -1);
                    if (fields.length != 2 || Long.parseLong(fields[1]) != rows) {
                        throw new IllegalArgumentException("Representative footer count mismatch");
                    }
                    completed = true;
                    if (reader.readLine() != null) throw new IllegalArgumentException(
                            "Unexpected data after representative footer");
                    break;
                }
                if (line.isBlank()) throw new IllegalArgumentException("Blank representative row");
                final String[] fields = line.strip().split("\\s+");
                if (fields.length != header.domain) throw new IllegalArgumentException(
                        "Representative row must contain exactly N omega images");
                final int[] omega = Arrays.stream(fields).mapToInt(Integer::parseInt).toArray();
                validateImages(omega);
                if (!fixedPointFree(omega)) throw new IllegalArgumentException(
                        "Representative contains an uncontracted algebraic fixed point");
                if (!matchesPartition(omega, header.partition)) throw new IllegalArgumentException(
                        "Representative is outside the declared cycle partition");
                final CyclicTargetPair pair = validatedPair(omega, beta);
                final var analysis = JointReductionAnalysis.analyze(omega, beta);
                if (analysis.route() == JointReductionAnalysis.Route.TOTALLY_UNORIENTED) {
                    throw new IllegalArgumentException("Totally unoriented pair in oriented residual file");
                }
                if (analysis.hasFavorablePrefix()) throw new IllegalArgumentException(
                        "Representative has a favorable prefix: " + analysis.route());
                if (representatives.putIfAbsent(pair.extendedToricClassKey(),
                        pair.canonicalRepresentative()) != null) {
                    throw new IllegalArgumentException("Duplicate extended-toric class at row " + (rows + 1));
                }
                classRoutes.merge(analysis.route(), 1L, Math::addExact);
                rows++;
            }
            if (!completed) throw new IllegalArgumentException("Missing representative footer");
            return new ReadResult(header, representatives, classRoutes);
        }
    }

    private static Header parseHeader(final String line) {
        final String[] fields = line.split(" ", -1);
        if (fields.length < 4 || fields.length > 5 || !fields[0].equals(MAGIC)
                || !fields[1].equals(Integer.toString(VERSION))) {
            throw new IllegalArgumentException("Unsupported joint-residual representative format");
        }
        final Map<String, String> metadata = new HashMap<>();
        for (int i = 2; i < fields.length; i++) {
            final int equals = fields[i].indexOf('=');
            if (equals < 1 || metadata.putIfAbsent(fields[i].substring(0, equals),
                    fields[i].substring(equals + 1)) != null) {
                throw new IllegalArgumentException("Malformed or repeated representative header field");
            }
        }
        if (!metadata.keySet().equals(Set.of("N", "scope"))
                && !metadata.keySet().equals(Set.of("N", "scope", "partition"))) {
            throw new IllegalArgumentException("Unexpected representative header fields");
        }
        if (!"oriented".equals(metadata.get("scope"))) throw new IllegalArgumentException(
                "Only oriented residual scope is supported");
        return checkedHeader(parseDomain(metadata.get("N")),
                parsePartition(metadata.getOrDefault("partition", "all")));
    }

    private static Header checkedHeader(final int domain, final int[] partition) {
        if (partition != null && Arrays.stream(partition).sum() != domain) {
            throw new IllegalArgumentException("Cycle partition does not sum to N");
        }
        return new Header(domain, partition);
    }

    private static int parseDomain(final String text) {
        final int domain = Integer.parseInt(text);
        if (domain < 3 || domain > 30) throw new IllegalArgumentException("Expected 3<=N<=30");
        return domain;
    }

    private static int[] parsePartition(final String text) {
        if (text.equals("all")) return null;
        final int[] partition = Arrays.stream(text.split(",", -1)).mapToInt(Integer::parseInt).toArray();
        for (int i = 0; i < partition.length; i++) {
            if (partition[i] < 2 || i > 0 && partition[i - 1] < partition[i]) {
                throw new IllegalArgumentException("Expected descending cycle lengths >=2");
            }
        }
        return partition;
    }

    private static void writeRepresentatives(final Path path, final Header header,
                                             final Iterable<CyclicTargetPair> pairs) throws IOException {
        final List<int[]> images = new ArrayList<>();
        for (final CyclicTargetPair pair : pairs) {
            final CyclicTargetPair canonical = pair.canonicalRepresentative();
            final int[] omega = new int[header.domain];
            for (int x = 0; x < omega.length; x++) omega[x] = canonical.getOmega().image(x);
            if (!Arrays.equals(canonical.getBeta().getSymbols(),
                    IntStream.range(0, header.domain).toArray())) {
                throw new IllegalStateException("Canonical representative beta is not canonical");
            }
            images.add(omega);
        }
        images.sort(Arrays::compare);
        try (final BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write(MAGIC + " " + VERSION + " N=" + header.domain
                    + " scope=oriented partition=" + header.partitionText());
            writer.newLine();
            for (final int[] omega : images) {
                writer.write(String.join(" ", Arrays.stream(omega).mapToObj(Integer::toString).toList()));
                writer.newLine();
            }
            writer.write("COUNT " + images.size());
            writer.newLine();
        }
    }

    private static CyclicTargetPair validatedPair(final int[] omega, final int[] beta) {
        final MulticyclePermutation permutation = new MulticyclePermutation();
        for (final int[] cycle : cycles(omega)) permutation.add(Cycle.of(cycle));
        return new CyclicTargetCandidate(permutation, Cycle.of(beta.clone())).requireCyclicTargetPair();
    }

    private static List<int[]> cycles(final int[] omega) {
        final boolean[] seen = new boolean[omega.length];
        final List<int[]> cycles = new ArrayList<>();
        for (int start = 0; start < omega.length; start++) {
            if (seen[start]) continue;
            final List<Integer> cycle = new ArrayList<>();
            int x = start;
            do { seen[x] = true; cycle.add(x); x = omega[x]; } while (x != start);
            cycles.add(cycle.stream().mapToInt(Integer::intValue).toArray());
        }
        return cycles;
    }

    private static boolean matchesPartition(final int[] omega, final int[] partition) {
        if (partition == null) return true;
        final int[] actual = cycles(omega).stream().mapToInt(cycle -> cycle.length).sorted().toArray();
        if (actual.length != partition.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != partition[partition.length - i - 1]) return false;
        }
        return true;
    }

    private static boolean fixedPointFree(final int[] omega) {
        for (int x = 0; x < omega.length; x++) if (omega[x] == x) return false;
        return true;
    }

    private static boolean fullTargetCycle(final int[] omega) {
        int x = 0;
        int length = 0;
        do { x = omega[(x + 1) % omega.length]; length++; } while (x != 0 && length < omega.length);
        return x == 0 && length == omega.length;
    }

    private static void validateImages(final int[] omega) {
        final boolean[] seen = new boolean[omega.length];
        for (final int image : omega) {
            if (image < 0 || image >= omega.length || seen[image]) throw new IllegalArgumentException(
                    "Invalid algebraic image array");
            seen[image] = true;
        }
    }

    private static void permutations(final int[] values, final int at, final Consumer<int[]> consumer) {
        if (at == values.length) { consumer.accept(values); return; }
        for (int i = at; i < values.length; i++) {
            final int tmp = values[at]; values[at] = values[i]; values[i] = tmp;
            permutations(values, at + 1, consumer);
            values[i] = values[at]; values[at] = tmp;
        }
    }

    private static void printReport(final Header header, final Exhaustion exhaustion,
                                    final ReadResult read, final int classes) {
        System.out.println("{\"format\":\"td20-joint2-residual-java-audit-v1\",\"domain\":"
                + header.domain + ",\"partition\":\"" + header.partitionText()
                + "\",\"scope\":\"oriented\",\"enumeration_performed\":" + (exhaustion != null)
                + ",\"representative_file_validated\":" + (read != null)
                + ",\"full_class_sets_agree\":" + (read != null && exhaustion != null ? "true" : "null")
                + ",\"oriented_residual_classes\":" + classes
                + ",\"permutations_examined\":" + (exhaustion == null ? "null" : exhaustion.permutations)
                + ",\"fixed_point_free_permutations_in_partition\":"
                + (exhaustion == null ? "null" : exhaustion.fixedPointFreePermutations)
                + ",\"realizable_raw_pairs\":" + (exhaustion == null ? "null" : exhaustion.validPairs)
                + ",\"oriented_residual_raw_pairs\":" + (exhaustion == null ? "null" : exhaustion.residualRawPairs)
                + ",\"raw_pair_routes\":{");
        if (exhaustion != null) {
            final List<String> entries = new ArrayList<>();
            exhaustion.routes.forEach((route, count) -> entries.add("\"" + route + "\":" + count));
            System.out.println(String.join(",", entries));
        }
        System.out.println("},\"representative_class_routes\":{");
        if (read != null) {
            final List<String> entries = new ArrayList<>();
            read.classRoutes.forEach((route, count) -> entries.add("\"" + route + "\":" + count));
            System.out.println(String.join(",", entries));
        }
        System.out.println("},\"sorting_certificates_checked\":false}");
    }
}
