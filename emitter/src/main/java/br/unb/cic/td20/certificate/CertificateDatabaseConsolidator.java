package br.unb.cic.td20.certificate;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.rocksdb.*;

/** Physical union of disjoint, closed emission databases. Values remain opaque. */
public final class CertificateDatabaseConsolidator {
    public static final long TARGET_SST_BYTES = 1L << 30;
    public record Input(Path directory, long witnesses, long markers) {
        public Input { if (witnesses < 0 || markers < 0) throw new IllegalArgumentException("Negative inventory"); }
    }
    public record Result(long witnesses, long markers, int inputs, long targetSstBytes) { }
    private record Range(int family, byte[] start, byte[] end, int ordinal) { }
    private CertificateDatabaseConsolidator() { }

    public static Result consolidate(Path output, List<Input> inputs, long target, Consumer<String> progress) throws Exception {
        RocksDB.loadLibrary();
        output = output.toAbsolutePath().normalize();
        if (inputs.isEmpty() || target < 1024 || Files.exists(output)) throw new IllegalArgumentException("Fresh output and nonempty inputs required");
        long expectedWitnesses = 0, expectedMarkers = 0; var unique = new HashSet<Path>();
        for (var input : inputs) {
            Path real = input.directory().toRealPath();
            if (!unique.add(real) || output.startsWith(real) || real.startsWith(output)) throw new IllegalArgumentException("Overlapping inputs");
            expectedWitnesses = Math.addExact(expectedWitnesses,input.witnesses());
            expectedMarkers = Math.addExact(expectedMarkers,input.markers());
        }
        Files.createDirectories(output.getParent());
        Path staging = Files.createDirectory(output.resolveSibling("."+output.getFileName()+"-"+UUID.randomUUID()+".partial"));
        Path tables = Files.createDirectory(staging.resolve("exported-tables"));
        int cpus = Integer.getInteger("td20.consolidationWorkers",Runtime.getRuntime().availableProcessors());
        if (cpus < 1) throw new IllegalArgumentException("Positive consolidation workers required");
        var copied = new AtomicLong(); long total = Math.addExact(expectedWitnesses,expectedMarkers);
        try (var reporter = Executors.newSingleThreadScheduledExecutor();
             var workers = Executors.newFixedThreadPool(cpus);
             var destination = new Database(staging,false,target)) {
            reporter.scheduleWithFixedDelay(() -> progress.accept("Raw KV consolidation: processed="+copied.get()+"/"+total+"; replay=none"),0,10,TimeUnit.SECONDS);
            try (var writes = new WriteOptions().setDisableWAL(true); var flush = new FlushOptions().setWaitForFlush(true)) {
                destination.db.put(destination.handles.getFirst(),writes,AsyncCertificateWriter.SCHEMA_KEY,AsyncCertificateWriter.SCHEMA_VALUE);
                destination.db.flush(flush,destination.handles);
            }
            int index = 0;
            for (var input : inputs) {
                try (var source = new Database(input.directory(),true,target)) {
                    var ranges = source.ranges(cpus); var futures = new ArrayList<Future<Long>>(); final int inputIndex = index++;
                    for (var range : ranges) futures.add(workers.submit(() -> copy(source,destination,range,tables.resolve(inputIndex+"-"+range.ordinal()+".sst"),copied)));
                    long[] count = new long[2];
                    try { for (int i = 0; i < futures.size(); i++) count[ranges.get(i).family()] = Math.addExact(count[ranges.get(i).family()],futures.get(i).get()); }
                    catch (Exception | Error error) {
                        workers.shutdownNow(); boolean interrupted = false;
                        for (;;) try { if (workers.awaitTermination(1,TimeUnit.SECONDS)) break; } catch (InterruptedException e) { interrupted = true; }
                        if (interrupted) Thread.currentThread().interrupt(); throw error;
                    }
                    if (count[0] != input.witnesses() || count[1] != input.markers()) throw new IllegalStateException("Source KV inventory differs: "+input.directory()+"; actual="+Arrays.toString(count));
                }
            }
            progress.accept("Raw KV copies complete: processed="+copied.get()+"/"+total+"; compacting to target SST bytes="+target);
            try (var flush = new FlushOptions().setWaitForFlush(true); var compact = new CompactRangeOptions().setChangeLevel(true).setTargetLevel(1)
                    .setBottommostLevelCompaction(CompactRangeOptions.BottommostLevelCompaction.kForce).setMaxSubcompactions(Math.min(cpus,16))) {
                destination.db.flush(flush,destination.handles);
                for (var handle : destination.handles) destination.db.compactRange(handle,null,null,compact);
                destination.db.flush(flush,destination.handles);
            }
            if (destination.count(0) != Math.addExact(expectedWitnesses,1) || destination.count(1) != expectedMarkers)
                throw new IllegalStateException("Compacted KV counts differ; source classes overlap or data are missing");
        }
        Files.delete(tables);
        var result = new Result(expectedWitnesses,expectedMarkers,inputs.size(),target);
        Files.write(staging.resolve("consolidation-v1.txt"),List.of("td20-raw-certificate-consolidation-v1","witnesses="+expectedWitnesses,"markers="+expectedMarkers,
                "inputs="+inputs.size(),"targetSstBytes="+target,"replay=none"),StandardOpenOption.CREATE_NEW);
        Files.move(staging,output,StandardCopyOption.ATOMIC_MOVE);
        progress.accept("Raw KV consolidation complete: processed="+total+"/"+total+"; "+result); return result;
    }

