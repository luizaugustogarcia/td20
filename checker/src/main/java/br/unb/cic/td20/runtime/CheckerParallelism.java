package br.unb.cic.td20.runtime;

/** Shared, configurable CPU and buffer sizing for the independent checker. */
public final class CheckerParallelism {
    private CheckerParallelism() { }

    public static int workers() {
        return positiveProperty("td20.checkerWorkers", Runtime.getRuntime().availableProcessors());
    }

    public static int lookupBatchSize() {
        // CertificateStore splits each batch across CPU workers before multiGet.
        // Keep about 262K keys per native call, with a bounded aggregate batch.
        return positiveProperty("td20.certificateLookupBatchSize",
                Math.multiplyExact(Math.min(workers(), 32), 262_144));
    }

    public static int reconstructionBufferKeys() {
        return positiveProperty("td20.reconstructionBufferKeys", 16_777_216);
    }

    public static int writeBatchEntries() {
        String configured = System.getProperty("td20.dbWriteBatchEntries");
        int entries = configured == null ? 524_288 : Integer.parseInt(configured.trim());
        if (entries < 1) throw new IllegalArgumentException(
                "td20.dbWriteBatchEntries must be positive");
        return entries;
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
