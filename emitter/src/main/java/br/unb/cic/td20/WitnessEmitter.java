package br.unb.cic.td20;

import java.io.IOException;

/** Command-line entry point; implementation lives in {@link br.unb.cic.td20.emission.WitnessEmitter}. */
public final class WitnessEmitter {
    private WitnessEmitter() { }

    public static void main(final String[] args) throws IOException {
        br.unb.cic.td20.emission.WitnessEmitter.main(args);
    }
}
