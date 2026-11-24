package br.unb.cic.td20.model;

import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;

import java.util.IdentityHashMap;

import static br.unb.cic.td20.search.CommonOperations.areSymbolsInCyclicOrder;
import static br.unb.cic.td20.search.CommonOperations.cycleIndex;

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
        val cycleBySymbol = cycleIndex(omega, beta);
        for (val symbol : beta.getSymbols()) {
            if (cycleBySymbol[symbol] == null) {
                cycleBySymbol[symbol] = Cycle.of(symbol);
            }
        }

        val labelByCycle = new IdentityHashMap<Cycle, Integer>();
        val orientedByCycle = new IdentityHashMap<Cycle, Boolean>();
        val rankBySymbolByCycle = new IdentityHashMap<Cycle, int[]>();
        val betaPosition = new int[beta.size()];
        val labels = new int[beta.size()];
        val ranks = new int[beta.size()];
        val betaInverse = beta.getInverse();
        var nextLabel = 1;

        for (var position = 0; position < beta.size(); position++) {
            betaPosition[beta.get(position)] = position;
        }

        for (var position = 0; position < beta.size(); position++) {
            val symbol = beta.get(position);
            val cycle = cycleBySymbol[symbol];
            var label = labelByCycle.get(cycle);
            if (label == null) {
                label = nextLabel++;
                labelByCycle.put(cycle, label);
            }

            labels[position] = label;

            val oriented = orientedByCycle.computeIfAbsent(cycle,
                    candidate -> !areSymbolsInCyclicOrder(betaInverse, candidate.getSymbols()));
            if (oriented) {
                val rankBySymbol = rankBySymbolByCycle.computeIfAbsent(cycle,
                        candidate -> ranksFromFirstOccurrence(candidate, betaPosition, beta.size()));
                ranks[position] = rankBySymbol[symbol];
            }
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

    private static int[] ranksFromFirstOccurrence(final Cycle cycle, final int[] betaPosition, final int domainSize) {
        val ranks = new int[domainSize];
        val symbols = cycle.getSymbols();
        var firstCycleIndex = 0;
        var firstPosition = Integer.MAX_VALUE;
        for (var cycleIndex = 0; cycleIndex < symbols.length; cycleIndex++) {
            val position = betaPosition[symbols[cycleIndex]];
            if (position < firstPosition) {
                firstPosition = position;
                firstCycleIndex = cycleIndex;
            }
        }
        for (var offset = 0; offset < symbols.length; offset++) {
            ranks[symbols[(firstCycleIndex + offset) % symbols.length]] = offset + 1;
        }

        return ranks;
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
