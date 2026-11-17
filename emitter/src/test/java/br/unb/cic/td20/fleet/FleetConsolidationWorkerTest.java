package br.unb.cic.td20.fleet;

import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.certificate.CertificateDatabaseConsolidator;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetConsolidationWorkerTest {
    private static final String RUN="0f97434b-941b-4424-990e-eb6cb073c9d8";
    @Test void inventoryRetainsScopedReceiptsAndRejectsForeignOrDuplicatePlans(@TempDir Path directory)throws Exception {
        var task=new FleetTask(RUN,"endpoint-001","endpoints",1,1,0);
        String name=task.id()+"-"+UUID.randomUUID();Path job=Files.createDirectory(directory.resolve(name));
        task.write(job);task.complete(job,0,0,12);Files.createDirectories(job.resolve("certificates"));Files.writeString(job.resolve("certificates/CURRENT"),"fixture");
        var inventory=FleetConsolidationWorker.inventory(RUN,directory);assertEquals(1,inventory.size());assertEquals(task,inventory.getFirst().task());
        Path plan=directory.resolve("inputs.txt");FleetConsolidationWorker.write(plan,RUN,"node",inventory);
        assertEquals(inventory,FleetConsolidationWorker.read(plan,RUN,"node"));
        assertThrows(IllegalArgumentException.class,()->FleetConsolidationWorker.read(plan,UUID.randomUUID().toString(),"node"));
        assertThrows(IllegalArgumentException.class,()->FleetConsolidationWorker.read(plan,RUN,"other"));
        FleetConsolidationWorker.write(plan,RUN,"node",List.of(inventory.getFirst(),inventory.getFirst()));
        assertThrows(IllegalArgumentException.class,()->FleetConsolidationWorker.read(plan,RUN,"node"));
        Files.delete(job.resolve("certificates/CURRENT"));assertThrows(IllegalArgumentException.class,()->FleetConsolidationWorker.inventory(RUN,directory));
    }
    @Test void downloadedCompletionCannotOmitARequiredTask(@TempDir Path directory)throws Exception {
        var task=new FleetTask(RUN,"root-001","roots",1,1,0);Files.createDirectories(directory.resolve("consolidation"));
        FleetFiles.write(directory.resolve("consolidation/complete-v1.txt"),List.of("td20-emission-consolidated-backup-v1","route=td20-eriksson-three-v1","run="+RUN,"tasks=1","servers=1","server=node"));
        assertThrows(Exception.class,()->FleetConsolidationBackup.completedInputs(directory,RUN,List.of(task)));
    }
    @Test void transfersReachEveryServerBeforeTakingAnotherFileFromTheFirst() {
        assertEquals(List.of("a1","b1","c1","a2","c2","a3"),
                FleetConsolidationBackup.roundRobin(List.of(List.of("a1","a2","a3"),List.of("b1"),List.of(),List.of("c1","c2"))));
    }
    @Test void candidateRejectsImpossibleReceiptInventory() {
        var task=new FleetTask(RUN,"endpoint-001","endpoints",1,1,0);
        var lines=new ArrayList<>(task.lines());lines.set(0,"td20-fleet-complete-v2");
        lines.addAll(List.of("endpointResiduals=3","reconstructedRoots=0","generatedEndpoints=2"));
        assertThrows(IllegalArgumentException.class,()->new FleetConsolidationWorker.Candidate(task.id()+"-"+UUID.randomUUID(),task,lines));
    }
    @Test void completedBackupCanReplaceTaskDownloadsButRejectsChangedReceipts(@TempDir Path run)throws Exception {
        var task=new FleetTask(RUN,"endpoint-001","endpoints",1,1,0);
        Path job=Files.createDirectories(run.resolve("results/"+task.id()));task.write(job);task.complete(job,0,0,0);
        Path source=run.resolve("source");try(var writer=new AsyncCertificateWriter(source,60)){}
        Path output=Files.createDirectories(run.resolve("downloads/node"));
        CertificateDatabaseConsolidator.consolidate(output.resolve("certificates"),List.of(new CertificateDatabaseConsolidator.Input(source,0,0)),16384,line->{});
        var candidate=new FleetConsolidationWorker.Candidate(task.id()+"-"+UUID.randomUUID(),task,Files.readAllLines(job.resolve("complete.txt")));
        FleetConsolidationWorker.write(output.resolve("tasks-v1.txt"),RUN,"node",List.of(candidate));
        List<Path> files;try(var paths=Files.walk(output)){files=paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().startsWith("LOG")&&!p.getFileName().toString().equals("LOCK")).sorted().toList();}
        var inventory=new ArrayList<>(List.of("td20-consolidation-files-v1","route=td20-eriksson-three-v1","run="+RUN,"server=node","files="+files.size()));
        for(Path file:files)inventory.add("file="+output.relativize(file)+"\t"+Files.size(file));
        FleetFiles.write(output.resolve("files-v1.txt"),inventory);
        FleetFiles.write(run.resolve("consolidation/complete-v1.txt"),List.of("td20-emission-consolidated-backup-v1","route=td20-eriksson-three-v1","run="+RUN,"tasks=1","servers=1","server=node"));
        assertEquals(List.of(output.resolve("certificates")),FleetConsolidationBackup.completedInputs(run,RUN,List.of(task)));
        task.complete(job,0,0,1);
        assertThrows(IllegalArgumentException.class,()->FleetConsolidationBackup.completedInputs(run,RUN,List.of(task)));
    }
    @Test void finalDatabaseDestinationSurvivesResumeAndRejectsAnotherRun(@TempDir Path directory)throws Exception {
        Path run=Files.createDirectory(directory.resolve("run"));Path finalDatabase=directory.resolve("certificates");
        assertEquals(finalDatabase,FleetEmitter.outputDatabase(run,RUN,finalDatabase));
        assertEquals(finalDatabase,FleetEmitter.outputDatabase(run,RUN,null));
        assertThrows(IllegalArgumentException.class,()->FleetEmitter.outputDatabase(run,RUN,directory.resolve("other")));
        assertThrows(IllegalArgumentException.class,()->FleetEmitter.outputDatabase(run,UUID.randomUUID().toString(),finalDatabase));
    }
}
