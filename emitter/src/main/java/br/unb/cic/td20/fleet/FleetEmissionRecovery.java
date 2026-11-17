package br.unb.cic.td20.fleet;

import br.unb.cic.td20.avoidance.DiskStateSet;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.runtime.ProjectPaths;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Regenerates one lost emission scope without restarting the inverse computation. */
public final class FleetEmissionRecovery {
    private final Path root,run,directory;
    private final String uuid,lost;
    private final List<FleetServer> servers;
    private final FleetProgress progress;
    private FleetEmissionRecovery(Path root,Path run,String uuid,String lost,List<FleetServer> servers,FleetProgress progress) {
        this.root=root;this.run=run;this.uuid=uuid;this.lost=lost;this.servers=servers;this.progress=progress;
        directory=run.resolve("emission-recovery-"+lost+"-v1");
    }
    public static void main(String[] args)throws Exception {
        Path run=null,file=null;String lost=null;
        for(String arg:args)if(arg.startsWith("--run="))run=Path.of(arg.substring(6));
            else if(arg.startsWith("--servers="))file=Path.of(arg.substring(10));
            else if(arg.startsWith("--lost-server="))lost=arg.substring(14);else throw new IllegalArgumentException("Unknown recovery option");
        if(run==null||file==null||lost==null||!lost.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Supply run, servers and lost-server");
        run=run.toAbsolutePath().normalize();Path root=ProjectPaths.root(Path.of("")).toRealPath();
        String uuid=Files.readAllLines(run.resolve("plan.txt")).stream().filter(s->s.startsWith("run=")).findFirst().orElseThrow().substring(4);UUID.fromString(uuid);
        var servers=FleetServer.read(file);String missing=lost;
        if(servers.stream().anyMatch(s->s.name().equals(missing)))throw new IllegalArgumentException("Lost server remains in recovery fleet");
        Path directory=Files.createDirectories(run.resolve("emission-recovery-"+lost+"-v1"));
        try(var channel=FileChannel.open(directory.resolve("fleet.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock();
            var progress=new FleetProgress(directory.resolve("emission.log"))){
            if(lock==null)throw new IllegalStateException("Recovery already active");
            Files.writeString(directory.resolve("jvm.pid"),ProcessHandle.current().pid()+"\n");
            var recovery=new FleetEmissionRecovery(root,run,uuid,lost,servers,progress);
            try{recovery.execute();}catch(Exception|Error error){progress.event("recovery","FAILED: "+error);throw error;}
        }
    }
    static List<FleetTask> validateScope(String uuid,List<FleetTask> complete,List<FleetConsolidationWorker.Candidate> lost,Path results)throws Exception {
        var expected=new HashMap<String,FleetTask>();complete.forEach(t->expected.put(t.id(),t));var seen=new HashSet<String>();
        for(var c:lost)if(!c.task().run().equals(uuid)||!c.task().equals(expected.get(c.task().id()))||!seen.add(c.task().id())
                ||!c.receipt().equals(Files.readAllLines(results.resolve(c.task().id()+"/complete.txt"))))throw new IllegalArgumentException("Foreign, duplicate or changed recovery task");
        if(lost.isEmpty())throw new IllegalArgumentException("Missing lost emission scope");
        return lost.stream().sorted(Comparator.<FleetConsolidationWorker.Candidate>comparingLong(c->Long.parseLong(FleetFiles.field(c.receipt(),11,"generatedEndpoints")))
                .reversed().thenComparing(c->c.task().id())).map(FleetConsolidationWorker.Candidate::task).toList();
    }
    private void execute()throws Exception {
        var all=EmissionBatches.read(uuid,run.resolve("emission"));
        if(Files.isRegularFile(directory.resolve("complete-v1.txt"))){FleetConsolidationBackup.completedInputs(run,uuid,all);progress.finished(all.size());return;}
        var original=FleetConsolidationWorker.read(run.resolve("consolidation/"+lost+"-inputs-v1.txt"),uuid,lost);
        var tasks=validateScope(uuid,all,original,run.resolve("results"));
        var scope=new ArrayList<>(List.of("td20-emission-recovery-v1","route="+ErikssonRoute.ID,"run="+uuid,"lostServer="+lost,"tasks="+tasks.size()));
        tasks.forEach(t->scope.add("task="+t.id()));
        Path record=directory.resolve("scope-v1.txt");
        if(Files.exists(record)&&!Files.readAllLines(record).equals(scope))throw new IllegalArgumentException("Recovery scope changed");
        if(!Files.exists(record))FleetFiles.write(record,scope);
        Path jobs=Files.createDirectories(directory.resolve("jobs"));
        for(var task:tasks){Path job=Files.createDirectories(jobs.resolve(task.id()));if(Files.exists(job.resolve("task.txt"))&&!task.equals(FleetTask.read(job)))throw new IllegalArgumentException("Changed recovery input");task.write(job);}
        var emitter=new FleetEmitter(root,directory,servers,uuid,256,progress,(s,c,i,o,l,v,k)->
                FleetEmitter.runShell(FleetFiles.command(s.remote(c)),root,i,o,l,v,k),"https://github.com/luizaugustogarcia/td20.git",null);
        Path snapshot=emitter.snapshot();
        progress.event("recovery","Regenerating exactly "+tasks.size()+" lost emission tasks using "+servers.size()+" servers; shared queue; largest endpoint tasks first; inverse reconstruction remains stopped");
        emitter.dispatch(tasks,jobs,snapshot);
        emitter.downloadMetadata(tasks);
        for(var task:tasks){
            Path old=run.resolve("results/"+task.id()),fresh=directory.resolve("results/"+task.id());
            if(!Files.readAllLines(old.resolve("complete.txt")).equals(Files.readAllLines(fresh.resolve("complete.txt"))))throw new IllegalStateException("Regenerated emission inventory differs: "+task.id());
            if(task.phase().equals("endpoints")){long count=task.receiptCounts(old).endpointResiduals();
                if(!residualKeys(old.resolve("work/endpoint-residuals"),count).equals(residualKeys(fresh.resolve("work/endpoint-residuals"),count)))
                    throw new IllegalStateException("Regenerated residual classes differ; preserve existing inverse inputs: "+task.id());
            }
        }
        FleetFiles.write(directory.resolve("emission-complete-v1.txt"),scope);
        backup(tasks,all);
    }
    private static Set<String> residualKeys(Path path,long count){
        var keys=new HashSet<String>();try(var states=DiskStateSet.openSealed(path,count)){states.forEach(s->keys.add(Base64.getEncoder().encodeToString(CertificateCodec.keyFor(s.pair()))+":"+s.maximumMoves()));}return keys;
    }
    private String base(FleetServer s){return s.directory()+"/"+uuid+"/"+s.name();}
    private String label(FleetServer s){return "recovered-"+lost+"-"+s.name();}
    private String output(FleetServer s){return base(s)+"/emission-recovery-"+lost+"-v1";}
    private void remote(FleetServer s,String command,Path input,Path output,String job,boolean bulk)throws Exception {
        FleetEmitter.runShell(FleetFiles.command(bulk?s.transferRemote(command,false):s.remote(command)),root,input,output,
                directory.resolve(s.name()+"-"+job+".log"),line->progress.remote(s.name(),job,line));
    }
    private void backup(List<FleetTask> tasks,List<FleetTask> all)throws Exception {
        var groups=new TreeMap<String,List<FleetConsolidationWorker.Candidate>>();servers.forEach(s->groups.put(s.name(),new ArrayList<>()));
        for(var task:tasks){var transfer=FleetTransfer.read(directory.resolve("transfers/emission/"+task.id()+".txt"));
            if(!transfer.run().equals(uuid)||!groups.containsKey(transfer.server())||!transfer.task().equals(task.id()))throw new IllegalArgumentException("Invalid recovered assignment");
            groups.get(transfer.server()).add(new FleetConsolidationWorker.Candidate(task.id()+"-"+transfer.attempt(),task,Files.readAllLines(directory.resolve("results/"+task.id()+"/complete.txt"))));
        }
        var active=servers.stream().filter(s->!groups.get(s.name()).isEmpty()).toList();
        progress.phase("remote emission database consolidation",active.size(),0);
        try(var pool=Executors.newFixedThreadPool(active.size())){var futures=new ArrayList<Future<?>>();
            for(var server:active)futures.add(pool.submit(()->{try{
                Path plan=directory.resolve(server.name()+"-inputs-v1.txt");FleetConsolidationWorker.write(plan,uuid,label(server),groups.get(server.name()));
                String remotePlan=base(server)+"/emission-recovery-inputs-v1.txt";remote(server,"cat > "+FleetFiles.quote(remotePlan),plan,null,"plan-upload",false);
                String options=server.runtimeOptions(FleetHardware.read(directory.resolve(server.name()+"-hardware-v1.txt")));
                String command="export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; export PATH=\"$JAVA_HOME/bin:$PATH\"; cd "+FleetFiles.quote(base(server)+"/source")
                    +" && env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS TD20_JSHELL_OPTIONS="+FleetFiles.quote(options)
                    +" ./tools/td20 fleet-consolidate-worker "+FleetFiles.quote("--mode=consolidate")+" "+FleetFiles.quote("--run="+uuid)+" "+FleetFiles.quote("--server="+label(server))
                    +" "+FleetFiles.quote("--jobs="+base(server)+"/jobs")+" "+FleetFiles.quote("--output="+output(server))+" "+FleetFiles.quote("--plan="+remotePlan);
                progress.start(server.name(),"consolidation");
                remote(server,"flock -n "+FleetFiles.quote(base(server)+"/worker.lock")+" "+FleetFiles.interactive(command),null,null,"consolidation",false);
                progress.done(server.name(),"consolidation");
            }catch(Exception e){progress.failed(server.name(),e);throw new CompletionException(e);}}));
            for(var future:futures)future.get();
        }
        var stages=new TreeMap<String,Path>();var files=new TreeMap<String,List<FleetConsolidationBackup.FileEntry>>();
        for(var server:active){
            Path published=run.resolve("downloads/"+label(server));Path stage=Files.exists(published)?published:run.resolve("downloads/."+label(server)+".partial");Files.createDirectories(stage);stages.put(server.name(),stage);
            Path manifest=directory.resolve(server.name()+"-files-v1.txt");remote(server,"cat "+FleetFiles.quote(output(server)+"/files-v1.txt"),null,manifest,"files-download",false);
            var inventory=FleetConsolidationBackup.readFiles(manifest,uuid,label(server));files.put(server.name(),inventory);
            if(Files.exists(stage.resolve("files-v1.txt"))&&!Files.readAllLines(manifest).equals(Files.readAllLines(stage.resolve("files-v1.txt"))))throw new IllegalArgumentException("Changed recovery download inventory");
            Files.copy(manifest,stage.resolve("files-v1.txt"),StandardCopyOption.REPLACE_EXISTING);
            String names=String.join(" ",inventory.stream().filter(f->!f.name().endsWith(".sst")).map(f->FleetFiles.quote(f.name())).toList());
            Path archive=directory.resolve(server.name()+"-headers.tar");remote(server,"tar -cf - -C "+FleetFiles.quote(output(server))+" "+names,null,archive,"headers",true);
            FleetEmitter.runShell("tar --no-same-owner -xf "+FleetFiles.quote(archive.toString())+" -C "+FleetFiles.quote(stage.toString()),root,null,null,directory.resolve(server.name()+"-extract.log"));Files.delete(archive);
        }
        record Download(FleetServer server,FleetConsolidationBackup.FileEntry file){}
        var queues=active.stream().map(s->files.get(s.name()).stream().filter(f->f.name().endsWith(".sst")).map(f->new Download(s,f)).toList()).toList();
        var downloads=FleetConsolidationBackup.roundRobin(queues);long total=downloads.stream().mapToLong(d->d.file().bytes()).sum();
        progress.phase("consolidated SST downloads",downloads.size(),0);
        progress.transferBytes(total,()->{long bytes=0;for(var d:downloads){Path p=stages.get(d.server().name()).resolve(d.file().name());try{if(Files.isRegularFile(p))bytes+=Files.size(p);else if(Files.isRegularFile(p.resolveSibling(p.getFileName()+".partial")))bytes+=Files.size(p.resolveSibling(p.getFileName()+".partial"));}catch(java.io.IOException ignored){}}return bytes;});
        try(var pool=new FleetDownloads(24,48)){for(var d:downloads){String job=label(d.server())+"-"+d.file().name();progress.downloadQueued(d.server().name(),job);
            pool.submit(()->{Path destination=stages.get(d.server().name()).resolve(d.file().name());
                fetch(d.server(),output(d.server())+"/"+d.file().name(),destination,d.file().bytes(),job);progress.accepted(d.server().name(),job,0,0,0);});}pool.await();}
        progress.phase("validating consolidated emission downloads",active.size(),0);
        for(var server:active){Path stage=stages.get(server.name());FleetConsolidationBackup.validate(stage,uuid,label(server),groups.get(server.name()));
            Path published=run.resolve("downloads/"+label(server));if(!stage.equals(published))Files.move(stage,published,StandardCopyOption.ATOMIC_MOVE);progress.start(server.name(),"validation");progress.done(server.name(),"validation");}
        var names=new ArrayList<String>();servers.forEach(s->names.add(s.name()));active.forEach(s->names.add(label(s)));
        var expected=new HashMap<String,FleetTask>();all.forEach(t->expected.put(t.id(),t));var seen=new HashSet<String>();
        for(String name:names){Path path=run.resolve("downloads/"+name);var candidates=FleetConsolidationWorker.read(path.resolve("tasks-v1.txt"),uuid,name);
            for(var c:candidates)if(!c.task().equals(expected.get(c.task().id()))||!seen.add(c.task().id())||!c.receipt().equals(Files.readAllLines(run.resolve("results/"+c.task().id()+"/complete.txt"))))throw new IllegalStateException("Incomplete or overlapping recovered emission scope");
            FleetConsolidationBackup.validate(path,uuid,name,candidates);}
        if(seen.size()!=all.size())throw new IllegalStateException("Missing recovered emission obligations");
        var complete=new ArrayList<>(List.of("td20-emission-consolidated-backup-v1","route="+ErikssonRoute.ID,"run="+uuid,"tasks="+all.size(),"servers="+names.size()));names.forEach(n->complete.add("server="+n));
        FleetFiles.write(run.resolve("consolidation/complete-v1.txt"),complete);FleetFiles.write(directory.resolve("complete-v1.txt"),complete);progress.finished(tasks.size());
        progress.event("recovery","All "+all.size()+" emission tasks have complete local databases again; inverse reconstruction and independent proof verification remain stopped");
    }
    private void fetch(FleetServer server,String remote,Path destination,long expected,String job)throws Exception {
        if(Files.isRegularFile(destination)&&Files.size(destination)==expected)return;
        Path partial=destination.resolveSibling(destination.getFileName()+".partial");
        for(int attempt=1;attempt<=5;attempt++)try{
            long saved=Files.isRegularFile(partial)?Files.size(partial):0;if(saved>expected)throw new IllegalStateException("Oversized partial SST");
            if(saved<expected){progress.downloadStage(server.name(),job,"resuming at byte "+saved);
                FleetEmitter.runShell(FleetFiles.command(server.transferRemote("tail -c +"+(saved+1)+" -- "+FleetFiles.quote(remote),false)),root,null,partial,directory.resolve(job.replace('/','-')+".log"),null,null,true);}
            if(Files.size(partial)!=expected)throw new IllegalStateException("Truncated SST");Files.move(partial,destination,StandardCopyOption.ATOMIC_MOVE);return;
        }catch(Exception error){progress.downloadFailed(server.name(),job,error,attempt==5||error instanceof InterruptedException);if(attempt==5||error instanceof InterruptedException)throw error;Thread.sleep(2000);}
    }
}
