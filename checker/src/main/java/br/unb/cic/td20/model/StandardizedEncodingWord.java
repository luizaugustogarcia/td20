package br.unb.cic.td20.model;

import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;

import static br.unb.cic.td20.search.CommonOperations.areSymbolsInCyclicOrder;

/**
 * Encoding word with auxiliary color names and oriented rank origins fixed by
 * first occurrence. Singleton colors encode algebraic fixed points.
 */
public final class StandardizedEncodingWord extends EncodingWord {

    private StandardizedEncodingWord(final int[] cycleLabels, final int[] ranks) {
        super(cycleLabels, ranks);
        validateStandardization();
    }

    public static StandardizedEncodingWord of(final int[] cycleLabels, final int[] ranks) {
        return new StandardizedEncodingWord(cycleLabels, ranks);
    }

    public static StandardizedEncodingWord from(final MulticyclePermutation omega, final Cycle beta) {
        final int n = beta.size();
        val cycleIdBySymbol = new int[n];
        java.util.Arrays.fill(cycleIdBySymbol, -1);

        val labelByCycleId = new int[n];
        val rankBySymbol = new int[n];
        val betaPosition = new int[n];
        val labels = new int[n];
        val ranks = new int[n];
        val betaInverse = beta.getInverse();
        for (int i = 0; i < n; i++) {
            betaPosition[beta.get(i)] = i;
        }

        int cycleId = 0;
        for (val cycle : omega) {
            final int[] symbols = cycle.getSymbols();
            for (int symbol : symbols) {
                cycleIdBySymbol[symbol] = cycleId;
            }
            if (!areSymbolsInCyclicOrder(betaInverse, symbols)) {
                int first = 0;
                for (int i = 1; i < symbols.length; i++) {
                    if (betaPosition[symbols[i]] < betaPosition[symbols[first]]) {
                        first = i;
                    }
                }
                for (int i = 0; i < symbols.length; i++) {
                    rankBySymbol[symbols[(first + i) % symbols.length]] = i + 1;
                }
            }

            cycleId++;
        }
        // Implicit fixed symbols remain singleton colors with rank zero.
        for (int symbol = 0; symbol < n; symbol++) {
            if (cycleIdBySymbol[symbol] < 0) {
                cycleIdBySymbol[symbol] = cycleId++;
            }
        }

        int nextLabel = 1;
        for (int position = 0; position < n; position++) {
            final int symbol = beta.get(position), id = cycleIdBySymbol[symbol];
            if (labelByCycleId[id] == 0) {
                labelByCycleId[id] = nextLabel++;
            }

            labels[position] = labelByCycleId[id];
            ranks[position] = rankBySymbol[symbol];
        }

        return new StandardizedEncodingWord(labels, ranks);
    }

    /**
     * Applies {@code r_shift}, or {@code r_shift s} when reflected, and
     * restandardizes auxiliary color names and oriented-rank origins.
     * The distinguished reflection is {@code s(j) = -j}.
     */
    public StandardizedEncodingWord transform(final int shift, final boolean reflected) {
        val cycleSizes = new int[length() + 1];
        for (var position = 0; position < length(); position++) {
            cycleSizes[cycleLabelAt(position)]++;
        }

        val newLabelByOldLabel = new int[length() + 1];
        val rankOriginByOldLabel = new int[length() + 1];
        val originSet = new boolean[length() + 1];
        val labels = new int[length()];
        val ranks = new int[length()];
        var nextLabel = 1;

        for (var offset = 0; offset < length(); offset++) {
            val position = reflected ? Math.floorMod(-shift - offset, length()) : Math.floorMod(shift + offset, length());
            val oldLabel = cycleLabelAt(position);
            if (newLabelByOldLabel[oldLabel] == 0) {
                newLabelByOldLabel[oldLabel] = nextLabel++;
            }

            labels[offset] = newLabelByOldLabel[oldLabel];

            val oldRank = rankAt(position);
            if (oldRank == 0) {
                continue;
            }

            val size = cycleSizes[oldLabel];
            val zeroBasedRank = oldRank - 1;
            val transformedRank = reflected ? Math.floorMod(-zeroBasedRank, size) : zeroBasedRank;
            if (!originSet[oldLabel]) {
                rankOriginByOldLabel[oldLabel] = transformedRank;
                originSet[oldLabel] = true;
            }

            ranks[offset] = Math.floorMod(transformedRank - rankOriginByOldLabel[oldLabel], size) + 1;
        }

        return new StandardizedEncodingWord(labels, ranks);
    }

    private void validateStandardization() {
        val firstRankByLabel = new int[length() + 1];
        val seenLabel = new boolean[length() + 1];
        var nextLabel = 1;
        for (var position = 0; position < length(); position++) {
            val label = cycleLabelAt(position);
            if (!seenLabel[label]) {
                if (label != nextLabel++) {
                    throw new IllegalArgumentException("Standardized cycle labels must be named by first occurrence");
                }

                seenLabel[label] = true;
                firstRankByLabel[label] = rankAt(position);
            }
        }
        for (var label = 1; label < firstRankByLabel.length; label++) {
            if (firstRankByLabel[label] != 0 && firstRankByLabel[label] != 1) {
                throw new IllegalArgumentException("A standardized oriented rank origin must be the first occurrence");
            }
        }
    }
}
