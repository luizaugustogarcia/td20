package br.unb.cic.td20.model;

import lombok.val;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reusable, checker-owned canonical key workspace for domains of at most 21
 * symbols. Input omega is a successor table; beta lists the current cycle.
 * Used directly by checker generation, reconstruction and persistence.
 *
 * <p>Each worker owns one instance. Only the returned packed key is allocated
 * per call. Auxiliary colors are ordered by decreasing cycle size, then
 * unoriented before oriented, and by first occurrence within an equal type.
 * Reflection reverses positions AND algebraic cycle direction.
 */
public final class PrimitiveCanonicalizer {

    public static final int MAX_SYMBOLS = 21;
    private final int[] positions = new int[MAX_SYMBOLS];
    private final int[] inverse = new int[MAX_SYMBOLS];
    private final int[] labels = new int[MAX_SYMBOLS];
    private final int[] ranks = new int[MAX_SYMBOLS];
    private final int[] sizes = new int[MAX_SYMBOLS];
    private final int[] types = new int[MAX_SYMBOLS];
    private final int[] typeBases = new int[2 * MAX_SYMBOLS];
    private final int[] counts = new int[2 * MAX_SYMBOLS];
    private final int[] firstSymbols = new int[MAX_SYMBOLS];
    private final int[] origins = new int[MAX_SYMBOLS];
    private final int[] current = new int[MAX_SYMBOLS];
    private final int[] least = new int[MAX_SYMBOLS];

    /**
     * Checks a complete cyclic-target pair before constructing its class key.
     */
    public byte[] keyFor(final int[] omega, final int[] beta, final int n) {
        validatePair(omega, beta, n);
        return keyForKnownPair(omega, beta, n);
    }

    /**
     * Used only after inverse enumeration has proved the pair by exact replay.
     */
    public byte[] keyForKnownPair(final int[] omega, final int[] beta, final int n) {
        return keyForKnownPair(omega, beta, n, 0);
    }

    /**
     * Packs once with reserved bytes for a versioned persistence prefix.
     */
    public byte[] keyForKnownPair(final int[] omega, final int[] beta, final int n, final int prefixBytes) {
        if (prefixBytes < 0) {
            throw new IllegalArgumentException("Negative key prefix");
        }
        for (int i = 0; i < n; i++) {
            positions[beta[i]] = i;
            inverse[omega[i]] = i;
        }

        Arrays.fill(counts, 0);

        int visited = 0;
        for (int start = 0; start < n; start++) {
            if ((visited & (1 << start)) != 0) {
                continue;
            }

            int x = start, size = 0, increasing = 0;
            do {
                visited |= 1 << x;
                labels[x] = start;
                ranks[x] = size++;

                final int next = omega[x];
                if (positions[next] > positions[x]) {
                    increasing++;
                }

                x = next;
            } while (x != start);

            sizes[start] = size;

            // An unoriented cycle follows inverse beta on its support. A
            // nonsingleton has exactly one increasing edge in linear positions.
            final boolean oriented = size >= 3 && increasing != 1;
            int type = 2 * (n - size) + (oriented ? 1 : 0);
            types[start] = type;
            counts[type]++;
        }

        int nextSymbol = 0;
        for (int type = 0; type < 2 * n; type++) {
            typeBases[type] = nextSymbol;

            final int width = (type & 1) == 0 ? 1 : n - type / 2;
            nextSymbol += counts[type] * width;
        }

        int leastShift = 0;
        boolean leastReflected = false, hasLeast = false;
        for (int shift = 0; shift < n; shift++) {
            for (int reflection = 0; reflection < 2; reflection++) {
                final boolean reflected = reflection != 0;
                Arrays.fill(counts, 0);
                Arrays.fill(firstSymbols, -1);

                int comparison = hasLeast ? 0 : -1;
                int position = reflected ? (n - shift) % n : shift;
                for (int offset = 0; offset < n; offset++) {
                    final int symbol = beta[position], label = labels[symbol];
                    int type = types[label];
                    final boolean oriented = (type & 1) != 0;
                    if (firstSymbols[label] < 0) {
                        firstSymbols[label] = typeBases[type] + counts[type]++ * (oriented ? sizes[label] : 1);
                        origins[label] = ranks[symbol];
                    }

                    int value = firstSymbols[label];
                    if (oriented) {
                        final int difference = reflected ? origins[label] - ranks[symbol] : ranks[symbol] - origins[label];
                        value += difference < 0 ? difference + sizes[label] : difference;
                    }

                    current[offset] = value;
                    if (comparison == 0) {
                        comparison = Integer.compare(value, least[offset]);
                        if (comparison > 0) {
                            break;
                        }
                    }

                    position = reflected ? (position == 0 ? n - 1 : position - 1) : (position + 1 == n ? 0 : position + 1);
                }
                if (comparison < 0) {
                    System.arraycopy(current, 0, least, 0, n);
                    leastShift = shift;
                    leastReflected = reflected;
                    hasLeast = true;
                }
            }
        }

        // Relabel the winning pair directly, without constructing/decoding an
        // encoding word. Twisted reflection also replaces omega by omega^-1.
        val key = new byte[prefixBytes + (5 * n + 7) / 8];
        for (int oldPosition = 0; oldPosition < n; oldPosition++) {
            final int symbol = beta[oldPosition];
            final int oldImagePosition = positions[leastReflected ? inverse[symbol] : omega[symbol]];
            int position = transform(oldPosition, leastShift, leastReflected, n);
            final int image = transform(oldImagePosition, leastShift, leastReflected, n);
            final int bit = 5 * position, index = prefixBytes + (bit >>> 3), remainder = bit & 7;
            if (remainder <= 3) {
                key[index] |= (byte) (image << (3 - remainder));
            } else {
                key[index] |= (byte) (image >>> (remainder - 3));
                key[index + 1] |= (byte) (image << (11 - remainder));
            }
        }

        return key;
    }

    private static int transform(final int position, final int shift, final boolean reflected, final int n) {
        int result = reflected ? -shift - position : position - shift;
        if (result < 0) {
            result += n;
        }

        return result < 0 ? result + n : result;
    }

    private void validatePair(final int[] omega, final int[] beta, final int n) {
        Objects.requireNonNull(omega);
        Objects.requireNonNull(beta);
        if (n < 1 || n > MAX_SYMBOLS || omega.length < n || beta.length < n) {
            throw new IllegalArgumentException("Primitive pairs require a domain of 1..21 and complete input arrays");
        }

        int omegaSeen = 0, betaSeen = 0;
        for (int i = 0; i < n; i++) {
            final int image = omega[i], symbol = beta[i];
            if (image < 0
                    || image >= n
                    || symbol < 0
                    || symbol >= n
                    || (omegaSeen & (1 << image)) != 0
                    || (betaSeen & (1 << symbol)) != 0) {
                throw new IllegalArgumentException("omega and beta must be permutations of the complete domain");
            }

            omegaSeen |= 1 << image;
            betaSeen |= 1 << symbol;
            positions[symbol] = i;
        }

        int x = beta[0], length = 0;
        // Validate the product directly in O(N), using the worker's position
        // table. This public boundary never changes the caller-owned arrays.
        do {
            final int position = positions[x];
            x = omega[beta[position + 1 == n ? 0 : position + 1]];
            length++;
        } while (x != beta[0] && length < n);
        if (x != beta[0] || length != n) {
            throw new IllegalArgumentException("The product omega * beta must be a full target cycle");
        }
    }
}
