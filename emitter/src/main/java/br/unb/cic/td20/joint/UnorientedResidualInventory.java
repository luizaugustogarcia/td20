package br.unb.cic.td20.joint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Explicit route-dependent U residual counts; missing rows never mean zero. */
final class UnorientedResidualInventory {
    private UnorientedResidualInventory() { }
    static Map<Integer, Long> read(final Path paper, final int expected) throws IOException {
        final String macro = Character.toString(92) + "unorientedresidualrow";
        final String pending = Character.toString(92) + "pending";
        final Pattern row = Pattern.compile(Pattern.quote(macro)
                + "[{]([1-9][0-9]*)[}][{]([0-9]+|" + Pattern.quote(pending) + ")[}]");
        final var result = new LinkedHashMap<Integer, Long>();
        for (final String source : Files.readAllLines(paper)) {
            final String line = source.strip();
            if (!line.startsWith(macro)) continue;
            final var match = row.matcher(line);
            if (!match.matches()) throw new IllegalArgumentException("Malformed unoriented residual row");
            final int number = Integer.parseInt(match.group(1));
            if (number != result.size() + 1 || number > expected)
                throw new IllegalArgumentException("Missing, duplicate or out-of-order unoriented residual row");
            result.put(number, match.group(2).equals(pending) ? -1L : Long.parseLong(match.group(2)));
        }
        if (result.size() != expected)
            throw new IllegalArgumentException("Incomplete unoriented residual inventory: expected " + expected);
        return Map.copyOf(result);
    }
}
