import java.util.*;
import br.unb.cic.td20.verification.ImportedTd18Verifier;

// Run with the checker classpath. Explicit environment paths also support a staged import.
// TD20_IMPORT_VERIFY_{SOURCE,CERTIFICATES,RESIDUALS,REPORT} override production defaults.
// Optional TD20_IMPORT_VERIFY_CHECK_MARKERS=true checks installation markers without selecting work.
class VerifyImportedTd18Scope {
    static void run() throws Exception {
        var arguments = new ArrayList<String>();
        for (String field : List.of("SOURCE", "CERTIFICATES", "RESIDUALS", "REPORT")) {
            String value = System.getenv("TD20_IMPORT_VERIFY_" + field);
            if (value != null && !value.isBlank()) arguments.add("--" + field.toLowerCase(Locale.ROOT) + "=" + value);
        }
        String markers = System.getenv("TD20_IMPORT_VERIFY_CHECK_MARKERS");
        if (markers != null && !markers.isBlank()) {
            if (!List.of("true", "false").contains(markers)) throw new IllegalArgumentException("Marker check must be true or false");
            if (markers.equals("true")) arguments.add("--check-markers");
        }
        ImportedTd18Verifier.main(arguments.toArray(String[]::new));
    }
}
int status = 1;
try { VerifyImportedTd18Scope.run(); status = 0; } catch (Throwable error) { error.printStackTrace(); status = 1; }
/exit status
