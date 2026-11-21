import java.nio.file.*;
import java.util.*;

class Td20Launch {
    // Shell-style quoting, without expansion or command execution.
    static List<String> words(String text) {
        var words = new ArrayList<String>(); var word = new StringBuilder();
        char quote = 0; boolean escape = false, started = false;
        for (char c : text.toCharArray()) {
            if (escape) { word.append(c); escape = false; started = true; }
            else if (c == '\\' && quote != '\'') { escape = true; started = true; }
            else if (quote != 0) { if (c == quote) quote = 0; else word.append(c); }
            else if (c == '\'' || c == '"') { quote = c; started = true; }
            else if (Character.isWhitespace(c)) {
                if (started) { words.add(word.toString()); word.setLength(0); started = false; }
            } else { word.append(c); started = true; }
        }
        if (escape || quote != 0) throw new IllegalArgumentException("Unterminated argument quoting");
        if (started) words.add(word.toString());
        return words;
    }
    static void run() throws Throwable {
        var fields = new ArrayList<>(Arrays.asList(Files.readString(Path.of(System.getenv("TD20_LAUNCH_ARGS"))).split("\\x00", -1)));
        if (!fields.removeLast().isEmpty()) throw new IllegalArgumentException("Truncated launcher arguments");
        String entry = fields.removeFirst(), command = fields.removeFirst();
        var expanded = new ArrayList<String>(); boolean seen = false;
        for (String option : fields) {
            if (option.startsWith("-Dexec.args=") || option.startsWith("-Dtd20.args=")) {
                if (seen) throw new IllegalArgumentException("Supply the Java argument property only once.");
                seen = true; expanded.addAll(words(option.substring(option.indexOf('=') + 1)));
            } else expanded.add(option);
        }
        var arguments = new ArrayList<String>();
        for (String option : expanded) {
            if (option.startsWith("-Dexec.mainClass=")) entry = option.substring(option.indexOf('=') + 1);
            else if (option.startsWith("-D")) {
                int separator = option.indexOf('=');
                System.setProperty(separator < 0 ? option.substring(2) : option.substring(2, separator),
                        separator < 0 ? "true" : option.substring(separator + 1));
            } else if (!List.of("-B", "-q", "-e", "-X", "-Pgpu").contains(option)) arguments.add(option);
        }
        if (command.equals("avoidance") && arguments.stream().anyMatch(a -> a.startsWith("--phase=") && !a.equals("--phase=avoidance")))
            throw new IllegalArgumentException("The avoidance command requires --phase=avoidance.");
        try { Class.forName(entry).getMethod("main", String[].class).invoke(null, (Object) arguments.toArray(String[]::new)); }
        catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
    }
}
int status = 1;
try { Td20Launch.run(); status = 0; } catch (Throwable error) { error.printStackTrace(); status = 1; }
/exit status
