package br.unb.cic.td20.model;

import java.util.Arrays;
import java.util.Objects;

/** Immutable value key identifying one twisted-bracelet orbit. */
public final class TwistedBraceletKey implements Comparable<TwistedBraceletKey> {

    private final StandardizedEncodingWord canonicalWord;

    private TwistedBraceletKey(final StandardizedEncodingWord canonicalWord) {
        this.canonicalWord = canonicalWord;
    }

    /** Builds a key from a word already proved least in its bracelet orbit. */
    public static TwistedBraceletKey ofCanonicalWord(
            final StandardizedEncodingWord canonicalWord
    ) {
        return new TwistedBraceletKey(Objects.requireNonNull(canonicalWord));
    }

    public static TwistedBraceletKey of(final CyclicTargetCandidate candidate) {
        final var word = candidate.standardizedEncodingWord();
        final var comparison = new OrbitComparison(word);
        int leastShift = 0;
        boolean leastReflected = false;
        for (int shift = 0; shift < word.length(); shift++) {
            for (int reflection = 0; reflection < 2; reflection++) {
                final boolean reflected = reflection != 0;
                if (comparison.consider(shift, reflected)) {
                    leastShift = shift;
                    leastReflected = reflected;
                }
            }
        }
        // Construct and validate only the winning auxiliary-standardized word.
        return new TwistedBraceletKey(word.transform(leastShift, leastReflected));
    }

    /**
     * Compares spatial transforms in the fixed alphabet used by direct generation.
     *
     * <p>Standardized encoding labels record global first occurrence.  That
     * convention is useful for an intrinsic representation, but it is not the
     * fixed color order: after a rotation, cycles belonging to two different
     * annotated parts can exchange their first-occurrence labels.  The fixed
     * alphabet orders colors by decreasing size, then puts unoriented colors
     * before oriented colors of the same size, and uses first occurrence only
     * to order indistinguishable colors.  Oriented ranks follow their color.
     */
    private static final class OrbitComparison {
        private final int length;
        private final int[] labels, ranks, sizes, types, typeBases;
        private final int[] colorsSeenByType, firstSymbolByLabel, rankOriginByLabel;
        private final int[] currentSymbols, leastSymbols;
        private boolean hasLeast;

        OrbitComparison(final StandardizedEncodingWord word) {
            length = word.length();
            labels = new int[length];
            ranks = new int[length];
            sizes = new int[length + 1];
            types = new int[length + 1];
            typeBases = new int[2 * length];
            colorsSeenByType = new int[2 * length];
            firstSymbolByLabel = new int[length + 1];
            rankOriginByLabel = new int[length + 1];
            currentSymbols = new int[length];
            leastSymbols = new int[length];
            for (int position = 0; position < length; position++) {
                final int label = word.cycleLabelAt(position);
                labels[position] = label;
                ranks[position] = word.rankAt(position);
                sizes[label]++;
                if (ranks[position] != 0) types[label] = 1;
            }
            for (int label = 1; label <= length; label++) {
                if (sizes[label] == 0) continue;
                // Increasing type indices give decreasing size, then U before O.
                types[label] += 2 * (length - sizes[label]);
                colorsSeenByType[types[label]]++;
            }
            int nextSymbol = 0;
            for (int type = 0; type < typeBases.length; type++) {
                typeBases[type] = nextSymbol;
                final int width = (type & 1) == 0 ? 1 : length - type / 2;
                nextSymbol += colorsSeenByType[type] * width;
            }
        }

        boolean consider(final int shift, final boolean reflected) {
            Arrays.fill(colorsSeenByType, 0);
            Arrays.fill(firstSymbolByLabel, -1);
            int comparison = hasLeast ? 0 : -1;
            int position = reflected ? (length - shift) % length : shift;
            for (int offset = 0; offset < length; offset++) {
                final int label = labels[position];
                final int rank = ranks[position];
                if (firstSymbolByLabel[label] < 0) {
                    final int type = types[label];
                    final int width = rank == 0 ? 1 : sizes[label];
                    firstSymbolByLabel[label] = typeBases[type] + colorsSeenByType[type]++ * width;
                    rankOriginByLabel[label] = rank;
                }
                int symbol = firstSymbolByLabel[label];
                if (rank != 0) {
                    final int difference = reflected
                            ? rankOriginByLabel[label] - rank : rank - rankOriginByLabel[label];
                    symbol += difference < 0 ? difference + sizes[label] : difference;
                }
                currentSymbols[offset] = symbol;
                if (comparison == 0) {
                    comparison = Integer.compare(symbol, leastSymbols[offset]);
                    if (comparison > 0) return false;
                }
                if (reflected) position = position == 0 ? length - 1 : position - 1;
                else position = position + 1 == length ? 0 : position + 1;
            }
            if (comparison >= 0) return false;
            System.arraycopy(currentSymbols, 0, leastSymbols, 0, length);
            hasLeast = true;
            return true;
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
        return object instanceof TwistedBraceletKey other
                && canonicalWord.equals(other.canonicalWord);
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
