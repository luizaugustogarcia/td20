package br.unb.cic.td20.fleet;

import br.unb.cic.td20.joint.ErikssonRoute;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

record FleetTask(String run, String id, String phase, int first, int last, long inputCount, String generation) {
    FleetTask(String run, String id, String phase, int first, int last, long inputCount) {
        this(run,id,phase,first,last,inputCount,"whole");
    }
    FleetTask {
        UUID.fromString(run);
        if (!id.matches("[a-z][a-z0-9-]*") || !List.of("roots", "endpoints", "avoidance").contains(phase))
            throw new IllegalArgumentException("Invalid fleet task identity");
        int limit = phase.equals("roots") ? ErikssonRoute.ROOT_PARTITIONS : phase.equals("endpoints") ? ErikssonRoute.ENDPOINT_TYPES : 1;
        boolean leaves = generation.startsWith("leaves/");
        if (first < 1 || last < first || last > limit || (phase.equals("avoidance") || leaves ? inputCount < 1 : inputCount != 0)
                || !generation.equals("whole") && (!phase.equals("endpoints") || first != last
                    || !generation.matches("(prefix|leaves)/([0-9]+(\\.[0-9]+)*)?")))
            throw new IllegalArgumentException("Invalid fleet task scope");
    }
    List<String> lines() {
        return List.of("td20-fleet-task-v2", "route=" + ErikssonRoute.ID, "run=" + run, "id=" + id,
                "phase=" + phase, "first=" + first, "last=" + last, "inputCount=" + inputCount,"generation=" + generation);
    }
    void write(Path job) throws IOException { FleetFiles.write(job.resolve("task.txt"), lines()); }
    static FleetTask read(Path job) throws IOException {
        var lines = Files.readAllLines(job.resolve("task.txt"));
        if (lines.size() != 9 || !lines.getFirst().equals("td20-fleet-task-v2")
                || !lines.get(1).equals("route=" + ErikssonRoute.ID)) throw new IllegalArgumentException("Incompatible fleet task");
        return new FleetTask(FleetFiles.field(lines,2,"run"), FleetFiles.field(lines,3,"id"),
                FleetFiles.field(lines,4,"phase"), Integer.parseInt(FleetFiles.field(lines,5,"first")),
                Integer.parseInt(FleetFiles.field(lines,6,"last")), Long.parseLong(FleetFiles.field(lines,7,"inputCount")),FleetFiles.field(lines,8,"generation"));
    }
    void complete(Path job, long endpoints, long roots) throws IOException {
        complete(job,endpoints,roots,0);
    }
    void complete(Path job, long endpoints, long roots, long generated) throws IOException {
        var lines = new ArrayList<>(lines()); lines.set(0, "td20-fleet-complete-v2");
        lines.add("endpointResiduals=" + endpoints); lines.add("reconstructedRoots=" + roots); lines.add("generatedEndpoints=" + generated);
        FleetFiles.write(job.resolve("complete.txt"), lines);
    }
    long residualCount(Path job) throws IOException {
        return receipt(job)[0];
    }
    long generatedCount(Path job) throws IOException { return receipt(job)[2]; }
    long reconstructedCount(Path job) throws IOException { return receipt(job)[1]; }
    private long[] receipt(Path job) throws IOException {
        var counts = receiptCounts(job);
        if (!Files.isRegularFile(job.resolve("certificates/CURRENT")))
            throw new IllegalArgumentException("Missing fleet certificate database: " + job);
        return new long[]{counts.endpointResiduals(),counts.reconstructedRoots(),counts.generatedEndpoints()};
    }
    /** Operational completion metadata; no certificate coverage is claimed by these counts. */
    record ReceiptCounts(long endpointResiduals, long reconstructedRoots, long generatedEndpoints) { }
    ReceiptCounts receiptCounts(Path job) throws IOException {
        var lines = Files.readAllLines(job.resolve("complete.txt"));
        var expected = new ArrayList<>(lines()); expected.set(0, "td20-fleet-complete-v2");
        if (lines.size() != 12 || !lines.subList(0,9).equals(expected))
            throw new IllegalArgumentException("Incomplete or incompatible fleet receipt: " + job);
        long count = Long.parseLong(FleetFiles.field(lines,9,"endpointResiduals"));
        long roots = Long.parseLong(FleetFiles.field(lines,10,"reconstructedRoots"));
        long generated = Long.parseLong(FleetFiles.field(lines,11,"generatedEndpoints"));
        if (count < 0 || roots < 0 || generated < count || !phase.equals("endpoints") && generated != 0
                || !phase.equals("endpoints") && count != 0 || !phase.equals("avoidance") && roots != 0
                || generation.startsWith("leaves/") && generated != inputCount)
            throw new IllegalArgumentException("Invalid fleet result inventory");
        return new ReceiptCounts(count,roots,generated);
    }
    br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator.GenerationWork generationWork() {
        if (generation.equals("whole")) throw new IllegalStateException("Whole annotation has no prefix descriptor");
        String path = generation.substring(generation.indexOf('/') + 1);
        var indices = path.isEmpty() ? List.<Integer>of() : Arrays.stream(path.split("\\.")).map(Integer::parseInt).toList();
        boolean leaves = generation.startsWith("leaves/");
        return new br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator.GenerationWork(indices,leaves,leaves ? inputCount : -1);
    }
}
