package br.unb.cic.td20;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.joint.JointInventory;
import br.unb.cic.td20.joint.JointRootGenerator;
import br.unb.cic.td20.runtime.ProjectPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** CPU-only exact root and endpoint counting for the joint-selection route. */
public final class CountTD20Bracelets {
    private CountTD20Bracelets() { }
    public static void main(String[] arguments) throws Exception {
        Path paper = ProjectPaths.root(Path.of("")).resolve("docs/td20-reduction-count.tex");
        String phase = "all";
        int workers = Runtime.getRuntime().availableProcessors();
        int first = 1, last = 83, endpointFirst = 1, endpointLast = 104;
        for (String arg : arguments) {
            if (arg.equals("--help")) {
                System.out.println("CountTD20Bracelets [--phase=roots|endpoints|all] [--partitions=FIRST-LAST]"
                        + " [--endpoints=FIRST-LAST] [--paper=PATH] [--workers=N]"); return;
            } else if (arg.startsWith("--phase=")) phase = arg.substring(8);
            else if (arg.startsWith("--paper=")) paper = Path.of(arg.substring(8));
            else if (arg.startsWith("--workers=")) workers = Integer.parseInt(arg.substring(10));
            else if (arg.startsWith("--partitions=")) { int[] r = range(arg.substring(13),83); first = r[0]; last = r[1]; }
            else if (arg.startsWith("--endpoints=")) { int[] r = range(arg.substring(12),104); endpointFirst = r[0]; endpointLast = r[1]; }
            else throw new IllegalArgumentException("Unknown argument: " + arg);
        }
        if (workers < 1) throw new IllegalArgumentException("Positive worker count required");
        if (!List.of("roots","endpoints","all").contains(phase)) throw new IllegalArgumentException("Invalid count phase");
        var inventory = JointInventory.read(paper);
        System.setProperty("td20.twistedBraceletWorkers", Integer.toString(workers));
        System.out.printf("Counting only: workers=%d availableCores=%d phase=%s%n", workers, Runtime.getRuntime().availableProcessors(), phase);
        var completed = new AtomicInteger();
        var reporter = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("count-progress").factory());
        reporter.scheduleWithFixedDelay(() -> System.out.println("Counting progress: completed="+completed.get()), 60, 60, TimeUnit.SECONDS);
        long started = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(workers)) {
            if (phase.equals("roots") || phase.equals("all")) {
                var tasks = new ArrayList<Future<RootCount>>();
                for (var row : inventory) if (row.number() >= first && row.number() <= last) tasks.add(pool.submit(() -> {
                    long u = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(row.partition(),new boolean[row.partition().length]);
                    var counts = JointRootGenerator.count(row.partition());
                    if (u != row.unorientedClasses() || counts.jointResidualClasses() != row.jointResidualClasses()
                            || counts.supportClearingClasses() != row.supportClearingClasses() || counts.orientedClasses() != row.orientedClasses())
                        throw new IllegalStateException("Count disagrees with paper at partition " + row.number() + ": U=" + u + " " + counts);
                    completed.incrementAndGet();
                    return new RootCount(row,u,counts);
                }));
                long unoriented = 0, joint = 0, cleared = 0, oriented = 0, noFirst = 0;
                for (var task : tasks) {
                    var count = task.get(); var c = count.counts;
                    System.out.printf("partition=%d lengths=%s unoriented=%d jointResiduals=%d supportCleared=%d directOriented=%d noQualifyingFirst=%d prefixNodes=%d%n",
                            count.row.number(),Arrays.toString(count.row.partition()),count.unoriented,c.jointResidualClasses(),
                            c.supportClearingClasses(),c.orientedClasses(),c.noQualifyingFirstMoveClasses(),c.prefixNodes());
                    unoriented = Math.addExact(unoriented,count.unoriented); joint = Math.addExact(joint,c.jointResidualClasses());
                    cleared = Math.addExact(cleared,c.supportClearingClasses()); oriented = Math.addExact(oriented,c.orientedClasses());
                    noFirst = Math.addExact(noFirst,c.noQualifyingFirstMoveClasses());
                }
                System.out.printf("Root counts checked: partitions=%d-%d unoriented=%d jointResiduals=%d supportCleared=%d directOriented=%d directTotal=%d noQualifyingFirst=%d%n",
                        first,last,unoriented,joint,cleared,oriented,Math.addExact(unoriented,oriented),noFirst);
            }
            if (phase.equals("endpoints") || phase.equals("all")) {
                var tasks = new ArrayList<Future<EndpointCount>>();
                for (var endpoint : JointInventory.endpoints()) if (endpoint.number() >= endpointFirst && endpoint.number() <= endpointLast)
                    tasks.add(pool.submit(() -> {
                        long count = DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(), endpoint.orientedByPart());
                        completed.incrementAndGet(); return new EndpointCount(endpoint,count);
                    }));
                long total = 0, requiringDischarge = 0;
                for (var task : tasks) {
                    var result = task.get(); total = Math.addExact(total,result.count);
                    if (!result.endpoint.elementaryClosure()) requiringDischarge = Math.addExact(requiringDischarge,result.count);
                    System.out.printf("endpoint=%d lengths=%s orientations=%s classes=%d elementaryClosure=%s%n",
                            result.endpoint.number(),Arrays.toString(result.endpoint.partition()),Arrays.toString(result.endpoint.orientedByPart()),
                            result.count,result.endpoint.elementaryClosure());
                }
                System.out.printf("Endpoint cover counted: annotations=%d-%d allClasses=%d classesRequiringDischarge=%d%n",endpointFirst,endpointLast,total,requiringDischarge);
            }
        } finally { reporter.shutdownNow(); DirectTwistedBraceletGenerator.shutdownExecutor(); }
        System.out.printf("Counting complete in %.3f seconds; sorting witnesses were not checked.%n", (System.nanoTime()-started)/1e9);
    }
    private record RootCount(JointInventory.Root row, long unoriented, JointRootGenerator.Counts counts) { }
    private record EndpointCount(JointInventory.Endpoint endpoint, long count) { }
    private static int[] range(String value, int maximum) {
        String[] parts = value.split("-", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Expected FIRST-LAST range");
        int first = Integer.parseInt(parts[0]), last = Integer.parseInt(parts[1]);
        if (first < 1 || first > last || last > maximum) throw new IllegalArgumentException("Invalid range");
        return new int[]{first,last};
    }
}
