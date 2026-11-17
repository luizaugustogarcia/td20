package br.unb.cic.td20.fleet;

import br.unb.cic.td20.joint.ErikssonRoute;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** A resumable transfer intent, never a completion receipt or a proof input. */
record FleetTransfer(String run, String task, String server, String attempt) {
    FleetTransfer {
        UUID.fromString(run); UUID.fromString(attempt);
        if (!task.matches("[a-z][a-z0-9-]*") || !server.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*"))
            throw new IllegalArgumentException("Invalid transfer identity");
    }
    void write(Path file) throws IOException {
        FleetFiles.write(file,List.of("td20-fleet-transfer-v1","route=" + ErikssonRoute.ID,"run=" + run,
                "task=" + task,"server=" + server,"attempt=" + attempt));
    }
    static FleetTransfer read(Path file) throws IOException {
        var lines = Files.readAllLines(file);
        if (lines.size() != 6 || !lines.getFirst().equals("td20-fleet-transfer-v1") || !lines.get(1).equals("route=" + ErikssonRoute.ID))
            throw new IllegalArgumentException("Incompatible or incomplete transfer intent");
        return new FleetTransfer(FleetFiles.field(lines,2,"run"),FleetFiles.field(lines,3,"task"),
                FleetFiles.field(lines,4,"server"),FleetFiles.field(lines,5,"attempt"));
    }
    String remoteDirectory(FleetServer node) {
        if (!server.equals(node.name())) throw new IllegalArgumentException("Transfer server mismatch");
        return node.directory() + "/" + run + "/" + server + "/jobs/" + task + "-" + attempt;
    }
}
