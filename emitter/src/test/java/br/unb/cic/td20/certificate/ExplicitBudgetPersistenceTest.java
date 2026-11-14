package br.unb.cic.td20.certificate;

import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitBudgetPersistenceTest {
    private static CyclicTargetPair pair() {
        int n=KnownDiameters.ROOT_SYMBOLS-3;
        int[] symbols=new int[n]; for (int i=0;i<n;i++) symbols[i]=i;
        int[] omega=new int[n]; for (int i=0;i<n;i++) omega[i]=(i+n-2)%n;
        var p=new MulticyclePermutation(); boolean[] seen=new boolean[n];
        for (int x=0;x<n;x++) if (!seen[x]) {
            List<Integer> cycle=new ArrayList<>(); int y=x;
            do { seen[y]=true; cycle.add(y); y=omega[y]; } while (y!=x);
            p.add(Cycle.of(cycle.stream().mapToInt(Integer::intValue).toArray()));
        }
        return CyclicTargetPair.ofOwned(p,Cycle.of(symbols)).canonicalRepresentative();
    }
    @Test void sameDomainCanHaveDifferentBudgetsAndLongerValidResultIsAMiss(@TempDir Path directory) {
        var pair=pair(); int required=KnownDiameters.MAX_BUDGET-1,smaller=required-1;
        assertEquals(required,CertificateValidator.requiredBudget(pair,SortingCertificate.closure(List.of())));
        try (var writer=new AsyncCertificateWriter(directory.resolve("proof"),60)) {
            writer.enqueueClosure(pair,List.of(),required); writer.flush();
            assertTrue(writer.findResult(pair,required).isPresent());
            assertTrue(writer.findResult(pair,smaller).isEmpty());
            assertTrue(writer.findResults(List.of(pair),smaller).getFirst().isEmpty());
            writer.enqueueObstruction(pair,smaller); writer.flush();
            List<byte[]> missing=new ArrayList<>(); writer.forEachObstruction(missing::add);
            assertEquals(1,missing.size(),"A larger-budget result cannot suppress the smaller obligation");
        }
    }
}
