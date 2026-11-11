package br.unb.cic.td20;

import br.unb.cic.td20.joint.JointRootGenerator;
import br.unb.cic.td20.reduction.Partitions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

/** Exact Java joint-3 counting; whole-partition reports are atomic resume boundaries. */
public final class CountTD20Joint {
    private CountTD20Joint() { }
    public static void main(String[] args) throws Exception {
        int n=21;Path list=null,output=br.unb.cic.td20.runtime.ProjectPaths.root(Path.of(""))
                .resolve("data/joint3-reduction-audit/native-v1");
        for(String arg:args) {
            if(arg.startsWith("--n=")) n=Integer.parseInt(arg.substring(4));
            else if(arg.startsWith("--partitions-file=")) list=Path.of(arg.substring(18));
            else if(arg.startsWith("--output=")) output=Path.of(arg.substring(9));
            else throw new IllegalArgumentException("Unknown argument: "+arg);
        }
        if(n<3||n>21) throw new IllegalArgumentException("Expected N=3..21");
        var parts=new ArrayList<int[]>();
        if(list==null) parts.addAll(Partitions.generateValidPartitions(n));
        else for(String line:Files.readAllLines(list)) {
            int[] part=Arrays.stream(line.split(",",-1)).mapToInt(Integer::parseInt).toArray();
            if(Arrays.stream(part).sum()!=n) throw new IllegalArgumentException("Wrong partition domain");
            parts.add(part);
        }
        var keys=new HashSet<String>();
        for(int[] part:parts) if(!keys.add(key(part))) throw new IllegalArgumentException("Duplicate partition");
        Files.createDirectories(output);
        int workers = Runtime.getRuntime().availableProcessors();
        System.out.printf("Java counting with TD18 machinery: available_cpu_cores=%d partitions=%d%n",workers,parts.size());
        var processed = new java.util.concurrent.atomic.AtomicInteger();
        var reporter=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("count-progress").factory());
        reporter.scheduleWithFixedDelay(() -> System.out.printf("Counting progress: completed=%d%n", processed.get()),
                60,60,java.util.concurrent.TimeUnit.SECONDS);
        final Path directory=output;
        try(var coordinators=Executors.newFixedThreadPool(workers)) {
            var completed=new ExecutorCompletionService<String>(coordinators);
            for(int[] part:parts) completed.submit(() -> publish(part,directory));
            for(int i=0;i<parts.size();i++) { System.out.println(completed.take().get()); processed.incrementAndGet(); }
        } finally { reporter.shutdownNow(); }
    }
    private static String key(int[] part) {
        return Arrays.stream(part).mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining("-"));
    }
    private static String publish(int[] part,Path directory) throws Exception {
        Path report=directory.resolve("partition-"+key(part)+".json");
        if(Files.exists(report)) throw new IllegalArgumentException("Refusing to overwrite "+report);
        long started=System.nanoTime();var audit=JointRootGenerator.countAudit(part);var c=audit.counts();
        var json=new StringBuilder("{\"format\":\"td20-joint3-residual-count-v3\",")
            .append("\"implementation\":\"java-joint3-td18-machinery-v1\",\"scheduling\":\"td18-partition-pool-v1\",")
            .append("\"partition\":").append(Arrays.toString(part).replace(" ",""))
            .append(",\"shard_index\":0,\"shard_count\":1,\"worker_limit\":").append(Runtime.getRuntime().availableProcessors())
            .append(",\"oriented_root_classes\":").append(c.jointResidualClasses())
            .append(",\"support_clearing_classes\":").append(c.supportClearingClasses())
            .append(",\"oriented_after_support_clearing\":").append(c.orientedClasses())
            .append(",\"no_qualifying_first_move\":").append(c.noQualifyingFirstMoveClasses())
            .append(",\"qualifying_but_no_favorable_prefix\":").append(c.jointResidualClasses()-c.noQualifyingFirstMoveClasses())
            .append(",\"prefix_nodes\":").append(c.prefixNodes())
            .append(",\"realizable_leaves\":").append(c.realizableLeaves())
            .append(",\"ignored_unoriented_leaves\":").append(audit.ignoredUnorientedLeaves())
            .append(",\"support_clearing_by_least_length\":{");
        boolean first=true;
        for(int length:new int[]{9,11,13,17}) {
            if(!first) json.append(',');first=false;
            json.append('"').append(length).append("\":").append(audit.clearingByLength().get(length));
        }
        json.append("},\"elapsed_seconds\":").append(String.format(java.util.Locale.ROOT,"%.6f",(System.nanoTime()-started)/1e9)).append('}');
        Path temporary=report.resolveSibling(report.getFileName()+".java.tmp");
        Files.writeString(temporary,json+"\n");Files.createLink(report,temporary);Files.delete(temporary);
        return json.toString();
    }
}
