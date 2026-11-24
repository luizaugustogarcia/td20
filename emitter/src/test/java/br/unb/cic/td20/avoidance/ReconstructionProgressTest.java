package br.unb.cic.td20.avoidance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionProgressTest {

    @Test
    void phaseEtaUsesRemainingInputsAndRoundsUpToAWholeSecond() {
        assertEquals("00:00:05", ReconstructionProgress.formatEta(9, 2));
        assertEquals("01:00:00", ReconstructionProgress.formatEta(7200, 2));
        assertEquals("2d 01:00:00", ReconstructionProgress.formatEta(176400, 1));
        assertEquals("00:00:00", ReconstructionProgress.formatEta(0, 0));
    }

    @Test
    void noProgressInvalidInventoryAndUnboundedEtasRemainUnknown() {
        for (final double rate : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertEquals("unknown", ReconstructionProgress.formatEta(1, rate));
        }

        assertEquals("unknown", ReconstructionProgress.formatEta(-1, 1));
        assertEquals("unknown", ReconstructionProgress.formatEta(Long.MAX_VALUE, Double.MIN_VALUE));
    }
}
