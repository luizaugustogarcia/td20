package br.unb.cic.td20.fleet;

import br.unb.cic.td20.base.GpuDeviceLayout;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

record FleetServer(String name, List<String> ssh, String directory, String architecture, String jshellOptions) {
    static List<FleetServer> read(Path file) throws IOException {
        return read(file,true);
    }
    static List<FleetServer> read(Path file, boolean requireTuning) throws IOException {
        var servers = new ArrayList<FleetServer>(); var names = new HashSet<String>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 5) throw new IllegalArgumentException("Expected five TAB-separated server fields");
            String name = fields[0], root = fields[2], architecture = fields[3];
            if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*") || !names.add(name))
                throw new IllegalArgumentException("Invalid or duplicate server name: " + name);
            var ssh = FleetFiles.words(fields[1]); validateSsh(ssh);
            if (!Path.of(root).normalize().startsWith("/workspace") || !root.startsWith("/")
                    || root.contains("\n") || root.contains("\r") || root.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Remote directory must be under /workspace");
            if (!architecture.matches("[1-9][0-9]*|cpu")) throw new IllegalArgumentException("Expected a CUDA architecture number or cpu");
            String configuration = fields[4];
            if (configuration.startsWith("@")) {
                Path profile = file.toAbsolutePath().getParent().resolve(configuration.substring(1)).normalize();
                configuration = String.join(" ",Files.readAllLines(profile).stream().map(String::strip)
                        .filter(value -> !value.isEmpty() && !value.startsWith("#")).toList());
            }
            var options = FleetFiles.words(configuration);
            for (String option : options) if ((!option.startsWith("-R-") && !option.startsWith("-J-"))
                    || option.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("Invalid JShell JVM option: " + option);
            var server = new FleetServer(name,ssh,root,architecture,String.join(" ",options));
            if (requireTuning) server.requireTuning();
            servers.add(server);
        }
        if (servers.isEmpty()) throw new IllegalArgumentException("At least one SSH server is required");
        return List.copyOf(servers);
    }
    private static void validateSsh(List<String> ssh) {
        if (ssh.size() < 2 || !ssh.getFirst().equals("ssh")) throw new IllegalArgumentException("Supply a plain ssh connection");
        for (int i = 1; i < ssh.size() - 1; i++) {
            String option = ssh.get(i);
            if (List.of("-p","-i","-o","-F","-J","-l").contains(option)) {
                if (++i >= ssh.size() - 1) throw new IllegalArgumentException("Missing SSH option value or destination");
            } else if (!List.of("-4","-6","-A","-a","-C","-q","-v","-vv","-vvv","-T").contains(option)
                    && !option.matches("-(p[0-9]+|i.+|o.+|F.+|J.+|l.+)"))
                throw new IllegalArgumentException("Unsupported SSH connection option: " + option);
        }
        if (!ssh.getLast().matches("[a-zA-Z0-9_@.:%\\[\\]-]+") || ssh.getLast().startsWith("-"))
            throw new IllegalArgumentException("SSH connection must end with its destination and contain no remote command");
    }
    List<String> remote(String command) {
        return remote(command,false,true);
    }
    List<String> transferRemote(String command) {
        return transferRemote(command,true);
    }
    List<String> transferRemote(String command,boolean compression) {
        return remote(command,true,compression);
    }
    private List<String> remote(String command, boolean transfer,boolean compression) {
        var args = new ArrayList<String>(); args.add("ssh");
        args.addAll(List.of("-T", "-o", "BatchMode=yes", "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=6"));
        // OpenSSH uses the first value: override any configured multiplexing for bulk transfers.
        if (transfer) {
            if(compression)args.add("-C");else args.addAll(List.of("-o","Compression=no"));
            args.addAll(List.of("-o","ControlMaster=no","-o","ControlPath=none"));
        }
        args.addAll(ssh.subList(1,ssh.size()));
        args.add(FleetFiles.interactive(command));
        return List.copyOf(args);
    }
    String runtimeOptions() {
        return jshellOptions;
    }
    int cpuThreads() { return positive("-R-XX:ActiveProcessorCount="); }
    String runtimeOptions(FleetHardware hardware) {
        requireTuning();
        int cpus = positive("-R-XX:ActiveProcessorCount=");
        if (cpus > hardware.cpuThreads()) throw configurationError("CPU limit exceeds this server's available CPU threads");
        if (heapBytes() >= hardware.totalMemoryBytes()) throw configurationError("Java heap leaves no memory for native code or the OS");
        if (!architecture.equals("cpu")) {
            int slots = positive("-R-Dtd20.gpuSlots=");
            long queueMiB = Math.multiplyExact((long)slots,positive("-R-Dtd20.gpuBudgetMB="));
            for (int device : GpuDeviceLayout.parseDevices(value("-R-Dtd20.gpuDevices="))) {
                var gpu = hardware.gpus().stream().filter(candidate -> candidate.index() == device).findFirst()
                        .orElseThrow(() -> configurationError("Configured GPU " + device + " is absent"));
                if (queueMiB >= gpu.freeMiB()) throw configurationError("GPU " + device + " has insufficient free VRAM for its queues and native overhead");
                if (Integer.parseInt(architecture) > gpu.architecture()) throw configurationError("CUDA architecture exceeds GPU " + device + " capability");
            }
        }
        var options = FleetFiles.words(jshellOptions);
        if (hardware.requiresNuma()) {
            if (options.contains("-R-XX:-UseNUMA")) throw configurationError("NUMA cannot be disabled on a server with multiple sockets or NUMA nodes");
            if (!options.contains("-R-XX:+UseNUMA")) return jshellOptions + " -R-XX:+UseNUMA";
        }
        return jshellOptions;
    }
    private void requireTuning() {
        heapBytes(); positive("-R-XX:ActiveProcessorCount="); positive("-R-Dtd20.twistedBraceletWorkers=");
        if (!architecture.equals("cpu")) {
            int slots = positive("-R-Dtd20.gpuSlots="); positive("-R-Dtd20.gpuBudgetMB=");
            new GpuDeviceLayout(GpuDeviceLayout.parseDevices(value("-R-Dtd20.gpuDevices=")),slots);
        }
    }
    private long heapBytes() {
        String heap = value("-R-Xmx");
        if (!heap.matches("[1-9][0-9]*[kKmMgG]?")) throw configurationError("Invalid maximum Java heap");
        char unit = Character.toLowerCase(heap.charAt(heap.length() - 1));
        long multiplier = switch (unit) { case 'k' -> 1L << 10; case 'm' -> 1L << 20; case 'g' -> 1L << 30; default -> 1; };
        String digits = Character.isDigit(unit) ? heap : heap.substring(0,heap.length() - 1);
        return Math.multiplyExact(Long.parseLong(digits),multiplier);
    }
    private int positive(String prefix) {
        int result = Integer.parseInt(value(prefix));
        if (result < 1) throw configurationError("Expected a positive value for " + prefix);
        return result;
    }
    private String value(String prefix) {
        var matches = FleetFiles.words(jshellOptions).stream().filter(option -> option.startsWith(prefix)).toList();
        if (matches.size() != 1) throw configurationError("Supply exactly one explicit " + prefix + " setting");
        return matches.getFirst().substring(prefix.length());
    }
    private IllegalArgumentException configurationError(String message) {
        return new IllegalArgumentException("Server " + name + ": " + message);
    }
}
