package br.unb.cic.td20.runtime;

/**
 * Shared, configurable CPU and buffer sizing for the independent checker.
 */
public final class CheckerParallelism {

    private CheckerParallelism() {
    }

    public static int workers() {
        return positiveProperty("td20.checkerWorkers", Runtime.getRuntime().availableProcessors());
    }

    public static int lookupBatchSize() {
        return positiveProperty("td20.certificateLookupBatchSize", 32_768);
    }

    public static int readWorkers() {
        return positiveProperty("td20.certificateReadWorkers", workers());
    }

    public static int scanWorkers() {
        return positiveProperty("td20.reconstructionScanWorkers", workers());
    }

    public static int scanRangesPerWorker() {
        return positiveProperty("td20.reconstructionScanRangesPerWorker", 4);
    }

    public static int producerBatchSize() {
        return positiveProperty("td20.certificateProducerBatchKeys", 4096);
    }

    public static long bufferedBytes() {
        final long defaultMB = Math.max(128, Math.min(4096, Runtime.getRuntime().maxMemory() / (3L << 20)));
        return Math.multiplyExact(positiveLongProperty("td20.certificateBufferedMB", defaultMB), 1L << 20);
    }

    public static long readCacheBytes() {
        final long defaultMB = Math.max(64, Math.min(4096, Runtime.getRuntime().maxMemory() / (4L << 20)));
        return Math.multiplyExact(positiveLongProperty("td20.certificateReadCacheMB", defaultMB), 1L << 20);
    }

    public static int maxOpenFiles() {
        return positiveProperty("td20.certificateMaxOpenFiles", 512);
    }

    public static int reconstructionBufferKeys() {
        return positiveProperty("td20.reconstructionBufferKeys", 16_777_216);
    }

    public static int writeBatchEntries() {
        final String configured = System.getProperty("td20.dbWriteBatchEntries");
        final int entries = configured == null ? 524_288 : Integer.parseInt(configured.trim());
        if (entries < 1) {
            throw new IllegalArgumentException("td20.dbWriteBatchEntries must be positive");
        }

        return entries;
    }

    private static long positiveLongProperty(final String name, final long defaultValue) {
        final long value = Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return value;
    }

    private static int positiveProperty(final String name, final int defaultValue) {
        final int value = Integer.getInteger(name, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return value;
    }
}
