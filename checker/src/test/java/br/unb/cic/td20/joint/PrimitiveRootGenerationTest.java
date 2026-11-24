package br.unb.cic.td20.joint;

import br.unb.cic.td20.certificate.CertificateCodec;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveRootGenerationTest {

    @Test
    void primitiveRootOutputPreservesClassesAndAllInventoryCounts() throws Exception {
        for (final int[] partition : new int[][] { { 7 }, { 5, 2, 2 }, { 4, 3, 2 } }) {
            final Set<String> legacy = ConcurrentHashMap.newKeySet(), primitive = ConcurrentHashMap.newKeySet();
            val expected = ErikssonRootGenerator.generate(partition,
                    2,
                    () -> {
                    },
                    pair -> assertTrue(legacy.add(HexFormat.of().formatHex(CertificateCodec.keyFor(pair)))));
            val actual = ErikssonRootGenerator.generatePrimitive(partition,
                    2,
                    () -> {
                    },
                    pair -> {
                        assertTrue(pair.isFixedPointFree());
                        assertTrue(primitive.add(HexFormat.of().formatHex(pair.canonicalKey())));
                    });
            assertEquals(expected, actual);
            assertEquals(legacy, primitive);
        }
    }
}
