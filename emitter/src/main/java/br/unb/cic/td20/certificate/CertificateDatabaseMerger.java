package br.unb.cic.td20.certificate;

import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.runtime.BoundedTasks;
import lombok.val;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded disk union. One input is open at a time; every duplicate witness is replayed.
 */
public final class CertificateDatabaseMerger {

    private CertificateDatabaseMerger() {
    }

    public record Result(long inputWitnesses, long uniqueWitnesses, long advisoryMarkers) {
    }

    private record Entry(byte[] key, byte[] value) {
    }

    public static void main(final String[] args) throws IOException {
        if (args.length < 2 || !args[0].startsWith("--database=")) {
            System.out.println("merge --database=NEW_PATH INPUT_DB [INPUT_DB ...]");
            if (args.length == 1 && args[0].equals("--help")) {
                return;
            }
            throw new IllegalArgumentException("A fresh output and at least one input database are required");
        }

        System.out.println(merge(Path.of(args[0].substring(11)),
                Arrays.stream(args).skip(1).map(Path::of).toList()));
    }

    public static Result merge(final Path output, final List<Path> inputs) throws IOException {
        return merge(output, inputs, message -> {
        });
    }

    public static Result merge(final Path output,
            final List<Path> inputs,
            final java.util.function.Consumer<String> progress) throws IOException {
        return merge(output, inputs, progress, false);
    }

    /**
     * Replays new inputs into a prepared TD20 database; existing witnesses and advisory markers are retained.
     */
    public static Result append(final Path output,
            final List<Path> inputs,
            final java.util.function.Consumer<String> progress) throws IOException {
        return merge(output, inputs, progress, true);
    }

