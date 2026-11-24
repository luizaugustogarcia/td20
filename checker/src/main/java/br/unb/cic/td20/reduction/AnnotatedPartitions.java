package br.unb.cic.td20.reduction;

import lombok.val;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unique orientation annotations for a non-increasing cycle partition.
 */
public final class AnnotatedPartitions {

    private AnnotatedPartitions() {
    }

    public static List<AnnotatedPartition> of(final int[] partition) {
        if (partition.length == 0) {
            throw new IllegalArgumentException("Partition must not be empty");
        }

        val result = new ArrayList<AnnotatedPartition>();
        enumerate(partition.clone(), new boolean[partition.length], 0, result);

        return List.copyOf(result);
    }

    private static void enumerate(final int[] partition, final boolean[] oriented, final int start, final List<AnnotatedPartition> result) {
        if (start == partition.length) {
            result.add(new AnnotatedPartition(partition.clone(), oriented.clone()));
            return;
        }

        var end = start + 1;
        while (end < partition.length && partition[end] == partition[start]) {
            end++;
        }

        val maximumOriented = partition[start] >= 3 ? end - start : 0;
        for (var orientedCount = 0; orientedCount <= maximumOriented; orientedCount++) {
            Arrays.fill(oriented, start, end, false);
            Arrays.fill(oriented, end - orientedCount, end, true);
            enumerate(partition, oriented, end, result);
        }
    }

    public record AnnotatedPartition(int[] partition, boolean[] oriented) {

        public AnnotatedPartition {
            partition = partition.clone();
            oriented = oriented.clone();
        }

        @Override
        public int[] partition() {
            return partition.clone();
        }

        @Override
        public boolean[] oriented() {
            return oriented.clone();
        }
    }
}
