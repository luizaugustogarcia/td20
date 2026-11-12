package br.unb.cic.td20;

import br.unb.cic.td20.joint.JointInventory;
import br.unb.cic.td20.joint.JointRootGenerator;
import br.unb.cic.td20.reduction.Partitions;
import br.unb.cic.td20.runtime.ProjectPaths;
import java.nio.file.*;
import java.util.*;

/** Exact Java U residual counting. Atomic partition reports are resume boundaries. */
public final class CountTD20UnorientedResiduals {
    private CountTD20UnorientedResiduals() { }
    public static void main(String[] arguments) throws Exception {
        Path root = ProjectPaths.root(Path.of(""));
        Path paper = root.resolve("docs/td20-reduction-count.tex");
        Path directory = root.resolve("data/unoriented-joint-residuals-v1");
        int n = 21, first = 1, last = Integer.MAX_VALUE;
        boolean publish = false, sequential = false;
        for (String arg : arguments) {
            if (arg.equals("--help")) {
                System.out.println("CountTD20UnorientedResiduals [--n=N] [--partitions=FIRST-LAST] [--output=DIR] [--paper=PATH] [--publish] [--sequential]");
                return;
            } else if (arg.startsWith("--n=")) n = Integer.parseInt(arg.substring(4));
            else if (arg.startsWith("--output=")) directory = Path.of(arg.substring(9));
            else if (arg.startsWith("--paper=")) paper = Path.of(arg.substring(8));
            else if (arg.startsWith("--partitions=")) {
                String[] bounds = arg.substring(13).split("-", -1);
                if (bounds.length != 2) throw new IllegalArgumentException("Expected FIRST-LAST");
                first = Integer.parseInt(bounds[0]); last = Integer.parseInt(bounds[1]);
            } else if (arg.equals("--publish")) publish = true;
            else if (arg.equals("--sequential")) sequential = true;
            else throw new IllegalArgumentException("Unknown argument: " + arg);
        }
        if (n < 3 || n > 21) throw new IllegalArgumentException("Invalid domain");
        var partitions = Partitions.generateValidPartitions(n);
        if (last == Integer.MAX_VALUE) last = partitions.size();
        if (first < 1 || first > last || last > partitions.size()) throw new IllegalArgumentException("Invalid partition range");
        if (publish && n != 21) throw new IllegalArgumentException("Small-domain counts cannot be published to the production inventory");
        var inventory = n == 21 ? JointInventory.read(paper) : List.<JointInventory.Root>of();
        Files.createDirectories(directory);
        for (int number = first; number <= last; number++) {
            int[] partition = partitions.get(number - 1);
            String key = Arrays.toString(partition).replace(" ", "").replace("[", "").replace("]", "");
            Path report = directory.resolve("partition-" + key.replace(',', '-') + ".txt");
            long count;
            if (Files.exists(report)) count = readReport(report, n, number, key);
            else {
                count = sequential ? JointRootGenerator.countUnorientedResiduals(partition)
                        : JointRootGenerator.countUnorientedResidualsConcurrently(partition);
                writeReport(report, n, number, key, count);
            }
            if (n == 21) {
                var row = inventory.get(number - 1);
                if (count > row.unorientedClasses() || row.unorientedResidualClasses() >= 0
                        && count != row.unorientedResidualClasses())
                    throw new IllegalStateException("Residual count disagrees with paper at " + number);
                if (publish) publish(paper, number, count);
                System.out.printf("partition=%d censusU=%d residualR=%d filtered=%d directObligations=%d%n",
                        number, row.unorientedClasses(), count, row.unorientedClasses() - count,
                        Math.addExact(count, row.orientedClasses()));
            }
            System.out.printf("partition=%d lengths=%s unorientedResiduals=%d report=%s%n", number, key, count, report);
        }
        System.out.println("Requested count scope complete. Sorting certificates were not checked.");
    }
    static long readReport(Path path, int n, int number, String key) throws Exception {
        var lines = Files.readAllLines(path);
        if (lines.size() != 7 || !lines.get(0).equals("td20-unoriented-joint-residual-count-v1")
                || !lines.get(1).equals("domain=" + n) || !lines.get(2).equals("number=" + number)
                || !lines.get(3).equals("partition=" + key) || !lines.get(4).startsWith("classes=")
                || !lines.get(5).equals("implementation=java-joint-prefix-v1") || !lines.get(6).equals("complete=true"))
            throw new IllegalArgumentException("Missing or superseded count data in " + path);
        String value = lines.get(4).substring(8);
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid residual count");
        return Long.parseLong(value);
    }
    static void writeReport(Path path, int n, int number, String key, long count) throws Exception {
        String data = String.join(System.lineSeparator(), "td20-unoriented-joint-residual-count-v1",
                "domain=" + n, "number=" + number, "partition=" + key, "classes=" + count,
                "implementation=java-joint-prefix-v1", "complete=true") + System.lineSeparator();
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".residual-", ".tmp");
        try { Files.writeString(temporary, data); Files.createLink(path, temporary); }
        finally { Files.deleteIfExists(temporary); }
    }
    static void publish(Path paper, int number, long count) throws Exception {
        String original = Files.readString(paper);
        var lines = new ArrayList<>(Files.readAllLines(paper));
        String prefix = Character.toString(92) + "unorientedresidualrow{" + number + "}{";
        int found = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(prefix)) {
            if (found >= 0) throw new IllegalArgumentException("Duplicate residual row");
            found = i;
        }
        if (found < 0) throw new IllegalArgumentException("Missing residual row");
        lines.set(found, prefix + count + "}");
        Path temporary = Files.createTempFile(paper.toAbsolutePath().getParent(), ".residual-paper-", ".tmp");
        try {
            Files.write(temporary, lines);
            if (!Files.readString(paper).equals(original)) throw new IllegalStateException("Paper changed during publication");
            Files.move(temporary, paper, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
