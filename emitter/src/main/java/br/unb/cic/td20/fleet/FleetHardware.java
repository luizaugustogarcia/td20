package br.unb.cic.td20.fleet;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Hardware visible to an unconfigured JVM, before applying a server's execution profile. */
public record FleetHardware(int cpuThreads, int sockets, int numaNodes, long totalMemoryBytes, List<Gpu> gpus) {
    public record Gpu(int index, long freeMiB, long totalMiB, int architecture, String name) {
        public Gpu {
            if (index < 0 || freeMiB < 0 || totalMiB < freeMiB || architecture < 1 || name.isBlank())
                throw new IllegalArgumentException("Invalid GPU inventory");
        }
    }
    public FleetHardware {
        if (cpuThreads < 1 || sockets < 1 || numaNodes < 1 || totalMemoryBytes < 1)
            throw new IllegalArgumentException("Incomplete CPU, NUMA or memory inventory");
        gpus = List.copyOf(gpus);
        var devices = new HashSet<Integer>();
        for (var gpu : gpus) if (!devices.add(gpu.index())) throw new IllegalArgumentException("Repeated GPU device");
    }
    boolean requiresNuma() { return sockets > 1 || numaNodes > 1; }
    List<String> lines() {
        var lines = new ArrayList<>(List.of("td20-fleet-hardware-v1","cpuThreads=" + cpuThreads,
                "sockets=" + sockets,"numaNodes=" + numaNodes,"totalMemoryBytes=" + totalMemoryBytes,"gpus=" + gpus.size()));
        for (var gpu : gpus) lines.add("gpu=" + gpu.index + "," + gpu.freeMiB + "," + gpu.totalMiB + "," + gpu.architecture + ","
                + Base64.getEncoder().encodeToString(gpu.name.getBytes(StandardCharsets.UTF_8)));
        return List.copyOf(lines);
    }
    public static FleetHardware read(Path file) throws IOException {
        var lines = Files.readAllLines(file);
        if (lines.size() < 6 || !lines.getFirst().equals("td20-fleet-hardware-v1")) throw new IllegalArgumentException("Incompatible hardware inventory");
        int count = Integer.parseInt(FleetFiles.field(lines,5,"gpus"));
        if (count < 0 || lines.size() != 6L + count) throw new IllegalArgumentException("Incomplete GPU inventory");
        var gpus = new ArrayList<Gpu>();
        for (int i = 0; i < count; i++) {
            String[] fields = FleetFiles.field(lines,i + 6,"gpu").split(",",-1);
            if (fields.length != 5) throw new IllegalArgumentException("Malformed GPU inventory");
            gpus.add(new Gpu(Integer.parseInt(fields[0]),Long.parseLong(fields[1]),Long.parseLong(fields[2]),
                    Integer.parseInt(fields[3]),new String(Base64.getDecoder().decode(fields[4]),StandardCharsets.UTF_8)));
        }
        return new FleetHardware(Integer.parseInt(FleetFiles.field(lines,1,"cpuThreads")),Integer.parseInt(FleetFiles.field(lines,2,"sockets")),
                Integer.parseInt(FleetFiles.field(lines,3,"numaNodes")),Long.parseLong(FleetFiles.field(lines,4,"totalMemoryBytes")),gpus);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].startsWith("--output=") || !List.of("--gpu","--cpu").contains(args[1]))
            throw new IllegalArgumentException("fleet-hardware --output=PATH --gpu|--cpu");
        var topology = topology(Path.of("/sys/devices/system"),Path.of("/proc/self/status"));
        long memory = ((OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        var gpus = new ArrayList<Gpu>();
        if (args[1].equals("--gpu")) {
            Path capture = Files.createTempFile("td20-gpu-inventory-",".csv"); String output;
            try {
                var process = new ProcessBuilder("bash","-ic","nvidia-smi --query-gpu=index,memory.free,memory.total,compute_cap,name --format=csv,noheader,nounits > " + FleetFiles.quote(capture.toString()))
                        .inheritIO().start();
                if (process.waitFor() != 0) throw new IllegalStateException("Cannot inspect GPU devices");
                output = Files.readString(capture);
            } finally { Files.deleteIfExists(capture); }
            for (String line : output.lines().toList()) {
                String[] fields = line.split(",\\s*",5);
                if (fields.length != 5 || !fields[3].matches("[0-9]+\\.[0-9]")) throw new IllegalArgumentException("Malformed nvidia-smi inventory");
                gpus.add(new Gpu(Integer.parseInt(fields[0].strip()),Long.parseLong(fields[1].strip()),Long.parseLong(fields[2].strip()),
                        Integer.parseInt(fields[3].replace(".","")),fields[4].strip()));
            }
            if (gpus.isEmpty()) throw new IllegalStateException("No GPU devices found");
        }
        var hardware = new FleetHardware(Runtime.getRuntime().availableProcessors(),topology[0],topology[1],memory,gpus);
        FleetFiles.write(Path.of(args[0].substring(9)).toAbsolutePath(),hardware.lines());
        System.out.println(hardware);
    }
    static int[] topology(Path system, Path status) throws IOException {
        var values = Files.readAllLines(status);
        Set<Integer> cpus = allowed(values,"Cpus_allowed_list:");
        var packages = new HashSet<Integer>();
        for (int cpu : cpus) packages.add(Integer.parseInt(Files.readString(system.resolve("cpu/cpu" + cpu + "/topology/physical_package_id")).strip()));
        var nodes = indices(Files.readString(system.resolve("node/online")).strip());
        nodes.retainAll(allowed(values,"Mems_allowed_list:"));
        if (packages.isEmpty() || packages.stream().anyMatch(id -> id < 0) || nodes.isEmpty())
            throw new IllegalArgumentException("Cannot establish CPU socket and NUMA topology");
        return new int[]{packages.size(),nodes.size()};
    }
    private static Set<Integer> allowed(List<String> lines, String name) {
        return indices(lines.stream().filter(line -> line.startsWith(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing topology affinity: " + name)).substring(name.length()).strip());
    }
    private static Set<Integer> indices(String text) {
        var result = new HashSet<Integer>();
        for (String part : text.split(",",-1)) {
            String[] range = part.split("-",-1);
            if (range.length > 2) throw new IllegalArgumentException("Invalid CPU/node list");
            int first = Integer.parseInt(range[0]), last = Integer.parseInt(range[range.length - 1]);
            if (first < 0 || last < first || last > 1_000_000) throw new IllegalArgumentException("Invalid CPU/node range");
            for (int i = first; i <= last; i++) result.add(i);
        }
        return result;
    }
}
