package br.unb.cic.td20.fleet;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetHardwareTest {
    private static final String GPU_PROFILE = "-R-Xmx2g -R-XX:ActiveProcessorCount=8 -R-Dtd20.twistedBraceletWorkers=15 "
            + "-R-Dtd20.gpuDevices=0 -R-Dtd20.gpuSlots=7 -R-Dtd20.gpuBudgetMB=64";
    private static FleetHardware hardware(int sockets, int nodes) {
        return new FleetHardware(16,sockets,nodes,8L << 30,List.of(new FleetHardware.Gpu(0,2048,4096,89,"Test GPU")));
    }
    private static FleetServer server(String profile) {
        return new FleetServer("test",List.of("ssh","host"),"/workspace/test","89",profile);
    }
    @Test void numaIsAddedForMultipleSocketsOrNodesAndCannotBeDisabled() {
        for (var resources : List.of(hardware(2,1),hardware(1,2),hardware(2,4))) {
            String options = server(GPU_PROFILE).runtimeOptions(resources);
            assertEquals(1,FleetFiles.words(options).stream().filter(option -> option.equals("-R-XX:+UseNUMA")).count());
            assertEquals(options,server(GPU_PROFILE + " -R-XX:+UseNUMA").runtimeOptions(resources));
            assertThrows(IllegalArgumentException.class,() -> server(GPU_PROFILE + " -R-XX:-UseNUMA").runtimeOptions(resources));
        }
        assertEquals(GPU_PROFILE,server(GPU_PROFILE).runtimeOptions(hardware(1,1)));
        assertFalse(server(GPU_PROFILE).runtimeOptions(hardware(1,1)).contains("gpuSlots=32"));
    }
    @Test void eachProfileIsCheckedAgainstItsOwnAllocatedResources() {
        var resources = hardware(1,1);
        assertThrows(IllegalArgumentException.class,() -> server(GPU_PROFILE.replace("ActiveProcessorCount=8","ActiveProcessorCount=32")).runtimeOptions(resources));
        assertThrows(IllegalArgumentException.class,() -> server(GPU_PROFILE.replace("-R-Xmx2g","-R-Xmx8g")).runtimeOptions(resources));
        assertThrows(IllegalArgumentException.class,() -> server(GPU_PROFILE.replace("gpuBudgetMB=64","gpuBudgetMB=512")).runtimeOptions(resources));
        assertThrows(IllegalArgumentException.class,() -> server(GPU_PROFILE.replace("gpuDevices=0","gpuDevices=1")).runtimeOptions(resources));
        assertThrows(IllegalArgumentException.class,() -> new FleetServer("test",List.of("ssh","host"),"/workspace/test","120",GPU_PROFILE).runtimeOptions(resources));
        assertThrows(IllegalArgumentException.class,() -> server(GPU_PROFILE + " -R-Xmx1g").runtimeOptions(resources));
        assertThrows(IllegalArgumentException.class,() -> server(GPU_PROFILE.replace("gpuSlots=7","gpuSlots=0")).runtimeOptions(resources));
    }
    @Test void profilesLoadRelativeToTheServerFileAndInspectionAllowsUntunedRows(@TempDir Path directory) throws Exception {
        Path profiles = Files.createDirectory(directory.resolve("profiles"));
        Files.writeString(profiles.resolve("a.jvm"),"# First server\n" + GPU_PROFILE.replace(" ","\n") + "\n");
        String other = GPU_PROFILE.replace("-R-Xmx2g","-R-Xmx1g").replace("ActiveProcessorCount=8","ActiveProcessorCount=4").replace("gpuSlots=7","gpuSlots=3");
        Files.writeString(profiles.resolve("b.jvm"),other.replace(" ","\n"));
        Path file = directory.resolve("servers.tsv");
        Files.writeString(file,"a\tssh host-a\t/workspace/a\t89\t@profiles/a.jvm\n"
                + "b\tssh host-b\t/workspace/b\t89\t@profiles/b.jvm\n");
        var servers = FleetServer.read(file);
        assertEquals(GPU_PROFILE,servers.getFirst().runtimeOptions()); assertEquals(other,servers.getLast().runtimeOptions());
        Files.writeString(file,"unknown\tssh host\t/workspace/unknown\t120\t\n");
        assertThrows(IllegalArgumentException.class,() -> FleetServer.read(file));
        assertEquals("",FleetServer.read(file,false).getFirst().runtimeOptions());
    }
    @Test void hardwareRecordsRejectIncompleteData(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("hardware.txt");
        Files.write(file,hardware(2,4).lines()); assertEquals(hardware(2,4),FleetHardware.read(file));
        Files.write(file,hardware(2,4).lines().subList(0,6));
        assertThrows(IllegalArgumentException.class,() -> FleetHardware.read(file));
    }
    @Test void topologyRespectsCpuAndMemoryAffinity(@TempDir Path directory) throws Exception {
        Path system = Files.createDirectory(directory.resolve("system"));
        Path status = directory.resolve("status");
        for (int cpu : new int[]{0,1,4,5}) {
            Path topology = system.resolve("cpu/cpu" + cpu + "/topology"); Files.createDirectories(topology);
            Files.writeString(topology.resolve("physical_package_id"),cpu < 4 ? "0\n" : "1\n");
        }
        Files.createDirectories(system.resolve("node")); Files.writeString(system.resolve("node/online"),"0-3\n");
        Files.writeString(status,"Cpus_allowed_list:\t0-1,4-5\nMems_allowed_list:\t0-1\n");
        assertArrayEquals(new int[]{2,2},FleetHardware.topology(system,status));
        Files.writeString(status,"Cpus_allowed_list:\t4\nMems_allowed_list:\t2\n");
        assertArrayEquals(new int[]{1,1},FleetHardware.topology(system,status));
        Files.writeString(status,"Cpus_allowed_list:\t4\nMems_allowed_list:\t5\n");
        assertThrows(IllegalArgumentException.class,() -> FleetHardware.topology(system,status));
    }
}
