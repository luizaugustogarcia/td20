package br.unb.cic.td20.fleet;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.joint.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Deterministic small generator subtrees, scheduled from one shared fleet queue. */
final class EmissionBatches {
    private EmissionBatches() { }
    static List<FleetTask> prepare(String run, Path directory, int target, Consumer<String> progress) throws IOException {
        Files.createDirectory(directory);
        var tasks = new ArrayList<FleetTask>();
        for (int partition = 1; partition <= ErikssonRoute.ROOT_PARTITIONS; partition++)
            tasks.add(new FleetTask(run,String.format(Locale.ROOT,"root-%03d",partition),"roots",partition,partition,0));
        var lines = new ArrayList<>(List.of("td20-fleet-emission-v1","route=" + ErikssonRoute.ID,"run=" + run));
        for (var endpoint : ErikssonInventory.endpoints()) {
            long expected = endpoint.elementaryClosure() ? 0 : DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(),endpoint.orientedByPart());
            var work = endpoint.elementaryClosure() || expected == 0 ? List.<DirectTwistedBraceletGenerator.GenerationWork>of()
                    : DirectTwistedBraceletGenerator.planWork(endpoint.partition(),endpoint.orientedByPart(),target);
            if (expected != 0 && work.isEmpty()) throw new IllegalStateException("Missing endpoint generation frontier");
            lines.add("endpoint-" + endpoint.number() + "=" + expected);
            if (work.isEmpty()) tasks.add(new FleetTask(run,String.format(Locale.ROOT,"endpoint-%03d-whole",endpoint.number()),"endpoints",endpoint.number(),endpoint.number(),0));
            for (int i = 0; i < work.size(); i++) {
                var item = work.get(i);
                String path = String.join(".",item.path().stream().map(Object::toString).toList());
                tasks.add(new FleetTask(run,String.format(Locale.ROOT,"endpoint-%03d-prefix-%06d",endpoint.number(),i),"endpoints",
                        endpoint.number(),endpoint.number(),item.leavesOnly() ? item.expectedLeaves() : 0,(item.leavesOnly() ? "leaves/" : "prefix/") + path));
            }
            progress.accept("Endpoint type " + endpoint.number() + ": classes=" + expected + ", generation tasks=" + Math.max(1,work.size()));
        }
        lines.add("tasks=" + tasks.size());
        for (var task : tasks) { task.write(Files.createDirectory(directory.resolve(task.id()))); lines.add("task=" + task.id()); }
        FleetFiles.write(directory.resolve("emission.txt"),lines);
        return List.copyOf(tasks);
    }
    static List<FleetTask> read(String run, Path directory) throws IOException {
        var lines = header(run,directory); int offset = 3 + ErikssonRoute.ENDPOINT_TYPES;
        int count = Integer.parseInt(FleetFiles.field(lines,offset,"tasks"));
        if (count < ErikssonRoute.ROOT_PARTITIONS + ErikssonRoute.ENDPOINT_TYPES || lines.size() != (long)offset + 1 + count)
            throw new IllegalArgumentException("Incomplete emission task inventory");
        var tasks = new ArrayList<FleetTask>(); var ids = new HashSet<String>();
        var roots = new HashSet<Integer>(); var endpoints = new HashSet<Integer>();
        var generation = new HashSet<String>();
        for (int i = 0; i < count; i++) {
            String id = FleetFiles.field(lines,offset + 1 + i,"task");
            if (!id.matches("[a-z][a-z0-9-]*") || !ids.add(id)) throw new IllegalArgumentException("Invalid or repeated emission task");
            var task = FleetTask.read(directory.resolve(id));
            if (!task.run().equals(run) || !task.id().equals(id) || task.phase().equals("avoidance")) throw new IllegalArgumentException("Emission task scope mismatch");
            if (task.first() != task.last()) throw new IllegalArgumentException("Emission tasks must identify one partition or annotation");
            if (task.phase().equals("roots")) {
                if (!roots.add(task.first())) throw new IllegalArgumentException("Duplicate root partition task");
            } else {
                endpoints.add(task.first());
                if (!generation.add(task.first() + ":" + task.generation())) throw new IllegalArgumentException("Duplicate endpoint generation scope");
            }
            tasks.add(task);
        }
        if (roots.size() != ErikssonRoute.ROOT_PARTITIONS || endpoints.size() != ErikssonRoute.ENDPOINT_TYPES)
            throw new IllegalArgumentException("Missing root partition or endpoint annotation");
        return List.copyOf(tasks);
    }
    static void validateCompleted(String run, Path plan, List<Path> jobs) throws IOException {
        var lines = header(run,plan); long[] generated = new long[ErikssonRoute.ENDPOINT_TYPES + 1];
        var expected = read(run,plan); var actual = new HashMap<String,Path>();
        for (Path job : jobs) if (actual.put(FleetTask.read(job).id(),job) != null) throw new IllegalArgumentException("Duplicate completed emission task");
        if (actual.size() != expected.size()) throw new IllegalArgumentException("Missing completed emission tasks");
        for (var task : expected) {
            Path job = actual.get(task.id());
            if (job == null || !task.equals(FleetTask.read(job))) throw new IllegalArgumentException("Missing or incompatible completed emission task");
            var counts = task.receiptCounts(job);
            if (task.phase().equals("endpoints")) generated[task.first()] = Math.addExact(generated[task.first()],counts.generatedEndpoints());
        }
        for (int endpoint = 1; endpoint <= ErikssonRoute.ENDPOINT_TYPES; endpoint++)
            if (generated[endpoint] != Long.parseLong(FleetFiles.field(lines,endpoint + 2,"endpoint-" + endpoint)))
                throw new IllegalStateException("Incomplete generated class count at endpoint type " + endpoint);
    }
    private static List<String> header(String run, Path directory) throws IOException {
        var lines = Files.readAllLines(directory.resolve("emission.txt"));
        if (lines.size() < 4 + ErikssonRoute.ENDPOINT_TYPES || !lines.getFirst().equals("td20-fleet-emission-v1")
                || !lines.get(1).equals("route=" + ErikssonRoute.ID) || !lines.get(2).equals("run=" + run))
            throw new IllegalArgumentException("Incompatible emission inventory");
        for (int i = 1; i <= ErikssonRoute.ENDPOINT_TYPES; i++) if (Long.parseLong(FleetFiles.field(lines,i + 2,"endpoint-" + i)) < 0)
            throw new IllegalArgumentException("Missing endpoint inventory");
        return lines;
    }
}
