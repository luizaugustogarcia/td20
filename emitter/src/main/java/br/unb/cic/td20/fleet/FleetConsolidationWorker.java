package br.unb.cic.td20.fleet;

import br.unb.cic.td20.certificate.CertificateDatabaseConsolidator;
import br.unb.cic.td20.joint.ErikssonInventory;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.runtime.ProjectPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Scoped inventories and raw KV consolidation of closed emission tasks. */
public final class FleetConsolidationWorker {
    record Candidate(String directory, FleetTask task, List<String> receipt) {
        Candidate {
            if (!directory.startsWith(task.id()+"-") || !List.of("roots","endpoints").contains(task.phase())) throw new IllegalArgumentException("Wrong emission directory");
            UUID.fromString(directory.substring(task.id().length()+1)); receipt=List.copyOf(receipt);
            if (receipt.size()!=12 || !receipt.getFirst().equals("td20-fleet-complete-v2")
                    || !receipt.subList(1,9).equals(task.lines().subList(1,9))) throw new IllegalArgumentException("Wrong emission receipt");
            long residuals=Long.parseLong(FleetFiles.field(receipt,9,"endpointResiduals"));
            long reconstructed=Long.parseLong(FleetFiles.field(receipt,10,"reconstructedRoots"));
            long generated=Long.parseLong(FleetFiles.field(receipt,11,"generatedEndpoints"));
            if(residuals<0 || reconstructed!=0 || generated<residuals
                    || task.phase().equals("roots") && (generated!=0 || residuals!=0)
                    || task.generation().startsWith("leaves/") && generated!=task.inputCount())
                throw new IllegalArgumentException("Invalid emission inventory");
        }
        CertificateDatabaseConsolidator.Input input(Path database,List<ErikssonInventory.Root> roots) {
            long markers=Long.parseLong(FleetFiles.field(receipt,9,"endpointResiduals"));
            long witnesses=Long.parseLong(FleetFiles.field(receipt,11,"generatedEndpoints"))-markers;
            if(task.phase().equals("roots")) {
                witnesses=0;
                for(int index=task.first();index<=task.last();index++) witnesses=Math.addExact(witnesses,roots.get(index-1).directClasses());
            }
            return new CertificateDatabaseConsolidator.Input(database,witnesses,markers);
        }
    }
    static List<Candidate> inventory(String run,Path jobs) throws Exception {
        var result=new ArrayList<Candidate>();
        try(var paths=Files.list(jobs)) {
            for(Path path:paths.sorted().toList()) if(Files.isRegularFile(path.resolve("task.txt"))&&Files.isRegularFile(path.resolve("complete.txt"))) {
                var task=FleetTask.read(path); if(!task.run().equals(run))throw new IllegalArgumentException("Different source run");
                if(task.phase().equals("avoidance"))continue; task.receiptCounts(path);
                if(!Files.isRegularFile(path.resolve("certificates/CURRENT")))throw new IllegalArgumentException("Missing closed source database");
                result.add(new Candidate(path.getFileName().toString(),task,Files.readAllLines(path.resolve("complete.txt"))));
            }
        } return List.copyOf(result);
    }
    static void write(Path file,String run,String server,List<Candidate> candidates) throws Exception {
        var lines=new ArrayList<>(List.of("td20-emission-consolidation-inputs-v1","route="+ErikssonRoute.ID,"run="+run,"server="+server,"tasks="+candidates.size()));
        for(var c:candidates)lines.add("job="+c.directory()+"\t"+encode(c.task().lines())+"\t"+encode(c.receipt()));
        FleetFiles.write(file,lines);
    }
    static List<Candidate> read(Path file,String run,String server) throws Exception {
        var lines=Files.readAllLines(file);
        if(lines.size()<5 || !lines.subList(0,4).equals(List.of("td20-emission-consolidation-inputs-v1","route="+ErikssonRoute.ID,"run="+run,"server="+server)))
            throw new IllegalArgumentException("Wrong consolidation scope");
        int n=Integer.parseInt(FleetFiles.field(lines,4,"tasks")); if(n<1 || lines.size()!=n+5L)throw new IllegalArgumentException("Missing consolidation tasks");
        var result=new ArrayList<Candidate>();var directories=new HashSet<String>();
        for(int i=0;i<n;i++) {
            String[] fields=FleetFiles.field(lines,i+5,"job").split("\t",-1);if(fields.length!=3)throw new IllegalArgumentException("Malformed consolidation input");
            var t=decode(fields[1]);
            if(t.size()!=9 || !t.getFirst().equals("td20-fleet-task-v2") || !t.get(1).equals("route="+ErikssonRoute.ID))throw new IllegalArgumentException("Wrong task route");
            var task=new FleetTask(FleetFiles.field(t,2,"run"),FleetFiles.field(t,3,"id"),FleetFiles.field(t,4,"phase"),
                    Integer.parseInt(FleetFiles.field(t,5,"first")),Integer.parseInt(FleetFiles.field(t,6,"last")),Long.parseLong(FleetFiles.field(t,7,"inputCount")),FleetFiles.field(t,8,"generation"));
            if(!task.run().equals(run)||!directories.add(fields[0]))throw new IllegalArgumentException("Duplicate or foreign input");
            result.add(new Candidate(fields[0],task,decode(fields[2])));
        }return List.copyOf(result);
    }
    private static String encode(List<String> lines){return Base64.getEncoder().encodeToString(String.join("\n",lines).getBytes(StandardCharsets.UTF_8));}
    private static List<String> decode(String value){return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8).lines().toList();}
    public static void main(String[] args) throws Exception {
        var options=new HashMap<String,String>();for(String arg:args){int split=arg.indexOf('=');if(split<3||!arg.startsWith("--"))throw new IllegalArgumentException("Named arguments required");options.put(arg.substring(2,split),arg.substring(split+1));}
        String run=Objects.requireNonNull(options.get("run")),server=Objects.requireNonNull(options.get("server"));UUID.fromString(run);
        Path jobs=Path.of(Objects.requireNonNull(options.get("jobs"))),output=Path.of(Objects.requireNonNull(options.get("output")));
        if("inventory".equals(options.get("mode"))) {write(output,run,server,inventory(run,jobs));System.out.println("Closed emission inventory: "+output);return;}
        if(!"consolidate".equals(options.get("mode")))throw new IllegalArgumentException("Wrong consolidation mode");
        var candidates=read(Path.of(Objects.requireNonNull(options.get("plan"))),run,server);
        if(candidates.stream().map(c->c.task().id()).distinct().count()!=candidates.size())throw new IllegalArgumentException("Duplicate selected task");
        if(Files.exists(output)) {
            if(!candidates.equals(read(output.resolve("tasks-v1.txt"),run,server)))throw new IllegalArgumentException("Different existing consolidation scope");
            CertificateDatabaseConsolidator.inspect(output.resolve("certificates"));return;
        }
        Path staging=Files.createDirectory(output.resolveSibling("."+output.getFileName()+"-"+UUID.randomUUID()+".partial"));
        var roots=ErikssonInventory.read(ProjectPaths.root(Path.of("")).resolve("docs/td20-reduction-count.tex"));
        var inputs=new ArrayList<CertificateDatabaseConsolidator.Input>();
        for(var candidate:candidates) {
            Path job=jobs.resolve(candidate.directory());
            if(!candidate.task().equals(FleetTask.read(job)) || !candidate.receipt().equals(Files.readAllLines(job.resolve("complete.txt"))))
                throw new IllegalArgumentException("Closed task changed");
            candidate.task().receiptCounts(job);
            inputs.add(candidate.input(job.resolve("certificates"),roots));
        }
        CertificateDatabaseConsolidator.consolidate(staging.resolve("certificates"),inputs,CertificateDatabaseConsolidator.TARGET_SST_BYTES,System.out::println);
        write(staging.resolve("tasks-v1.txt"),run,server,candidates);
        writeFiles(staging,run,server);
        Files.move(staging,output,StandardCopyOption.ATOMIC_MOVE);System.out.println("EMISSION_CONSOLIDATION_COMPLETE: tasks="+candidates.size());
    }
    private static void writeFiles(Path directory,String run,String server) throws Exception {
        List<Path> paths;try(var files=Files.walk(directory)){paths=files.filter(Files::isRegularFile)
                .filter(p->!p.getFileName().toString().startsWith("LOG")&&!p.getFileName().toString().equals("LOCK")).sorted().toList();}
        var lines=new ArrayList<>(List.of("td20-consolidation-files-v1","route="+ErikssonRoute.ID,"run="+run,"server="+server,"files="+paths.size()));
        for(Path path:paths)lines.add("file="+directory.relativize(path)+"\t"+Files.size(path));
        FleetFiles.write(directory.resolve("files-v1.txt"),lines);
    }
}
