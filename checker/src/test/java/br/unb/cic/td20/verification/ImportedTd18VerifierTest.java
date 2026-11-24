package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ImportedTd18VerifierTest {

    private record Entry(byte[] key, byte[] value) {
    }

    private record Fixture(Path source, Path certificates, Path residuals, Path report, CyclicTargetPair hit, CyclicTargetPair missing) {

        ImportedTd18Verifier.Scope scope(final boolean markers) {
            return new ImportedTd18Verifier.Scope(source, certificates, residuals, report, 2, 1, 1, markers);
        }
    }

    @Test
    void exactImportScopeSucceedsWithoutObstructionMarkersAndLabelsItsLimitedScope(@TempDir final Path path) throws Exception {
        val fixture = fixture(path, false, false, false);
        val result = ImportedTd18Verifier.verify(fixture.scope(false));
        assertEquals(new ImportedTd18Verifier.Result(2, 1, 1, false), result);

        final List<String> report = Files.readAllLines(fixture.report());
        assertEquals(ImportedTd18Verifier.REPORT_FORMAT, report.getFirst());
        assertTrue(report.contains("scope=persisted-after-two-import-inventory"));
        assertTrue(report.contains("wholeEndpointRegeneration=false"));
        assertTrue(report.contains("fullTd20ProofVerification=false"));
        assertTrue(report.contains("residualSetEqualsMissingClasses=true"));
        assertTrue(report.contains("budgetNineHits=1"));
        assertThrows(IllegalArgumentException.class, () -> ImportedTd18Verifier.verify(fixture.scope(false)));
    }

    @Test
    void optionalMarkerInstallationCheckNeverSelectsTheScope(@TempDir final Path path) throws Exception {
        val absent = fixture(path.resolve("absent"), false, false, false);
        assertThrows(IllegalStateException.class, () -> ImportedTd18Verifier.verify(absent.scope(true)));
        assertFalse(Files.exists(absent.report()));

        val present = fixture(path.resolve("present"), false, false, true);
        assertTrue(ImportedTd18Verifier.verify(present.scope(true)).markersChecked());
    }

    @Test
    void missingResidualOrCoveredResidualCannotPublishCompleteReport(@TempDir final Path path) throws Exception {
        for (final boolean covered : new boolean[] { false, true }) {
            val fixture = fixture(path.resolve("covered-" + covered), !covered, covered, false);
            assertThrows(IllegalStateException.class, () -> ImportedTd18Verifier.verify(fixture.scope(false)));
            assertFalse(Files.exists(fixture.report()));
        }
    }

    @Test
    void invalidImportedResultFailsThroughActualReplay(@TempDir final Path path) throws Exception {
        val fixture = fixture(path, false, false, false);
        writeCertificates(fixture.certificates(), fixture.hit(), SortingCertificate.complete(List.of()), fixture.missing(), false);

        val error = assertThrows(IllegalStateException.class, () -> ImportedTd18Verifier.verify(fixture.scope(false)));
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("does not sort"));
        assertFalse(Files.exists(fixture.report()));
    }

    @Test
    void nonemptyValuesAndWrongDomainBudgetOrCanonicalKeysAreRejected(@TempDir final Path path) throws Exception {
        val pair = pair(3, 15);
        final byte[] good = DirectStateKey.encode(pair, 9);
        val wrongBudget = good.clone();
        wrongBudget[1] = 8;

        val wrongDomain = DirectStateKey.encode(pair(3, 17), 9);
        val malformed = good.clone();
        malformed[0] = 2;

        val variants = List.of(new Entry(good, new byte[] { 1 }),
                new Entry(wrongBudget, new byte[0]),
                new Entry(wrongDomain, new byte[0]),
                new Entry(malformed, new byte[0]),
                new Entry(noncanonicalKey(pair), new byte[0]));
        for (int i = 0; i < variants.size(); i++) {
            val fixture = fixture(path.resolve("variant-" + i), false, false, false);
            writeStates(fixture.source(), List.of(variants.get(i), state(fixture.missing())));
            assertThrows(IllegalStateException.class, () -> ImportedTd18Verifier.verify(fixture.scope(false)));
            assertFalse(Files.exists(fixture.report()));
        }
    }

    @Test
    void extraResidualInventoryAndIncorrectExpectedCountsAreRejected(@TempDir final Path path) throws Exception {
        val fixture = fixture(path, false, false, false);
        val extra = pair(5, 13);
        assertNotEquals(fixture.missing().extendedToricClassKey(), extra.extendedToricClassKey());
        writeStates(fixture.residuals(), List.of(state(fixture.missing()), state(extra)));
        assertThrows(IllegalStateException.class, () -> ImportedTd18Verifier.verify(fixture.scope(false)));
        assertFalse(Files.exists(fixture.report()));
        assertThrows(IllegalArgumentException.class,
                () -> new ImportedTd18Verifier.Scope(fixture.source(), fixture.certificates(), fixture.residuals(), fixture.report(), 2, 2, 1, false));
    }

    @Test
    void explicitArgumentsSupportStagedPathsAndExactSmallScopes() {
        val parsed = ImportedTd18Verifier.parse(new String[] { "--source=/tmp/source", "--certificates=/tmp/staged", "--residuals=/tmp/residuals", "--report=/tmp/report", "--expected-source=2", "--expected-hits=1", "--expected-residuals=1", "--check-markers" },
                Path.of("/tmp/project"));
        assertEquals(Path.of("/tmp/staged"), parsed.certificates());
        assertEquals(2, parsed.sourceCount());
        assertEquals(1, parsed.hitCount());
        assertTrue(parsed.checkMarkers());

        val defaults = ImportedTd18Verifier.parse(new String[0], Path.of("/tmp/project"));
        assertEquals(5_032_950, defaults.sourceCount());
        assertEquals(4_062_619, defaults.hitCount());
        assertEquals(970_331, defaults.residualCount());
    }

    private static Fixture fixture(final Path path,
            final boolean omitResidual,
            final boolean coveredResidual,
            final boolean markers) throws Exception {
        Files.createDirectories(path);

        val hit = pair(3, 15);
        val missing = pair(3, 3, 3, 9);
        assertNotEquals(hit.extendedToricClassKey(), missing.extendedToricClassKey());

        val fixture = new Fixture(path.resolve("source"),
                path.resolve("certificates"),
                path.resolve("residuals"),
                path.resolve("report.txt"),
                hit,
                missing);
        writeStates(fixture.source(), List.of(state(hit), state(missing)));
        writeStates(fixture.residuals(), omitResidual ? List.of() : List.of(state(coveredResidual ? hit : missing)));

        val three = hit.getOmega().stream().filter(cycle -> cycle.size() == 3).findFirst().orElseThrow();
        val result = SortingCertificate.closure(List.of(Cycle.of(Arrays.stream(three.getSymbols()).sorted().toArray())));
        assertEquals(9, CertificateValidator.requiredBudget(hit, result));
        writeCertificates(fixture.certificates(), hit, result, missing, markers);

        return fixture;
    }

    private static CyclicTargetPair pair(final int... partition) {
        val omega = new MulticyclePermutation();
        int cursor = 0;
        for (final int size : partition) {
            omega.add(Cycle.of(IntStream.range(cursor, cursor + size).toArray()));
            cursor += size;
        }

        return CyclicTargetPair.ofOwned(omega, Cycle.of(IntStream.range(0, cursor).toArray())).canonicalRepresentative();
    }

    private static Entry state(final CyclicTargetPair pair) {
        return new Entry(DirectStateKey.encode(pair, 9), new byte[0]);
    }

    private static byte[] noncanonicalKey(final CyclicTargetPair pair) {
        final int n = pair.getBeta().size();
        for (int shift = 1; shift < n; shift++) {
            val omega = new MulticyclePermutation();
            for (val cycle : pair.getOmega()) {
                final int rotation = shift;
                omega.add(Cycle.of(Arrays.stream(cycle.getSymbols()).map(symbol -> (symbol + rotation) % n).toArray()));
            }

            val rotated = CyclicTargetPair.ofOwned(omega, Cycle.of(IntStream.range(0, n).toArray()));
            final byte[] payload = CertificateCodec.keyFor(rotated);
            val key = new byte[3 + payload.length];
            key[0] = 3;
            key[1] = 9;
            key[2] = (byte) n;
            System.arraycopy(payload, 0, key, 3, payload.length);
            if (!Arrays.equals(key, DirectStateKey.encode(rotated, 9))) {
                return key;
            }
        }
        throw new AssertionError("Fixture has no noncanonical toric rotation");
    }

    private static void writeStates(final Path directory, final List<Entry> entries) throws Exception {
        RocksDB.loadLibrary();

        try (val options = new Options().setCreateIfMissing(true);
            val database = RocksDB.open(options, directory.toString());
            val writes = new WriteOptions().setDisableWAL(true);
            val flush = new FlushOptions().setWaitForFlush(true)) {
            try (val iterator = database.newIterator()) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    database.delete(writes, iterator.key());
                }

                iterator.status();
            }
            for (val entry : entries) {
                database.put(writes, entry.key(), entry.value());
            }

            database.flush(flush);
        }
    }

    private static void writeCertificates(final Path directory,
            final CyclicTargetPair pair,
            final SortingCertificate result,
            final CyclicTargetPair missing,
            final boolean marker) throws Exception {
        RocksDB.loadLibrary();

        try (val options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            val first = new ColumnFamilyOptions();
            val second = new ColumnFamilyOptions()) {
            val handles = new ArrayList<ColumnFamilyHandle>();
            val database = RocksDB.open(options,
                    directory.toString(),
                    List.of(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, first),
                            new ColumnFamilyDescriptor("obstructions".getBytes(StandardCharsets.UTF_8), second)),
                    handles);

            try (val writes = new WriteOptions().setDisableWAL(true);
                val flush = new FlushOptions().setWaitForFlush(true)) {
                database.put(handles.getFirst(),
                        writes,
                        "td20-schema".getBytes(StandardCharsets.UTF_8),
                        "eriksson-three-v1".getBytes(StandardCharsets.UTF_8));
                database.put(handles.getFirst(), writes, CertificateCodec.canonicalKeyFor(pair), CertificateCodec.resultFor(result));
                if (marker) {
                    database.put(handles.get(1), writes, CertificateCodec.canonicalKeyFor(missing), new byte[0]);
                }

                database.flush(flush, handles);
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
                database.close();
            }
        }
    }
}
