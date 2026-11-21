package br.unb.cic.td20.runtime;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBufferManager;
import org.rocksdb.ReadOptions;

/** Execution-wide cache and memtable accounting; native read temporaries need separate admission. */
public final class RocksDbMemoryBudget {
    private static RocksDbMemoryBudget shared;
    private final LRUCache cache;
    private final WriteBufferManager writeBuffers;
    private final long bytes, writeBytes;
    private final boolean strict, cacheMetadata;
    private int references;

    private RocksDbMemoryBudget() {
        RocksDB.loadLibrary();
        bytes = Math.multiplyExact(positive("td20.rocksDbMemoryMB", CheckerParallelism.readCacheBytes() / (1L << 20)), 1L << 20);
        writeBytes = Math.multiplyExact(positive("td20.rocksDbWriteBufferMB", Math.max(1, bytes / (8L << 20))), 1L << 20);
        if (writeBytes >= bytes) throw new IllegalArgumentException("RocksDB write-buffer budget must be smaller than its managed budget");
        strict = bool("td20.rocksDbStrictCapacity", true);
        cacheMetadata = bool("td20.certificateCacheIndexAndFilterBlocks", true);
        int shardBits = Integer.getInteger("td20.rocksDbCacheShardBits", -1);
        double priority = Double.parseDouble(System.getProperty("td20.rocksDbCacheHighPriorityRatio", "0.5"));
        if (shardBits < -1 || shardBits > 19 || !Double.isFinite(priority) || priority < 0 || priority > 1)
            throw new IllegalArgumentException("Invalid RocksDB cache sharding or priority ratio");
        cache = new LRUCache(bytes, shardBits, strict, priority);
        WriteBufferManager manager;
        try { manager = new WriteBufferManager(writeBytes, cache, bool("td20.rocksDbWriteStall", true)); }
        catch (RuntimeException | Error failure) { cache.close(); throw failure; }
        writeBuffers = manager;
        System.out.printf("RocksDB memory: managedMiB=%d memtableMiB=%d strictCapacity=%s cacheMetadata=%s cacheShardBits=%d highPriorityRatio=%s nativeTemporaries=separately-bounded%n",
                bytes / (1L << 20), writeBytes / (1L << 20), strict, cacheMetadata, shardBits, priority);
    }

    public static synchronized Lease acquire() {
        if (shared == null) shared = new RocksDbMemoryBudget();
        shared.references++; return new Lease(shared);
    }
    public static synchronized String progress() {
        if (shared == null) return "rocksDbMemory=closed";
        return "rocksDbManagedMiB=" + shared.cache.getUsage() / (1L << 20) + "/" + shared.bytes / (1L << 20)
                + " rocksDbPinnedMiB=" + shared.cache.getPinnedUsage() / (1L << 20)
                + " rocksDbMemtableLimitMiB=" + shared.writeBytes / (1L << 20);
    }
    public static final class Lease implements AutoCloseable {
        private final RocksDbMemoryBudget owner;
        private boolean closed;
        private Lease(RocksDbMemoryBudget owner) { this.owner = owner; }
        public LRUCache cache() { return owner.cache; }
        public WriteBufferManager writeBuffers() { return owner.writeBuffers; }
        public boolean fillCache() { return bool("td20.rocksDbFillCache", true); }
        public ReadOptions reads(String policy, boolean fallback) {
            return new ReadOptions().setFillCache(fillCache() && bool(policy, fallback));
        }
        public BlockBasedTableConfig tables() {
            return new BlockBasedTableConfig().setBlockCache(owner.cache)
                    .setCacheIndexAndFilterBlocks(owner.cacheMetadata).setCacheIndexAndFilterBlocksWithHighPriority(true)
                    .setPinL0FilterAndIndexBlocksInCache(false).setPinTopLevelIndexAndFilter(false);
        }
        @Override public void close() {
            synchronized (RocksDbMemoryBudget.class) {
                if (closed) return; closed = true;
                if (--owner.references == 0) {
                    owner.writeBuffers.close(); owner.cache.close(); shared = null;
                }
            }
        }
    }
    public static long positive(String name, long fallback) {
        long value = Long.parseLong(System.getProperty(name, Long.toString(fallback)));
        if (value < 1) throw new IllegalArgumentException(name + " must be positive"); return value;
    }
    private static boolean bool(String name, boolean fallback) {
        String value = System.getProperty(name, Boolean.toString(fallback));
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(name + " must be true or false");
        return Boolean.parseBoolean(value);
    }
}
