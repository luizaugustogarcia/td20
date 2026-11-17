package br.unb.cic.td20.fleet;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** One append-only local stream, with synchronized lines from every remote job. */
final class FleetProgress implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ssXXX")
            .withZone(ZoneId.of("Europe/Lisbon"));
    private final BufferedWriter writer;
    private final Path statusFile;
    private final ScheduledExecutorService reporter;
    private final Map<String,String> active = new TreeMap<>(), latest = new TreeMap<>();
    private final Map<String,Instant> started = new TreeMap<>();
    private final Map<String,String> stages = new TreeMap<>();
    private record Download(String server, String stage, Instant started) { }
    private final Map<String,Download> downloads = new TreeMap<>();
    private final Instant launched = Instant.now();
    private Instant phaseStarted = launched;
    private String phase = "starting";
    private int total, completed, reused, failed, retries;
    private long generated, residuals, reconstructed;
    FleetProgress(Path path) throws IOException {
        writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        statusFile = path.resolveSibling("status.txt");
        reporter = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("fleet-progress").factory());
        reporter.scheduleWithFixedDelay(this::report,10,10,TimeUnit.SECONDS);
    }
    synchronized void phase(String name, int jobs, int resumed) {
        phase = name; phaseStarted = Instant.now(); total = jobs; completed = resumed; reused = resumed; failed = 0;
        active.clear(); latest.clear(); started.clear(); stages.clear(); downloads.clear(); generated = residuals = reconstructed = 0; report();
    }
    synchronized void finished(int jobs) {
        phase = "complete"; total = jobs; completed = jobs; active.clear(); latest.clear(); started.clear(); stages.clear(); downloads.clear(); report();
    }
    synchronized void start(String server, String job) {
        active.put(server,job); started.put(server,Instant.now()); stages.put(server,"uploading");
    }
    synchronized void stage(String server, String name) { stages.put(server,name); }
    synchronized void done(String server, String job) {
        computed(server,job); completed++;
    }
    synchronized void computed(String server, String job) {
        if (job.equals(active.get(server))) {
            active.remove(server); latest.remove(server); started.remove(server); stages.remove(server);
        }
    }
    synchronized void downloadQueued(String server, String job) {
        downloads.put(job,new Download(server,"queued",Instant.now()));
    }
    synchronized void downloadStage(String server, String job, String stage) {
        Download previous = Objects.requireNonNull(downloads.get(job),"Unregistered background download");
        downloads.put(job,new Download(server,stage,previous.started()));
    }
    synchronized void downloadFailed(String server, String job, Throwable error, boolean finalFailure) {
        failed++; retries++;
        if (finalFailure) downloads.remove(job);
        else downloadStage(server,job,"retrying");
        event(server + "/" + job,"DOWNLOAD FAILED: " + error); report();
    }
    synchronized void accepted(String server, String job, long endpointsGenerated, long endpointResiduals, long roots) {
        generated = Math.addExact(generated,endpointsGenerated); residuals = Math.addExact(residuals,endpointResiduals);
        reconstructed = Math.addExact(reconstructed,roots);
        if (downloads.remove(job) != null) completed++;
        else done(server,job);
    }
    synchronized void failed(String server, Throwable error) {
        active.remove(server); started.remove(server); stages.remove(server); failed++; retries++;
        latest.remove(server); event(server,"FAILED: " + error); report();
    }
    synchronized void remote(String server, String job, String line) {
        // Complete command output remains in the per-task/setup log. Consolidate
        // only progress snapshots and genuine diagnostics, never shell chatter.
        if (line.startsWith("bash: cannot set terminal process group (")
                || line.equals("bash: no job control in this shell")) return;
        if (job.equals(active.get(server)) && (line.contains("processed=") || line.contains("generated="))
                && !line.contains("Certificate memtable flush") && !line.contains("Certificate persistence"))
            latest.put(server,line.replaceFirst("^\\S+ INFO ",""));
        if (line.contains("ERROR") || line.contains("Exception") || line.contains("Error")
                || line.contains("error:") || line.contains("fatal:") || line.contains("FAILED"))
            append(server + "/" + job,line);
    }
    synchronized void event(String origin, String message) {
        append(origin,message);
    }
    synchronized void report() {
        int queued = Math.max(0,total - completed - active.size() - downloads.size());
        String percentage = total == 0 ? "n/a" : String.format(Locale.ROOT,"%.2f%%",100.0 * completed / total);
        Instant now = Instant.now();
        double minutes = Math.max(1.0 / 60,Duration.between(phaseStarted,now).toMillis() / 60000.0);
        var snapshot = new ArrayList<String>();
        snapshot.add("Overall: " + milestone() + "; elapsed=" + elapsed(launched,now)
                + "; independent verification=pending");
        snapshot.add(String.format(Locale.ROOT,"phase=%s jobs=%d/%d (%s) reused=%d queued=%d active=%d failed=%d",
                phase,completed,total,percentage,reused,queued,active.size(),failed)
                + String.format(Locale.ROOT," remaining=%d rate=%.1f tasks/min retryAttempts=%d",
                Math.max(0,total-completed),(completed-reused)/minutes,retries)
                + " backgroundDownloads=" + downloads.size()
                + " queuedDownloads=" + downloads.values().stream().filter(download -> download.stage().equals("queued")).count());
        if (phase.equals("emission")) snapshot.add("Job progress counts closed remote computations; certificates stay remote; reconstruction total=unknown until inverse preparation");
        if (phase.equals("reconstruction")) snapshot.add("Job progress counts closed remote computations; certificates stay remote until the final transfer phase");
        if (phase.equals("endpoint residual downloads")) snapshot.add("Validated operational receipts: generatedEndpointClasses=" + generated
                + " residualEndpointClasses=" + residuals + "; certificate databases remain remote");
        if (phase.equals("certificate downloads")) snapshot.add("Accepted certificate databases this phase: generatedEndpointClasses=" + generated
                + " reconstructedRootClasses=" + reconstructed + " (cross-task duplicates possible)");
        var reportingServers = new TreeSet<>(active.keySet());
        downloads.values().forEach(download -> reportingServers.add(download.server()));
        for (String server : reportingServers) {
            var transfers = downloads.entrySet().stream().filter(entry -> entry.getValue().server().equals(server)).toList();
            String transfer = transfers.stream().filter(entry -> !entry.getValue().stage().equals("queued")).findFirst()
                    .map(entry -> " download=" + entry.getKey() + " downloadStage=" + entry.getValue().stage()
                            + " downloadElapsed=" + elapsed(entry.getValue().started(),now)).orElse("");
            snapshot.add(server + " job=" + active.getOrDefault(server,"idle")
                    + " stage=" + stages.getOrDefault(server,"idle")
                    + " elapsed=" + elapsed(started.get(server),now)
                    + " latest=" + latest.getOrDefault(server,"no class counter yet")
                    + " backgroundDownloads=" + transfers.size() + transfer);
        }
        snapshot.forEach(line -> append("fleet",line));
        try {
            Path temporary = Files.createTempFile(statusFile.getParent(),".status-",".tmp");
            try {
                var lines = new ArrayList<>(List.of("td20-fleet-status-v1","Updated: " + TIME.format(now)));
                lines.addAll(snapshot); Files.write(temporary,lines);
                Files.move(temporary,statusFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) { throw new UncheckedIOException("Cannot publish fleet status",error); }
    }
    private String milestone() {
        return switch (phase) {
            case "emission" -> "1/6 remote emission computation";
            case "endpoint residual downloads" -> "2/6 receipts and endpoint residual downloads";
            case "local first inverse wave and batch preparation" -> "3/6 inverse preparation";
            case "reconstruction" -> "4/6 remote reconstruction computation";
            case "certificate downloads" -> "5/6 final certificate downloads";
            case "local certificate merge" -> "6/6 certificate merge";
            case "complete" -> "production complete";
            default -> "preparing production (" + phase + ")";
        };
    }
    private static String elapsed(Instant from, Instant until) {
        if (from == null) return "00:00:00";
        long seconds = Math.max(0,Duration.between(from,until).toSeconds());
        return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
    }
    private void append(String origin, String message) {
        try {
            String line = TIME.format(Instant.now()) + " [" + origin + "] " + message;
            writer.write(line);
            writer.newLine(); writer.flush();
            System.out.println(line);
        } catch (IOException error) { throw new UncheckedIOException("Cannot append consolidated emission log",error); }
    }
    @Override public void close() throws IOException {
        reporter.shutdownNow();
        boolean interrupted = false;
        for (;;) {
            try { if (reporter.awaitTermination(1,TimeUnit.SECONDS)) break; }
            catch (InterruptedException error) { interrupted = true; }
        }
        synchronized (this) { report(); writer.close(); }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
