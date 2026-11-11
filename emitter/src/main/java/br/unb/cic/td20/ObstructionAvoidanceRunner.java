package br.unb.cic.td20;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/** Runs final reconstruction after regenerating endpoint witness misses. */
public final class ObstructionAvoidanceRunner {
    private ObstructionAvoidanceRunner() { }
    public static void main(String[] arguments) throws IOException {
        var args = new ArrayList<String>();
        for (String arg : arguments) if (arg.startsWith("--phase="))
            throw new IllegalArgumentException("This entry point selects the avoidance phase");
        args.add("--phase=avoidance"); args.addAll(Arrays.asList(arguments));
        br.unb.cic.td20.emission.WitnessEmitter.main(args.toArray(String[]::new));
    }
}
