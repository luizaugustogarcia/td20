package br.unb.cic.td20.fleet;

import br.unb.cic.td20.WitnessEmitter;
import br.unb.cic.td20.avoidance.*;
import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.emission.ErikssonWitnessEmitter;
import br.unb.cic.td20.joint.ErikssonInventory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One isolated remote job. A receipt is published only after all emitter resources close. */
public final class FleetWorker {
    private FleetWorker() { }
    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !args[0].startsWith("--job=")) throw new IllegalArgumentException("fleet-worker --job=PATH");
        execute(Path.of(args[0].substring(6)).toAbsolutePath().normalize());
    }
    static void execute(Path job) throws IOException {
        var task = FleetTask.read(job);
        System.out.println("Worker execution: cpuThreads=" + Runtime.getRuntime().availableProcessors()
                + ", maximumHeapBytes=" + Runtime.getRuntime().maxMemory()
                + ", jvmArguments=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (Files.exists(job.resolve("certificates")) || Files.exists(job.resolve("work")) || Files.exists(job.resolve("complete.txt")))
            throw new IllegalArgumentException("Remote task requires fresh output directories");
        if (!task.generation().equals("whole")) {
            var endpoint = ErikssonInventory.endpoints().get(task.first() - 1);
            if (endpoint.elementaryClosure()) throw new IllegalArgumentException("Elementary endpoints must use whole-annotation closure tasks");
            var result = ErikssonWitnessEmitter.emitEndpointBatch(endpoint,task.generationWork(),job.resolve("certificates"),job.resolve("work"));
            task.complete(job,result.residuals(),0,result.generated());
            return;
        }
        var arguments = new ArrayList<>(List.of("--phase=" + task.phase(), "--database=" + job.resolve("certificates"),
                "--work=" + job.resolve("work")));
        if (task.phase().equals("avoidance")) {
            var checkpoint = ErikssonCheckpoint.read(job.resolve("input"));
            if (checkpoint.stage() != ErikssonCheckpoint.Stage.AFTER_TWO || checkpoint.afterTwo() != task.inputCount())
                throw new IllegalArgumentException("Reconstruction task count or stage mismatch");
            arguments.add("--resume-work=" + job.resolve("input"));
        } else arguments.add((task.phase().equals("roots") ? "--partitions=" : "--endpoints=") + task.first() + "-" + task.last());
        long endpoints = 0, roots = 0, generated = 0;
        if (task.phase().equals("endpoints")) for (var endpoint : ErikssonInventory.endpoints())
            if (endpoint.number() >= task.first() && endpoint.number() <= task.last() && !endpoint.elementaryClosure())
                generated = Math.addExact(generated,DirectTwistedBraceletGenerator.countRepresentativesByBurnside(endpoint.partition(),endpoint.orientedByPart()));
        WitnessEmitter.main(arguments.toArray(String[]::new));
        if (task.phase().equals("endpoints")) try (var residuals = DiskStateSet.openForInventory(job.resolve("work/endpoint-residuals"))) {
            endpoints = residuals.seal();
        }
        if (task.phase().equals("avoidance")) {
            var checkpoint = ErikssonCheckpoint.read(job.resolve("work"));
            if (checkpoint.stage() != ErikssonCheckpoint.Stage.ROOTS) throw new IllegalStateException("Missing final reconstructed root inventory");
            roots = checkpoint.roots();
        }
        task.complete(job, endpoints, roots, generated);
    }
}
