package br.unb.cic.td20.experimental;

import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Capacity and admission checks run on CPU; complete output comparisons are in the CUDA benchmark.
 */
class GpuInverseReconstructionTest {

    @Test
    void capacityIncludesBothEndpointsAndEveryGapAndSupport() {
        assertEquals(2660, GpuInverseReconstruction.candidatesPerSource(21, 0));
        assertEquals(7600, GpuInverseReconstruction.candidatesPerSource(20, 1));
        assertEquals(6156, GpuInverseReconstruction.candidatesPerSource(18, 2));
        assertEquals(2280, GpuInverseReconstruction.candidatesPerSource(18, 3));
        assertThrows(IllegalArgumentException.class, () -> GpuInverseReconstruction.candidatesPerSource(21, 1));
        assertThrows(IllegalArgumentException.class, () -> GpuInverseReconstruction.candidatesPerSource(18, 4));
        assertThrows(IllegalArgumentException.class,
                () -> GpuInverseReconstruction.candidatesPerSource(Integer.MAX_VALUE, 1));
    }

    @Test
    void oversizedOrUncontractedInputsFailBeforeLoadingCuda() {
        final int[] omega = new int[20], beta = new int[20];
        for (int i = 0; i < 20; i++) {
            omega[i] = (i + 2) % 20;
            beta[i] = i;
        }

        val child = PrimitivePair.of(omega, beta);
        assertThrows(IllegalArgumentException.class,
                () -> GpuInverseReconstruction.expand(Collections.nCopies(3000, child), 1, 11));
        assertThrows(IllegalArgumentException.class, () -> GpuInverseReconstruction.expand(List.of(child), 1, 12));

        val fixed = PrimitivePair.of(new int[] { 0, 1, 2 }, new int[] { 0, 1, 2 });
        assertThrows(IllegalArgumentException.class, () -> GpuInverseReconstruction.expand(List.of(fixed), 1, 11));
    }
}
