package br.unb.cic.td20.avoidance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Emission checkpoint inventory; independent proof verification regenerates these sets. */
public record ReconstructionCheckpoint(long endpoints, long intermediates) {
    private static final String VERSION = "td20-reconstruction-checkpoint-v1";
    private static final String FILE = "reconstruction-checkpoint.txt";

    public ReconstructionCheckpoint {
        if (endpoints < 0 || intermediates < 0)
            throw new IllegalArgumentException("Nonnegative checkpoint counts required");
    }

    public void write(Path work) {
        Path temporary = work.resolve(FILE + ".tmp");
        try {
            Files.write(temporary, List.of(VERSION, "endpoints=" + endpoints, "intermediates=" + intermediates));
            Files.move(temporary, work.resolve(FILE), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) { throw new IllegalStateException("Cannot write reconstruction checkpoint", error); }
    }

    public static ReconstructionCheckpoint read(Path work) {
        try {
            var lines = Files.readAllLines(work.resolve(FILE));
            if (lines.size() != 3 || !lines.get(0).equals(VERSION)
                    || !lines.get(1).matches("endpoints=[0-9]+")
                    || !lines.get(2).matches("intermediates=[0-9]+"))
                throw new IllegalArgumentException("Malformed reconstruction checkpoint inventory");
            return new ReconstructionCheckpoint(Long.parseLong(lines.get(1).substring(10)),
                    Long.parseLong(lines.get(2).substring(14)));
        } catch (IOException error) { throw new IllegalStateException("Missing reconstruction checkpoint inventory", error); }
    }
}
