package br.unb.cic.td20.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates repository inputs from the root or a module directory. */
public final class ProjectPaths {
    private ProjectPaths() { }

    public static Path root(final Path directory) {
        var root = directory.toAbsolutePath().normalize();
        while (root != null) {
            if (Files.isRegularFile(root.resolve("pom.xml"))
                    && Files.isRegularFile(root.resolve("docs/td20-reduction-count.tex"))) {
                return root;
            }
            root = root.getParent();
        }
        throw new IllegalArgumentException("Run from the project root or one of its subdirectories");
    }

}
