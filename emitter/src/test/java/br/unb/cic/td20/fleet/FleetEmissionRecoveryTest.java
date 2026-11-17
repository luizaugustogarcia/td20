package br.unb.cic.td20.fleet;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetEmissionRecoveryTest {
    private static final String RUN="2eaa491c-c0d9-476c-bf85-652b957042a7";
    private static FleetConsolidationWorker.Candidate candidate(Path results,FleetTask task,long generated)throws Exception {
        Path job=Files.createDirectories(results.resolve(task.id()));task.write(job);task.complete(job,0,0,generated);
        return new FleetConsolidationWorker.Candidate(task.id()+"-"+UUID.randomUUID(),task,Files.readAllLines(job.resolve("complete.txt")));
    }
    @Test void recoveryRetainsExactTaskDefinitionsAndSchedulesLargerTasksFirst(@TempDir Path directory)throws Exception {
        var a=new FleetTask(RUN,"endpoint-a","endpoints",1,1,0,"prefix/1");
        var b=new FleetTask(RUN,"endpoint-b","endpoints",1,1,0,"prefix/2");
        var ca=candidate(directory,a,10);var cb=candidate(directory,b,20);
        assertEquals(List.of(b,a),FleetEmissionRecovery.validateScope(RUN,List.of(a,b),List.of(ca,cb),directory));
        assertThrows(IllegalArgumentException.class,()->FleetEmissionRecovery.validateScope(RUN,List.of(a,b),List.of(ca,ca),directory));
        assertThrows(IllegalArgumentException.class,()->FleetEmissionRecovery.validateScope(RUN,List.of(a),List.of(cb),directory));
        assertThrows(IllegalArgumentException.class,()->FleetEmissionRecovery.validateScope(UUID.randomUUID().toString(),List.of(a,b),List.of(ca),directory));
        a.complete(directory.resolve(a.id()),0,0,11);
        assertThrows(IllegalArgumentException.class,()->FleetEmissionRecovery.validateScope(RUN,List.of(a,b),List.of(ca),directory));
    }
    @Test void interruptedBinaryOutputCanBeResumedWithoutDiscardingSavedBytes(@TempDir Path directory)throws Exception {
        Path bytes=directory.resolve("table.partial"),log=directory.resolve("ssh.log");
        assertThrows(java.io.IOException.class,()->FleetEmitter.runShell("printf abc; exit 1",directory,null,bytes,log,null,null,true));
        assertEquals("abc",Files.readString(bytes));
        FleetEmitter.runShell("printf def",directory,null,bytes,log,null,null,true);
        assertEquals("abcdef",Files.readString(bytes));
    }
}
