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
                    + "\\{([0-9]+)}\\{([0-9]+)}\\{([0-9]+)}\\{([0-9]+)}$");

    private JointInventory() { }

    public record Root(int number, int[] partition, long unorientedClasses,
                       long jointResidualClasses, long supportClearingClasses,
                       long orientedClasses) {
        public Root {
            partition = partition.clone();
            if (number < 1 || unorientedClasses < 0 || jointResidualClasses < 0
                    || supportClearingClasses < 0 || orientedClasses < 0
                    || supportClearingClasses > jointResidualClasses
                    || orientedClasses != jointResidualClasses - supportClearingClasses) {
                throw new IllegalArgumentException("Invalid joint root inventory counts");
            }
            Math.addExact(unorientedClasses, orientedClasses);
        }

        @Override public int[] partition() { return partition.clone(); }
        public long directClasses() { return Math.addExact(unorientedClasses, orientedClasses); }
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

        /** Clearing an oriented 3-cycle leaves 13 symbols with seven moves. */
        public boolean elementaryClosure() {
            for (int i = 0; i < partition.length; i++) {
                if (partition[i] == 3 && orientedByPart[i]) return true;
            }
            return false;
        }
    }

    public static List<Root> read(final Path paper) throws IOException {
        final List<Root> rows = new ArrayList<>();
        final List<int[]> expected = Partitions.generateValidPartitions(KnownDiameters.ROOT_SYMBOLS);
        int lineNumber = 0;
        for (final String line : Files.readAllLines(paper)) {
            lineNumber++;
            final String stripped = line.strip();
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
                    Long.parseLong(matcher.group(6))));
        }
        if (rows.size() != expected.size()) throw new IllegalArgumentException(
                "Incomplete joint inventory: expected " + expected.size() + " rows, found " + rows.size());
        return List.copyOf(rows);
    }

    public static List<Root> roots(final Path paper) throws IOException { return read(paper); }

    public static List<Endpoint> endpoints() {
        final List<Endpoint> result = new ArrayList<>();
        for (final int[] partition : Partitions.generateValidPartitions(16)) {
            if (KnownDiameters.threeNorm(partition) > 7) continue;
            for (final var annotation : AnnotatedPartitions.of(partition)) {
                result.add(new Endpoint(result.size() + 1, partition, annotation.oriented()));
            }
        }
        return List.copyOf(result);
    }
}
