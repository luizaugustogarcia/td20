package br.unb.cic.td20.reduction;

import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotatedPartitionsTest {

    @Test
    void equalPartsAreAnnotatedOnlyByTheirOrientedMultiplicity() {
        val annotations = AnnotatedPartitions.of(new int[] { 5, 3, 3, 2, 2 });

        assertEquals(6, annotations.size());

        val keys = new HashSet<String>();
        annotations.forEach(annotation -> keys.add(Arrays.toString(annotation.partition()) + Arrays.toString(annotation.oriented())));
        assertEquals(annotations.size(), keys.size());
    }

    @Test
    void twoCyclesCannotBeOriented() {
        val annotations = AnnotatedPartitions.of(new int[] { 2, 2, 2, 2 });

        assertEquals(1, annotations.size());
        assertArrayEquals(new boolean[4], annotations.getFirst().oriented());
    }
}
