package br.unb.cic.td20.joint;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.search.CommonOperations;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JointInverseReconstructionTest {
    @Test void regeneratesFixedPointFreeOrientedIntermediatePreimages() {
        final var endpoint=CyclicTargetPair.of(new MulticyclePermutation(List.of(
                Cycle.of(0,11,13,12,2,4,9,5,7,3,14),Cycle.of(1,10,8,15,6))),
                Cycle.of(0,6,5,15,14,8,4,9,1,12,11,13,3,2,7,10)).canonicalRepresentative();
        final var count=new AtomicInteger();
        JointInverseReconstruction.intermediates(endpoint,parent->{
            assertTrue(parent.getBeta().size()==18||parent.getBeta().size()==19);
            assertEquals(0,JointMoves.fixed(JointMoves.images(parent)));
            assertFalse(JointMoves.totallyUnoriented(JointMoves.images(parent),parent.getBeta().getSymbols()));
            final int gain=parent.getBeta().size()-endpoint.getBeta().size();
            assertEquals(JointMoves.odd(JointMoves.images(parent))+2-gain,
                    JointMoves.odd(JointMoves.images(endpoint)));
            count.incrementAndGet();
        });
        assertTrue(count.get()>0);
    }
    @Test void inverseSupportsIncludeFirstMovesSplittingASingleEvenLengthCycle() {
        final CyclicTargetPair[] endpoint={null};
        assertThrows(Found.class,()->DirectTwistedBraceletGenerator.generateRepresentatives(
                new int[]{7,5,2,2},new boolean[]{true,true,false,false},pair->{endpoint[0]=pair;throw new Found();}));
        assertThrows(Found.class,()->JointInverseReconstruction.intermediates(endpoint[0],intermediate->{
            assertTrue(intermediate.getBeta().size()==18||intermediate.getBeta().size()==19);
            JointInverseReconstruction.middle(intermediate,middle->JointInverseReconstruction.roots(middle,root->{
                assertEquals(20,middle.getBeta().size());
                assertEquals(21,root.getBeta().size());
                final var even=root.getOmega().stream().filter(c->c.size()%2==0&&Arrays.stream(c.getSymbols()).anyMatch(x->x==20)).findFirst();
                if(even.isEmpty())return;
                final var support=even.orElseThrow().getSymbols();
                final var p=JointMoves.images(root);
                for(final int a:support)for(final int b:support){
                    if(a==20||b==20||a==b||!CommonOperations.areSymbolsInCyclicOrder(root.getBeta(),20,a,b))continue;
                    final var q=JointMoves.apply(p,20,a,b);
                    if(q[20]!=20||JointMoves.fixed(q)!=1||JointMoves.odd(q)!=JointMoves.odd(p)+2)continue;
                    final var beta=CommonOperations.applyTranspositionOptimized(root.getBeta(),Cycle.of(20,a,b));
                    final var omega=new MulticyclePermutation();
                    final boolean[] seen=new boolean[20];
                    for(int origin=0;origin<20;origin++) {
                        if(seen[origin])continue;
                        final int[] buffer=new int[20];int length=0,cursor=origin;
                        do {seen[cursor]=true;buffer[length++]=cursor;cursor=q[cursor];}while(cursor!=origin);
                        omega.add(Cycle.of(Arrays.copyOf(buffer,length)));
                    }
                    final var child=CyclicTargetPair.of(omega,Cycle.of(Arrays.stream(beta.getSymbols()).filter(x->x!=20).toArray()));
                    if(child.extendedToricClassKey().equals(middle.extendedToricClassKey()))throw new Found();
                }
            }));
        }));
    }
    private static final class Found extends RuntimeException { }
}
