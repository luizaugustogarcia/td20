import java.nio.file.*;
import java.util.*;
import java.io.*;

class StartFleet {
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static void run() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path argumentsFile = Path.of(System.getenv("TD20_FLEET_LAUNCH_ARGS"));
        var arguments = Files.readAllLines(argumentsFile);
        String runArgument = arguments.stream().filter(arg -> arg.startsWith("--run=")).findFirst().orElseThrow();
        Path directory = root.resolve(runArgument.substring(6)).normalize(); Files.createDirectories(directory);
        Path pidFile = directory.resolve("coordinator.pid");
        if (Files.exists(pidFile) && ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip())).filter(ProcessHandle::isAlive).isPresent())
            throw new IllegalStateException("The recorded coordinator is still running");
        String token = System.getenv("TD20_GITHUB_TOKEN");
        String command = "export JAVA_HOME=" + quote(System.getProperty("java.home")) + "; export PATH=\"$JAVA_HOME/bin:$PATH\"; exec setsid ./tools/td20 fleet "
                + String.join(" ",arguments.stream().map(StartFleet::quote).toList());
        var builder = new ProcessBuilder("bash","-ic",command).directory(root.toFile()).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("console.log").toFile()));
        if (token != null && !token.isBlank()) builder.environment().put("TD20_GITHUB_TOKEN",token);
        var process = builder.start();
        Files.writeString(pidFile,process.pid() + "\n");
        System.out.println("Started coordinator pid=" + process.pid() + "; log=" + directory.resolve("console.log"));
    }
}
int status = 1;
try { StartFleet.run(); status = 0; } catch (Throwable error) { error.printStackTrace(); status = 1; }
/exit status
