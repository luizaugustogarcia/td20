package br.unb.cic.td20.model;

import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CyclicTargetCandidateTest {

    @Test
    void exposesCyclicTargetSemantics() {
        val omega = new MulticyclePermutation(Cycle.of(0, 1, 2));
        val beta = Cycle.of(0, 1, 2);
        val pair = new CyclicTargetCandidate(omega, beta);

        assertEquals(omega, pair.getOmega());
        assertEquals(beta, pair.getBeta());
        assertEquals(omega.times(beta), pair.getProduct());
        assertTrue(pair.formsCyclicTargetPair());

        val noncyclic = new CyclicTargetCandidate(omega, beta.getInverse());
        assertFalse(noncyclic.formsCyclicTargetPair());
        assertTrue(noncyclic.asCyclicTargetPair().isEmpty());
        assertEquals(omega.times(beta).asNCycle(), pair.requireCyclicTargetPair().getRho());
    }

    @Test
    void separatesConcreteEqualityFromBraceletEquivalence() {
        val candidate = new CyclicTargetCandidate("(0 9 7)(1 5 2 6 3)(4 10 8)");
        val rotated = CyclicTargetCandidate.fromEncodingWord(candidate.standardizedEncodingWord().transform(1, false));

        assertNotEquals(candidate,
                rotated,
                "Concrete candidate equality must retain omega and beta");
        assertEquals(candidate.twistedBraceletKey(),
                rotated.twistedBraceletKey(),
                "Orbit equivalence belongs to the explicit bracelet key");
    }

    @Test
    void integerRanksHaveNoTwoDigitLimit() {
        val symbols = new int[101];
        for (var symbol = 0; symbol < symbols.length; symbol++) {
            symbols[symbol] = symbol;
        }

        val candidate = new CyclicTargetCandidate(new MulticyclePermutation(Cycle.of(symbols)), Cycle.of(symbols.clone()));

        assertEquals(101, candidate.standardizedEncodingWord().rankAt(100));
    }

    @Test
    void canonicalRepresentativeIsIdempotent() {
        val candidate = new CyclicTargetCandidate("(0 9 7)(1 5 2 6 3)(4 10 8)");

        assertSame(candidate.canonicalRepresentative(), candidate.canonicalRepresentative());
    }

    @Test
    void fixedPointsAreSingletonColorsInTheCanonicalWord() {
        val beta = Cycle.of(0, 1, 2, 3);
        val implicitIdentity = new CyclicTargetCandidate(new MulticyclePermutation(), beta);
        val explicitIdentity = new CyclicTargetCandidate(new MulticyclePermutation("(0)(1)(2)(3)"), beta);

        assertEquals("[1,2,3,4]", implicitIdentity.standardizedEncodingWord().toString());
        assertEquals(implicitIdentity.twistedBraceletKey(),
                explicitIdentity.twistedBraceletKey());
        assertEquals(implicitIdentity, implicitIdentity.canonicalRepresentative());
    }
}
