package br.unb.cic.td20.certificate;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CertificateDatabaseConsolidatorTest {

    @Test
    void rawUnionPreservesOpaqueValuesAndBothFamilies(@TempDir final Path directory) throws Exception {
        val a = data(directory.resolve("a"), 0, 4000);
        val b = data(directory.resolve("b"), 4000, 4000);
        val inputs = List.of(new CertificateDatabaseConsolidator.Input(a, 4000, 4000),
                new CertificateDatabaseConsolidator.Input(b, 4000, 4000));
        final Path output = directory.resolve("union");
        val result = CertificateDatabaseConsolidator.consolidate(output, inputs, 16384, message -> {
        });
        assertEquals(new CertificateDatabaseConsolidator.Result(8000, 8000, 2, 16384), result);
        assertEquals(result, CertificateDatabaseConsolidator.inspect(output));

        try (val options = new DBOptions();
            val family = new ColumnFamilyOptions()) {
            val handles = new ArrayList<ColumnFamilyHandle>();

            try (val db = RocksDB.openReadOnly(options,
                    output.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, family),
                            new ColumnFamilyDescriptor(AsyncCertificateWriter.OBSTRUCTIONS, family)),
                    handles)) {
                for (int i = 0; i < 8000; i++) {
                    assertArrayEquals(value(i), db.get(handles.getFirst(), key(i)));
                    assertArrayEquals(new byte[0], db.get(handles.getLast(), key(i)));
                }

                assertTrue(db.getPropertiesOfAllTables(handles.getFirst()).size() > 1,
                        "Target size produces multiple compacted SSTs");
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }

        assertTrue(Files.isRegularFile(a.resolve("CURRENT")));
        assertTrue(Files.isRegularFile(b.resolve("CURRENT")));
    }

    @Test
    void duplicateKeysAndWrongInventoryRejectPublication(@TempDir final Path directory) throws Exception {
        val a = data(directory.resolve("a"), 0, 10);
        val b = data(directory.resolve("b"), 0, 10);
        assertThrows(Exception.class,
                () -> CertificateDatabaseConsolidator.consolidate(directory.resolve("duplicates"),
                        List.of(new CertificateDatabaseConsolidator.Input(a, 10, 10),
                                new CertificateDatabaseConsolidator.Input(b, 10, 10)),
                        16384,
                        message -> {
                        }));
        assertFalse(Files.exists(directory.resolve("duplicates")));
        assertThrows(Exception.class,
                () -> CertificateDatabaseConsolidator.consolidate(directory.resolve("wrong"),
                        List.of(new CertificateDatabaseConsolidator.Input(a, 11, 10)),
                        16384,
                        message -> {
                        }));
        assertFalse(Files.exists(directory.resolve("wrong")));
    }

    @Test
    void corruptOrMissingSchemaAndMissingTablesFail(@TempDir final Path directory) throws Exception {
        final Path a = data(directory.resolve("a"), 0, 2);
        final Path output = directory.resolve("valid");
        CertificateDatabaseConsolidator.consolidate(output, List.of(new CertificateDatabaseConsolidator.Input(a, 2, 2)), 16384, message -> {
        });

        try (val files = Files.list(output)) {
            Files.delete(files.filter(p -> p.toString().endsWith(".sst")).findFirst().orElseThrow());
        }

        assertThrows(Exception.class, () -> CertificateDatabaseConsolidator.inspect(output));

        final Path missing = directory.resolve("missing");
        assertThrows(Exception.class,
                () -> CertificateDatabaseConsolidator.consolidate(directory.resolve("bad"),
                        List.of(new CertificateDatabaseConsolidator.Input(missing, 0, 0)),
                        16384,
                        message -> {
                        }));
    }

    private static Path data(final Path path, final int start, final int count) throws Exception {
        RocksDB.loadLibrary();

        try (val options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            val family = new ColumnFamilyOptions().setWriteBufferSize(16384);
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            val handles = new ArrayList<ColumnFamilyHandle>();

            try (val db = RocksDB.open(options,
                    path.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, family),
                            new ColumnFamilyDescriptor(AsyncCertificateWriter.OBSTRUCTIONS, family)),
                    handles)) {
                db.put(handles.getFirst(), writes, AsyncCertificateWriter.SCHEMA_KEY, AsyncCertificateWriter.SCHEMA_VALUE);
                for (int i = start; i < start + count; i++) {
                    db.put(handles.getFirst(), writes, key(i), value(i));
                    db.put(handles.getLast(), writes, key(i), new byte[0]);
                }

                db.flush(flush, handles);
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }

        return path;
    }

    private static byte[] key(final int i) {
        return new byte[] { 0, (byte) (i >>> 16), (byte) (i >>> 8), (byte) i };
    }

    private static byte[] value(final int i) {
        val value = new byte[64];
        new Random(i).nextBytes(value);

        return value;
    }
}
