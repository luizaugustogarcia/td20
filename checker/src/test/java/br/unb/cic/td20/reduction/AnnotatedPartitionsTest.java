package br.unb.cic.td20.reduction;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotatedPartitionsTest {
    @Test
    void equalPartsAreAnnotatedOnlyByTheirOrientedMultiplicity() {
        final var annotations = AnnotatedPartitions.of(new int[]{5, 3, 3, 2, 2});

        assertEquals(6, annotations.size());
        final var keys = new HashSet<String>();
        annotations.forEach(annotation -> keys.add(
                Arrays.toString(annotation.partition())
                        + Arrays.toString(annotation.oriented())));
        assertEquals(annotations.size(), keys.size());
    }

    @Test
    void twoCyclesCannotBeOriented() {
        final var annotations = AnnotatedPartitions.of(new int[]{2, 2, 2, 2});

        assertEquals(1, annotations.size());
        assertArrayEquals(new boolean[4], annotations.getFirst().oriented());
    }
}
