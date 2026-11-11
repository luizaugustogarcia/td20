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
                    assertTrue(oracleResidual(pair));
                    final var key=key(pair);
                    assertTrue(actual.add(key),"Duplicate emitted class");
                    assertTrue(forPartition.add(key));
                });
                final Set<String> parallel=ConcurrentHashMap.newKeySet();
                IndependentJointRootGenerator.generateResidualsConcurrently(partition,()->{},pair->{
                    assertTrue(JointMoves.residual(pair));
                    assertTrue(oracleResidual(pair));
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
                final boolean oracle=oracleResidual(pair);
                assertEquals(oracle,JointMoves.residual(pair),"Move replay mismatch at N="+n);
                if(oracle) expected.add(key(pair));
            } while(next(p));
            assertEquals(expected,actual,"Full class-set mismatch for N="+n);
        }
    }
    @Test void rejectsDomainsBeyondTheFiveBitJointThreeScope() {
        assertThrows(IllegalArgumentException.class,
                () -> IndependentJointRootGenerator.generateResiduals(new int[]{23}, ignored -> fail()));
    }
    /** Small-domain oracle written as a state recursion rather than generator constraints. */
    private static boolean oracleResidual(final CyclicTargetPair pair) {
        final int[] images=JointMoves.images(pair),beta=pair.getBeta().getSymbols();
        if(JointMoves.fixed(images)!=0)throw new IllegalArgumentException("Contract fixed points first");
        return !JointMoves.totallyUnoriented(images,beta)&&!favorable(images,beta,0,0);
    }
    private static boolean favorable(final int[] images,final int[] beta,final int depth,final int secondGain) {
        final int initialOdd=JointMoves.odd(images),n=images.length;
        for(int i=0;i<n-2;i++)for(int j=i+1;j<n-1;j++)for(int k=j+1;k<n;k++) {
            final int a=beta[i],b=beta[j],c=beta[k];
            if(images[a]==a||images[b]==b||images[c]==c)continue;
            final int gain=JointMoves.gain(images,a,b,c);
            if(gain==0)continue;
            final int[] successor=JointMoves.apply(images,a,b,c);
            if(JointMoves.odd(successor)!=initialOdd+2)continue;
            if(depth==0&&gain>=2||depth==1&&gain==3
                    ||depth==2&&(secondGain==1&&gain==3||secondGain==2&&gain>=2))return true;
            if(depth==0&&gain!=1||depth==1&&(gain!=1&&gain!=2)||depth>=2)continue;
            final int[] nextBeta=JointMoves.transpose(beta,i,j,k);
            if(JointMoves.totallyUnoriented(successor,nextBeta))continue;
            if(favorable(successor,nextBeta,depth+1,depth==1?gain:secondGain))return true;
        }
        return false;
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