    public static Result inspect(Path directory) throws IOException {
        RocksDB.loadLibrary(); var lines = Files.readAllLines(directory.resolve("consolidation-v1.txt"));
        if (lines.size() != 6 || !lines.getFirst().equals("td20-raw-certificate-consolidation-v1") || !lines.get(5).equals("replay=none"))
            throw new IllegalArgumentException("Incomplete consolidation record");
        var result = new Result(Long.parseLong(field(lines,1,"witnesses")),Long.parseLong(field(lines,2,"markers")),
                Integer.parseInt(field(lines,3,"inputs")),Long.parseLong(field(lines,4,"targetSstBytes")));
        if (result.witnesses()<0 || result.markers()<0 || result.inputs()<1 || result.targetSstBytes()<1024) throw new IllegalArgumentException("Invalid consolidation counts");
        try (var database = new Database(directory,true,result.targetSstBytes())) {
            if (database.count(0) != Math.addExact(result.witnesses(),1) || database.count(1) != result.markers()) throw new IllegalStateException("Missing consolidated KV records");
            database.db.verifyChecksum();
        } catch (RocksDBException error) { throw new IllegalStateException("Cannot inspect consolidated database",error); }
        return result;
    }
    private static String field(List<String> lines,int index,String name) {
        if (!lines.get(index).startsWith(name+"=")) throw new IllegalArgumentException("Missing "+name); return lines.get(index).substring(name.length()+1);
    }
    private static long copy(Database source,Database destination,Range range,Path path,AtomicLong copied) throws Exception {
        long count = 0, pending = 0;
        try (var reads = new ReadOptions().setFillCache(false).setReadaheadSize(4L<<20); var iterator = source.db.newIterator(source.handles.get(range.family()),reads);
             var environment = new EnvOptions(); var options = new Options().setCompressionType(CompressionType.ZSTD_COMPRESSION);
             var writer = new SstFileWriter(environment,options)) {
            if (range.start()==null) iterator.seekToFirst(); else iterator.seek(range.start());
            boolean opened = false;
            while (iterator.isValid()) {
                byte[] key = iterator.key(); if (range.end()!=null && Arrays.compareUnsigned(key,range.end())>=0) break;
                if (range.family()!=0 || !Arrays.equals(key,AsyncCertificateWriter.SCHEMA_KEY)) {
                    if (!opened) { writer.open(path.toString()); opened = true; }
                    writer.put(key,iterator.value()); count++;
                    if (++pending == 4096) { copied.addAndGet(pending); pending=0; if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
                }
                iterator.next();
            }
            iterator.status(); copied.addAndGet(pending);
            if (opened) {
                writer.finish();
                try (var ingest = new IngestExternalFileOptions().setMoveFiles(true).setAllowGlobalSeqNo(true).setWriteGlobalSeqno(false)) {
                    synchronized (destination) { destination.db.ingestExternalFile(destination.handles.get(range.family()),List.of(path.toString()),ingest); }
                }
            }
        }
        return count;
    }

    private static final class Database implements AutoCloseable {
        final DBOptions options; final ColumnFamilyOptions families; final List<ColumnFamilyHandle> handles = new ArrayList<>(); RocksDB db;
        Database(Path path,boolean readOnly,long target) throws RocksDBException {
            options = new DBOptions().setMaxOpenFiles(64); families = new ColumnFamilyOptions();
            if (!readOnly) {
                options.setCreateIfMissing(true).setCreateMissingColumnFamilies(true).setMaxBackgroundJobs(Math.max(2,Runtime.getRuntime().availableProcessors()));
                families.setNumLevels(2).setDisableAutoCompactions(true).setCompressionType(CompressionType.ZSTD_COMPRESSION)
                    .setTargetFileSizeBase(target).setMaxBytesForLevelBase(Math.max(64L<<20,target*64)).setLevel0SlowdownWritesTrigger(Integer.MAX_VALUE).setLevel0StopWritesTrigger(Integer.MAX_VALUE);
            }
            try {
                var descriptors = List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY,families),new ColumnFamilyDescriptor(AsyncCertificateWriter.OBSTRUCTIONS,families));
                if (readOnly) {
                    try (var listing = new Options()) {
                        var names = RocksDB.listColumnFamilies(listing,path.toString());
                        if (names.size()!=2 || names.stream().noneMatch(n->Arrays.equals(n,RocksDB.DEFAULT_COLUMN_FAMILY)) || names.stream().noneMatch(n->Arrays.equals(n,AsyncCertificateWriter.OBSTRUCTIONS)))
                            throw new IllegalArgumentException("Wrong certificate column families");
                    }
                    db = RocksDB.openReadOnly(options,path.toString(),descriptors,handles);
                    if (!Arrays.equals(db.get(handles.getFirst(),AsyncCertificateWriter.SCHEMA_KEY),AsyncCertificateWriter.SCHEMA_VALUE)) throw new IllegalArgumentException("Wrong certificate schema");
                } else db = RocksDB.open(options,path.toString(),descriptors,handles);
            } catch (RocksDBException | RuntimeException error) { close(); throw error; }
        }
        List<Range> ranges(int workers) {
            var result = new ArrayList<Range>();
            for (int family = 0; family < 2; family++) {
                final byte[] name = family==0 ? RocksDB.DEFAULT_COLUMN_FAMILY : AsyncCertificateWriter.OBSTRUCTIONS;
                var minima = new TreeSet<byte[]>(Arrays::compareUnsigned);
                for (var file : db.getLiveFilesMetaData()) if (Arrays.equals(file.columnFamilyName(),name)) minima.add(file.smallestKey());
                var sorted = new ArrayList<>(minima); int slices = Math.max(1,Math.min(workers,sorted.size())); byte[] start = null;
                for (int i = 1; i <= slices; i++) { byte[] end = i==slices ? null : sorted.get(i*sorted.size()/slices); result.add(new Range(family,start,end,result.size())); start=end; }
            }
            return result;
        }
        long count(int family) throws RocksDBException {
            if (!"0".equals(db.getProperty(handles.get(family),"rocksdb.num-files-at-level0"))) throw new IllegalStateException("Uncompacted consolidation");
            long count = 0; for (var table : db.getPropertiesOfAllTables(handles.get(family)).values()) count=Math.addExact(count,table.getNumEntries()); return count;
        }
        public void close() { handles.forEach(ColumnFamilyHandle::close); if (db!=null) db.close(); families.close(); options.close(); }
    }
}
