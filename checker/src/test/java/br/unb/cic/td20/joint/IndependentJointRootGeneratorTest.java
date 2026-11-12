package br.unb.cic.td20.joint;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.reduction.Partitions;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class IndependentJointRootGeneratorTest {
    @Test void fullClassSetsAgreeWithUnprunedPermutationEnumerationThroughNine() {
        for (int n=3;n<=9;n++) {
            final Set<String> actual=new HashSet<>();
            for (final var partition:Partitions.generateValidPartitions(n)) {
                final Set<String> forPartition=new HashSet<>();
                IndependentJointRootGenerator.generateResiduals(partition,pair->{
                    assertTrue(JointMoves.residual(pair));
                    final var key=key(pair);
                    assertTrue(actual.add(key),"Duplicate emitted class");
                    assertTrue(forPartition.add(key));
                });
                final Set<String> parallel=ConcurrentHashMap.newKeySet();
                IndependentJointRootGenerator.generateResidualsConcurrently(partition,4,()->{},pair->{
                    assertTrue(JointMoves.residual(pair));
                    assertTrue(parallel.add(key(pair)),"Duplicate parallel class");
                });
                assertEquals(forPartition,parallel,"Parallel prefix split mismatch");
            }
            final Set<String> expected=new HashSet<>();
            final int[] p=JointMoves.identity(n);
            do {
                if(JointMoves.fixed(p)!=0||!fullTarget(p)) continue;
                final var pair=pair(p);
                if(!Arrays.equals(CertificateCodec.keyFor(pair),CertificateCodec.keyFor(pair.canonicalRepresentative()))) continue;
                if(JointMoves.residual(pair)) expected.add(key(pair));
            } while(next(p));
            assertEquals(expected,actual,"Full class-set mismatch for N="+n);
        }
    }
    @Test void nineteenCycleRegeneratesAll667ResidualClasses() {
        final Set<String> actual=new HashSet<>();
        IndependentJointRootGenerator.generateResidualsConcurrently(new int[]{19},4,()->{},pair->{
            assertTrue(JointMoves.residual(pair));
            assertTrue(actual.add(key(pair)));
        });
        assertEquals(667,actual.size());
    }
    private static boolean fullTarget(final int[] p) {
        int x=0,length=0;
        do {x=p[(x+1)%p.length];length++;}while(x!=0&&length<p.length);
        return x==0&&length==p.length;
    }
    static CyclicTargetPair pair(final int[] p) {
        final var omega=new MulticyclePermutation();final boolean[] seen=new boolean[p.length];
        for(int i=0;i<p.length;i++) {
            if(seen[i]) continue;
            final int[] buffer=new int[p.length];int length=0,x=i;
            do {seen[x]=true;buffer[length++]=x;x=p[x];}while(x!=i);
            omega.add(Cycle.of(Arrays.copyOf(buffer,length)));
        }
        return CyclicTargetPair.of(omega,Cycle.of(JointMoves.identity(p.length)));
    }
    private static String key(final CyclicTargetPair pair) {
        return HexFormat.of().formatHex(CertificateCodec.keyFor(pair.canonicalRepresentative()));
    }
    private static boolean next(final int[] p) {
        int i=p.length-2;while(i>=0&&p[i]>=p[i+1])i--;if(i<0)return false;
        int j=p.length-1;while(p[j]<=p[i])j--;int t=p[i];p[i]=p[j];p[j]=t;
        for(int a=i+1,b=p.length-1;a<b;a++,b--){t=p[a];p[a]=p[b];p[b]=t;}return true;
    }
}
