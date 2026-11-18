package br.unb.cic.td20.fleet;

import br.unb.cic.td20.avoidance.*;
import br.unb.cic.td20.certificate.*;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResidualFleetInputTest {
    private static final String RUN="0f97434b-941b-4424-990e-eb6cb073c9d8";
    @Test void importedInputIsPinnedAndFullCheckpointsAreRejected(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectories(directory.resolve("td18-after-two-reuse-v1/residuals"));
        var checkpoint=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,2036,970331,-1,-1,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        checkpoint.write(source);
        assertEquals(source.toRealPath(),FleetEmitter.reconstructionInput(directory,RUN,null));
        assertEquals(source.toRealPath(),FleetEmitter.reconstructionInput(directory,RUN,null));
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,2036,970330,-1,-1,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION).write(source);
        assertThrows(IllegalArgumentException.class,() -> FleetEmitter.reconstructionInput(directory,RUN,null));
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,2036,970331,-1,-1).write(source);
        assertThrows(IllegalArgumentException.class,() -> ReconstructionBatches.prepareResidualWave(source));
    }
    @Test void residualTasksHaveDistinctIdentitiesAndWorkerRejectsModeMismatch(@TempDir Path directory) throws Exception {
        var old=ReconstructionBatches.describe(RUN,3,2);
        var residual=ReconstructionBatches.describe(RUN,3,2,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        assertEquals(List.of(2L,1L),residual.stream().map(FleetTask::inputCount).toList());
        assertEquals(ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION,residual.getFirst().reconstructionMode());
        assertNotEquals(old.getFirst().id(),residual.getFirst().id());
        assertNotEquals(FleetEmitter.transferPhase(old.getFirst()),FleetEmitter.transferPhase(residual.getFirst()),
                "Archived full-wave transfers cannot enter the residual execution queue");
        Path job=Files.createDirectory(directory.resolve("job"));residual.getFirst().write(job);
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,2036,2,-1,-1).write(Files.createDirectory(job.resolve("input")));
        assertThrows(IllegalArgumentException.class,() -> FleetWorker.execute(job));
    }
    @Test void residualPublicationAppendsFinalRootsToThePreparedSingleDatabase(@TempDir Path directory) throws Exception {
        Path output=directory.resolve("certificates"),roots=directory.resolve("root-certificates"),report=directory.resolve("complete.txt");
        var imported=CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0,1,2)),Cycle.of(0,1,2));
        var residual=CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),Cycle.of(0,1,2,3,4,5));
        var finalRoot=CyclicTargetPair.of(new MulticyclePermutation("(0)(1)(2)(3)"),Cycle.of(0,1,2,3));
        try(var writer=new AsyncCertificateWriter(output,60)) {writer.enqueue(imported,List.of(Cycle.of(0,1,2)));writer.enqueueObstruction(residual,2);}
        try(var writer=new AsyncCertificateWriter(roots,60)) {writer.enqueue(finalRoot,List.of());}
        assertThrows(IllegalStateException.class,() -> FleetEmitter.publishCertificates(output,report,RUN,3,ErikssonCheckpoint.Mode.FULL_SEARCH,List.of(roots),line -> {}));
        FleetEmitter.publishCertificates(output,report,RUN,3,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION,List.of(roots),line -> {});
        assertEquals(List.of("td20-fleet-merged-v2","run="+RUN,"tasks=3","mode=RESIDUAL_EXPANSION"),Files.readAllLines(report));
        try(var writer=new AsyncCertificateWriter(output,60)) {
            assertTrue(writer.find(imported,1).isPresent());assertTrue(writer.find(finalRoot,0).isPresent());
            var markers=new ArrayList<byte[]>();writer.forEachObstruction(markers::add);
            assertEquals(1,markers.size());assertArrayEquals(CertificateCodec.keyFor(residual),markers.getFirst());
        }
        FleetEmitter.publishCertificates(output,report,RUN,3,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION,List.of(roots),line -> {});
        assertThrows(IllegalStateException.class,() -> FleetEmitter.publishCertificates(output,report,RUN,4,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION,List.of(roots),line -> {}));
    }
    @Test void freshMergeInputIncludesTheImportedReuseDatabase(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("residuals")),imported=Files.createDirectory(directory.resolve("reuse-certificates"));
        Path emission=Files.createDirectory(directory.resolve("emission-certificates"));
        Files.write(source.resolve(ImportTd18AfterTwo.REPORT),List.of(
                "td20-td18-after-two-import-v1","route="+ErikssonRoute.ID,"mode=RESIDUAL_EXPANSION",
                "rootSymbols=21","rootBudget=11","symbols=18","budget=9","informationalEndpoints=2036",
                "sourceStates=5032950","importedCertificates=4062619","missingWitnesses=13962",
                "overBudgetWitnesses=956369","activeResiduals=970331","sourceDatabase=/unused/td18",
                "sourceWork=/unused/waves","database="+imported,"workers=1","replay=source-and-destination","status=complete"));
        assertEquals(List.of(emission.toRealPath(),imported.toRealPath()),
                FleetEmitter.certificateInputs(directory.resolve("future-output"),source,List.of(emission,emission)));
        assertEquals(List.of(emission.toRealPath()),FleetEmitter.certificateInputs(imported,source,List.of(emission)));
        Files.writeString(source.resolve(ImportTd18AfterTwo.REPORT),"td20-td18-after-two-import-v1\n");
        assertThrows(IllegalArgumentException.class,() -> FleetEmitter.certificateInputs(directory.resolve("future-output"),source,List.of(emission)));
    }
    @Test void preparedEmissionUnionOmitsOnlyTheAlreadyAcceptedBaseInputs(@TempDir Path directory) throws Exception {
        var tasks=emissionTasks();var inputs=emissionInputs(directory);Path output=preparedOutput(directory);
        writePreparedUnion(directory,output,tasks.size(),inputs.size());
        var selected=FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION);
        assertTrue(selected.isEmpty(),"The accepted emission base must not replay its original witness databases");
        Path roots=Files.createDirectory(directory.resolve("final-roots"));
        var mergeInputs=new ArrayList<>(selected);mergeInputs.add(roots);
        assertEquals(List.of(roots.toRealPath()),FleetEmitter.certificateInputs(output,null,mergeInputs),
                "Every final reconstruction output must still enter the append");
        assertEquals(inputs,FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.FULL_SEARCH));
        Files.delete(directory.resolve("local-certificate-consolidation/complete-v1.txt"));
        assertEquals(inputs,FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION),
                "Without accepted preparation, every original source remains a replay obligation");
    }
    @Test void preparedEmissionScopeMismatchCannotSilentlyOmitAnySource(@TempDir Path directory) throws Exception {
        var tasks=emissionTasks();var inputs=emissionInputs(directory);Path output=preparedOutput(directory);
        writePreparedUnion(directory,output,tasks.size(),inputs.size());
        Path receipt=directory.resolve("local-certificate-consolidation/complete-v1.txt");var original=Files.readAllLines(receipt);
        Path other=Files.createDirectory(directory.resolve("other-output"));
        var mutations=List.of(Map.entry(0,"td20-local-emission-union-v2"),
                Map.entry(1,"run=13e9eb37-22e3-45e0-a378-bc030f35df17"),Map.entry(2,"tasks=3"),
                Map.entry(3,"inputs=3"),Map.entry(4,"witnesses=-1"),Map.entry(6,"replay=witnesses"),
                Map.entry(7,"output="+other),Map.entry(7,"output=relative-output"),Map.entry(8,"completed=not-an-instant"));
        for(var mutation:mutations) {
            var changed=new ArrayList<>(original);changed.set(mutation.getKey(),mutation.getValue());Files.write(receipt,changed);
            assertThrows(Exception.class,() -> FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION),mutation.getValue());
        }
        Files.write(receipt,original.subList(0,8));
        assertThrows(IllegalArgumentException.class,() -> FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION));
        Files.write(receipt,original);
        var foreign=List.of(new FleetTask("13e9eb37-22e3-45e0-a378-bc030f35df17","root-1","roots",1,1,0),tasks.getLast());
        assertThrows(IllegalArgumentException.class,() -> FleetEmitter.emissionMergeInputs(directory,output,RUN,foreign,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION));
        assertThrows(IllegalArgumentException.class,() -> FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,List.of(inputs.getFirst(),inputs.getFirst()),ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION));
    }
    @Test void preparedEmissionRequiresTheDestinationAndItsOriginalMatchingReport(@TempDir Path directory) throws Exception {
        var tasks=emissionTasks();var inputs=emissionInputs(directory);Path output=preparedOutput(directory);
        writePreparedUnion(directory,output,tasks.size(),inputs.size());
        Files.delete(output.resolve("CURRENT"));
        assertThrows(IllegalArgumentException.class,() -> FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION));
        Files.writeString(output.resolve("CURRENT"),"fixture-current");
        Path report=output.resolve("consolidation-v1.txt");var original=Files.readAllLines(report);
        var mutations=List.of(Map.entry(0,"td20-certificate-merge-v1"),Map.entry(1,"witnesses=43"),
                Map.entry(2,"markers=3"),Map.entry(3,"inputs=3"),Map.entry(4,"targetSstBytes=0"),
                Map.entry(4,"targetSstBytes=not-a-count"),Map.entry(5,"replay=witnesses"));
        for(var mutation:mutations) {
            var changed=new ArrayList<>(original);changed.set(mutation.getKey(),mutation.getValue());Files.write(report,changed);
            assertThrows(Exception.class,() -> FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION),mutation.getValue());
        }
        Files.delete(report);
        assertThrows(java.io.IOException.class,() -> FleetEmitter.emissionMergeInputs(directory,output,RUN,tasks,inputs,ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION));
    }
    private static List<FleetTask> emissionTasks() {
        return List.of(new FleetTask(RUN,"root-1","roots",1,1,0),new FleetTask(RUN,"endpoint-1","endpoints",1,1,0));
    }
    private static List<Path> emissionInputs(Path directory) throws Exception {
        return List.of(Files.createDirectory(directory.resolve("emission-a")),Files.createDirectory(directory.resolve("emission-b")));
    }
    private static Path preparedOutput(Path directory) throws Exception {
        Path output=Files.createDirectory(directory.resolve("prepared-certificates"));
        // Input-selection tests use filesystem fixtures and never open or write a certificate database.
        Files.writeString(output.resolve("CURRENT"),"fixture-current");return output;
    }
    private static void writePreparedUnion(Path directory,Path output,int tasks,int inputs) throws Exception {
        Files.write(Files.createDirectories(directory.resolve("local-certificate-consolidation")).resolve("complete-v1.txt"),List.of(
                "td20-local-emission-union-v1","run="+RUN,"tasks="+tasks,"inputs="+inputs,
                "witnesses=42","markers=2","replay=none","output="+output.toAbsolutePath(),"completed=2026-11-17T21:00:00Z"));
        Files.write(output.resolve("consolidation-v1.txt"),List.of("td20-raw-certificate-consolidation-v1",
                "witnesses=42","markers=2","inputs="+inputs,"targetSstBytes=1073741824","replay=none"));
    }
}
