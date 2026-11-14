package br.unb.cic.td20.joint;

import org.junit.jupiter.api.Test;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SupportClearingTest {
    @Test void admittedPowersAndTheirRelabelingsMatchTheRootBudget() {
        int[][] candidates={
                {5, 3, 0}, {7, 4, 0}, {9, 5, 0}, {9, 7, 1},
                {11, 6, 1}, {13, 7, 1}, {13, 10, 1}, {13, 11, 1},
                {15, 8, 0}, {17, 9, 1}, {17, 13, 1}, {17, 15, 1}
        };
        for (int[] candidate:candidates) {
            if (candidate[2]==0) continue;
            int length=candidate[0],power=candidate[1],n=KnownDiameters.ROOT_SYMBOLS,k=(n-length)/2;
            // A two-symbol complement cannot form an even fixed-point-free root.
            if (k==1) continue;
            int[] images=new int[n];
            for (int x=0;x<length;x++) images[x]=(x+power)%length;
            for (int i=0;i<k;i++) {
                images[length+2*i]=length+2*((i+k-1)%k);
                images[length+2*i+1]=length+2*((i+k-1)%k)+1;
            }
            var pair=pair(images);
            assertEquals(length,SupportClearing.closingLength(pair));
            var omega=new MulticyclePermutation();
            for (var cycle:pair.getOmega()) omega.add(Cycle.of(Arrays.stream(cycle.getSymbols()).map(x->(2*x+3)%n).toArray()));
            var beta=Cycle.of(Arrays.stream(pair.getBeta().getSymbols()).map(x->(2*x+3)%n).toArray());
            assertEquals(length,SupportClearing.closingLength(CyclicTargetPair.of(omega,beta)));
        }
    }
    private static CyclicTargetPair pair(int[] images) {
        var omega=new MulticyclePermutation(); boolean[] seen=new boolean[images.length];
        for (int x=0;x<images.length;x++) if (!seen[x]) {
            int[] symbols=new int[images.length]; int size=0,y=x;
            do { symbols[size++]=y; seen[y]=true; y=images[y]; } while (y!=x);
            omega.add(Cycle.of(Arrays.copyOf(symbols,size)));
        }
        int[] beta=new int[images.length]; Arrays.setAll(beta,i->i);
        return CyclicTargetPair.of(omega,Cycle.of(beta));
    }
}
