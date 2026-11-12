package br.unb.cic.td20;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CountTD20UnorientedResidualsTest {
    @TempDir Path directory;
    @Test void reportsRejectMissingDataWrongKeysAndSupersededFormats() throws Exception {
        Path report = directory.resolve("count.txt");
        CountTD20UnorientedResiduals.writeReport(report, 8, 1, "4,4", 1);
        assertEquals(1, CountTD20UnorientedResiduals.readReport(report, 8, 1, "4,4"));
        assertThrows(FileAlreadyExistsException.class, () -> CountTD20UnorientedResiduals.writeReport(report, 8, 1, "4,4", 1));
        assertThrows(IllegalArgumentException.class, () -> CountTD20UnorientedResiduals.readReport(report, 8, 2, "4,4"));
        assertThrows(IllegalArgumentException.class, () -> CountTD20UnorientedResiduals.readReport(report, 8, 1, "6,2"));
        String original = Files.readString(report);
        for (String damaged : new String[]{original.replace("complete=true", "complete=false"),
                original.replace("count-v1", "count-v0"), original.replace("classes=1", "classes=-1"),
                original.replace("implementation=java-joint-prefix-v1", "implementation=unknown")}) {
            Files.writeString(report, damaged);
            assertThrows(IllegalArgumentException.class, () -> CountTD20UnorientedResiduals.readReport(report, 8, 1, "4,4"));
        }
        Files.write(report, Files.readAllLines(report).subList(0, 6));
        assertThrows(IllegalArgumentException.class, () -> CountTD20UnorientedResiduals.readReport(report, 8, 1, "4,4"));
    }
    @Test void publishingRequiresAnExplicitUniqueInventoryRow() throws Exception {
        Path paper = directory.resolve("paper.tex");
        String row = Character.toString(92) + "unorientedresidualrow{1}{" + Character.toString(92) + "pending}";
        Files.writeString(paper, row + System.lineSeparator());
        CountTD20UnorientedResiduals.publish(paper, 1, 2);
        assertEquals(Character.toString(92) + "unorientedresidualrow{1}{2}", Files.readAllLines(paper).getFirst());
        assertThrows(IllegalArgumentException.class, () -> CountTD20UnorientedResiduals.publish(paper, 2, 2));
        Files.writeString(paper, row + System.lineSeparator() + row + System.lineSeparator());
        assertThrows(IllegalArgumentException.class, () -> CountTD20UnorientedResiduals.publish(paper, 1, 2));
    }
    @Test void smallDomainCountsCannotPublishToTheProductionPaper() {
        assertThrows(IllegalArgumentException.class, () -> CountTD20UnorientedResiduals.main(new String[]{"--n=8", "--publish"}));
    }
}
