package br.unb.cic.td20.avoidance;

import lombok.val;

import java.nio.file.Path;

/**
 * Key-only inventory repair for a completed intermediate reconstruction wave.
 */
public final class CountReconstructionWave {

    private CountReconstructionWave() {
    }

    public static void main(final String[] arguments) {
        if (arguments.length == 1 && arguments[0].equals("--help")) {
            System.out.println("CountReconstructionWave --source-work=COMPLETED_AFTER_ONE_WORK");
            return;
        }
        if (arguments.length != 1 || !arguments[0].startsWith("--source-work=") || arguments[0].length() == 14) {
            throw new IllegalArgumentException("Supply --source-work=COMPLETED_AFTER_ONE_WORK");
        }

        val source = Path.of(arguments[0].substring(14)).toAbsolutePath().normalize();
        val counted = ErikssonAvoidance.countCompletedAfterOne(source);
        System.out.println("Completed budget-ten inventory: afterOne=" + counted.afterOne() + ", sourceWork=" + source);
    }
}
