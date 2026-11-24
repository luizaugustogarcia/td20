package br.unb.cic.td20.base;

import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.PrimitiveCertificate;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.OneLinePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "td20.gpuSmoke", matches = "true")
class GPUSortingSearchSmokeTest {

    @Test
    void primitiveBridgeReplaysProductionLengthStateWithAnArbitraryAnchor() {
        GPUSortingSearch.init(new int[] { 0 }, 2, 32L * 1024L * 1024L);

        final int[] omega = java.util.stream.IntStream.range(0, 21).toArray();
        omega[0] = 1;
        omega[1] = 2;
        omega[2] = 0;

        final int[] beta = java.util.stream.IntStream.range(0, 21).map(i -> (i + 1) % 21).toArray();
        val pair = PrimitivePair.of(omega, beta);
        val beforeOmega = pair.omegaBytes();
        val beforeBeta = pair.betaBytes();
        val result = GPUSortingSearch.getInstance().search(beforeBeta, beforeOmega, 1, false);
        assertEquals(GPUSortingSearch.Disposition.COMPLETE, result.disposition());
        assertEquals(1, result.moveCount());
        CertificateValidator.validate(pair, PrimitiveCertificate.complete(result.packedMoves()), 1);
        assertArrayEquals(beforeOmega, pair.omegaBytes());
        assertArrayEquals(beforeBeta, pair.betaBytes());
    }

    @Test
    void sortsConcurrentThreeCyclesThroughTheBoundedDispatcher() throws Exception {
        GPUSortingSearch.init(new int[] { 0 }, 2, 32L * 1024L * 1024L);

        val executor = Executors.newVirtualThreadPerTaskExecutor();
        val futures = new ArrayList<Future<?>>();

        try {
            for (var search = 0; search < 32; search++) {
                futures.add(executor.submit(() -> {
                    val moves = GPUSortingSearch.getInstance().search(new byte[] { 0, 1, 2 },
                            new OneLinePermutation(new byte[] { 1, 2, 0 }),
                            1,
                            false);

                    assertEquals(GPUSortingSearch.Disposition.COMPLETE, moves.disposition());
                    assertEquals(1, moves.moves().size());
                    assertArrayEquals(new int[] { 0, 1, 2 }, moves.moves().getFirst());
                }));
            }
            for (val future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
