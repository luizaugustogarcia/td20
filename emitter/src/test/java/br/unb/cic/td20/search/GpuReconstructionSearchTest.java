package br.unb.cic.td20.search;

import br.unb.cic.td20.certificate.AsyncCertificateWriter;
import br.unb.cic.td20.base.GPUSortingSearch;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="td20.gpuSmoke",matches="true")
class GpuReconstructionSearchTest {
    @Test void realCudaSearchPersistsAReplayedClosureWithoutCpuSorting(@TempDir Path directory) {
        GPUSortingSearch.init(new int[]{0},2,32L*1024*1024);
        var pair=CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),Cycle.of(0,1,2,3,4,5))
                .canonicalRepresentative();
        String previous=System.getProperty("td20.cpuPrefixDepth");
        System.setProperty("td20.cpuPrefixDepth","-1");
        try(var writer=new AsyncCertificateWriter(directory,60)) {
            WitnessSearch.installWriter(writer);
            try {
                var result=WitnessSearch.searchGpu(pair,2);
                assertEquals(WitnessSearch.Disposition.DIAMETER_CLOSURE,result.disposition());
                writer.flush();assertEquals(result.moves(),writer.find(pair,2).orElseThrow());
            } finally {WitnessSearch.uninstallWriter(writer);WitnessSearch.shutdownGpuStats();}
        } finally {
            if(previous==null)System.clearProperty("td20.cpuPrefixDepth");else System.setProperty("td20.cpuPrefixDepth",previous);
        }
    }
}
