package br.unb.cic.td20.model;

import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncodingWordDihedralActionTest {

    @Test
    void distinguishedTwistedReflectionMatchesTheWorkedExample() {
        val word = StandardizedEncodingWord.of(new int[] { 1, 2, 1, 2, 2, 1, 2, 2 },
                new int[] { 0, 1, 0, 3, 5, 0, 2, 4 });

        val original = CyclicTargetCandidate.fromEncodingWord(word);
        assertEquals(new MulticyclePermutation("(0 5 2)(1 6 3 7 4)"),
                original.getOmega());
        assertEquals(new MulticyclePermutation("(0 6 4 2 7 5 3 1)"),
                original.getProduct());

        val reflectedWord = word.transform(0, true);
        assertEquals("[1,2_0,2_2,1,2_4,2_1,1,2_3]", reflectedWord.toString());

        val reflected = CyclicTargetCandidate.fromEncodingWord(reflectedWord);
        assertEquals(new MulticyclePermutation("(0 6 3)(1 5 2 7 4)"),
                reflected.getOmega());
        assertEquals(new MulticyclePermutation("(0 5 3 1 7 6 4 2)"),
                reflected.getProduct());
        assertEquals(word, reflectedWord.transform(0, true));
    }

    @Test
    void implementationUsesTheTwistedDihedralIndexingAndDecoderAction() {
        val word = StandardizedEncodingWord.of(new int[] { 1, 2, 1, 2, 2, 1, 2, 2 },
                new int[] { 0, 1, 0, 3, 5, 0, 2, 4 });
        val omega = CyclicTargetCandidate.fromEncodingWord(word).getOmega();
        val inverse = omega.getInverse();
        val n = word.length();

        for (var shift = 0; shift < n; shift++) {
            val directReflectedRotation = word.transform(shift, true);
            assertEquals(directReflectedRotation,
                    word.transform(0, true).transform(shift, false),
                    "transform(shift, true) must implement r_shift s");

            val groupRelation = word.transform(0, true).transform(shift, false).transform(0, true);
            assertEquals(word.transform(-shift, false),
                    groupRelation,
                    "The implementation must satisfy s r_shift s = r_-shift");

            val transformedOmega = CyclicTargetCandidate.fromEncodingWord(directReflectedRotation).getOmega();
            for (var symbol = 0; symbol < n; symbol++) {
                val reflectedSymbol = Math.floorMod(-shift - symbol, n);
                val expected = Math.floorMod(-shift - inverse.image(reflectedSymbol), n);
                assertEquals(expected,
                        transformedOmega.image(symbol),
                        "Decoder equivariance failed for reflected shift " + shift + " at symbol " + symbol);
            }
        }
    }
}
