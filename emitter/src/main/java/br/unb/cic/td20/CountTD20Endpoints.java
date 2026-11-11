package br.unb.cic.td20;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.joint.JointInventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Exact independent Burnside count for the 16-symbol endpoint cover. */
public final class CountTD20Endpoints {
    private CountTD20Endpoints() { }

    public static void main(final String[] arguments) throws Exception {
        int workers = Runtime.getRuntime().availableProcessors();
        Path output = Path.of("data/joint3-reduction-audit/endpoints-v1.json");
        for (final String argument : arguments) {
            if (argument.startsWith("--output=")) output = Path.of(argument.substring(9));
            else throw new IllegalArgumentException("Unknown argument: " + argument);
        }
        if (workers < 1) throw new IllegalArgumentException("Positive worker count required");
        System.setProperty("td20.twistedBraceletWorkers", Integer.toString(workers));
        final var endpoints = JointInventory.endpoints();
        if (endpoints.size() != 104 || endpoints.stream().filter(JointInventory.Endpoint::elementaryClosure).count() != 52)
            throw new IllegalStateException("Incorrect endpoint annotation inventory");
        final long started = System.nanoTime();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(workers)) {
            final var rows = new ArrayList<Row>();
            final var tasks = new ArrayList<java.util.concurrent.Future<Row>>();
            for (final var endpoint : endpoints) {
                tasks.add(pool.submit(() -> new Row(endpoint,
                        DirectTwistedBraceletGenerator.countRepresentativesByBurnside(
                                endpoint.partition(), endpoint.orientedByPart()))));
            }
            for (final var task : tasks) {
                rows.add(task.get());
                System.out.printf("endpoint progress: %d/104%n", rows.size());
            }
            rows.sort(Comparator.comparingInt(row -> row.endpoint.number()));
            long total = 0, elementary = 0, requiring = 0;
            final var json = new StringBuilder(24_576);
            json.append("{\n  \"format\": \"td20-joint3-endpoint-count-v1\",\n")
                    .append("  \"complete\": true,\n  \"domain\": 16,\n")
                    .append("  \"required_annotations\": 104,\n  \"completed_annotations\": 104,\n")
                    .append("  \"worker_limit\": ").append(workers).append(",\n  \"rows\": [\n");
            for (int index = 0; index < rows.size(); index++) {
                final var row = rows.get(index); final var endpoint = row.endpoint;
                total = Math.addExact(total, row.classes);
                if (endpoint.elementaryClosure()) elementary = Math.addExact(elementary, row.classes);
                else requiring = Math.addExact(requiring, row.classes);
                json.append("    {\"number\":").append(endpoint.number())
                        .append(",\"partition\":").append(Arrays.toString(endpoint.partition()).replace(" ", ""))
                        .append(",\"oriented_by_part\":").append(Arrays.toString(endpoint.orientedByPart()).replace(" ", ""))
                        .append(",\"classes\":").append(row.classes)
                        .append(",\"elementary_closure\":").append(endpoint.elementaryClosure()).append('}')
                        .append(index + 1 == rows.size() ? '\n' : ",\n");
            }
            json.append("  ],\n  \"all_endpoint_classes\": ").append(total)
                    .append(",\n  \"elementary_closure_classes\": ").append(elementary)
                    .append(",\n  \"classes_requiring_discharge\": ").append(requiring)
                    .append(",\n  \"elapsed_seconds\": ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1e9))
                    .append("\n}\n");
            output = output.toAbsolutePath().normalize(); Files.createDirectories(output.getParent());
            if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite " + output);
            final Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
            Files.writeString(temporary, json); Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            System.out.printf("Endpoint count complete: all=%d elementary=%d requiring=%d output=%s%n",
                    total, elementary, requiring, output);
        } finally { DirectTwistedBraceletGenerator.shutdownExecutor(); }
    }

    private record Row(JointInventory.Endpoint endpoint, long classes) { }
}
