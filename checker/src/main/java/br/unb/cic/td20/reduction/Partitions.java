package br.unb.cic.td20.reduction;

import lombok.val;

import java.util.ArrayList;
import java.util.List;

public class Partitions {

    /**
     * Generates all valid partitions of n into parts >= 2 with an even number
     * of even parts (parity constraint: omega must be in A_n).
     */
    public static List<int[]> generateValidPartitions(final int n) {
        val result = new ArrayList<int[]>();
        generatePartitions(n, n, new ArrayList<>(), 0, result);

        return result;
    }

    private static void generatePartitions(final int remaining,
            final int maxPart,
            final List<Integer> partition,
            final int evenPartCount,
            final List<int[]> result) {
        if (remaining == 0) {
            if (evenPartCount % 2 == 0) {
                result.add(partition.stream().mapToInt(Integer::intValue).toArray());
            }

            return;
        }

        for (int part = Math.min(maxPart, remaining); part >= 2; part--) {
            partition.add(part);

            generatePartitions(remaining - part,
                    // ensures non-increasing order
                    part,
                    partition,
                    evenPartCount + (part % 2 == 0 ? 1 : 0),
                    result);

            partition.remove(partition.size() - 1);
        }
    }
}
