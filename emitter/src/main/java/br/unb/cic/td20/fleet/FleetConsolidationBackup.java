package br.unb.cic.td20.fleet;

import br.unb.cic.td20.certificate.CertificateDatabaseConsolidator;
import br.unb.cic.td20.joint.ErikssonInventory;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.runtime.ProjectPaths;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Physical consolidation on each server, followed by parallel downloads of large SSTs. */
public final class FleetConsolidationBackup {
    private record FileEntry(String name,long bytes) { }
    private record DownloadFile(FleetServer server,FileEntry entry) { }
    private final Path root,run,directory; private final String uuid; private final List<FleetServer> servers; private final FleetProgress progress;
    private FleetConsolidationBackup(Path root,Path run,String uuid,List<FleetServer> servers,FleetProgress progress) {
        this.root=root;this.run=run;this.uuid=uuid;this.servers=servers;this.progress=progress;directory=run.resolve("consolidation");
    }
    public static void main(String[] args) throws Exception {
        Path run=null,serverFile=null;for(String arg:args)if(arg.startsWith("--run="))run=Path.of(arg.substring(6));else if(arg.startsWith("--servers="))serverFile=Path.of(arg.substring(10));else throw new IllegalArgumentException("Wrong backup argument");
        if(run==null||serverFile==null)throw new IllegalArgumentException("Supply run and servers");
        Path root=ProjectPaths.root(Path.of("")).toRealPath();run=run.toAbsolutePath().normalize();Path directory=run.resolve("consolidation");Files.createDirectories(directory);
        String uuid=Files.readAllLines(run.resolve("plan.txt")).stream().filter(s->s.startsWith("run=")).findFirst().orElseThrow().substring(4);UUID.fromString(uuid);
        try(var channel=FileChannel.open(directory.resolve("backup.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock();var progress=new FleetProgress(directory.resolve("emission.log"))) {
            if(lock==null)throw new IllegalStateException("Another consolidation backup is active");
            Files.writeString(directory.resolve("jvm.pid"),ProcessHandle.current().pid()+"\n");
            var backup=new FleetConsolidationBackup(root,run,uuid,FleetServer.read(serverFile),progress);
            try{backup.execute();}catch(Exception|Error error){progress.event("consolidation","FAILED: "+error);throw error;}
        }
    }
    private String base(FleetServer server){return server.directory()+"/"+uuid+"/"+server.name();}
    private String remoteOutput(FleetServer server){return base(server)+"/emission-consolidated-v1";}
    private void remote(FleetServer server,String command,Path input,Path output,String job,boolean bulk) throws Exception {
        FleetEmitter.runShell(FleetFiles.command(bulk?server.transferRemote(command,false):server.remote(command)),root,input,output,
                directory.resolve(server.name()+"-"+job.replace('/','-')+".log"),line->progress.remote(server.name(),job,line));
    }
    private void execute() throws Exception {
        var tasks=EmissionBatches.read(uuid,run.resolve("emission"));
        if(Files.isRegularFile(directory.resolve("complete-v1.txt"))) {
            completedInputs(run,uuid,tasks);progress.finished(tasks.size());return;
        }
        Files.createDirectories(run.resolve("downloads"));
        var setup=new FleetEmitter(root,directory,servers,uuid,256,progress,(server,command,input,output,log,observer,secret)->
                FleetEmitter.runShell(FleetFiles.command(server.remote(command)),root,input,output,log,observer,secret),
                "https://github.com/luizaugustogarcia/td20.git",null);
        Path snapshot=setup.snapshot();var sources=new ConcurrentHashMap<String,String>();
        progress.phase("consolidation source preparation",servers.size(),0);
        parallel(server->{progress.start(server.name(),"prepare");sources.put(server.name(),setup.prepare(server,snapshot,false));
            String file=base(server)+"/closed-emission-v1.txt";
            remote(server,worker(sources.get(server.name()),server,"inventory",file,null),null,null,"inventory",false);
            remote(server,"cat "+FleetFiles.quote(file),null,directory.resolve(server.name()+"-closed-emission-v1.txt"),"inventory-download",false);progress.done(server.name(),"prepare");});
        var chosen=select(tasks);
        progress.event("consolidation","Complete disjoint emission scope selected: tasks="+tasks.size()+"; raw values are copied; witnesses are not replayed");
        progress.phase("remote emission database consolidation",servers.size(),0);
        parallel(server->{
            Path plan=directory.resolve(server.name()+"-inputs-v1.txt");FleetConsolidationWorker.write(plan,uuid,server.name(),chosen.get(server.name()));
            String remotePlan=base(server)+"/consolidation-inputs-v1.txt";remote(server,"cat > "+FleetFiles.quote(remotePlan),plan,null,"plan-upload",false);
            progress.start(server.name(),"consolidation");progress.stage(server.name(),"copying and compacting");
            remote(server,worker(sources.get(server.name()),server,"consolidate",remoteOutput(server),remotePlan),null,null,"consolidation",false);
            progress.done(server.name(),"consolidation");
        });
        var files=new TreeMap<String,List<FileEntry>>();var staging=new TreeMap<String,Path>();int ssts=0;long bytes=0;
        for(var server:servers) {
            Path published=run.resolve("downloads/"+server.name());
            Path path=Files.exists(published)?published:run.resolve("downloads/."+server.name()+"-consolidation.partial");
            Files.createDirectories(path);staging.put(server.name(),path);
            Path inventory=directory.resolve(server.name()+"-files-v1.txt");
            remote(server,"cat "+FleetFiles.quote(remoteOutput(server)+"/files-v1.txt"),null,inventory,"files-download",false);
            var entries=readFiles(inventory,uuid,server.name());files.put(server.name(),entries);
            if(Files.exists(path.resolve("files-v1.txt"))&&!Files.readAllLines(inventory).equals(Files.readAllLines(path.resolve("files-v1.txt"))))
                throw new IllegalArgumentException("Different staged consolidation inventory");
            if(Files.exists(published))validate(path,uuid,server.name(),chosen.get(server.name()));
            else Files.copy(inventory,path.resolve("files-v1.txt"),StandardCopyOption.REPLACE_EXISTING);
            for(var entry:entries)if(entry.name().endsWith(".sst")){ssts++;bytes=Math.addExact(bytes,entry.bytes());}
            String names=String.join(" ",entries.stream().filter(e->!e.name().endsWith(".sst")).map(e->FleetFiles.quote(e.name())).toList());
            Path archive=directory.resolve(server.name()+"-headers.tar");
            remote(server,"tar -cf - -C "+FleetFiles.quote(remoteOutput(server))+" "+names,null,archive,"headers-download",true);
            FleetEmitter.runShell("tar --no-same-owner -xf "+FleetFiles.quote(archive.toString())+" -C "+FleetFiles.quote(path.toString()),root,null,null,directory.resolve(server.name()+"-headers-extract.log"));Files.delete(archive);
        }
        progress.phase("consolidated SST downloads",ssts,0);progress.event("consolidation","Transferring "+ssts+" SSTs, bytes="+bytes+"; 24 independent SSH workers; original job databases remain remote");
        var groups=new ArrayList<List<DownloadFile>>();
        for(var server:servers)groups.add(files.get(server.name()).stream().filter(e->e.name().endsWith(".sst")).map(e->new DownloadFile(server,e)).toList());
        progress.transferBytes(bytes,()->downloadedBytes(staging,files));
        try(var transfers=new FleetDownloads(24,48)) {
            for(var download:roundRobin(groups)) {
                var server=download.server();var entry=download.entry();
                String job=server.name()+"-"+entry.name();progress.downloadQueued(server.name(),job);
                transfers.submit(()->{
                    Path output=staging.get(server.name()).resolve(entry.name());Files.createDirectories(output.getParent());Path partial=output.resolveSibling(output.getFileName()+".partial");
                    if(Files.isRegularFile(output)&&Files.size(output)==entry.bytes()){progress.accepted(server.name(),job,0,0,0);return;}
                    for(int attempt=1;;attempt++)try {
                        progress.downloadStage(server.name(),job,"downloading");remote(server,"cat "+FleetFiles.quote(remoteOutput(server)+"/"+entry.name()),null,partial,job,true);
                        if(Files.size(partial)!=entry.bytes())throw new IllegalStateException("Truncated SST transfer");
                        Files.move(partial,output,StandardCopyOption.ATOMIC_MOVE);progress.accepted(server.name(),job,0,0,0);return;
                    }catch(Exception error){progress.downloadFailed(server.name(),job,error,attempt==3||error instanceof InterruptedException);if(attempt==3||error instanceof InterruptedException)throw error;Thread.sleep(2000);}
                });
            }transfers.await();
        }
        var completed=new ArrayList<>(List.of("td20-emission-consolidated-backup-v1","route="+ErikssonRoute.ID,"run="+uuid,"tasks="+tasks.size(),"servers="+servers.size()));
        progress.phase("validating consolidated emission downloads",servers.size(),0);
        for(var server:servers) {
            Path path=staging.get(server.name());validate(path,uuid,server.name(),chosen.get(server.name()));
            Path output=run.resolve("downloads/"+server.name());if(!path.equals(output))Files.move(path,output,StandardCopyOption.ATOMIC_MOVE);
            completed.add("server="+server.name());progress.start(server.name(),"validation");progress.done(server.name(),"validation");
        }
        FleetFiles.write(directory.resolve("complete-v1.txt"),completed);progress.finished(servers.size());
        Files.writeString(run.resolve("emission.log"),java.time.Instant.now()+" [consolidation] All "+tasks.size()+" emission tasks are preserved in "+servers.size()+" local consolidated databases; old servers are no longer needed for completed emission.\n",StandardOpenOption.APPEND);
        progress.event("consolidation","Completed emission backup: "+run.resolve("downloads")+"; reconstruction remains stopped; independent verification remains pending");
    }
    private String worker(String source,FleetServer server,String mode,String output,String plan) throws Exception {
        String options=server.runtimeOptions(FleetHardware.read(directory.resolve(server.name()+"-hardware-v1.txt")));
        if(mode.equals("consolidate"))FleetFiles.write(directory.resolve(server.name()+"-execution-v1.txt"),List.of(
                "td20-fleet-consolidation-execution-v1","run="+uuid,"server="+server.name(),"configured="+server.runtimeOptions(),"effective="+options));
        String command="export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; export PATH=\"$JAVA_HOME/bin:$PATH\"; cd "+FleetFiles.quote(source)+" && env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS TD20_JSHELL_OPTIONS="+FleetFiles.quote(options)
                +" ./tools/td20 fleet-consolidate-worker "+FleetFiles.quote("--mode="+mode)+" "+FleetFiles.quote("--run="+uuid)+" "+FleetFiles.quote("--server="+server.name())
                +" "+FleetFiles.quote("--jobs="+base(server)+"/jobs")+" "+FleetFiles.quote("--output="+output)+(plan==null?"":" "+FleetFiles.quote("--plan="+plan));
        return "flock -n "+FleetFiles.quote(base(server)+"/worker.lock")+" "+FleetFiles.interactive(command);
    }
    @FunctionalInterface private interface ServerWork {void run(FleetServer server)throws Exception;}
    private void parallel(ServerWork work)throws Exception {
        try(var executor=Executors.newFixedThreadPool(servers.size())) {var futures=new ArrayList<Future<?>>();
            for(var server:servers)futures.add(executor.submit(()->{try{work.run(server);}catch(Exception e){progress.failed(server.name(),e);throw new CompletionException(e);}}));
            for(var future:futures)future.get();
        }
    }
    private Map<String,List<FleetConsolidationWorker.Candidate>> select(List<FleetTask> tasks)throws Exception {
        record Located(FleetServer server,FleetConsolidationWorker.Candidate candidate){}
        var expected=new HashMap<String,FleetTask>();tasks.forEach(t->expected.put(t.id(),t));var chosen=new TreeMap<String,Located>();
        for(var server:servers)for(var candidate:FleetConsolidationWorker.read(directory.resolve(server.name()+"-closed-emission-v1.txt"),uuid,server.name())) {
            if(!candidate.task().equals(expected.get(candidate.task().id()))||!candidate.receipt().equals(Files.readAllLines(run.resolve("results/"+candidate.task().id()+"/complete.txt"))))
                throw new IllegalArgumentException("Closed source differs from validated emission scope");
            chosen.putIfAbsent(candidate.task().id(),new Located(server,candidate));
        }
        if(chosen.size()!=tasks.size())throw new IllegalStateException("Missing completed emission tasks");
        var result=new TreeMap<String,List<FleetConsolidationWorker.Candidate>>();servers.forEach(s->result.put(s.name(),new ArrayList<>()));
        chosen.values().forEach(c->result.get(c.server().name()).add(c.candidate()));return result;
    }
    private static List<FileEntry> readFiles(Path file,String run,String server)throws Exception {
        var lines=Files.readAllLines(file);if(lines.size()<5||!lines.subList(0,4).equals(List.of("td20-consolidation-files-v1","route="+ErikssonRoute.ID,"run="+run,"server="+server)))
            throw new IllegalArgumentException("Wrong consolidated file inventory");
        int n=Integer.parseInt(FleetFiles.field(lines,4,"files"));if(n<1||lines.size()!=n+5L)throw new IllegalArgumentException("Missing consolidated files");
        var result=new ArrayList<FileEntry>();var names=new HashSet<String>();
        for(int i=0;i<n;i++){String[] values=FleetFiles.field(lines,i+5,"file").split("\t",-1);if(values.length!=2||!values[0].matches("[A-Za-z0-9._/-]+")||Path.of(values[0]).isAbsolute()||Path.of(values[0]).normalize().startsWith("..")||!Path.of(values[0]).normalize().toString().equals(values[0])||!names.add(values[0]))throw new IllegalArgumentException("Wrong file path");
            long size=Long.parseLong(values[1]);if(size<0)throw new IllegalArgumentException("Negative file size");result.add(new FileEntry(values[0],size));}
        return List.copyOf(result);
    }
    private static void validate(Path path,String run,String server,List<FleetConsolidationWorker.Candidate> candidates)throws Exception {
        for(var file:readFiles(path.resolve("files-v1.txt"),run,server))if(Files.size(path.resolve(file.name()))!=file.bytes())throw new IllegalStateException("Missing or truncated consolidated file");
        if(!candidates.equals(FleetConsolidationWorker.read(path.resolve("tasks-v1.txt"),run,server)))throw new IllegalArgumentException("Different downloaded task scope");
        var roots=ErikssonInventory.read(ProjectPaths.root(Path.of("")).resolve("docs/td20-reduction-count.tex"));
        long witnesses=0,markers=0;
        for(var candidate:candidates){var input=candidate.input(path.resolve("certificates"),roots);witnesses=Math.addExact(witnesses,input.witnesses());markers=Math.addExact(markers,input.markers());}
        var report=CertificateDatabaseConsolidator.inspect(path.resolve("certificates"));
        if(report.inputs()!=candidates.size()||report.witnesses()!=witnesses||report.markers()!=markers)throw new IllegalStateException("Different consolidated KV inventory");
    }
    static List<Path> completedInputs(Path run,String uuid,List<FleetTask> tasks)throws Exception {
        var lines=Files.readAllLines(run.resolve("consolidation/complete-v1.txt"));
        if(lines.size()<5||!lines.subList(0,4).equals(List.of("td20-emission-consolidated-backup-v1","route="+ErikssonRoute.ID,"run="+uuid,"tasks="+tasks.size())))throw new IllegalArgumentException("Different consolidated emission scope");
        int n=Integer.parseInt(FleetFiles.field(lines,4,"servers"));if(n<1||lines.size()!=n+5L)throw new IllegalArgumentException("Missing consolidated servers");
        var expected=new HashMap<String,FleetTask>();tasks.forEach(t->expected.put(t.id(),t));var seen=new HashSet<String>();var names=new HashSet<String>();var result=new ArrayList<Path>();
        for(int i=0;i<n;i++){String server=FleetFiles.field(lines,i+5,"server");if(!server.matches("[A-Za-z0-9_-]+")||!names.add(server))throw new IllegalArgumentException("Wrong server path");Path path=run.resolve("downloads/"+server);
            var candidates=FleetConsolidationWorker.read(path.resolve("tasks-v1.txt"),uuid,server);for(var candidate:candidates)if(!candidate.task().equals(expected.get(candidate.task().id()))||!seen.add(candidate.task().id())
                    ||!candidate.receipt().equals(Files.readAllLines(run.resolve("results/"+candidate.task().id()+"/complete.txt"))))throw new IllegalArgumentException("Duplicate, foreign or changed task");
            validate(path,uuid,server,candidates);result.add(path.resolve("certificates"));}
        if(seen.size()!=tasks.size())throw new IllegalArgumentException("Missing consolidated emission tasks");return List.copyOf(result);
    }
    static <T> List<T> roundRobin(List<List<T>> groups) {
        var result=new ArrayList<T>();var iterators=groups.stream().map(List::iterator).toList();
        boolean pending=true;while(pending){pending=false;for(var iterator:iterators)if(iterator.hasNext()){result.add(iterator.next());pending=true;}}
        return List.copyOf(result);
    }
    private static long downloadedBytes(Map<String,Path> staging,Map<String,List<FileEntry>> files) {
        long bytes=0;
        for(var server:files.entrySet())for(var file:server.getValue())if(file.name().endsWith(".sst"))try {
            Path output=staging.get(server.getKey()).resolve(file.name());Path partial=output.resolveSibling(output.getFileName()+".partial");
            if(Files.isRegularFile(output))bytes=Math.addExact(bytes,Math.min(file.bytes(),Files.size(output)));
            else if(Files.isRegularFile(partial))bytes=Math.addExact(bytes,Math.min(file.bytes(),Files.size(partial)));
        }catch(java.io.IOException ignored){ /* A transfer may be atomically renamed during a snapshot. */ }
        return bytes;
    }
}
