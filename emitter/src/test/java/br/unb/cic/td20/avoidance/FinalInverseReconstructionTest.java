package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.model.PrimitiveCanonicalizer;
import br.unb.cic.td20.model.PrimitivePair;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FinalInverseReconstructionTest {
    @Test void exactResidualMultisetsMatchArbitrarySupportOracleThroughSevenSymbols() {
        var complete = new PrimitiveInverseReconstruction();
        var filtered = new FinalInverseReconstruction();
        var closure = new ImmediateRootClosure();
        var canonical = new PrimitiveCanonicalizer();
        long[] checked = {0};
        for (int n = 3; n <= 7; n++) {
            int[] beta = new int[n]; Arrays.setAll(beta, i -> i);
            permutations(beta.clone(), 0, omega -> {
                for (int x = 0; x < omega.length; x++) if (omega[x] == x) return;
                if (!CyclicTargetMoves.isFullTargetCycle(omega, beta)) return;
                var source = PrimitivePair.of(omega, beta);
                for (int gain = 0; gain <= 1; gain++) {
                    var expected = new HashMap<ByteBuffer, Long>();
                    var actual = new HashMap<ByteBuffer, Long>();
                    complete.preimages(source, gain, (w,b,length) -> {
                        boolean easy = arbitraryClosure(w,b,length);
                        assertEquals(easy, closure.closes(w,b,length));
                        if (!easy) expected.merge(ByteBuffer.wrap(canonical.keyForKnownPair(w,b,length)),1L,Long::sum);
                    });
                    long parents = filtered.preimages(source, gain, (w,b,length) -> {
                        if (!closure.closes(w,b,length))
                            actual.merge(ByteBuffer.wrap(canonical.keyForKnownPair(w,b,length)),1L,Long::sum);
                    });
                    assertEquals(expected,actual,Arrays.toString(omega)+" gain="+gain);
                    assertTrue(parents <= filtered.candidateSlots()-filtered.familyClosedSlots());
                    checked[0]++;
                }
            });
        }
        assertTrue(checked[0] > 100);
    }

    @Test void actualRootDomainsPersistExactlyTheResidualClassesAndRejectIncompleteSets(@TempDir Path directory) {
        var expected = new TreeSet<String>();
        var closure = new ImmediateRootClosure();
        var canonical = new PrimitiveCanonicalizer();
        List<PrimitivePair> sources = List.of(reverseSource(20),reverseSource(21));
        for (var source : sources) new PrimitiveInverseReconstruction().preimages(source,21-source.size(),(w,b,n)-> {
            boolean easy=arbitraryClosure(w,b,n);
            assertEquals(easy,closure.closes(w,b,n));
            if (!easy) expected.add(HexFormat.of().formatHex(canonical.keyForKnownPair(w,b,n)));
        });
        assertFalse(expected.isEmpty(),"This fixture must exercise actual residual obligations");
        try (var states = new DiskStateSet(directory.resolve("roots"))) {
            for (var source:sources) states.addFinalInverseGenerated(source);
            assertEquals(expected.size(),states.seal());
            var actual = new TreeSet<String>();
            states.forEach(state -> {
                assertEquals(11,state.maximumMoves());
                assertFalse(closure.closes(state.primitivePair()));
                actual.add(HexFormat.of().formatHex(state.primitivePair().canonicalKey()));
            });
            assertEquals(expected,actual);
            var progress=states.finalInverseProgress();
            assertEquals(10260,progress.candidateSlots());
            assertEquals(states.writeProgress().generatedKeyOccurrences(),
                    progress.replayedParents()-progress.immediateClosedParents());
        }
        assertThrows(IllegalStateException.class,()->DiskStateSet.openSealed(directory.resolve("roots"),expected.size()+1));
        try (var states=new DiskStateSet(directory.resolve("failed"))) {
            int[] identity=new int[20];Arrays.setAll(identity,i->i);
            assertThrows(IllegalArgumentException.class,()->states.addFinalInverseGenerated(
                    PrimitivePair.of(identity,identity)));
            assertThrows(IllegalStateException.class,states::seal);
        }
    }

    @Test void filteredCheckpointCannotMasqueradeAsAnUnfilteredOrIntermediateInventory(@TempDir Path directory) throws Exception {
        var checkpoint=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.ROOTS,1,2,3,4,
                ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION,ErikssonCheckpoint.RootInventory.ELEMENTARY_RESIDUALS);
        checkpoint.write(directory); assertEquals(checkpoint,ErikssonCheckpoint.read(directory));
        var fresh=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.ROOTS,1,2,3,4,
                ErikssonCheckpoint.Mode.FULL_SEARCH,ErikssonCheckpoint.RootInventory.ELEMENTARY_RESIDUALS);
        fresh.write(directory);assertEquals(fresh,ErikssonCheckpoint.read(directory));
        Path path=directory.resolve("eriksson-reconstruction-v1.txt");
        var lines=java.nio.file.Files.readAllLines(path);
        assertEquals("td-eriksson-reconstruction-v4",lines.getFirst());
        java.nio.file.Files.write(path,lines.subList(0,10));
        assertThrows(IllegalStateException.class,()->ErikssonCheckpoint.read(directory));
        lines.set(0,"td-eriksson-reconstruction-v2");java.nio.file.Files.write(path,lines);
        assertThrows(IllegalStateException.class,()->ErikssonCheckpoint.read(directory));
        assertThrows(IllegalArgumentException.class,()->new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,1,2,3,-1,
                ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION,ErikssonCheckpoint.RootInventory.ELEMENTARY_RESIDUALS));
    }

    static PrimitivePair reverseSource(int n) {
        int[] omega=new int[n],beta=new int[n];
        for (int i=0;i<n;i++) { omega[i]=(i+n-2)%n; beta[i]=i; }
        return PrimitivePair.of(omega,beta);
    }

    static boolean arbitraryClosure(int[] omega,int[] beta,int n) {
        if (oneSide(omega,beta,n)) return true;
        var pair=PrimitivePair.of(Arrays.copyOf(omega,n),Arrays.copyOf(beta,n));
        return oneSide(pair.swapped().omegaImages(),pair.rhoSymbols(),n);
    }
    private static boolean oneSide(int[] omega,int[] beta,int n) {
        for (int i=0;i<n-2;i++) for (int j=i+1;j<n-1;j++) for (int k=j+1;k<n;k++) {
            int[] changed=Arrays.copyOf(omega,n);
            int a=beta[i],b=beta[j],c=beta[k];
            changed[a]=omega[c];changed[b]=omega[a];changed[c]=omega[b];
            int fixed=0; for (int x=0;x<n;x++) if (changed[x]==x) fixed++;
            if (fixed>=2) return true;
        }
        return false;
    }
    private static void permutations(int[] p,int index,Consumer<int[]> output) {
        if(index==p.length) { output.accept(p);return; }
        for(int j=index;j<p.length;j++) {
            int t=p[index];p[index]=p[j];p[j]=t;permutations(p,index+1,output);t=p[index];p[index]=p[j];p[j]=t;
        }
    }
}
