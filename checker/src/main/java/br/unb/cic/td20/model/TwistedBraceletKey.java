package br.unb.cic.td20.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.val;

import java.util.Objects;

/**
 * Immutable value key identifying one twisted-bracelet orbit.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class TwistedBraceletKey implements Comparable<TwistedBraceletKey> {

    private final StandardizedEncodingWord canonicalWord;

    /**
     * Builds a key from a word already proved least in its bracelet orbit.
     */
    public static TwistedBraceletKey ofCanonicalWord(final StandardizedEncodingWord canonicalWord) {
        return new TwistedBraceletKey(Objects.requireNonNull(canonicalWord));
    }

    public static TwistedBraceletKey of(final CyclicTargetCandidate candidate) {
        val word = candidate.standardizedEncodingWord();
        val orbit = new OrbitMinimum(word);
        for (int shift = 0; shift < word.length(); shift++) {
            orbit.consider(shift, false);
            orbit.consider(shift, true);
        }

        // Only the winning image needs a constructed, fully validated word.
        return new TwistedBraceletKey(word.transform(orbit.shift, orbit.reflected));
    }

    /**
     * Compares images in the fixed alphabet used by direct generation.
     *
     * <p>Standardized encoding labels record global first occurrence.  That
     * convention is useful for an intrinsic representation, but it is not the
     * fixed color order: after a rotation, cycles belonging to two different
     * annotated parts can exchange their first-occurrence labels.  The fixed
     * alphabet orders colors by decreasing size, then puts unoriented colors
     * before oriented colors of the same size, and uses first occurrence only
     * to order indistinguishable colors.  Oriented ranks follow their color.
     */
    private static final class OrbitMinimum {

        private final int n;
        private final int[] labels, ranks, sizes, types, typeBases;
        private final int[] labelEpochs, typeEpochs, typeCounts, symbolBases, origins;
        private final int[] symbols, minimum;
        private int epoch;
        private int shift;
        private boolean reflected;

        OrbitMinimum(final StandardizedEncodingWord word) {
            n = word.length();
            labels = new int[n];
            ranks = new int[n];
            sizes = new int[n + 1];
            types = new int[n + 1];
            typeBases = new int[2 * n];
            labelEpochs = new int[n + 1];
            typeEpochs = new int[2 * n];
            typeCounts = new int[2 * n];
            symbolBases = new int[n + 1];
            origins = new int[n + 1];
            symbols = new int[n];
            minimum = new int[n];
            for (int position = 0; position < n; position++) {
                int label = word.cycleLabelAt(position);
                labels[position] = label;
                ranks[position] = word.rankAt(position);
                sizes[label]++;
                if (ranks[position] != 0) {
                    types[label] = 1;
                }
            }
            for (int label = 1; label <= n; label++) {
                if (sizes[label] == 0) {
                    continue;
                }

                // Smaller type indices mean larger cycles, then U before O.
                types[label] += 2 * (n - sizes[label]);
                typeCounts[types[label]]++;
            }

            int nextSymbol = 0;
            for (int type = 0; type < typeBases.length; type++) {
                typeBases[type] = nextSymbol;

                final int width = (type & 1) == 0 ? 1 : n - type / 2;
                nextSymbol += typeCounts[type] * width;
            }
        }

        void consider(final int imageShift, final boolean imageReflected) {
            final int currentEpoch = ++epoch;
            int comparison = currentEpoch == 1 ? -1 : 0;
            int position = imageReflected ? (n - imageShift) % n : imageShift;
            for (int offset = 0; offset < n; offset++) {
                final int label = labels[position], rank = ranks[position];
                if (labelEpochs[label] != currentEpoch) {
                    labelEpochs[label] = currentEpoch;

                    final int type = types[label];
                    if (typeEpochs[type] != currentEpoch) {
                        typeEpochs[type] = currentEpoch;
                        typeCounts[type] = 0;
                    }

                    final int width = rank == 0 ? 1 : sizes[label];
                    symbolBases[label] = typeBases[type] + typeCounts[type]++ * width;
                    origins[label] = rank;
                }

                int symbol = symbolBases[label];
                if (rank != 0) {
                    // Reflection reverses both positions and oriented ranks.
                    final int difference = imageReflected ? origins[label] - rank : rank - origins[label];
                    symbol += difference < 0 ? difference + sizes[label] : difference;
                }

                symbols[offset] = symbol;
                if (comparison == 0) {
                    if (symbol > minimum[offset]) {
                        return;
                    }
                    if (symbol < minimum[offset]) {
                        comparison = -1;
                    }
                }
                if (imageReflected) {
                    position = position == 0 ? n - 1 : position - 1;
                } else {
                    position = position + 1 == n ? 0 : position + 1;
                }
            }
            // Strict replacement keeps the stream minimum's first-image tie rule.
            if (comparison == 0) {
                return;
            }

            System.arraycopy(symbols, 0, minimum, 0, n);
            shift = imageShift;
            reflected = imageReflected;
        }
    }

    public StandardizedEncodingWord canonicalWord() {
        return canonicalWord;
    }

    public CyclicTargetCandidate canonicalRepresentative() {
        return CyclicTargetCandidate.fromEncodingWord(canonicalWord);
    }

    @Override
    public int compareTo(final TwistedBraceletKey other) {
        return canonicalWord.compareTo(other.canonicalWord);
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof TwistedBraceletKey other && canonicalWord.equals(other.canonicalWord);
    }

    @Override
    public int hashCode() {
        return canonicalWord.hashCode();
    }

    @Override
    public String toString() {
        return canonicalWord.toString();
    }
}
