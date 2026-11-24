package br.unb.cic.td20;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.joint.*;
import br.unb.cic.td20.runtime.ProjectPaths;
import lombok.val;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * CPU-only active-route census. Run through JShell; every report has an explicit scope.
 */
public final class CountEriksson {

    private CountEriksson() {
    }

    public static void main(final String[] args) throws Exception {
        String phase = "all";
        int workers = Runtime.getRuntime().availableProcessors();
        int first = 1, last = ErikssonRoute.ROOT_PARTITIONS, ef = 1, el = ErikssonRoute.ENDPOINT_TYPES;
        Path paper = ProjectPaths.root(Path.of("")).resolve("docs/td20-reduction-count.tex");
        for (final String arg : args) {
            if (arg.equals("--help")) {
                System.out.println("CountEriksson [--phase=roots|endpoints|all] [--workers=N] [--paper=PATH] [--partitions=FIRST-LAST] [--endpoints=FIRST-LAST]");
                return;
            } else if (arg.startsWith("--phase=")) {
                phase = arg.substring(8);
            } else if (arg.startsWith("--workers=")) {
                workers = Integer.parseInt(arg.substring(10));
            } else if (arg.startsWith("--paper=")) {
                paper = Path.of(arg.substring(8));
            } else if (arg.startsWith("--partitions=")) {
                final int[] range = range(arg.substring(13), ErikssonRoute.ROOT_PARTITIONS);
                first = range[0];
                last = range[1];
            } else if (arg.startsWith("--endpoints=")) {
                final int[] range = range(arg.substring(12), ErikssonRoute.ENDPOINT_TYPES);
                ef = range[0];
                el = range[1];
            } else {
                throw new IllegalArgumentException("Unknown census argument: " + arg);
            }
        }
        if (workers < 1 || !List.of("all", "roots", "endpoints").contains(phase)) {
            throw new IllegalArgumentException("Invalid census phase or worker count");
        }

        System.setProperty("td20.twistedBraceletWorkers", Integer.toString(workers));
        System.out.println("Counting route=" + ErikssonRoute.ID + " workers=" + workers + " phase=" + phase);

        try {
            val rows = ErikssonInventory.read(paper);
            if (!phase.equals("endpoints")) {
                long direct = 0;
                for (val row : rows) {
                    if (first <= row.number() && row.number() <= last) {
                        row.requireCompleteCounts();

                        val counts = ErikssonRootGenerator.count(row.partition(), workers, new LongAdder());
                        if (counts.unoriented() != row.unoriented()
                                || counts.oriented() != row.oriented()
                                || counts.supportCleared() != row.supportCleared()) {
                            throw new IllegalStateException("Active count mismatch at partition " + row.number());
                        }

                        direct = Math.addExact(direct, counts.direct());
                        System.out.printf("partition=%d unoriented=%d oriented=%d cleared=%d direct=%d%n",
                                row.number(),
                                counts.unoriented(),
                                counts.oriented(),
                                counts.supportCleared(),
                                counts.direct());
                    }
                }

                System.out.println("Root scope=" + first + "-" + last + " direct=" + direct);
            }
            if (!phase.equals("roots")) {
                final Path directory = ProjectPaths.root(Path.of("")).resolve(System.getProperty("td20.countModule", "emitter").equals("checker") ? "data/eriksson-endpoint-counts-checker-v1" : "data/eriksson-endpoint-counts-v1");
                Files.createDirectories(directory);

                long total = 0, required = 0;

                try (val pool = Executors.newFixedThreadPool(workers)) {
                    final List<Future<Long>> futures = new ArrayList<>();
                    final List<ErikssonInventory.Endpoint> selected = new ArrayList<>();
                    for (val endpoint : ErikssonInventory.endpoints()) {
                        if (ef <= endpoint.number() && endpoint.number() <= el) {
                            selected.add(endpoint);
                            futures.add(pool.submit(() -> endpointCount(directory, endpoint)));
                        }
                    }
                    for (int i = 0; i < futures.size(); i++) {
                        final long count = futures.get(i).get();
                        val endpoint = selected.get(i);
                        total = Math.addExact(total, count);
                        if (!endpoint.elementaryClosure()) {
                            required = Math.addExact(required, count);
                        }

                        System.out.printf("endpoint=%d classes=%d elementaryClosure=%s budget=%d%n",
                                endpoint.number(),
                                count,
                                endpoint.elementaryClosure(),
                                ErikssonRoute.ENDPOINT_BUDGET);
                    }
                }

                System.out.printf("COMPLETE endpointScope=%d-%d classes=%d requiringDischarge=%d sortingChecked=false%n",
                        ef,
                        el,
                        total,
                        required);
            }
        } finally {
            DirectTwistedBraceletGenerator.shutdownExecutor();
        }
    }

    private static long endpointCount(final Path directory, final ErikssonInventory.Endpoint endpoint) throws Exception {
        final Map<String, String> expected = new TreeMap<>();
        expected.put("format", "td20-eriksson-endpoint-inventory-v1");
        expected.put("route", ErikssonRoute.ID);
        expected.put("domain", Integer.toString(ErikssonRoute.ENDPOINT_SYMBOLS));
        expected.put("budget", Integer.toString(ErikssonRoute.ENDPOINT_BUDGET));
        expected.put("number", Integer.toString(endpoint.number()));
        expected.put("partition", Arrays.toString(endpoint.partition()));
        expected.put("orientations", Arrays.toString(endpoint.orientedByPart()));
        expected.put("elementary", Boolean.toString(endpoint.elementaryClosure()));
        expected.put("complete", "true");

        final Path path = directory.resolve(String.format("endpoint-%03d.txt", endpoint.number()));
        if (Files.exists(path)) {
            final Map<String, String> actual = new TreeMap<>();
            for (final String line : Files.readAllLines(path)) {
                final String[] fields = line.split("=", 2);
                if (fields.length != 2 || actual.putIfAbsent(fields[0], fields[1]) != null) {
                    throw new IllegalStateException("Malformed endpoint report");
                }
            }
            for (val entry : expected.entrySet()) {
                if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                    throw new IllegalStateException("Incompatible endpoint report");
                }
            }

            final Set<String> keys = new HashSet<>(expected.keySet());
            keys.add("classes");
            if (!keys.equals(actual.keySet())) {
                throw new IllegalStateException("Missing endpoint count fields");
            }

            final long count = Long.parseLong(actual.get("classes"));
            if (count < 0) {
                throw new IllegalStateException("Negative endpoint count");
            }

            return count;
        }

        final long count = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(), endpoint.orientedByPart());
        expected.put("classes", Long.toString(count));

        final Path temporary = Files.createTempFile(directory, ".endpoint-", ".tmp");

        try {
            Files.write(temporary, expected.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }

        return count;
    }

    private static int[] range(final String text, final int maximum) {
        final String[] fields = text.split("-", -1);
        if (fields.length != 2) {
            throw new IllegalArgumentException("Expected FIRST-LAST");
        }

        final int first = Integer.parseInt(fields[0]), last = Integer.parseInt(fields[1]);
        if (first < 1 || last < first || last > maximum) {
            throw new IllegalArgumentException("Invalid census range");
        }

        return new int[] { first, last };
    }
}
