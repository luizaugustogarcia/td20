package br.unb.cic.td20.runtime;

/** Shared, configurable CPU and buffer sizing for the independent checker. */
public final class CheckerParallelism {
    private CheckerParallelism() { }

    public static int workers() {
        return positiveProperty("td20.checkerWorkers", Runtime.getRuntime().availableProcessors());
    }

    public static int lookupBatchSize() {
        return positiveProperty("td20.certificateLookupBatchSize", 262_144);
    }

    public static int reconstructionBufferKeys() {
        return positiveProperty("td20.reconstructionBufferKeys", 1_048_576);
    }

    public static int lookupPipelines() {
        return positiveProperty("td20.certificateLookupPipelines", Math.min(4, workers()));
    }

    private static int positiveProperty(final String name, final int defaultValue) {
        final int value = Integer.getInteger(name, defaultValue);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
