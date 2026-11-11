package br.unb.cic.td20.avoidance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Sealed inventory after reversing the tagged middle move. */
public record MiddleReconstructionCheckpoint(long endpoints, long intermediates,
                                             long propagatedIntermediates, long middles) {
    private static final String VERSION = "td20-middle-reconstruction-checkpoint-v1";
    private static final String FILE = "middle-reconstruction-checkpoint.txt";

    public MiddleReconstructionCheckpoint {
        if (endpoints < 0 || intermediates < 0 || propagatedIntermediates < 0 || middles < 0
                || propagatedIntermediates > intermediates)
            throw new IllegalArgumentException("Invalid middle reconstruction checkpoint counts");
    }

    public void write(Path work) {
        final Path temporary = work.resolve(FILE + ".tmp");
        try {
            Files.write(temporary, List.of(VERSION, "endpoints=" + endpoints,
                    "intermediates=" + intermediates,
                    "propagatedIntermediates=" + propagatedIntermediates,
                    "middles=" + middles));
            Files.move(temporary, work.resolve(FILE), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write middle reconstruction checkpoint", error);
        }
    }

    public static boolean exists(Path work) { return Files.isRegularFile(work.resolve(FILE)); }

    public static MiddleReconstructionCheckpoint read(Path work) {
        try {
            final var lines = Files.readAllLines(work.resolve(FILE));
            if (lines.size() != 5 || !lines.get(0).equals(VERSION)
                    || !lines.get(1).matches("endpoints=[0-9]+")
                    || !lines.get(2).matches("intermediates=[0-9]+")
                    || !lines.get(3).matches("propagatedIntermediates=[0-9]+")
                    || !lines.get(4).matches("middles=[0-9]+"))
                throw new IllegalArgumentException("Malformed middle reconstruction checkpoint inventory");
            return new MiddleReconstructionCheckpoint(
                    Long.parseLong(lines.get(1).substring(10)),
                    Long.parseLong(lines.get(2).substring(14)),
                    Long.parseLong(lines.get(3).substring(24)),
                    Long.parseLong(lines.get(4).substring(8)));
        } catch (IOException error) {
            throw new IllegalStateException("Missing middle reconstruction checkpoint inventory", error);
        }
    }
}
