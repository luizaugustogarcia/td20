package br.unb.cic.td20.joint;

import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SupportClearingTest {
    @Test void onlyTheBudgetAdmittedLocalPowersAreAccepted() {
        final int[][] candidates = {
                {5, 3, 0}, {7, 4, 0}, {9, 5, 0}, {9, 7, 1},
                {11, 6, 1}, {13, 7, 1}, {13, 10, 1}, {13, 11, 1},
                {15, 8, 0}, {17, 9, 1}, {17, 13, 1}, {17, 15, 1}
        };
        for (int[] candidate:candidates) {
            int length=candidate[0],exponent=candidate[1];
            int[] omega=IntStream.range(0,KnownDiameters.ROOT_SYMBOLS).toArray();
            int[] support=IntStream.range(0,length).toArray();
            for (int x=0;x<length;x++) omega[x]=(x+exponent)%length;
            assertEquals(candidate[2]==1,SupportClearing.admittedPower(omega,support,length),
                    "length="+length+", exponent="+exponent);
        }
    }
}
