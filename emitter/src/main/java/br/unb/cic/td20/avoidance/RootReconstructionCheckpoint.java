package br.unb.cic.td20.avoidance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Sealed reconstructed-root inventory; independent proof verification regenerates it. */
public record RootReconstructionCheckpoint(long endpoints, long intermediates,
                                           long propagatedIntermediates, long roots) {
    private static final String VERSION = "td20-joint2-root-reconstruction-checkpoint-v1";
    private static final String FILE = "root-reconstruction-checkpoint.txt";

    public RootReconstructionCheckpoint {
        if (endpoints < 0 || intermediates < 0 || propagatedIntermediates < 0 || roots < 0
                || propagatedIntermediates > intermediates)
            throw new IllegalArgumentException("Invalid root reconstruction checkpoint counts");
    }

    public void write(Path work) {
        Path temporary = work.resolve(FILE + ".tmp");
        try {
            Files.write(temporary, List.of(VERSION, "endpoints=" + endpoints,
                    "intermediates=" + intermediates,
                    "propagatedIntermediates=" + propagatedIntermediates,
                    "roots=" + roots));
            Files.move(temporary, work.resolve(FILE), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) { throw new IllegalStateException("Cannot write root reconstruction checkpoint", error); }
    }

    public static boolean exists(Path work) { return Files.isRegularFile(work.resolve(FILE)); }

    public static RootReconstructionCheckpoint read(Path work) {
        try {
            var lines = Files.readAllLines(work.resolve(FILE));
            if (lines.size() != 5 || !lines.get(0).equals(VERSION)
                    || !lines.get(1).matches("endpoints=[0-9]+")
                    || !lines.get(2).matches("intermediates=[0-9]+")
                    || !lines.get(3).matches("propagatedIntermediates=[0-9]+")
                    || !lines.get(4).matches("roots=[0-9]+"))
                throw new IllegalArgumentException("Malformed root reconstruction checkpoint inventory");
            return new RootReconstructionCheckpoint(
                    Long.parseLong(lines.get(1).substring(10)),
                    Long.parseLong(lines.get(2).substring(14)),
                    Long.parseLong(lines.get(3).substring(24)),
                    Long.parseLong(lines.get(4).substring(6)));
        } catch (IOException error) { throw new IllegalStateException("Missing root reconstruction checkpoint inventory", error); }
    }
}
