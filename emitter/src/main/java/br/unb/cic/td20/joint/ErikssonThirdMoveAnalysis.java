package br.unb.cic.td20.joint;

/**
 * Active Eriksson two-sided reduction followed by one further move.
 * Source and target are interchangeable because their sorting distance is
 * symmetric. A target-side move is a source-side move on (omega^-1, rho).
 * A move on either side costs one in the resulting triangle-inequality bound.
 *
 * <p>First seek an immediate gain >=2 on either side. Otherwise enumerate every
 * first transposition (including gain zero), every second transposition leaving
 * at least three fixed symbols, and a third creating at least two more. Two
 * moves leaving >=4 fixed symbols already close by the paper's diameters.
 * Contract fixed points before every subsequent fixed-point-free move test.
 * No assumption that the first two moves are qualifying 2-moves is made.
 */
public final class ErikssonThirdMoveAnalysis {
    private ErikssonThirdMoveAnalysis() { }
    private record State(int[] omega,int[] beta) { }

    public static boolean closesOrHasPrefix(int[] omega,int[] beta) {
        CyclicTargetMoves.fastMoves(omega,beta); // Validates fixed-point-free input arrays.
        if (!CyclicTargetMoves.isFullTargetCycle(omega,beta))
            throw new IllegalArgumentException("Expected a valid cyclic-target pair");
        return closesOrHasPrefixUnchecked(omega,beta,false);
    }

    static boolean closesOrHasPrefixUnchecked(int[] omega,int[] beta,boolean allCurrentExcluded) {
        State root=new State(omega,beta);
        // Eriksson Lemma 5.1 excludes the reverse permutation. Theorem 4.2
        // independently gives ceil(N/2), which fits the configured root budget.
        if (omega.length==KnownDiameters.ROOT_SYMBOLS && (omega.length+1)/2<=KnownDiameters.MAX_BUDGET && reverse(root)) return true;
        State inverse=swap(root);
        if (hasFast(root) || hasFast(inverse)) return true;
        for (int firstSide=0;firstSide<2;firstSide++) {
            State start=firstSide==0 ? root:inverse;
            int[] current=start.beta;
            for (int i=0;i<current.length-2;i++) for (int j=i+1;j<current.length-1;j++)
                for (int k=j+1;k<current.length;k++) {
                    var first=new CyclicTargetMoves.Move(current[i],current[j],current[k]);
                    int firstGain=CyclicTargetMoves.gain(start.omega,first);
                    if (firstGain>=2) return true;
                    State next=applyAndContract(start,first);
                    for (int secondSide=0;secondSide<2;secondSide++) {
                        State secondState=secondSide==0 ? next:swap(next);
                        for (var second:CyclicTargetMoves.fastMoves(secondState.omega,secondState.beta)) {
                            int fixed=firstGain+CyclicTargetMoves.gain(secondState.omega,second);
                            if (fixed<3) continue;
                            if (fixed>=4) return true;
                            State endpoint=applyAndContract(secondState,second);
                            if (!(allCurrentExcluded && firstSide==0 && secondSide==0) && hasFast(endpoint)) return true;
                            if (hasFast(swap(endpoint))) return true;
                        }
                    }
                }
        }
        return false;
    }

    private static State applyAndContract(State state,CyclicTargetMoves.Move move) {
        int[] omega=CyclicTargetMoves.applyToOmega(state.omega,move);
        int[] beta=CyclicTargetMoves.applyToBeta(state.beta,move);
        var contracted=CyclicTargetMoves.contractFixedPoints(omega,beta);
        return new State(contracted.omega(),contracted.beta());
    }
    private static State swap(State state) {
        int n=state.omega.length;
        int[] inverse=new int[n],target=new int[n],cycle=new int[n];
        for (int x=0;x<n;x++) inverse[state.omega[x]]=x;
        for (int i=0;i<n;i++) target[state.beta[i]]=state.omega[state.beta[(i+1)%n]];
        int x=0;
        for (int i=0;i<n;i++) { cycle[i]=x; x=target[x]; }
        return new State(inverse,cycle);
    }
    private static boolean hasFast(State state) {
        int n=state.omega.length; int[] position=new int[n];
        for (int i=0;i<n;i++) position[state.beta[i]]=i;
        for (int a=0;a<n;a++) {
            int b=state.omega[a],c=state.omega[b];
            if (a==b || a==c || b==c) continue;
            if ((position[b]-position[a]+n)%n<(position[c]-position[a]+n)%n) return true;
        }
        return false;
    }
    private static boolean reverse(State state) {
        int n=state.omega.length;
        for (int i=0;i<n;i++) if (state.omega[state.beta[i]]!=state.beta[(i+n-2)%n]) return false;
        return true;
    }
}
