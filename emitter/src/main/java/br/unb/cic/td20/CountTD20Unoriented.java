package br.unb.cic.td20;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.reduction.Partitions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Exact all-unoriented root count, independent of the joint-3 predicates. */
public final class CountTD20Unoriented {
    private CountTD20Unoriented() { }

    public static void main(final String[] arguments) throws Exception {
        int workers = Runtime.getRuntime().availableProcessors();
        Path output = Path.of("data/joint3-reduction-audit/unoriented-v1.json");
        for (final String argument : arguments) {
            if (argument.startsWith("--output=")) output = Path.of(argument.substring(9));
            else throw new IllegalArgumentException("Unknown argument: " + argument);
        }
        if (workers < 1) throw new IllegalArgumentException("Positive worker count required");
        System.setProperty("td20.twistedBraceletWorkers", Integer.toString(workers));
        final var partitions = Partitions.generateValidPartitions(KnownDiameters.ROOT_SYMBOLS);
        if (partitions.size() != 83) throw new IllegalStateException("Expected 83 root partitions");
        final long started = System.nanoTime();
        final var futures = new ArrayList<Future<Row>>();
        try (var pool = Executors.newFixedThreadPool(workers)) {
            for (int index = 0; index < partitions.size(); index++) {
                final int number = index + 1;
                final int[] partition = partitions.get(index);
                futures.add(pool.submit(() -> new Row(number, partition,
                        DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                                partition, new boolean[partition.length]))));
            }
            final var rows = new ArrayList<Row>();
            for (final var future : futures) {
                final var row = future.get();
                rows.add(row);
                System.out.printf("unoriented progress: %d/83 partition=%s classes=%d%n",
                        rows.size(), Arrays.toString(row.partition), row.classes);
            }
            rows.sort(Comparator.comparingInt(Row::number));
            long total = 0;
            final var json = new StringBuilder(16_384);
            json.append("{\n  \"format\": \"td20-unoriented-root-count-v1\",\n")
                    .append("  \"complete\": true,\n  \"domain\": 21,\n")
                    .append("  \"required_partitions\": 83,\n  \"completed_partitions\": 83,\n")
                    .append("  \"worker_limit\": ").append(workers).append(",\n  \"rows\": [\n");
            for (int index = 0; index < rows.size(); index++) {
                final var row = rows.get(index);
                total = Math.addExact(total, row.classes);
                json.append("    {\"number\":").append(row.number).append(",\"partition\":")
                        .append(Arrays.toString(row.partition).replace(" ", ""))
                        .append(",\"totally_unoriented_root_classes\":").append(row.classes).append('}');
                json.append(index + 1 == rows.size() ? '\n' : ",\n");
            }
            json.append("  ],\n  \"totally_unoriented_root_classes\": ").append(total)
                    .append(",\n  \"elapsed_seconds\": ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1e9))
                    .append("\n}\n");
            output = output.toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite " + output);
            final Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
            Files.writeString(temporary, json);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            System.out.printf("Unoriented count complete: classes=%d output=%s%n", total, output);
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    private record Row(int number, int[] partition, long classes) {
        private Row { partition = partition.clone(); }
        @Override public int[] partition() { return partition.clone(); }
    }
}
