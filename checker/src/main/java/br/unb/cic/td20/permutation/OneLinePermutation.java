package br.unb.cic.td20.permutation;

import java.util.Arrays;

/** Immutable one-line permutation passed to the native exact sorter. */
public final class OneLinePermutation {
    private final byte[] oneLine;

    public OneLinePermutation(final byte[] oneLine) {
        this.oneLine = oneLine.clone();
    }

    public byte[] getOneLine() {
        return oneLine.clone();
    }

    public int getNumberOfSymbols() {
        return oneLine.length;
    }

    public int image(final int symbol) {
        return Byte.toUnsignedInt(oneLine[symbol]);
    }

    @Override
    public boolean equals(final Object object) {
        return object instanceof OneLinePermutation other
                && Arrays.equals(oneLine, other.oneLine);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(oneLine);
    }
}
