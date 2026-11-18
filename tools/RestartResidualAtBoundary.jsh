import java.nio.file.*;
import java.nio.channels.*;
import java.time.*;
import java.io.*;
import java.util.*;
import br.unb.cic.td20.avoidance.ErikssonCheckpoint;

// Operational migration of a completed legacy generation boundary. No proof checker uses this receipt.
class RestartResidualAtBoundary {
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static Path path(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank())throw new IllegalArgumentException("Missing "+name);
        return Path.of(value).toAbsolutePath().normalize();
    }
    static void event(String message) { System.out.println(Instant.now()+" "+message); }
    static boolean alive(ProcessHandle process) {
        if(!process.isAlive())return false;
        // A terminated Linux child may await parent reaping while all its files and locks are already released.
        try {
            String stat=Files.readString(Path.of("/proc",Long.toString(process.pid()),"stat"));
            char state=stat.charAt(stat.lastIndexOf(')')+2);
            return state!='Z' && state!='X';
        } catch(IOException error) {return process.isAlive();}
    }
    static void writeStatus(Path run,String status,long previousPid,long nextPid,Path source) throws Exception {
        Path temporary=Files.createTempFile(run,".boundary-restart-",".tmp");
        try {
            Files.write(temporary,List.of("td20-local-residual-boundary-restart-v1","status="+status,
                    "previousLauncher="+previousPid,"nextLauncher="+nextPid,"sourceWork="+source,
                    "afterOneCount=pending-until-mandatory-expansion","proofInput=false","updated="+Instant.now()));
            Files.move(temporary,run.resolve("boundary-restart-v1.txt"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
    static void run() throws Exception {
        Path root=path("TD20_RESTART_PROJECT"),run=path("TD20_RESTART_RUN"),source=path("TD20_RESTART_SOURCE_WORK");
        Path seedWork=path("TD20_RESTART_SEED_WORK"),log=path("TD20_RESTART_SOURCE_LOG");
        long pid=Long.parseLong(Files.readString(run.resolve("launcher.pid")).strip());
        var seed=ErikssonCheckpoint.read(seedWork);
        if(seed.stage()!=ErikssonCheckpoint.Stage.AFTER_TWO || seed.mode()!=ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION)
            throw new IllegalArgumentException("Restart source must be the complete residual input");
        if(!source.startsWith(run.resolve("work")) || !Files.isRegularFile(source.resolve("after-one/CURRENT")))
            throw new IllegalArgumentException("Missing generated intermediate database");
        var launcher=ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow();
        if(!launcher.info().commandLine().orElse("").contains("run-local-emitter.sh"))
            throw new IllegalStateException("Recorded PID does not identify the local emitter launcher");
        try(var lockChannel=FileChannel.open(run.resolve("boundary-restart.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=lockChannel.tryLock();var input=Files.newBufferedReader(log)) {
            if(lock==null)throw new IllegalStateException("A boundary restart is already armed");
            writeStatus(run,"waiting-for-completed-generation",pid,-1,source);
            event("Waiting for all residual expansions and writer drains; current computation is retained");
            String expansion="Inverse expansion after-two residuals: processed="+seed.afterTwo()+"/"+seed.afterTwo();
            String scanned="State inventory after-two scan: processed="+seed.afterTwo()+"/"+seed.afterTwo();
            boolean expanded=false,read=false,ready=false;
            while(!ready) {
                String line=input.readLine();
                if(line!=null) {
                    expanded|=line.contains(expansion);read|=line.contains(scanned);
                    ready=expanded && read && line.contains("State inventory after-one unique-class counting:");
                } else {
                    if(!alive(launcher))throw new IllegalStateException("Emitter exited before the complete generation boundary");
                    Thread.sleep(1000);
                }
            }
            // The old seal() reports this phase only after every generator and SST writer has joined.
            event("Intermediate generation and persistence completed; stopping the old counting scan");
            writeStatus(run,"stopping-old-counting-scan",pid,-1,source);
            var processes=new ArrayList<ProcessHandle>();launcher.descendants().forEach(processes::add);processes.add(launcher);
            processes.forEach(ProcessHandle::destroy);
            long deadline=System.nanoTime()+60_000_000_000L;
            while(processes.stream().anyMatch(RestartResidualAtBoundary::alive) && System.nanoTime()<deadline)Thread.sleep(250);
            processes.stream().filter(RestartResidualAtBoundary::alive).forEach(ProcessHandle::destroyForcibly);
            deadline=System.nanoTime()+10_000_000_000L;
            while(processes.stream().anyMatch(RestartResidualAtBoundary::alive) && System.nanoTime()<deadline)Thread.sleep(250);
            if(processes.stream().anyMatch(RestartResidualAtBoundary::alive))throw new IllegalStateException("Old emitter processes have not exited");
            Path checkpoint=source.resolve("eriksson-reconstruction-v1.txt");
            if(Files.exists(checkpoint)) {
                var existing=ErikssonCheckpoint.read(source);
                if(existing.stage()!=ErikssonCheckpoint.Stage.AFTER_ONE || existing.mode()!=seed.mode()
                        || existing.endpoints()!=seed.endpoints() || existing.afterTwo()!=seed.afterTwo())
                    throw new IllegalStateException("An incompatible checkpoint already exists at the completed boundary");
            } else new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,seed.endpoints(),seed.afterTwo(),-1,-1,seed.mode()).write(source);
            var builder=new ProcessBuilder("bash","-ic","exec tools/run-local-emitter.sh --resume-work="+quote(source.toString()))
                    .directory(root.toFile()).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(run.resolve("console.log").toFile()));
            builder.environment().put("TD20_RUN_ROOT",run.toString());
            builder.environment().put("TD20_CERTIFICATE_DB",root.resolve("certificates").toString());
            builder.environment().put("TMUX_STARTED","1");
            for(String variable:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","TD20_JSHELL_OPTIONS"))builder.environment().remove(variable);
            var next=builder.start();Files.writeString(run.resolve("launcher.pid"),next.pid()+System.lineSeparator());
            writeStatus(run,"starting-updated-emitter",pid,next.pid(),source);
            deadline=System.nanoTime()+60_000_000_000L;
            boolean running=false;
            Path latest=run.resolve("logs/emitter-latest.log");
            while(!running && next.isAlive() && System.nanoTime()<deadline) {
                if(Files.exists(latest) && !latest.toRealPath().equals(log.toRealPath()))
                    running=Files.readString(latest).contains("Inverse expansion after-one inverse states:");
                if(!running)Thread.sleep(1000);
            }
            if(!running) {
                writeStatus(run,"restart-failed",pid,next.pid(),source);
                throw new IllegalStateException("Updated emitter did not enter final inverse expansion; inspect console.log");
            }
            event("Restarted intermediate expansion without a separate counting pass; launcher PID="+next.pid());
            writeStatus(run,"restarted",pid,next.pid(),source);
        }
    }
}
int status=1;
try {RestartResidualAtBoundary.run();status=0;} catch(Throwable error) {error.printStackTrace();}
/exit status
