package br.unb.cic.td20.model;

import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwistedBraceletKeyTest {

    @Test
    void matchesTheFullWordOrbitReferenceForEveryPermutationThroughSevenSymbols() {
        for (int n = 1; n <= 7; n++) {
            val images = new int[n];
            for (int symbol = 0; symbol < n; symbol++) {
                images[symbol] = symbol;
            }

            permute(images,
                    0,
                    permutation -> {
                        val candidate = candidate(permutation);
                        assertEquals(reference(candidate),
                                TwistedBraceletKey.of(candidate).canonicalWord(),
                                () -> "Orbit minimum disagrees for omega=" + Arrays.toString(permutation));
                    });
        }
    }

    @Test
    void preservesMixedEqualSizeAnnotationsAndTwistedReflectionInAnyCurrentCycle() {
        val candidate = new CyclicTargetCandidate(new MulticyclePermutation("(0 5 2)(1 6 3 7 4)(8 9 10)(11 13 12)"),
                Cycle.of(5, 1, 2, 3, 4, 0, 6, 7, 8, 9, 10, 11, 12, 13, 14));
        val expected = reference(candidate);
        assertEquals(expected, TwistedBraceletKey.of(candidate).canonicalWord());
        candidate.dihedralOrbitWords().forEach(image -> {
            val transformed = CyclicTargetCandidate.fromEncodingWord(image.word());
            assertEquals(expected, TwistedBraceletKey.of(transformed).canonicalWord());
        });
    }

    @Test
    void preservesRankOriginsBeyondTheGpuDomain() {
        val labels = new int[23];
        val ranks = new int[23];
        Arrays.fill(labels, 1);
        for (int position = 0; position < ranks.length; position++) {
            ranks[position] = position * 7 % 23 + 1;
        }

        val candidate = CyclicTargetCandidate.fromEncodingWord(StandardizedEncodingWord.of(labels, ranks));
        assertEquals(reference(candidate), TwistedBraceletKey.of(candidate).canonicalWord());
    }

    @Test
    void matchesFullOrbitOnProductionDomainsAndRandomCurrentCycles() {
        val random = new Random(0x18c0ffeeL);
        for (final int n : new int[] { 14, 16, 18, 19, 23 }) {
            for (int sample = 0; sample < 200; sample++) {
                final int[] images = new int[n], current = new int[n];
                for (int i = 0; i < n; i++) {
                    images[i] = i;
                    current[i] = i;
                }
                for (int i = n - 1; i > 0; i--) {
                    swap(images, i, random.nextInt(i + 1));
                    swap(current, i, random.nextInt(i + 1));
                }

                val omega = candidate(images).getOmega();
                val pair = new CyclicTargetCandidate(omega, Cycle.of(current));
                assertEquals(reference(pair),
                        TwistedBraceletKey.of(pair).canonicalWord(),
                        "N=" + n + " sample=" + sample);
            }
        }
    }

    /**
     * The previous object-based orbit minimum is an independent reference.
     */
    private static StandardizedEncodingWord reference(final CyclicTargetCandidate candidate) {
        return candidate.dihedralOrbitWords().map(DihedralOrbitWord::word).min((left, right) -> Arrays.compare(fixedColorSymbols(left), fixedColorSymbols(right))).orElseThrow();
    }

    private static int[] fixedColorSymbols(final StandardizedEncodingWord word) {
        val sizes = new int[word.length() + 1];
        val oriented = new boolean[word.length() + 1];
        int colorCount = 0;
        for (int position = 0; position < word.length(); position++) {
            final int label = word.cycleLabelAt(position);
            if (sizes[label]++ == 0) {
                colorCount++;
            }

            oriented[label] |= word.rankAt(position) != 0;
        }

        val colors = new Integer[colorCount];
        for (int label = 1; label <= colorCount; label++) {
            colors[label - 1] = label;
        }

        Arrays.sort(colors,
                (left, right) -> {
                    final int sizeComparison = Integer.compare(sizes[right], sizes[left]);
                    if (sizeComparison != 0) {
                        return sizeComparison;
                    }

                    final int orientationComparison = Boolean.compare(oriented[left], oriented[right]);

                    return orientationComparison != 0 ? orientationComparison : Integer.compare(left, right);
                });

        val firstSymbolByLabel = new int[colorCount + 1];
        int nextSymbol = 0;
        for (int label : colors) {
            firstSymbolByLabel[label] = nextSymbol;
            nextSymbol += oriented[label] ? sizes[label] : 1;
        }

        val symbols = new int[word.length()];
        for (int position = 0; position < word.length(); position++) {
            final int label = word.cycleLabelAt(position);
            symbols[position] = firstSymbolByLabel[label] + (oriented[label] ? word.rankAt(position) - 1 : 0);
        }

        return symbols;
    }

    private static CyclicTargetCandidate candidate(final int[] images) {
        val omega = new MulticyclePermutation();
        val seen = new boolean[images.length];
        for (int first = 0; first < images.length; first++) {
            if (seen[first]) {
                continue;
            }

            val symbols = new int[images.length];
            int length = 0;
            int x = first;
            do {
                seen[x] = true;
                symbols[length++] = x;
                x = images[x];
            } while (x != first);
            // Include implicit fixed points in the exhaustive reference domain.
            if (length > 1) {
                omega.add(Cycle.of(Arrays.copyOf(symbols, length)));
            }
        }

        val beta = new int[images.length];
        for (int symbol = 0; symbol < beta.length; symbol++) {
            beta[symbol] = symbol;
        }

        return new CyclicTargetCandidate(omega, Cycle.of(beta));
    }

    private static void permute(final int[] values, final int index, final Consumer<int[]> consumer) {
        if (index == values.length) {
            consumer.accept(values);
            return;
        }
        for (int next = index; next < values.length; next++) {
            swap(values, index, next);
            permute(values, index + 1, consumer);
            swap(values, index, next);
        }
    }

    private static void swap(final int[] values, final int first, final int second) {
        final int value = values[first];
        values[first] = values[second];
        values[second] = value;
    }
}
