package br.unb.cic.td20.fleet;

import br.unb.cic.td20.certificate.CertificateDatabaseMerger;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.runtime.ProjectPaths;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** SSH orchestration only. Each remote JVM uses every visible CPU and its configured GPU slots. */
public final class FleetEmitter {
    private final Path root, directory;
    private final List<FleetServer> servers;
    private final String run;
    private final int batchSize;
    private final int endpointTasks;
    private final String repository, token;
    private final Map<String,String> executionOptions = new ConcurrentHashMap<>();
    private final Map<String,FleetHardware> hardware = new ConcurrentHashMap<>();
    private final FleetProgress progress;
    @FunctionalInterface interface Transport {
        void run(FleetServer server, String command, Path input, Path output, Path log,
                 java.util.function.Consumer<String> observer, byte[] secretInput) throws Exception;
    }
    private final Transport transport;
    FleetEmitter(Path root, Path directory, List<FleetServer> servers, String run, int batchSize,
                 FleetProgress progress, Transport transport, String repository, String token) {
        this(root,directory,servers,run,batchSize,progress,transport,repository,token,512);
    }
    FleetEmitter(Path root, Path directory, List<FleetServer> servers, String run, int batchSize,
                 FleetProgress progress, Transport transport, String repository, String token, int endpointTasks) {
        this.root = root; this.directory = directory; this.servers = servers; this.run = run; this.batchSize = batchSize;
        this.progress = progress; this.transport = transport;
        this.repository = repository; this.token = token;
        this.endpointTasks = endpointTasks;
    }
    public static void main(String[] args) throws Exception {
        Path serversFile = null, directory = null; int batchSize = 64, endpointTasks = 512; boolean planOnly = false, inspectOnly = false;
        String repository = "https://github.com/luizaugustogarcia/td20.git";
        for (String arg : args) {
            if (arg.equals("--help")) {
                System.out.println("fleet --servers=SERVERS.tsv --run=LOCAL_DIRECTORY [--reconstruction-batch-size=64] [--endpoint-tasks=512] [--repository=HTTPS_GITHUB_URL] [--plan-only|--inspect-only]\n"
                        + "Set TD20_GITHUB_TOKEN for authenticated server preparation. Repeat the identical command to resume.\n"
                        + "Output: LOCAL_DIRECTORY/certificates. See docs/DISTRIBUTED.md."); return;
            } else if (arg.startsWith("--servers=")) serversFile = Path.of(arg.substring(10));
            else if (arg.startsWith("--run=")) directory = Path.of(arg.substring(6));
            else if (arg.startsWith("--reconstruction-batch-size=")) batchSize = Integer.parseInt(arg.substring(28));
            else if (arg.startsWith("--endpoint-tasks=")) endpointTasks = Integer.parseInt(arg.substring(17));
            else if (arg.startsWith("--repository=")) repository = arg.substring(13);
            else if (arg.equals("--plan-only")) planOnly = true;
            else if (arg.equals("--inspect-only")) inspectOnly = true;
            else throw new IllegalArgumentException("Unknown fleet option: " + arg);
        }
        if (serversFile == null || directory == null || batchSize < 1 || endpointTasks < 1) throw new IllegalArgumentException("Supply --servers, --run and positive batch sizes");
        if (planOnly && inspectOnly) throw new IllegalArgumentException("Choose --plan-only or --inspect-only");
        var servers = FleetServer.read(serversFile,!inspectOnly);
        var uri = java.net.URI.create(repository);
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1
                || !uri.getPath().matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Supply a credential-free HTTPS GitHub repository URL");
        if (planOnly) {
            System.out.printf("Fleet: %d servers; 83 root partitions; 143 endpoint types; target %d prefix tasks per nonempty type; after-two batches of at most %d classes.%n", servers.size(),endpointTasks,batchSize);
            for (var server : servers) System.out.printf("%s: architecture=%s options=%s%n",server.name(),server.architecture(),server.runtimeOptions());
            System.out.println("Preparation: sudo apt install openjdk-21-jdk maven cmake -y; clone under /workspace; mvn compile.");
            return;
        }
        Path root = ProjectPaths.root(Path.of("")).toRealPath();
        directory = directory.toAbsolutePath().normalize(); Files.createDirectories(directory);
        try (var lockChannel = FileChannel.open(directory.resolve("fleet.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lockChannel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another coordinator owns this run directory");
            if (inspectOnly) {
                if (Files.exists(directory.resolve("plan.txt"))) throw new IllegalArgumentException("Use a separate directory for hardware inspection");
                try (var progress = new FleetProgress(directory.resolve("emission.log"))) {
                    new FleetEmitter(root,directory,servers,UUID.randomUUID().toString(),batchSize,progress,
                            (server,command,input,output,log,observer,secret) -> runShell(FleetFiles.command(server.remote(command)),root,input,output,log,observer,secret),
                            repository,System.getenv("TD20_GITHUB_TOKEN"),endpointTasks).inspect();
                }
                return;
            }
            var configuration = new ArrayList<>(List.of("td20-fleet-plan-v2", "route=" + ErikssonRoute.ID,
                    "batchSize=" + batchSize,"endpointTasks=" + endpointTasks, "repository=" + repository));
            for (var server : servers) configuration.add("server=" + server.name() + "\t" + FleetFiles.command(server.ssh())
                    + "\t" + server.directory() + "\t" + server.architecture());
            Path plan = directory.resolve("plan.txt"); String run;
            if (Files.exists(plan)) {
                var lines = Files.readAllLines(plan);
                if (lines.size() != configuration.size() + 1 || !lines.subList(0,configuration.size()).equals(configuration))
                    throw new IllegalArgumentException("Resume configuration differs from the persisted fleet plan");
                run = FleetFiles.field(lines,configuration.size(),"run"); UUID.fromString(run);
            } else { run = UUID.randomUUID().toString(); configuration.add("run=" + run); FleetFiles.write(plan,configuration); }
            var profiles = new ArrayList<>(List.of("td20-fleet-profiles-v1","run=" + run));
            for (var server : servers) profiles.add("server=" + server.name() + "\t" + server.runtimeOptions());
            FleetFiles.write(directory.resolve("profiles-" + UUID.randomUUID() + "-v1.txt"),profiles);
            try (var progress = new FleetProgress(directory.resolve("emission.log"))) {
                progress.event("coordinator","Run " + run + "; consolidated log: " + directory.resolve("emission.log"));
                var emitter = new FleetEmitter(root,directory,servers,run,batchSize,progress,
                        (server,command,input,output,log,observer,secret) -> runShell(FleetFiles.command(output != null && command.startsWith("tar -cf -")
                                ? server.transferRemote(command) : server.remote(command)),root,input,output,log,observer,secret),
                        repository,System.getenv("TD20_GITHUB_TOKEN"),endpointTasks);
                try { emitter.execute(); }
                catch (Exception | Error error) { progress.event("coordinator","FAILED: " + error); throw error; }
            }
        }
    }

    private Path snapshot() throws Exception {
        Path snapshot = directory.resolve("source.tar");
        if (!Files.exists(snapshot)) {
            Path temporary = directory.resolve("source-" + UUID.randomUUID() + ".partial");
            // Include current tracked edits and new source files, excluding ignored run/build artifacts.
            String exclude = directory.startsWith(root) ? " " + FleetFiles.quote(":(exclude)" + root.relativize(directory)) : "";
            runShell("set -o pipefail; git ls-files --cached --others --exclude-standard -z -- AGENTS.md pom.xml README.md emitter checker tools docs" + exclude + " | tar --null -T - -cf "
                    + FleetFiles.quote(temporary.toString()), root, null, null, directory.resolve("snapshot.log"));
            Files.move(temporary,snapshot,StandardCopyOption.ATOMIC_MOVE);
        }
        if (token == null || token.isBlank()) {
            Path bundle = directory.resolve("source.bundle");
            if (!Files.exists(bundle)) {
                Path temporary = directory.resolve("source-" + UUID.randomUUID() + ".bundle.partial");
                runShell("git bundle create " + FleetFiles.quote(temporary.toString()) + " HEAD",root,null,null,directory.resolve("snapshot.log"));
                Files.move(temporary,bundle,StandardCopyOption.ATOMIC_MOVE);
            }
        }
        return snapshot;
    }
    private void inspect() throws Exception {
        Path snapshot = snapshot();
        progress.phase("hardware inspection",servers.size(),0);
        try (var workers = Executors.newFixedThreadPool(servers.size())) {
            var futures = new ArrayList<Future<?>>();
            for (var server : servers) futures.add(workers.submit(() -> {
                progress.start(server.name(),"inspection");
                try { prepare(server,snapshot,false); progress.done(server.name(),"inspection"); }
                catch (Exception error) { progress.failed(server.name(),error); throw new CompletionException(error); }
            }));
            for (var future : futures) future.get();
        }
        progress.finished(servers.size());
        progress.event("coordinator","Hardware inspection complete. Tune each server's profile from its hardware report and measured utilization before emission.");
    }
    private void execute() throws Exception {
        Path snapshot = snapshot();
        Files.createDirectories(directory.resolve("jobs")); Files.createDirectories(directory.resolve("downloads"));
        Path emissionPlan = directory.resolve("emission");
        if (!Files.exists(emissionPlan)) {
            progress.phase("planning fine-grained endpoint generation",0,0);
            Path temporary = directory.resolve("emission-" + UUID.randomUUID() + ".partial");
            EmissionBatches.prepare(run,temporary,endpointTasks,message -> progress.event("planning",message));
            Files.move(temporary,emissionPlan,StandardCopyOption.ATOMIC_MOVE);
        }
        var emission = EmissionBatches.read(run,emissionPlan);
        dispatch(emission,emissionPlan,snapshot);
        var completed = downloadMetadata(emission);
        EmissionBatches.validateCompleted(run,emissionPlan,completed);
        progress.event("coordinator","All 143 endpoint annotation counts match their complete generation task unions.");
        Path prepared = directory.resolve("reconstruction");
        if (!Files.exists(prepared)) {
            Path temporary = directory.resolve("reconstruction-" + UUID.randomUUID() + ".partial");
            progress.phase("local first inverse wave and batch preparation",0,0);
            progress.event("coordinator","Generating and splitting the first inverse wave locally using all available CPU cores.");
            ReconstructionBatches.prepare(run,temporary,completed,batchSize,message -> progress.event("preparation",message));
            Files.move(temporary,prepared,StandardCopyOption.ATOMIC_MOVE);
        }
        var reconstruction = ReconstructionBatches.read(run,prepared);
        progress.event("coordinator","Reconstruction tasks: " + reconstruction.size());
        dispatch(reconstruction,prepared,snapshot);
        var allTasks = new ArrayList<>(emission); allTasks.addAll(reconstruction);
        var all = downloadCertificates(allTasks);
        // Recheck every expected receipt before publishing the merged database.
        var inputs = all.stream().map(path -> path.resolve("certificates")).toList();
        Path output = directory.resolve("certificates");
        Path report = directory.resolve("complete.txt");
        if (Files.exists(output)) {
            var expected = List.of("td20-fleet-merged-v1", "run=" + run, "tasks=" + all.size());
            if (!Files.readAllLines(output.resolve("fleet-complete.txt")).equals(expected)
                    || Files.exists(report) && !Files.readAllLines(report).equals(expected) || !Files.isRegularFile(output.resolve("CURRENT"))
                    || !Files.isRegularFile(output.resolve("merge-v1.txt"))) throw new IllegalStateException("Incomplete published fleet merge");
        } else {
            Path temporary = directory.resolve("merged-" + UUID.randomUUID() + ".partial");
            progress.phase("local certificate merge",0,0);
            progress.event("coordinator","Replaying and merging " + inputs.size() + " downloaded certificate databases.");
            var result = CertificateDatabaseMerger.merge(temporary,inputs,message -> progress.event("merge",message));
            // The receipt and merge record also travel with the atomic database publication.
            FleetFiles.write(temporary.resolve("fleet-complete.txt"),List.of("td20-fleet-merged-v1", "run=" + run, "tasks=" + all.size()));
            Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE);
            progress.event("coordinator","Merged certificates: " + result);
        }
        if (!Files.exists(report)) FleetFiles.write(report,Files.readAllLines(output.resolve("fleet-complete.txt")));
        progress.finished(all.size());
        progress.event("coordinator","Fleet emission completed: " + output + ". Run the independent full checker against this database.");
    }

