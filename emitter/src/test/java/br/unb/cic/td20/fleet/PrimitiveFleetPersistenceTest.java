package br.unb.cic.td20.fleet;

import br.unb.cic.td20.avoidance.DiskStateSet;
import br.unb.cic.td20.avoidance.ErikssonCheckpoint;
import br.unb.cic.td20.avoidance.ErikssonInverseReconstruction;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.PrimitivePair;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.*;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveFleetPersistenceTest {
    private static final String RUN = "a18822ed-c0fd-4bbc-a5bf-cd9fdcf00b2a";
    private final Map<String,String> properties = new HashMap<>();
    @BeforeEach void boundedPersistence() {
        for (var entry : Map.of("td20.reconstructionWriters","2","td20.reconstructionBufferKeys","128",
                "td20.reconstructionQueueBatches","16","td20.fleetBatchWorkers","2").entrySet()) {
            properties.put(entry.getKey(),System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(),entry.getValue());
        }
    }
    @AfterEach void restoreProperties() {
        properties.forEach((key,value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key,value); });
    }

    @Test void firstWaveMatchesTheLegacyFullLengthInverseClassSetAndResumes(@TempDir Path root) throws Exception {
        var endpoint = power(16,2); var legacy = LegacyPairs.toLegacy(endpoint);
        var expected = new TreeSet<String>();
        ErikssonInverseReconstruction.preimages(legacy,2,parent ->
                expected.add(HexFormat.of().formatHex(CertificateCodec.keyFor(parent.canonicalRepresentative()))));
        assertFalse(expected.isEmpty());
        Path job = Files.createDirectory(root.resolve("endpoint-job"));
        var task = new FleetTask(RUN,"endpoint-fixture","endpoints",1,1,0);
        task.write(job); task.complete(job,1,0,1);
        Files.createDirectories(job.resolve("work"));
        try (var states = new DiskStateSet(job.resolve("work/endpoint-residuals"))) {
            states.add(legacy,8); assertEquals(1,states.seal());
        }
        var wave = ReconstructionBatches.prepareWave(root.resolve("prepared"),List.of(job),line -> { });
        assertEquals(1,wave.endpoints()); assertEquals(expected.size(),wave.afterTwo());
        var actual = new TreeSet<String>();
        try (var states = DiskStateSet.openSealedForScan(wave.directory().resolve("after-two"),expected.size())) {
            states.forEach(state -> {
                assertEquals(18,state.primitivePair().size()); assertEquals(9,state.maximumMoves());
                actual.add(HexFormat.of().formatHex(CertificateCodec.keyFor(state.primitivePair())));
            });
        }
        assertEquals(expected,actual);
        assertEquals(wave,ReconstructionBatches.prepareWave(root.resolve("prepared"),List.of(job),line -> { }));
        assertEquals(ErikssonCheckpoint.Stage.AFTER_TWO,ErikssonCheckpoint.read(wave.directory()).stage());
    }

    @Test void primitiveSortedSlicesKeepTheVersionThreeKeysAndLegacyRecoveryEncoding(@TempDir Path root) throws Exception {
        var inputs = List.of(power(18,4),power(18,10),power(18,16));
        var expectedRaw = new ArrayList<byte[]>();
        try (var states = new DiskStateSet(root.resolve("source"))) {
            for (var pair : inputs) states.add(pair,9);
            assertEquals(3,states.seal());
            states.forEach(state -> {
                byte[] legacy = CertificateCodec.keyFor(LegacyPairs.toLegacy(state.primitivePair()));
                expectedRaw.add(ByteBuffer.allocate(legacy.length+3).put((byte)3).put((byte)9).put((byte)18).put(legacy).array());
            });
            Path jobs = Files.createDirectory(root.resolve("jobs"));
            var tasks = ReconstructionBatches.split(RUN,jobs,states,7,2);
            assertEquals(List.of(2L,1L),tasks.stream().map(FleetTask::inputCount).toList());
            var actualRaw = new ArrayList<byte[]>();
            for (var task : tasks) {
                try (var options = new Options(); var database = RocksDB.openReadOnly(options,
                        jobs.resolve(task.id()).resolve("input/after-two").toString()); var iterator = database.newIterator()) {
                    for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                        assertEquals(0,iterator.value().length); actualRaw.add(iterator.key());
                    }
                    iterator.status();
                }
            }
            assertEquals(expectedRaw.size(),actualRaw.size());
            for (int i = 0; i < expectedRaw.size(); i++) assertArrayEquals(expectedRaw.get(i),actualRaw.get(i));
            assertEquals(tasks,ReconstructionBatches.split(RUN,jobs,states,7,2));
        }
        var expectedRecovery = new HashSet<String>();
        for (var pair : inputs) expectedRecovery.add(Base64.getEncoder().encodeToString(
                CertificateCodec.keyFor(LegacyPairs.toLegacy(pair)))+":9");
        assertEquals(expectedRecovery,FleetEmissionRecovery.residualKeys(root.resolve("source"),3));
        assertThrows(IllegalStateException.class, () -> FleetEmissionRecovery.residualKeys(root.resolve("source"),4));
    }

    private static PrimitivePair power(int n,int shift) {
        int[] omega = new int[n]; for (int i = 0; i < n; i++) omega[i] = (i + shift) % n;
        return PrimitivePair.of(omega,IntStream.range(0,n).toArray()).canonicalRepresentative();
    }
}
