package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import lombok.val;

import java.nio.file.*;
import java.util.*;

/**
 * Optional emitter state, never a proof input. Only completed generation boundaries are resumed.
 */
public record ErikssonCheckpoint(Stage stage, long endpoints, long afterTwo, long afterOne, long roots, Mode mode, RootInventory rootInventory) {

    public enum Stage {

        AFTER_TWO, AFTER_ONE, ROOTS
    }

    public enum Mode {

        FULL_SEARCH, RESIDUAL_EXPANSION
    }

    public enum RootInventory {

        ALL_PARENTS, ELEMENTARY_RESIDUALS
    }

    private static final String FILE = "eriksson-reconstruction-v1.txt";

    public ErikssonCheckpoint(final Stage stage, final long endpoints, final long afterTwo, final long afterOne, final long roots) {
        this(stage, endpoints, afterTwo, afterOne, roots, Mode.FULL_SEARCH);
    }

    public ErikssonCheckpoint(final Stage stage,
            final long endpoints,
            final long afterTwo,
            final long afterOne,
            final long roots,
            final Mode mode) {
        this(stage, endpoints, afterTwo, afterOne, roots, mode, RootInventory.ALL_PARENTS);
    }

    public ErikssonCheckpoint {
        Objects.requireNonNull(stage);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(rootInventory);
        if (rootInventory == RootInventory.ELEMENTARY_RESIDUALS && stage != Stage.ROOTS) {
            throw new IllegalArgumentException("Filtered root inventories require completed roots");
        }

        final boolean pendingCount = stage == Stage.AFTER_ONE && mode == Mode.RESIDUAL_EXPANSION && afterOne == -1;
        if (endpoints < 0
                || afterTwo < 0
                || stage != Stage.AFTER_TWO
                && afterOne < 0
                && !pendingCount
                || stage == Stage.ROOTS
                && roots < 0
                || stage == Stage.AFTER_TWO
                && (afterOne != -1
                || roots != -1)
                || stage == Stage.AFTER_ONE
                && roots != -1) {
            throw new IllegalArgumentException("Incomplete or inconsistent sealed-wave inventory");
        }
    }

    public void write(final Path directory) {
        Path temporary = null;

        try {
            temporary = Files.createTempFile(directory, ".eriksson-checkpoint-", ".tmp");

            val lines = new ArrayList<>(List.of(rootInventory == RootInventory.ELEMENTARY_RESIDUALS ? "td-eriksson-reconstruction-v4" : afterOneCountPending() ? "td-eriksson-reconstruction-v3" : "td-eriksson-reconstruction-v2",
                    "route=" + ErikssonRoute.ID,
                    "rootSymbols=" + ErikssonRoute.ROOT_SYMBOLS,
                    "rootBudget=" + ErikssonRoute.ROOT_BUDGET,
                    "stage=" + stage,
                    "endpoints=" + endpoints,
                    "afterTwo=" + afterTwo,
                    "afterOne=" + afterOne,
                    "roots=" + roots,
                    "mode=" + mode));
            if (rootInventory == RootInventory.ELEMENTARY_RESIDUALS) {
                lines.add("rootInventory=" + rootInventory);
            }

            Files.write(temporary, lines);
            Files.move(temporary, directory.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final Exception error) {
            throw new IllegalStateException("Cannot persist sealed Eriksson wave", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (final Exception ignored) {
                }
            }
        }
    }

    /**
     * Version three records a legacy completed residual wave that must be counted before final expansion.
     */
    public boolean afterOneCountPending() {
        return stage == Stage.AFTER_ONE && afterOne == -1;
    }

    public static ErikssonCheckpoint read(final Path directory) {
        try {
            final List<String> values = Files.readAllLines(directory.resolve(FILE));
            final boolean legacy = values.size() == 9 && values.get(0).equals("td-eriksson-reconstruction-v1");
            final boolean current = values.size() == 10 && values.get(0).equals("td-eriksson-reconstruction-v2");
            final boolean deferred = values.size() == 10 && values.get(0).equals("td-eriksson-reconstruction-v3");
            final boolean filtered = values.size() == 11 && values.get(0).equals("td-eriksson-reconstruction-v4");
            if ((!legacy
                    && !current
                    && !deferred
                    && !filtered)
                    || !values.get(1).equals("route=" + ErikssonRoute.ID)
                    || !values.get(2).equals("rootSymbols=" + ErikssonRoute.ROOT_SYMBOLS)
                    || !values.get(3).equals("rootBudget=" + ErikssonRoute.ROOT_BUDGET)) {
                throw new IllegalArgumentException("Checkpoint belongs to a different route or budget");
            }

            final String[] keys = { "stage", "endpoints", "afterTwo", "afterOne", "roots" };
            for (int i = 0; i < keys.length; i++) {
                if (!values.get(i + 4).startsWith(keys[i] + "=")) {
                    throw new IllegalArgumentException("Missing checkpoint field: " + keys[i]);
                }
            }
            if (!legacy && !values.get(9).startsWith("mode=")) {
                throw new IllegalArgumentException("Missing checkpoint mode");
            }
            if (filtered && !values.get(10).equals("rootInventory=ELEMENTARY_RESIDUALS")) {
                throw new IllegalArgumentException("Missing or incompatible filtered root inventory");
            }

            val checkpoint = new ErikssonCheckpoint(Stage.valueOf(values.get(4).substring(6)),
                    Long.parseLong(values.get(5).substring(10)),
                    Long.parseLong(values.get(6).substring(9)),
                    Long.parseLong(values.get(7).substring(9)),
                    Long.parseLong(values.get(8).substring(6)),
                    legacy ? Mode.FULL_SEARCH : Mode.valueOf(values.get(9).substring(5)),
                    filtered ? RootInventory.ELEMENTARY_RESIDUALS : RootInventory.ALL_PARENTS);
            if (deferred != checkpoint.afterOneCountPending()) {
                throw new IllegalArgumentException("Checkpoint version disagrees with its deferred inventory count");
            }

            return checkpoint;
        } catch (final Exception error) {
            throw new IllegalStateException("Missing or incompatible Eriksson checkpoint", error);
        }
    }
}
