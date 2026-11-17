package br.unb.cic.td20.fleet;

import br.unb.cic.td20.certificate.CertificateDatabaseMerger;
import br.unb.cic.td20.joint.ErikssonInventory;
import br.unb.cic.td20.runtime.ProjectPaths;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetTransportTest {
    @Test void realArchivesAndJshellWorkersStreamProgressAndResumeAfterInterruptedDownload(@TempDir Path directory) throws Exception {
        String run = UUID.randomUUID().toString();
        Path root = ProjectPaths.root(Path.of(""));
        Path jobs = Files.createDirectory(directory.resolve("jobs")); Files.createDirectory(directory.resolve("downloads"));
        Path snapshot = directory.resolve("source.tar");
        FleetEmitter.runShell("set -o pipefail; git ls-files --cached --others --exclude-standard -z -- pom.xml emitter checker tools docs | tar --null -T - -cf "
                + FleetFiles.quote(snapshot.toString()),root,null,null,directory.resolve("snapshot.log"));
        int endpoint = ErikssonInventory.endpoints().stream().filter(ErikssonInventory.Endpoint::elementaryClosure).findFirst().orElseThrow().number();
        var tasks = List.of(new FleetTask(run,"small-a","endpoints",endpoint,endpoint,0),
                new FleetTask(run,"small-b","endpoints",endpoint,endpoint,0));
        for (var task : tasks) task.write(Files.createDirectory(jobs.resolve(task.id())));
        int cpuThreads = Math.min(2,Runtime.getRuntime().availableProcessors());
        var servers = List.of(new FleetServer("a",List.of("ssh","unused-a"),directory.resolve("server a").toString(),"cpu",
                        "-R-Xmx512m -R-XX:ActiveProcessorCount=" + cpuThreads + " -R-Dtd20.twistedBraceletWorkers=" + cpuThreads),
                new FleetServer("b",List.of("ssh","unused-b"),directory.resolve("server-b").toString(),"cpu",
                        "-R-Xmx384m -R-XX:ActiveProcessorCount=1 -R-Dtd20.twistedBraceletWorkers=1"));
        var interrupted = new AtomicInteger(); var started = new AtomicInteger();
        var failedDownload = new AtomicReference<String>();
        var recoverDownloads = new AtomicBoolean();
        var installs = new AtomicInteger(); var clones = new AtomicInteger();
        FleetEmitter.Transport local = (server,command,input,output,log,observer,secret) -> {
            if (command.equals("sudo apt install openjdk-21-jdk maven cmake -y")) { installs.incrementAndGet(); return; }
            if (command.contains("git clone")) clones.incrementAndGet();
            if (command.contains("fleet-worker")) started.incrementAndGet();
            if (output != null && command.startsWith("tar -cf -") && command.contains(" certificates")) {
                failedDownload.compareAndSet(null,command);
                if (!recoverDownloads.get() && failedDownload.get().equals(command)) {
                    interrupted.incrementAndGet(); throw new IOException("Simulated download interruption");
                }
            }
            // Exercise actual tar, Bash and JShell without requiring a live SSH daemon or CUDA.
            String java = "export JAVA_HOME=" + FleetFiles.quote(System.getProperty("java.home")) + "; export PATH=\"$JAVA_HOME/bin:$PATH\"; ";
            FleetEmitter.runShell(java + command.replace("cd /workspace", "cd " + FleetFiles.quote(root.toString()))
                    .replace("./tools/td20 build","./tools/td20 build -o"),root,input,output,log,observer,secret);
            if (output != null && command.startsWith("cat ") && server.name().equals("a")) {
                // A simulated multi-socket allocation must pass UseNUMA to the actual worker VM.
                var actual = FleetHardware.read(output);
                Files.write(output,new FleetHardware(actual.cpuThreads(),2,2,actual.totalMemoryBytes(),actual.gpus()).lines());
            }
        };
        try (var progress = new FleetProgress(directory.resolve("emission.log"))) {
            var fleet = new FleetEmitter(root,directory,servers,run,2,progress,local,root.toString(),"synthetic-test-credential");
            fleet.dispatch(tasks,jobs,snapshot);
            assertEquals(0,interrupted.get(),"Emission must not start certificate downloads");
            try (var entries = Files.list(directory.resolve("downloads"))) { assertEquals(0,entries.count()); }
            fleet.dispatch(tasks,jobs,snapshot);
            assertEquals(2,started.get(),"Remote completion intents must prevent recomputation before downloads");
            var metadata = fleet.downloadMetadata(tasks);
            for (int i = 0; i < tasks.size(); i++) {
                assertFalse(Files.exists(metadata.get(i).resolve("certificates")),"Residual preparation must not download certificates");
                assertEquals(0,tasks.get(i).receiptCounts(metadata.get(i)).endpointResiduals());
                final int index = i;
                assertThrows(IllegalArgumentException.class,() -> tasks.get(index).residualCount(metadata.get(index)));
            }
            Path prepared = directory.resolve("reconstruction");
            assertTrue(ReconstructionBatches.prepare(run,prepared,metadata,2).isEmpty());
            assertTrue(ReconstructionBatches.read(run,prepared).isEmpty());
            assertThrows(IllegalStateException.class,() -> fleet.downloadCertificates(tasks));
            long completed;
            try (var entries = Files.list(directory.resolve("downloads"))) { completed = entries.filter(path -> !path.getFileName().toString().startsWith(".")).count(); }
            assertEquals(1,completed,"A failed background transfer must not be accepted");
            assertEquals(3,interrupted.get());
            assertEquals(2,started.get(),"Download failure must not rerun completed computation");
            recoverDownloads.set(true);
            fleet.dispatch(tasks,jobs,snapshot);
            fleet.downloadCertificates(tasks);
            fleet.dispatch(tasks,jobs,snapshot);
            assertEquals(2,started.get(),"Resume must retry the persisted transfer without any computation");
            assertEquals(2,installs.get()); assertEquals(2,clones.get());
            var downloads = tasks.stream().map(task -> directory.resolve("downloads").resolve(task.id())).toList();
            for (int i = 0; i < tasks.size(); i++) assertEquals(0,tasks.get(i).residualCount(downloads.get(i)));
            var result = CertificateDatabaseMerger.merge(directory.resolve("merged"),downloads.stream().map(path -> path.resolve("certificates")).toList());
            assertEquals(0,result.uniqueWitnesses());
            Files.delete(downloads.get(0).resolve("certificates/CURRENT"));
            assertThrows(IllegalArgumentException.class,() -> fleet.downloadCertificates(tasks));
        }
        String consolidated = Files.readString(directory.resolve("emission.log"));
        String raw;
        try (var files = Files.list(directory)) {
            var output = new StringBuilder();
            for (var file : files.filter(path -> path.getFileName().toString().endsWith(".log")
                    && !path.getFileName().toString().equals("emission.log")).toList()) output.append(Files.readString(file));
            raw = output.toString();
        }
        assertTrue(raw.contains("Requested emission scope completed:"));
        assertTrue(consolidated.contains("Simulated download interruption"));
        assertTrue(consolidated.contains("reused=2"));
        assertTrue(consolidated.contains("jobs=2/2 (100.00%)"));
        assertFalse(consolidated.contains("synthetic-test-credential"));
        assertTrue(raw.contains("maximumHeapBytes="));
        assertTrue(raw.contains("Worker execution: cpuThreads="));
        assertTrue(raw.lines().anyMatch(line -> line.contains("Worker execution:") && line.contains("-XX:+UseNUMA")));
        for (var server : servers) assertTrue(Files.exists(directory.resolve(server.name() + "-hardware-v1.txt")));
        try (var files = Files.list(directory)) {
            assertEquals(2,files.filter(path -> path.getFileName().toString().endsWith("-execution-v1.txt")).count());
        }
    }
}
