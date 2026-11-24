package br.unb.cic.td20.experimental;

import br.unb.cic.td20.model.PrimitivePair;

import java.util.Objects;

/**
 * Experimental, worker-owned sufficient predicate for immediate gain-two
 * closures of final inverse parents. It changes no production coverage.
 *
 * <p>For an inverse on one endpoint the other endpoint's cyclic order is
 * unchanged. Let v be the algebraic permutation on that other endpoint. An
 * applicable consecutive triple (x,v(x),v^2(x)) survives whenever the inverse
 * support avoids {v(x),v^2(x)}. Restoring fixed symbols preserves old cyclic
 * order, so these masks work for every gap placement.
 *
 * <p>A positive result guarantees a gain-at-least-two move only for valid,
 * fixed-point-free inverse parents. At 21 symbols that move gives the
 * established closure 1 + TD(18) <= 11. No budget-ten pruning is authorized.
 */
public final class InverseFamilyClosure {

    private final int[] opposite = new int[PrimitivePair.MAX_SYMBOLS];
    private final int[] positions = new int[PrimitivePair.MAX_SYMBOLS];
    private final int[] masks = new int[PrimitivePair.MAX_SYMBOLS];
    private int size, count;

    /**
     * Prepare one inverse endpoint of a valid, contracted source.
     */
    public void prepare(final PrimitivePair child, final boolean inverseOnTarget) {
        Objects.requireNonNull(child);
        size = child.size();
        count = 0;
        for (int x = 0; x < size; x++) {
            final int image = child.omegaImage(x);
            if (image == x) {
                throw new IllegalArgumentException("Contract fixed points before preparing inverse families");
            }
            if (inverseOnTarget) {
                opposite[x] = image;
            } else {
                opposite[image] = x;
            }

            positions[inverseOnTarget ? child.betaAt(x) : child.rhoAt(x)] = x;
        }
        for (int x = 0; x < size; x++) {
            final int y = opposite[x], z = opposite[y];
            if (x == y || y == z || z == x) {
                continue;
            }

            final int px = positions[x], py = positions[y], pz = positions[z];
            if ((px < py && py < pz) || (py < pz && pz < px) || (pz < px && px < py)) {
                masks[count++] = (1 << y) | (1 << z);
            }
        }
    }

    public int witnessMaskCount() {
        return count;
    }

    /**
     * The mask contains only the old symbols of the inverse support; all
     * restored symbols lie outside every witness mask. A false result leaves
     * the parent unresolved and does not assert absence of other closures.
     */
    public boolean guaranteesImmediateClosure(final int oldSupportMask) {
        if (size == 0) {
            throw new IllegalStateException("Prepare a source before testing supports");
        }
        if (oldSupportMask < 0 || (oldSupportMask >>> size) != 0) {
            throw new IllegalArgumentException("Support outside the source domain");
        }
        for (int i = 0; i < count; i++) {
            if ((masks[i] & oldSupportMask) == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * These masks certify every support iff they have no vertex cover of size at most 3-gain.
     */
    public boolean closesEverySupport(final int inverseGain) {
        requireFinalGain(inverseGain);
        return !hasCover(0, 3 - inverseGain);
    }

    private boolean hasCover(final int selected, final int remaining) {
        for (int i = 0; i < count; i++) {
            final int edge = masks[i];
            if ((edge & selected) != 0) {
                continue;
            }
            if (remaining == 0) {
                return false;
            }

            final int first = Integer.lowestOneBit(edge);

            return hasCover(selected | first, remaining - 1) || hasCover(selected | (edge ^ first), remaining - 1);
        }

        return true;
    }

    /**
     * Number of old supports not discharged by these masks, before gap placements or gain replay.
     */
    public long unresolvedOldSupports(final int inverseGain) {
        requireFinalGain(inverseGain);
        if (closesEverySupport(inverseGain)) {
            return 0;
        }

        long result = 0;
        for (int a = 0; a < size - 1; a++) {
            for (int b = a + 1; b < size; b++) {
                final int pair = (1 << a) | (1 << b);
                if (inverseGain == 1) {
                    if (!guaranteesImmediateClosure(pair)) {
                        result++;
                    }
                } else {
                    for (int c = b + 1; c < size; c++) if (!guaranteesImmediateClosure(pair | (1 << c))) {
                        result++;
                    }
                }
            }
        }

        return result;
    }

    private void requireFinalGain(final int gain) {
        if (size == 0) {
            throw new IllegalStateException("Prepare a source before testing supports");
        }
        if (gain < 0 || gain > 1 || size + gain > PrimitivePair.MAX_SYMBOLS) {
            throw new IllegalArgumentException("This experiment covers only final gain-zero/one inverses");
        }
    }
}
