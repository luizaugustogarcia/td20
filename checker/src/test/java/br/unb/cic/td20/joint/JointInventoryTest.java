package br.unb.cic.td20.joint;

import br.unb.cic.td20.runtime.ProjectPaths;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JointInventoryTest {
    @Test void sourcePaperAndIndependentInventoriesAgree() throws Exception {
        final var roots=JointInventory.read(ProjectPaths.root(Path.of("")).resolve("docs/td20-reduction-count.tex"));
        assertEquals(JointInventory.ROOT_COUNT,roots.size());
        assertArrayEquals(new int[]{21}, roots.getFirst().partition());
        assertTrue(roots.stream().mapToLong(r -> r.unorientedClasses() + r.orientedClasses()).sum() > 0);
        final var endpoints=JointInventory.endpoints();
        assertEquals(JointInventory.ENDPOINT_COUNT,endpoints.size());
        assertEquals(52,endpoints.stream().filter(JointInventory.Endpoint::elementaryClosure).count());
        assertEquals(9,KnownDiameters.knownDiameter(17));
        assertEquals(8,KnownDiameters.knownDiameter(15));
        assertEquals(10,KnownDiameters.knownDiameter(19));
        assertEquals(11,KnownDiameters.knownDiameter(20));
        assertTrue(KnownDiameters.closes(19,1));
        assertFalse(KnownDiameters.closes(19,2));
        assertEquals(Integer.MAX_VALUE,KnownDiameters.knownDiameter(21));
    }
}
