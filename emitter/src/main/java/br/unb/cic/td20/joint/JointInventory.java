package br.unb.cic.td20.joint;

import br.unb.cic.td20.reduction.AnnotatedPartitions;
import br.unb.cic.td20.reduction.Partitions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Strict root inventory from the paper and complete independently generated endpoint cover. */
public final class JointInventory {
    private static final Pattern ROW = Pattern.compile(
            "^\\\\jointrow\\{([1-9][0-9]*)}\\{([0-9]+(?:,[0-9]+)*)}"
                    + "\\{([0-9]+)}\\{(-1|[0-9]+)}\\{(-1|[0-9]+)}\\{(-1|[0-9]+)}$");

    private JointInventory() { }

    public record Root(int number, int[] partition, long unorientedClasses,
                       long jointResidualClasses, long supportClearingClasses,
                       long orientedClasses, long unorientedResidualClasses) {
        public Root {
            partition = partition.clone();
            if (unorientedResidualClasses < -1 || unorientedResidualClasses > unorientedClasses)
                throw new IllegalArgumentException("Invalid unoriented residual count");
            boolean pending = jointResidualClasses == -1 && supportClearingClasses == -1 && orientedClasses == -1;
            if (number < 1 || unorientedClasses < 0 || !pending && (jointResidualClasses < 0
                    || supportClearingClasses < 0 || orientedClasses < 0
                    || supportClearingClasses > jointResidualClasses
                    || orientedClasses != jointResidualClasses - supportClearingClasses))
                throw new IllegalArgumentException("Invalid joint root inventory counts");
            if (!pending) Math.addExact(unorientedResidualClasses, orientedClasses);
        }

        public boolean countsComplete() { return jointResidualClasses >= 0 && unorientedResidualClasses >= 0; }
        public void requireCompleteCounts() {
            if (!countsComplete()) throw new IllegalStateException("Pending joint-2 counts: partition=" + number);
        }
        @Override public int[] partition() { return partition.clone(); }
        public long directClasses() { requireCompleteCounts(); return Math.addExact(unorientedResidualClasses, orientedClasses); }
    }

    public record Endpoint(int number, int[] partition, boolean[] orientedByPart) {
        public Endpoint {
            partition = partition.clone();
            orientedByPart = orientedByPart.clone();
            if (number < 1 || partition.length != orientedByPart.length) {
                throw new IllegalArgumentException("Invalid endpoint annotation");
            }
        }

        @Override public int[] partition() { return partition.clone(); }
        @Override public boolean[] orientedByPart() { return orientedByPart.clone(); }

        /** Clearing an oriented 3-cycle leaves 15 symbols with eight moves. */
        public boolean elementaryClosure() {
            for (int i = 0; i < partition.length; i++) {
                if (partition[i] == 3 && orientedByPart[i]) return true;
            }
            return false;
        }
    }

    private static String normalizePending(String line) {
        if (line.startsWith("\\unorientedjointrow{")) {
            if (!line.matches("^\\\\unorientedjointrow\\{[1-9][0-9]*}\\{[0-9]+(?:,[0-9]+)*}\\{[0-9]+}$"))
                throw new IllegalArgumentException("Malformed pending root row");
            return line.replace("\\unorientedjointrow", "\\jointrow") + "{-1}{-1}{-1}";
        }
        if (line.startsWith("\\jointrow{") && line.contains("{-"))
            throw new IllegalArgumentException("Use the explicit pending-row macro for missing counts");
        return line;
    }
    public static List<Root> read(final Path paper) throws IOException {
        final var residuals = UnorientedResidualInventory.read(paper, 83);
        final List<Root> rows = new ArrayList<>();
        final List<int[]> expected = Partitions.generateValidPartitions(KnownDiameters.ROOT_SYMBOLS);
        int lineNumber = 0;
        for (final String line : Files.readAllLines(paper)) {
            lineNumber++;
            final String stripped = normalizePending(line.strip());
            if (!stripped.startsWith("\\jointrow")) continue;
            final var matcher = ROW.matcher(stripped);
            if (!matcher.matches()) throw new IllegalArgumentException(
                    "Malformed joint inventory row at " + paper + ':' + lineNumber);
            final int number = Integer.parseInt(matcher.group(1));
            final int[] partition = Arrays.stream(matcher.group(2).split(","))
                    .mapToInt(Integer::parseInt).toArray();
            if (number != rows.size() + 1 || number > expected.size()
                    || !Arrays.equals(partition, expected.get(number - 1))) {
                throw new IllegalArgumentException(
                        "Missing, duplicate, or out-of-order root partition at " + paper + ':' + lineNumber);
            }
            rows.add(new Root(number, partition, Long.parseLong(matcher.group(3)),
                    Long.parseLong(matcher.group(4)), Long.parseLong(matcher.group(5)),
                    Long.parseLong(matcher.group(6)), residuals.get(number)));
        }
        if (rows.size() != expected.size()) throw new IllegalArgumentException(
                "Incomplete joint inventory: expected " + expected.size() + " rows, found " + rows.size());
        return List.copyOf(rows);
    }

    public static List<Root> roots(final Path paper) throws IOException { return read(paper); }

    public static List<Endpoint> endpoints() {
        final List<Endpoint> result = new ArrayList<>();
        for (final int[] partition : Partitions.generateValidPartitions(18)) {
            if (KnownDiameters.threeNorm(partition) > 8) continue;
            for (final var annotation : AnnotatedPartitions.of(partition)) {
                result.add(new Endpoint(result.size() + 1, partition, annotation.oriented()));
            }
        }
        return List.copyOf(result);
    }
}
