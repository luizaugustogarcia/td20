package br.unb.cic.td20.fleet;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Strict, ordered, versioned operational records; these are never checker proof inputs. */
final class FleetFiles {
    private FleetFiles() { }
    static void write(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".fleet-", ".tmp");
        try {
            Files.write(temporary, lines);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    static String field(List<String> lines, int index, String key) {
        if (index >= lines.size() || !lines.get(index).startsWith(key + "="))
            throw new IllegalArgumentException("Missing fleet field: " + key);
        return lines.get(index).substring(key.length() + 1);
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static String command(List<String> args) { return String.join(" ", args.stream().map(FleetFiles::quote).toList()); }
    /** Keep interactive startup chatter off binary SSH stdout while still sourcing ~/.bashrc. */
    static String interactive(String command) {
        return "env TMUX_STARTED=1 bash -ic " + quote("exec 1>&3 3>&-; " + command) + " 3>&1 1>&2";
    }
    static List<String> words(String value) {
        var result = new ArrayList<String>(); var word = new StringBuilder();
        char quote = 0; boolean escape = false, started = false;
        for (char c : value.toCharArray()) {
            if (escape) { word.append(c); escape = false; started = true; }
            else if (c == '\\' && quote != '\'') { escape = true; started = true; }
            else if (quote != 0) { if (quote == c) quote = 0; else word.append(c); }
            else if (c == '\'' || c == '"') { quote = c; started = true; }
            else if (Character.isWhitespace(c)) {
                if (started) { result.add(word.toString()); word.setLength(0); started = false; }
            } else { word.append(c); started = true; }
        }
        if (escape || quote != 0) throw new IllegalArgumentException("Unterminated quoting");
        if (started) result.add(word.toString());
        return List.copyOf(result);
    }
}
