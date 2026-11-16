package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.joint.CyclicTargetMoves.Move;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.util.Arrays;
import java.util.function.Consumer;

/** Complete inverse gains 0..3 on both endpoints, with exact forward replay. */
public final class ErikssonInverseReconstruction {
    private ErikssonInverseReconstruction() { }
    public static void preimages(CyclicTargetPair child,int gain,Consumer<CyclicTargetPair> output) {
        if (gain<0 || gain>3 || child.getBeta().size()+gain>ErikssonRoute.ROOT_SYMBOLS)
            throw new IllegalArgumentException("Invalid inverse gain or domain");
        for (int x:child.getBeta().getSymbols()) if (child.getOmega().image(x)==x)
            throw new IllegalArgumentException("Contract fixed points before reconstruction");
        source(child,gain,output);
        source(swap(child),gain,parent -> output.accept(swap(parent)));
    }
    public static CyclicTargetPair swap(CyclicTargetPair pair) {
        return CyclicTargetPair.of(pair.getOmega().getInverse(),pair.getRho());
    }
    private static void source(CyclicTargetPair child,int gain,Consumer<CyclicTargetPair> output) {
        int m=child.getBeta().size(),n=m+gain;
        int[] omega=new int[n];
        for (int x=0;x<n;x++) omega[x]=x<m ? child.getOmega().image(x):x;
        gaps(child,omega,new int[gain],0,1,output);
    }
    private static void gaps(CyclicTargetPair child,int[] omega,int[] slots,int depth,int minimum,
                             Consumer<CyclicTargetPair> output) {
        if (depth<slots.length) {
            for (int slot=minimum;slot<omega.length-(slots.length-depth-1);slot++) {
                slots[depth]=slot; gaps(child,omega,slots,depth+1,slot+1,output);
            }
            return;
        }
        int m=child.getBeta().size(),gain=slots.length;
        int[] beta=new int[omega.length]; int old=0,restored=0;
        for (int i=0;i<beta.length;i++) beta[i]=restored<gain && slots[restored]==i ? m+restored++:child.getBeta().get(old++);
        if (gain==3) reverse(omega,beta,m,m+1,m+2,gain,output);
        else if (gain==2) for (int x=0;x<m;x++) reverse(omega,beta,m,m+1,x,gain,output);
        else if (gain==1) for (int x=0;x<m-1;x++) for (int y=x+1;y<m;y++) reverse(omega,beta,m,x,y,gain,output);
        else for (int x=0;x<m-2;x++) for (int y=x+1;y<m-1;y++) for (int z=y+1;z<m;z++) reverse(omega,beta,x,y,z,gain,output);
    }
    private static void reverse(int[] childOmega,int[] childBeta,int a,int b,int c,int gain,Consumer<CyclicTargetPair> output) {
        Move inverse=ordered(childBeta,a,b,c);
        int[] omega=CyclicTargetMoves.applyToOmega(childOmega,inverse);
        for (int x=0;x<omega.length;x++) if (omega[x]==x) return;
        int[] beta=CyclicTargetMoves.applyToBeta(childBeta,inverse);
        Move forward=ordered(beta,a,b,c);
        if (CyclicTargetMoves.gain(omega,forward)!=gain) return;
        if (!Arrays.equals(childOmega,CyclicTargetMoves.applyToOmega(omega,forward))
                || !Arrays.equals(childBeta,CyclicTargetMoves.applyToBeta(beta,forward)))
            throw new IllegalStateException("Eriksson inverse failed actual forward replay");
        output.accept(pair(omega,beta));
    }
    private static Move ordered(int[] beta,int a,int b,int c) {
        int[] values=new int[3]; int size=0;
        for (int x:beta) if (x==a || x==b || x==c) values[size++]=x;
        if (size!=3) throw new IllegalArgumentException("Support outside current cycle");
        return new Move(values[0],values[1],values[2]);
    }
    private static CyclicTargetPair pair(int[] omega,int[] beta) {
        MulticyclePermutation permutation=new MulticyclePermutation(); boolean[] seen=new boolean[omega.length];
        for (int x=0;x<omega.length;x++) if (!seen[x]) {
            int[] cycle=new int[omega.length]; int size=0,y=x;
            do { cycle[size++]=y; seen[y]=true; y=omega[y]; } while (y!=x);
            permutation.add(Cycle.of(Arrays.copyOf(cycle,size)));
        }
        return CyclicTargetPair.ofOwned(permutation,Cycle.of(beta));
    }
}
