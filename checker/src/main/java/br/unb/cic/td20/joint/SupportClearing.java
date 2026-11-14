package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;

/** Budget-valid local support-clearing powers listed in the paper. */
public final class SupportClearing {
    private SupportClearing() { }
    public static boolean closes(final CyclicTargetPair pair) { return closingLength(pair) != 0; }
    public static int closingLength(final CyclicTargetPair pair) {
        if (pair.getBeta().size() != KnownDiameters.ROOT_SYMBOLS) return 0;
        if (CyclicTargetMoves.fixed(CyclicTargetMoves.images(pair)) != 0)
            throw new IllegalArgumentException("Support-clearing roots require 21 nonfixed symbols");
        int smallest = Integer.MAX_VALUE;
        for (final var cycle : pair.getOmega()) {
            final int length = cycle.size();
            final int[] powers = switch (length) {
                case 9 -> new int[]{7};
                case 11 -> new int[]{6};
                case 13 -> new int[]{7, 10, 11};
                case 17 -> new int[]{9, 13, 15};
                default -> new int[0];
            };
            if (powers.length == 0) continue;
            final int[] support = new int[length];
            int at = 0;
            final boolean[] member = new boolean[KnownDiameters.ROOT_SYMBOLS];
            for (final int symbol : cycle.getSymbols()) member[symbol] = true;
            for (final int symbol : pair.getBeta().getSymbols())
                if (member[symbol]) support[at++] = symbol;
            for (final int power : powers) {
                boolean match = true;
                for (int i = 0; i < length; i++)
                    if (pair.getOmega().image(support[i]) != support[(i + power) % length]) { match = false; break; }
                if (match) {
                    if ((length + 1) / 2
                            + KnownDiameters.knownDiameter(KnownDiameters.ROOT_SYMBOLS - length)
                            > KnownDiameters.MAX_BUDGET)
                        throw new IllegalStateException("Unsupported clearing budget");
                    smallest = Math.min(smallest, length);
                }
            }
        }
        return smallest == Integer.MAX_VALUE ? 0 : smallest;
    }
}
