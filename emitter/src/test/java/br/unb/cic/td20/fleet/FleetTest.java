package br.unb.cic.td20.fleet;

import br.unb.cic.td20.avoidance.*;
import br.unb.cic.td20.certificate.*;
import br.unb.cic.td20.joint.*;
import br.unb.cic.td20.model.*;
import br.unb.cic.td20.permutation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetTest {
    private static final String RUN = "0f97434b-941b-4424-990e-eb6cb073c9d8";
    @Test void idleWorkerClaimsEveryRemainingQueuedTask(@TempDir Path directory) throws Exception {
        var tasks = new java.util.concurrent.ConcurrentLinkedQueue<FleetTask>();
        for (int i = 1; i <= 10; i++) tasks.add(new FleetTask(RUN,"root-" + i,"roots",i,i,0));
        var remaining = new java.util.concurrent.atomic.AtomicInteger(10);
        var slowStarted = new CountDownLatch(1); var releaseSlow = new CountDownLatch(1);
        var fastFinished = new CountDownLatch(9); var completed = ConcurrentHashMap.<String>newKeySet();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var slow = workers.submit(() -> { try { FleetEmitter.drainTasks(tasks,remaining,task -> {
                slowStarted.countDown(); assertTrue(releaseSlow.await(10,TimeUnit.SECONDS)); assertTrue(completed.add(task.id()));
            },error -> fail(error)); } catch (Exception error) { throw new CompletionException(error); } });
            assertTrue(slowStarted.await(5,TimeUnit.SECONDS));
            var fast = workers.submit(() -> { try { FleetEmitter.drainTasks(tasks,remaining,task -> { assertTrue(completed.add(task.id())); fastFinished.countDown(); },error -> fail(error)); }
                catch (Exception error) { throw new CompletionException(error); } });
            try { assertTrue(fastFinished.await(5,TimeUnit.SECONDS)); assertEquals(9,completed.size()); assertTrue(tasks.isEmpty()); }
            finally { releaseSlow.countDown(); }
            slow.get(10,TimeUnit.SECONDS); fast.get(10,TimeUnit.SECONDS); assertEquals(10,completed.size());
        }
        assertThrows(IllegalArgumentException.class,() -> new FleetTask(RUN,"bad","avoidance",1,1,0));
    }
    @Test void sshConnectionsPreserveQuotedArgumentsAndRejectCommands(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("servers.tsv");
        Files.writeString(file,"a\tssh -p 123 -i '/some/key path' -o 'ConnectTimeout=5' root@example\t/workspace/work\t120\t-R-Xmx64g -R-XX:ActiveProcessorCount=128 -R-Dtd20.twistedBraceletWorkers=160 -R-Dtd20.gpuDevices=0 -R-Dtd20.gpuSlots=32 -R-Dtd20.gpuBudgetMB=896\n");
        var server = FleetServer.read(file).getFirst();
        assertTrue(server.ssh().contains("/some/key path")); assertTrue(server.runtimeOptions().contains("gpuSlots=32"));
        assertEquals(FleetFiles.interactive("echo 'literal'"),server.remote("echo 'literal'").getLast());
        var transfer = server.transferRemote("tar -cf - result");
        assertTrue(transfer.contains("-C")); assertTrue(transfer.contains("ControlMaster=no")); assertTrue(transfer.contains("ControlPath=none"));
        for (String connection : List.of("ssh root@example echo hello","ssh -N root@example","ssh -p root@example","scp root@example")) {
            Files.writeString(file,"a\t" + connection + "\t/workspace/work\t120\t-R-Xmx64g\n");
            assertThrows(IllegalArgumentException.class,() -> FleetServer.read(file));
        }
    }
    @Test void emissionReceiptsCannotOmitAnyPartitionOrAnnotation(@TempDir Path directory) throws Exception {
        Path plan = Files.createDirectory(directory.resolve("plan"));
        Path shared = directory.resolve("empty-database"); try (var writer = new AsyncCertificateWriter(shared,60)) { }
        var tasks = new ArrayList<FleetTask>();
        for (int i = 1; i <= 83; i++) tasks.add(new FleetTask(RUN,"root-" + i,"roots",i,i,0));
        for (int i = 1; i <= 143; i++) tasks.add(new FleetTask(RUN,"endpoint-" + i,"endpoints",i,i,0));
        var lines = new ArrayList<>(List.of("td20-fleet-emission-v1","route=" + ErikssonRoute.ID,"run=" + RUN));
        for (int i = 1; i <= 143; i++) lines.add("endpoint-" + i + "=0");
        lines.add("tasks=" + tasks.size()); var jobs = new ArrayList<Path>();
        for (var task : tasks) {
            Path job = Files.createDirectory(plan.resolve(task.id())); task.write(job); task.complete(job,0,0,0);
            Files.createSymbolicLink(job.resolve("certificates"),shared); jobs.add(job); lines.add("task=" + task.id());
        }
        FleetFiles.write(plan.resolve("emission.txt"),lines);
        assertEquals(tasks,EmissionBatches.read(RUN,plan)); EmissionBatches.validateCompleted(RUN,plan,jobs);
        assertThrows(IllegalArgumentException.class,() -> EmissionBatches.validateCompleted(RUN,plan,jobs.subList(1,jobs.size())));
        var endpoint = tasks.getLast(); Path job = jobs.getLast(); Files.delete(job.resolve("complete.txt")); endpoint.complete(job,0,0,1);
        assertThrows(IllegalStateException.class,() -> EmissionBatches.validateCompleted(RUN,plan,jobs));
    }
    @Test void sortedBatchesRecombineWithoutOmissionsAndRejectMissingData(@TempDir Path directory) throws Exception {
        try (var states = new DiskStateSet(directory.resolve("source"))) {
            var random = new Random(42); var keys = new HashSet<String>();
            while (keys.size() < 5) {
                var pair = randomPair(ErikssonRoute.ROOT_SYMBOLS - 3,random).canonicalRepresentative();
                if (pair.getOmega().stream().anyMatch(cycle -> cycle.size() == 1)) continue;
                keys.add(HexFormat.of().formatHex(CertificateCodec.keyFor(pair))); states.add(pair,ErikssonRoute.ROOT_BUDGET - 2);
            }
            assertEquals(5,states.seal());
            Path prepared = Files.createDirectory(directory.resolve("prepared"));
            var tasks = ReconstructionBatches.split(RUN,prepared,states,2,2);
            assertEquals(List.of(2L,2L,1L),tasks.stream().map(FleetTask::inputCount).toList());
            var actual = new HashSet<String>();
            for (var task : tasks) {
                Path input = prepared.resolve(task.id()).resolve("input");
                assertEquals(task.inputCount(),ErikssonCheckpoint.read(input).afterTwo());
                try (var batch = DiskStateSet.openSealed(input.resolve("after-two"),task.inputCount())) {
                    batch.forEach(state -> assertTrue(actual.add(HexFormat.of().formatHex(CertificateCodec.keyFor(state.pair())))));
                }
                assertThrows(IllegalStateException.class,() -> DiskStateSet.openSealed(input.resolve("after-two"),task.inputCount() + 1));
            }
            assertEquals(keys,actual);
            var lines = new ArrayList<>(List.of("td20-fleet-batches-v1","route=" + ErikssonRoute.ID,"run=" + RUN,
                    "endpoints=2","afterTwo=5","batches=3"));
            tasks.forEach(task -> lines.add(task.id() + "=" + task.inputCount()));
            FleetFiles.write(prepared.resolve("batches.txt"),lines);
            assertEquals(tasks,ReconstructionBatches.read(RUN,prepared));
            Files.delete(prepared.resolve(tasks.getLast().id()).resolve("task.txt"));
            assertThrows(Exception.class,() -> ReconstructionBatches.read(RUN,prepared));
        }
    }
    @Test void receiptsRejectWrongScopeMissingDatabaseAndTruncation(@TempDir Path directory) throws Exception {
        var task = new FleetTask(RUN,"endpoint-001","endpoints",1,1,0); task.write(directory); task.complete(directory,0,0);
        assertThrows(IllegalArgumentException.class,() -> task.residualCount(directory));
        assertEquals(0,task.receiptCounts(directory).endpointResiduals(),"A metadata receipt does not claim certificate coverage");
        try (var writer = new AsyncCertificateWriter(directory.resolve("certificates"),60)) { }
        assertEquals(0,task.residualCount(directory));
        assertThrows(IllegalArgumentException.class,() -> new FleetTask(RUN,"endpoint-002","endpoints",2,2,0).residualCount(directory));
        Files.writeString(directory.resolve("complete.txt"),"td20-fleet-complete-v1\n");
        assertThrows(IllegalArgumentException.class,() -> task.residualCount(directory));
    }
    @Test void smallCountedScansKeepMoreThanOneCpuWorkerBusy(@TempDir Path directory) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        try (var states = new DiskStateSet(directory.resolve("states")); var executor = Executors.newSingleThreadExecutor()) {
            for (int budget = 1; budget <= 3; budget++) states.add(pair,budget);
            assertEquals(3,states.seal());
            var scan = executor.submit(() -> states.forEachConcurrently(2,state -> {
                entered.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Blocked worker"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            try { assertTrue(entered.await(5,TimeUnit.SECONDS),"Small checkpoint scans must expose independent work to multiple CPU workers"); }
            finally { release.countDown(); }
            scan.get(10,TimeUnit.SECONDS);
        }
    }
    @Test void consolidatedLogStreamsLiveAndKeepsResumeHistory(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("emission.log");
        try (var progress = new FleetProgress(log); var writers = Executors.newFixedThreadPool(4)) {
            progress.phase("local preparation",0,0);
            progress.phase("emission",75,2); progress.start("a","endpoint-003");
            var work = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                int id = i; work.add(writers.submit(() -> { for (int j = 0; j < 20; j++) progress.remote("a","endpoint-003","processed=" + id + "-" + j); }));
            }
            for (var future : work) future.get();
            progress.report();
            assertTrue(Files.readString(log).contains("latest=processed="),"The status snapshot must include current class progress");
            progress.done("a","endpoint-003");
            progress.start("b","endpoint-004"); progress.failed("b",new IllegalStateException("lost connection"));
        }
        try (var progress = new FleetProgress(log)) { progress.phase("emission",75,3); }
        var lines = Files.readAllLines(log);
        assertEquals(0,lines.stream().filter(line -> line.contains("[a/endpoint-003]")).count(),"Routine worker output belongs in the per-task log");
        assertTrue(Files.readString(directory.resolve("status.txt")).startsWith("td20-fleet-status-v1\n"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("jobs=3/75 (4.00%) reused=2")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("failed=1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("reused=3")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("phase=local preparation jobs=0/0 (n/a)")));
    }
    @Test void transientGitCredentialIsRedactedBeforeEitherLogSeesIt(@TempDir Path directory) throws Exception {
        String token = "synthetic-test-credential";
        String encoded = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] input = ("Authorization: Basic " + encoded + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var observed = new java.util.concurrent.CopyOnWriteArrayList<String>();
        Path log = directory.resolve("secret.log");
        FleetEmitter.runShell("IFS= read -r header; printf '%s\\n' \"$header\"",directory,null,null,log,observed::add,input);
        String text = Files.readString(log);
        assertTrue(text.contains("[redacted]")); assertFalse(text.contains(token)); assertFalse(text.contains(encoded));
        assertTrue(observed.stream().noneMatch(line -> line.contains(token) || line.contains(encoded)));
    }
    @Test void startupMessagesCannotCorruptBinaryTransfers(@TempDir Path directory) throws Exception {
        Path home = Files.createDirectory(directory.resolve("home"));
        Files.writeString(home.resolve(".bashrc"),"printf 'startup-banner\\n'\n");
        Path output = directory.resolve("binary"), log = directory.resolve("command.log");
        FleetEmitter.runShell("env HOME=" + FleetFiles.quote(home.toString()) + " " + FleetFiles.interactive("printf payload"),directory,null,output,log);
        assertEquals("payload",Files.readString(output)); assertTrue(Files.readString(log).contains("startup-banner"));
    }
    static CyclicTargetPair randomPair(int n, Random random) {
        int[] beta = java.util.stream.IntStream.range(0,n).toArray();
        var order = new ArrayList<Integer>(); for (int i = 0; i < n; i++) order.add(i); Collections.shuffle(order,random);
        var target = Cycle.of(order.stream().mapToInt(Integer::intValue).toArray());
        return CyclicTargetPair.of(PermutationGroups.computeProduct(target,Cycle.of(beta).getInverse()),Cycle.of(beta));
    }
}