    private static Result merge(Path output,
            final List<Path> inputs,
            final java.util.function.Consumer<String> progress,
            final boolean append) throws IOException {
        RocksDB.loadLibrary();
        output = output.toAbsolutePath().normalize();
        if (!append && inputs.isEmpty() || append && !Files.isRegularFile(output.resolve("CURRENT")) || !append && Files.exists(output)) {
            throw new IllegalArgumentException(append ? "Append requires an existing certificate database" : "Merge requires inputs and a fresh output path");
        }
        if (append) {
            output = output.toRealPath();
        }

        val paths = new ArrayList<Path>();
        val seen = new HashSet<Path>();
        for (final Path path : inputs) {
            final Path real = path.toRealPath();
            if (output.startsWith(real) || real.startsWith(output) || !seen.add(real)) {
                throw new IllegalArgumentException("Overlapping or repeated merge paths");
            }

            paths.add(real);
        }

        val read = new LongAdder();
        val unique = new LongAdder();
        long markers = 0;
        Files.createDirectories(output.getParent());

        try (val reporter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("merge-progress").factory());
            val writer = new AsyncCertificateWriter(output, 60)) {
            reporter.scheduleWithFixedDelay(() -> progress.accept("inputWitnessesReplayed=" + read.sum() + " uniqueWitnesses=" + unique.sum() + " total=unknown until exhaustive merge completes"),
                    0,
                    10,
                    java.util.concurrent.TimeUnit.SECONDS);
            for (final Path path : paths) {
                try (val source = new Cursor(path, 0);
                    val tasks = new BoundedTasks(Runtime.getRuntime().availableProcessors(), "certificate-merge-")) {
                    markers = Math.addExact(markers, source.validateMarkers());

                    val batch = new ArrayList<Entry>(1024);
                    while (source.key != null) {
                        tasks.check();
                        batch.add(new Entry(source.key, source.iterator.value()));
                        source.advance();
                        if (batch.size() == 1024) {
                            submit(tasks, writer, batch, read, unique);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        submit(tasks, writer, batch, read, unique);
                    }
                }

                // All records in one source have distinct keys. Join and flush
                // before the next source so unique counts and duplicate lookups
                // cannot depend on asynchronous writer timing.
                writer.flush();
            }
        } catch (final RocksDBException error) {
            throw new IllegalStateException("Cannot read certificate markers", error);
        }

        val result = new Result(read.sum(), unique.sum(), markers);
        progress.accept("Merge replay complete: inputWitnesses=" + result.inputWitnesses + " uniqueWitnesses=" + result.uniqueWitnesses + " duplicates=" + (result.inputWitnesses - result.uniqueWitnesses));

        final List<String> report = List.of(append ? "td20-certificate-append-v1" : "td20-certificate-merge-v1",
                "inputWitnesses=" + result.inputWitnesses,
                "uniqueWitnesses=" + result.uniqueWitnesses,
                "advisoryMarkersOmitted=" + result.advisoryMarkers);
        if (append) {
            final Path temporary = Files.createTempFile(output, ".append-", ".tmp");

            try {
                Files.write(temporary, report);
                Files.move(temporary,
                        output.resolve("append-v1.txt"),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } else {
            Files.write(output.resolve("merge-v1.txt"), report, StandardOpenOption.CREATE_NEW);
        }

        return result;
    }

    private static void submit(final BoundedTasks tasks,
            final AsyncCertificateWriter writer,
            final List<Entry> groups,
            final LongAdder read,
            final LongAdder unique) {
        val batch = List.copyOf(groups);
        tasks.submit(() -> {
            val pairs = new ArrayList<PrimitivePair>(batch.size());
            val results = new ArrayList<PrimitiveCertificate>(batch.size());
            for (val entry : batch) {
                val pair = CertificateCodec.primitivePairForKey(entry.key);
                val result = CertificateCodec.decodePrimitiveResult(entry.value);
                final int budget = CertificateValidator.requiredBudget(pair, result);
                if (budget > ErikssonRoute.ROOT_BUDGET) {
                    throw new IllegalArgumentException("Input witness exceeds the root budget");
                }

                pairs.add(pair);
                results.add(result);
            }

            val existing = writer.findPrimitiveResults(pairs, ErikssonRoute.ROOT_BUDGET);
            for (int i = 0; i < batch.size(); i++) {
                if (existing.get(i).isEmpty()) {
                    unique.increment();
                }

                read.increment();
                writer.enqueue(pairs.get(i), results.get(i), ErikssonRoute.ROOT_BUDGET);
            }
        });
    }

    private static final class Cursor implements AutoCloseable {

        final int ordinal;
        final DBOptions options = new DBOptions().setMaxOpenFiles(8);
        final ColumnFamilyOptions familyOptions = new ColumnFamilyOptions();
        final List<ColumnFamilyHandle> handles = new ArrayList<>();
        final ReadOptions reads = new ReadOptions().setFillCache(false);
        RocksDB database;
        RocksIterator iterator;
        byte[] key;
        boolean closed;

        Cursor(final Path directory, final int ordinal) {
            this.ordinal = ordinal;

            try (val listing = new Options()) {
                val families = RocksDB.listColumnFamilies(listing, directory.toString());
                if (families.size() != 2
                        || families.stream().noneMatch(x -> Arrays.equals(x, RocksDB.DEFAULT_COLUMN_FAMILY))
                        || families.stream().noneMatch(x -> Arrays.equals(x, AsyncCertificateWriter.OBSTRUCTIONS))) {
                    throw new IllegalArgumentException("Wrong certificate column families: " + directory);
                }

                database = RocksDB.openReadOnly(options,
                        directory.toString(),
                        List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions),
                                new ColumnFamilyDescriptor(AsyncCertificateWriter.OBSTRUCTIONS, familyOptions)),
                        handles);
                if (!Arrays.equals(database.get(handles.getFirst(), AsyncCertificateWriter.SCHEMA_KEY),
                        AsyncCertificateWriter.SCHEMA_VALUE)) {
                    throw new IllegalArgumentException("Incompatible certificate schema: " + directory);
                }

                iterator = database.newIterator(handles.getFirst(), reads);
                iterator.seekToFirst();
                current();
            } catch (final RocksDBException | RuntimeException error) {
                close();
                throw new IllegalStateException("Cannot read " + directory, error);
            }
        }

        long validateMarkers() throws RocksDBException {
            long count = 0;

            try (val markers = database.newIterator(handles.get(1), reads)) {
                for (markers.seekToFirst(); markers.isValid(); markers.next()) {
                    if (markers.value().length != 0) {
                        throw new IllegalArgumentException("Malformed advisory marker");
                    }

                    CertificateCodec.primitivePairForKey(markers.key());
                    count = Math.addExact(count, 1);
                }

                markers.status();
            }

            return count;
        }

        void advance() {
            iterator.next();
            current();
        }

        void current() {
            if (iterator.isValid() && Arrays.equals(iterator.key(), AsyncCertificateWriter.SCHEMA_KEY)) {
                iterator.next();
            }

            key = iterator.isValid() ? iterator.key() : null;

            try {
                iterator.status();
            } catch (final RocksDBException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            closed = true;
            if (iterator != null) {
                iterator.close();
            }

            handles.forEach(ColumnFamilyHandle::close);
            if (database != null) {
                database.close();
            }

            reads.close();
            familyOptions.close();
            options.close();
        }
    }
}