    void dispatch(List<FleetTask> tasks, Path jobs, Path snapshot) throws Exception {
        var pending = new ConcurrentLinkedQueue<FleetTask>();
        String phase = tasks.isEmpty() || !tasks.getFirst().phase().equals("avoidance") ? "emission" : "reconstruction";
        Path transfersDirectory = directory.resolve("transfers").resolve(phase);
        Files.createDirectories(transfersDirectory);
        var byTask = new HashMap<String,FleetTask>();
        for (var task : tasks) byTask.put(task.id(),task);
        var byServer = new HashMap<String,FleetServer>();
        for (var server : servers) byServer.put(server.name(),server);
        var recovered = new TreeMap<String,FleetTransfer>();
        try (var records = Files.list(transfersDirectory)) {
            for (var file : records.sorted().toList()) {
                String filename = file.getFileName().toString();
                if (filename.startsWith(".fleet-") && filename.endsWith(".tmp")) {
                    Files.delete(file); continue; // Unpublished intent: its task remains an obligation.
                }
                var transfer = FleetTransfer.read(file);
                if (!transfer.run().equals(run) || !byTask.containsKey(transfer.task()) || !byServer.containsKey(transfer.server())
                        || !file.getFileName().toString().equals(transfer.task() + ".txt"))
                    throw new IllegalArgumentException("Transfer intent differs from this run's task scope");
                recovered.put(transfer.task(),transfer);
            }
        }
        int reused = 0;
        for (var task : tasks) {
            Path output = directory.resolve("downloads").resolve(task.id());
            if (Files.exists(output)) {
                if (!FleetTask.read(output).equals(task)) throw new IllegalArgumentException("Downloaded task scope mismatch");
                task.residualCount(output);
                reused++;
                recovered.remove(task.id()); Files.deleteIfExists(transfersDirectory.resolve(task.id() + ".txt"));
            } else if (recovered.containsKey(task.id())) reused++;
            else pending.add(task);
        }
        progress.phase(phase,tasks.size(),reused);
        progress.event("coordinator","Computations are independent across types; closed results remain remote until their required transfer phase.");
        if (pending.isEmpty()) return;
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var remaining = new java.util.concurrent.atomic.AtomicInteger(pending.size());
        try (var workers = Executors.newFixedThreadPool(Math.min(servers.size(),pending.size()))) {
                var futures = new ArrayList<Future<?>>();
                for (var server : servers.subList(0, Math.min(servers.size(), pending.size()))) futures.add(workers.submit(() -> {
                    try {
                        if (pending.isEmpty()) return;
                        String source;
                        try { source = prepare(server,snapshot,true); }
                        catch (Exception first) {
                            progress.event(server.name(),"Preparation interrupted; retrying: " + first);
                            source = prepare(server,snapshot,true);
                        }
                        progress.event(server.name(),"ready for jobs");
                        String preparedSource = source;
                        drainTasks(pending,remaining,task -> executeRemote(server,preparedSource,task,jobs.resolve(task.id()),transfersDirectory),
                                error -> progress.failed(server.name(),error));
                    } catch (Throwable error) { progress.failed(server.name(),error); failures.add(error); throw new CompletionException(error); }
                }));
                for (var future : futures) try { future.get(); }
                catch (ExecutionException error) { failures.add(error.getCause()); }
        }
        if (remaining.get() != 0 || !pending.isEmpty()) throw new IllegalStateException("No server could complete all pending tasks; completed downloads are retained. Repeat the command to resume.",failures.peek());
    }
    List<Path> downloadMetadata(List<FleetTask> tasks) throws Exception { return transferTasks(tasks,false); }
    List<Path> downloadCertificates(List<FleetTask> tasks) throws Exception { return transferTasks(tasks,true); }
    private Path transferIntent(FleetTask task) {
        return directory.resolve("transfers").resolve(task.phase().equals("avoidance") ? "reconstruction" : "emission").resolve(task.id() + ".txt");
    }
    private List<Path> transferTasks(List<FleetTask> tasks, boolean certificates) throws Exception {
        Path destination = directory.resolve(certificates ? "downloads" : "results");
        Files.createDirectories(destination);
        var byServer = new HashMap<String,FleetServer>();
        for (var server : servers) byServer.put(server.name(),server);
        var pending = new LinkedHashMap<FleetTask,FleetTransfer>();
        var result = new ArrayList<Path>();
        for (var task : tasks) {
            Path output = destination.resolve(task.id()); result.add(output);
            if (Files.exists(output)) validateDownload(task,output,certificates);
            else {
                var transfer = FleetTransfer.read(transferIntent(task));
                if (!transfer.run().equals(run) || !transfer.task().equals(task.id()) || !byServer.containsKey(transfer.server()))
                    throw new IllegalArgumentException("Missing or incompatible remote result for required transfer");
                pending.put(task,transfer);
            }
        }
        progress.phase(certificates ? "certificate downloads" : "endpoint residual downloads",tasks.size(),tasks.size() - pending.size());
        if (!pending.isEmpty()) {
            int workers = positiveSetting("td20.fleetDownloadWorkers",2 * servers.size());
            int capacity = positiveSetting("td20.fleetDownloadQueueCapacity",2 * servers.size());
            progress.event("coordinator","Transfer phase: workers=" + workers + ", queued capacity=" + capacity
                    + "; dedicated compressed SSH connections; scope=" + (certificates ? "final certificates" : "receipts and endpoint residuals only"));
            try (var downloads = new FleetDownloads(workers,capacity)) {
                for (var entry : pending.entrySet()) queueDownload(byServer.get(entry.getValue().server()),entry.getKey(),entry.getValue(),certificates,downloads);
                downloads.await();
            }
        }
        for (var task : tasks) validateDownload(task,destination.resolve(task.id()),certificates);
        return List.copyOf(result);
    }
    private static int positiveSetting(String name, int fallback) {
        String configured = System.getProperty(name);
        int value = configured == null ? fallback : Integer.parseInt(configured);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    @FunctionalInterface interface TaskRunner { void run(FleetTask task) throws Exception; }
    static void drainTasks(Queue<FleetTask> pending, java.util.concurrent.atomic.AtomicInteger remaining, TaskRunner runner,
                           java.util.function.Consumer<Throwable> failed) throws Exception {
        int consecutiveFailures = 0;
        while (remaining.get() > 0) {
            var task = pending.poll();
            if (task == null) { Thread.sleep(250); continue; }
            try { runner.run(task); remaining.decrementAndGet(); consecutiveFailures = 0; }
            catch (Exception error) {
                pending.add(task); failed.accept(error);
                if (++consecutiveFailures >= 3) throw error;
                Thread.sleep(2000);
            }
        }
    }
    private String prepare(FleetServer server, Path snapshot, boolean computing) throws Exception {
        String source = server.directory() + "/" + run + "/" + server.name() + "/source";
        Path log = directory.resolve(server.name() + "-setup.log");
        progress.event(server.name(),"preparing source and runtime");
        prepareServer(server,source,log);
        remote(server,"mkdir -p " + FleetFiles.quote(source) + " && " + locked(source,"tar -xf - -C " + FleetFiles.quote(source)),snapshot,null,log);
        remote(server,locked(source,"cd " + FleetFiles.quote(source) + " && ./tools/td20 build"),null,null,log);
        String report = source + "/../hardware-" + UUID.randomUUID() + ".txt";
        remote(server,locked(source,"cd " + FleetFiles.quote(source) + " && env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS TD20_JSHELL_OPTIONS= "
                + "./tools/td20 fleet-hardware " + FleetFiles.quote("--output=" + report) + (server.architecture().equals("cpu") ? " --cpu" : " --gpu")),null,null,log);
        Path temporary = directory.resolve(server.name() + "-hardware-" + UUID.randomUUID() + ".partial");
        remote(server,"cat " + FleetFiles.quote(report),null,temporary,log);
        var resources = FleetHardware.read(temporary);
        Files.move(temporary,directory.resolve(server.name() + "-hardware-v1.txt"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        hardware.put(server.name(),resources);
        progress.event(server.name(),"hardware=" + resources + "; NUMA required=" + resources.requiresNuma());
        if (computing) {
            String options = server.runtimeOptions(resources); executionOptions.put(server.name(),options);
            progress.event(server.name(),"Execution profile applied: CPU threads=" + server.cpuThreads()
                    + "; full JVM settings are in the per-attempt execution records.");
            if (!server.architecture().equals("cpu")) {
                remote(server,locked(source,"cd " + FleetFiles.quote(source)
                    + " && cmake -S emitter/src/native -B emitter/target/native -DCMAKE_BUILD_TYPE=Release "
                    + FleetFiles.quote("-DTD20_CUDA_ARCHITECTURES=" + server.architecture() + "-real;" + server.architecture() + "-virtual")
                    + " && cmake --build emitter/target/native --parallel " + server.cpuThreads()),null,null,log);
            }
        }
        return source;
    }
    private void executeRemote(FleetServer server, String source, FleetTask task, Path job,
                               Path transfersDirectory) throws Exception {
        Path completed = directory.resolve("downloads").resolve(task.id());
        if (Files.exists(completed)) {
            if (!task.equals(FleetTask.read(completed))) throw new IllegalArgumentException("Completed task scope mismatch");
            task.residualCount(completed); progress.start(server.name(),task.id()); progress.done(server.name(),task.id()); return;
        }
        String attempt = UUID.randomUUID().toString();
        String options = Objects.requireNonNull(executionOptions.get(server.name()),"Missing inspected server execution profile");
        var execution = new ArrayList<>(List.of("td20-fleet-execution-v1","run=" + run,"task=" + task.id(),"server=" + server.name(),
                "configured=" + server.runtimeOptions(),"effective=" + options));
        execution.addAll(hardware.get(server.name()).lines());
        FleetFiles.write(directory.resolve(task.id() + "-" + attempt + "-execution-v1.txt"),execution);
        String remote = server.directory() + "/" + run + "/" + server.name() + "/jobs/" + task.id() + "-" + attempt;
        Path log = directory.resolve(task.id() + "-" + server.name() + ".log");
        Path upload = directory.resolve(task.id() + "-" + attempt + ".tar");
        progress.start(server.name(),task.id());
        runShell("tar -cf " + FleetFiles.quote(upload.toString()) + " -C " + FleetFiles.quote(job.toString()) + " .",root,null,null,log);
        remote(server,"mkdir -p " + FleetFiles.quote(remote) + " && tar -xf - -C " + FleetFiles.quote(remote),upload,null,log);
        Files.delete(upload);
        progress.stage(server.name(),"computing");
        remote(server,locked(source,"cd " + FleetFiles.quote(source) + " && env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS TD20_JSHELL_OPTIONS=" + FleetFiles.quote(options)
                + " ./tools/td20 fleet-worker " + FleetFiles.quote("--job=" + remote)),null,null,log);
        var transfer = new FleetTransfer(run,task.id(),server.name(),attempt);
        transfer.write(transfersDirectory.resolve(task.id() + ".txt"));
        progress.done(server.name(),task.id());
    }
    private void queueDownload(FleetServer server, FleetTask task, FleetTransfer transfer,
                               boolean certificates, FleetDownloads downloads) {
        progress.downloadQueued(server.name(),task.id());
        downloads.submit(() -> {
            for (int attempt = 1; ; attempt++) try {
                download(server,task,transfer,certificates);
                if (certificates) Files.deleteIfExists(transferIntent(task));
                Path completed = directory.resolve(certificates ? "downloads" : "results").resolve(task.id());
                var counts = task.receiptCounts(completed);
                progress.accepted(server.name(),task.id(),counts.generatedEndpoints(),counts.endpointResiduals(),counts.reconstructedRoots());
                return;
            } catch (Exception error) {
                progress.downloadFailed(server.name(),task.id(),error,attempt == 3 || error instanceof InterruptedException);
                if (attempt == 3 || error instanceof InterruptedException) throw error;
                Thread.sleep(2000);
            }
        });
    }
    private void download(FleetServer server, FleetTask task, FleetTransfer transfer, boolean certificates) throws Exception {
        Path destination = directory.resolve(certificates ? "downloads" : "results");
        Path completed = destination.resolve(task.id());
        if (Files.exists(completed)) {
            validateDownload(task,completed,certificates);
            return;
        }
        Path log = directory.resolve(task.id() + "-" + server.name() + (certificates ? "-certificate-download.log" : "-residual-download.log"));
        String remote = transfer.remoteDirectory(server);
        String staging = transfer.attempt() + "-" + UUID.randomUUID();
        Path archive = directory.resolve(task.id() + "-" + staging + ".download.partial");
        progress.downloadStage(server.name(),task.id(),"downloading");
        String paths = "task.txt complete.txt" + (certificates ? " certificates" : task.phase().equals("endpoints") ? " work/endpoint-residuals" : "");
        remote(server,"tar -cf - -C " + FleetFiles.quote(remote) + " " + paths,null,archive,log);
        Path partial = Files.createDirectory(destination.resolve("." + task.id() + "-" + staging));
        progress.downloadStage(server.name(),task.id(),"validating download");
        runShell("tar --no-same-owner -xf " + FleetFiles.quote(archive.toString()) + " -C " + FleetFiles.quote(partial.toString()),root,null,null,log);
        validateDownload(task,partial,certificates);
        Files.move(partial,completed,StandardCopyOption.ATOMIC_MOVE);
        Files.delete(archive);
    }
    private void validateDownload(FleetTask task, Path output, boolean certificates) throws IOException {
        if (!FleetTask.read(output).equals(task)) throw new IllegalArgumentException("Remote task identity mismatch");
        var counts = task.receiptCounts(output);
        if (certificates) task.residualCount(output); // Full certificate publication requires its actual database.
        else if (task.phase().equals("endpoints")) try (var states = br.unb.cic.td20.avoidance.DiskStateSet.openSealed(
                output.resolve("work/endpoint-residuals"),counts.endpointResiduals())) { }
    }
    private void remote(FleetServer server, String command, Path input, Path output, Path log) throws Exception {
        String label = log.getFileName().toString().replace("-" + server.name() + ".log","");
        transport.run(server,command,input,output,log,line -> progress.remote(server.name(),label,line),null);
    }
    private void prepareServer(FleetServer server, String source, Path log) throws Exception {
        if (token != null && (token.contains("\n") || token.contains("\r")))
            throw new IllegalArgumentException("Malformed TD20_GITHUB_TOKEN");
        progress.event(server.name(),"installing openjdk-21-jdk, maven and cmake");
        remote(server,"sudo apt install openjdk-21-jdk maven cmake -y",null,null,log);
        remote(server,"mkdir -p " + FleetFiles.quote(source),null,null,log);
        if (token == null || token.isBlank()) {
            String bundle = source + "/../source.bundle";
            remote(server,"cat > " + FleetFiles.quote(bundle),directory.resolve("source.bundle"),null,log);
            progress.event(server.name(),"Cloning the pinned local Git bundle; overlaying the local source snapshot.");
            remote(server,locked(source,"test -d " + FleetFiles.quote(source + "/.git")
                    + " || git clone --no-checkout -- " + FleetFiles.quote(bundle) + " " + FleetFiles.quote(source)),null,null,log);
            return;
        }
        String encoded = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] secret = ("Authorization: Basic " + encoded + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String clone = "IFS= read -r GIT_CONFIG_VALUE_1 && export GIT_CONFIG_VALUE_1 && cd /workspace && (test -d "
                + FleetFiles.quote(source + "/.git") + " || GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=credential.helper GIT_CONFIG_VALUE_0= "
                + "GIT_CONFIG_KEY_1=http.https://github.com/.extraHeader GIT_TERMINAL_PROMPT=0 GIT_TRACE=0 GIT_TRACE_CURL=0 GIT_CURL_VERBOSE=0 "
                + "git clone --no-checkout -- " + FleetFiles.quote(repository) + " " + FleetFiles.quote(source) + ")";
        progress.event(server.name(),"cloning repository under /workspace with transient authentication");
        try {
            transport.run(server,locked(source,clone),null,null,log,line -> progress.remote(server.name(),"setup",line),secret);
        } finally { Arrays.fill(secret,(byte)0); }
    }
    private static String locked(String source, String command) {
        return "flock -n " + FleetFiles.quote(source + "/../worker.lock") + " "
                + FleetFiles.interactive("export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; export PATH=\"$JAVA_HOME/bin:$PATH\"; " + command);
    }
    static void runShell(String command, Path cwd, Path input, Path output, Path log) throws IOException, InterruptedException {
        runShell(command,cwd,input,output,log,null);
    }
    static void runShell(String command, Path cwd, Path input, Path output, Path log,
                         java.util.function.Consumer<String> observer) throws IOException, InterruptedException {
        runShell(command,cwd,input,output,log,observer,null);
    }
    static void runShell(String command, Path cwd, Path input, Path output, Path log,
                         java.util.function.Consumer<String> observer, byte[] secretInput) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("bash","-ic",command).directory(cwd.toFile());
        builder.environment().remove("TD20_GITHUB_TOKEN");
        if (secretInput == null) builder.redirectInput(input == null ? ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()) : ProcessBuilder.Redirect.from(input.toFile()));
        builder.redirectErrorStream(output == null);
        if (output != null) builder.redirectOutput(ProcessBuilder.Redirect.to(output.toFile()));
        var process = builder.start();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var drain = Thread.ofPlatform().name("fleet-command-output").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(output == null ? process.getInputStream() : process.getErrorStream(),java.nio.charset.StandardCharsets.UTF_8));
                 var writer = Files.newBufferedWriter(log,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (secretInput != null) line = redact(line,secretInput);
                    writer.write(line); writer.newLine(); writer.flush();
                    if (observer != null) observer.accept(line);
                }
            } catch (Throwable error) { failure.set(error); process.destroy(); }
        });
        try {
            if (secretInput != null) try (var stdin = process.getOutputStream()) { stdin.write(secretInput); }
            int exit = process.waitFor();
            drain.join();
            if (failure.get() != null) throw new IOException("Cannot stream command output; see " + log,failure.get());
            if (exit != 0) throw new IOException("Command exited " + exit + "; see " + log);
        } catch (IOException | RuntimeException error) { terminate(process,drain); throw error; }
        catch (InterruptedException error) { terminate(process,drain); Thread.currentThread().interrupt(); throw error; }
    }
    private static void terminate(Process process, Thread drain) {
        process.descendants().forEach(ProcessHandle::destroy); process.destroy();
        boolean interrupted = false;
        while (drain.isAlive()) {
            try { drain.join(); } catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static String redact(String line, byte[] secretInput) {
        String header = new String(secretInput,java.nio.charset.StandardCharsets.UTF_8).strip();
        String encoded = header.substring(header.lastIndexOf(' ') + 1);
        String credential = new String(Base64.getDecoder().decode(encoded),java.nio.charset.StandardCharsets.UTF_8);
        return line.replace(header,"[redacted]").replace(encoded,"[redacted]")
                .replace(credential.substring(credential.indexOf(':') + 1),"[redacted]");
    }
}
