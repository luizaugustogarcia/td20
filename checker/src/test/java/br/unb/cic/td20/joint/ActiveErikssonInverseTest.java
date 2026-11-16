package br.unb.cic.td20.joint;


import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ActiveErikssonInverseTest {
    record Child(int gain,ExtendedToricClassKey key) { }
    @Test void arbitraryMovesOnBothCyclesHaveExactlyTheRegeneratedPreimages() {
        Set<Integer> gains=new HashSet<>(); int checked=0;
        for (int n=3;n<=7;n++) {
            Map<Child,Set<ExtendedToricClassKey>> expected=new HashMap<>();
            Map<Child,CyclicTargetPair> children=new HashMap<>();
            int[] beta=IntStream.range(0,n).toArray();
            permutations(beta.clone(),0,omega -> {
                for (int x=0;x<omega.length;x++) if (omega[x]==x) return;
                int[] target=new int[omega.length]; int x=0,seen=0;
                for (int i=0;i<omega.length;i++) { if ((seen&(1<<x))!=0) return; seen|=1<<x; target[i]=x; x=omega[(x+1)%omega.length]; }
                if (x!=0) return;
                var parent=pair(omega,beta).extendedToricClassKey();
                for (int side=0;side<2;side++) {
                    int[] cycle=side==0 ? beta:target;
                    for (int a=0;a<cycle.length-2;a++) for (int b=a+1;b<cycle.length-1;b++) for (int c=b+1;c<cycle.length;c++) {
                        int[] changed=cycle.clone();
                        System.arraycopy(cycle,b,changed,a,c-b); System.arraycopy(cycle,a,changed,a+c-b,b-a);
                        int[] current=side==0 ? changed:beta, rho=side==0 ? target:changed;
                        int[] p=images(current,rho); int gain=0;
                        for (int i=0;i<p.length;i++) if (p[i]==i) gain++;
                        if (p.length-gain<3) continue;
                        CyclicTargetPair child=contract(p,current);
                        Child key=new Child(gain,child.extendedToricClassKey());
                        expected.computeIfAbsent(key,ignored -> new HashSet<>()).add(parent); children.putIfAbsent(key,child.canonicalRepresentative());
                    }
                }
            });
            for (var entry:expected.entrySet()) {
                Set<ExtendedToricClassKey> actual=new HashSet<>();
                ErikssonInverseReconstruction.preimages(children.get(entry.getKey()),entry.getKey().gain,p -> actual.add(p.extendedToricClassKey()));
                assertEquals(entry.getValue(),actual,"N="+n+" gain="+entry.getKey().gain+" child="+entry.getKey().key);
                gains.add(entry.getKey().gain); checked++;
            }
        }
        assertEquals(Set.of(0,1,2,3),gains); assertTrue(checked>20);
    }
    private static int[] images(int[] current,int[] target) {
        int n=current.length; int[] previous=new int[n],next=new int[n],omega=new int[n];
        for (int i=0;i<n;i++) { previous[current[(i+1)%n]]=current[i]; next[target[i]]=target[(i+1)%n]; }
        for (int i=0;i<n;i++) omega[i]=next[previous[i]]; return omega;
    }
    private static CyclicTargetPair contract(int[] omega,int[] current) {
        int[] labels=new int[omega.length]; Arrays.fill(labels,-1); int n=0;
        for (int i=0;i<omega.length;i++) if (omega[i]!=i) labels[i]=n++;
        int[] p=new int[n],beta=new int[n]; int at=0;
        for (int i=0;i<omega.length;i++) if (labels[i]>=0) p[labels[i]]=labels[omega[i]];
        for (int i:current) if (labels[i]>=0) beta[at++]=labels[i]; return pair(p,beta);
    }
    private static CyclicTargetPair pair(int[] omega,int[] beta) {
        MulticyclePermutation p=new MulticyclePermutation(); boolean[] seen=new boolean[omega.length];
        for (int x=0;x<omega.length;x++) if (!seen[x]) {
            List<Integer> symbols=new ArrayList<>(); int y=x;
            do { symbols.add(y); seen[y]=true; y=omega[y]; } while (y!=x);
            p.add(Cycle.of(symbols.stream().mapToInt(Integer::intValue).toArray()));
        }
        return CyclicTargetPair.ofOwned(p,Cycle.of(beta));
    }
    private static void permutations(int[] p,int index,Consumer<int[]> output) {
        if (index==p.length) { output.accept(p); return; }
        for (int j=index;j<p.length;j++) { int t=p[index]; p[index]=p[j]; p[j]=t; permutations(p,index+1,output); t=p[index]; p[index]=p[j]; p[j]=t; }
    }
}
