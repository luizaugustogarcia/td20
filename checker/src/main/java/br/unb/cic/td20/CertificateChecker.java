package br.unb.cic.td20;

import java.io.IOException;

/** Command-line entry point; implementation lives in {@link br.unb.cic.td20.verification.CertificateChecker}. */
public final class CertificateChecker {
    private CertificateChecker() { }

    public static void main(final String[] args) throws IOException {
        br.unb.cic.td20.verification.CertificateChecker.main(args);
    }
}
