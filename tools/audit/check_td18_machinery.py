#!/usr/bin/env python3
"""Check shared machinery against TD(18), allowing only explicit domain/budget adapters.

Joint selection, inverse-wave orchestration, inventories, and support-clearing
budgets are checked by the independent Java tests and source specification.
"""
from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
# These files implement the joint route itself rather than shared machinery.
JOINT_ADAPTERS = {
    'avoidance/JointAvoidance.java',
    'avoidance/JointInverseReconstruction.java',
    'avoidance/ReconstructionCheckpoint.java',
    'avoidance/RootReconstructionCheckpoint.java',
    'verification/CertificateChecker.java',
}


def adapt(relative: str, text: str) -> str:
    """Apply literal, reviewable changes to the reference; never strip algorithms."""
    text = text.replace('td18', 'td20').replace('TD18', 'TD20').replace('TD(18)', 'TD(20)')
    # User-requested scheduling exception: one CPU deadline for all phases.
    if relative == 'emitter/pom.xml':
        text = text.replace('td20.reconstructionCpuPrefixMillis', 'td20.cpuPrefixMillis')
    if relative.endswith('search/CpuPrefixSearch.java'):
        text = text.replace('    private static final int DEFAULT_TRIPLE_LIMIT = 8192;',
                            '    private static final int DEFAULT_TRIPLE_LIMIT = 8192;\n'
                            '    private static final long DEFAULT_TIMEOUT_MILLIS = 10;')
        text = text.replace('        return recordedSearch(pair, maximumMoves, Long.MAX_VALUE);',
                            '        return recordedSearch(pair, maximumMoves, Math.multiplyExact(timeoutMillis(), 1_000_000L));\n'
                            '    }\n\n'
                            '    /** General wall-clock limit for roots, endpoints, and reconstructed classes. */\n'
                            '    public static long timeoutMillis() {\n'
                            '        long value = Long.getLong("td20.cpuPrefixMillis", DEFAULT_TIMEOUT_MILLIS);\n'
                            '        if (value < 0) throw new IllegalArgumentException("td20.cpuPrefixMillis must not be negative");\n'
                            '        return value;')
    if relative.endswith('base/GPUSortingSearch.java'):
        text = text.replace('maxMoves > 10', 'maxMoves > 11').replace('beta.length > 19', 'beta.length > 21')
    elif relative.endswith('search/WitnessSearch.java'):
        text = text.replace('between 1 and 10', 'between 1 and 11')
        text = text.replace('        return search(input, maximumMoves, Long.MAX_VALUE, WitnessSearch::dispatchGpu);',
                            '        return search(input, maximumMoves, WitnessSearch::dispatchGpu);\n'
                            '    }\n\n'
                            '    static SearchResult search(CyclicTargetPair input, int maximumMoves, GpuSearch dispatcher) {\n'
                            '        return search(input, maximumMoves, Long.MAX_VALUE, dispatcher);')
    elif relative.endswith('certificate/CertificateCodec.java'):
        text = text.replace('size() > 10', 'size() > 11').replace('exceeds ten moves', 'exceeds eleven moves').replace('count > 10', 'count > 11')
    elif relative.endswith('certificate/AsyncCertificateWriter.java'):
        text = text.replace('joint-selection-v2', 'joint2-selection-v1')
        text = text.replace('    private static int obligationBudget(int domain) { return domain == 16 ? 8 : domain == 18 ? 9 : 10; }',
                            '    private static int obligationBudget(int domain) {\n        return domain == 18 ? 9 : domain == 20 ? 10 : 11;\n    }')
        text = text.replace('maximumMoves > 10', 'maximumMoves > 11').replace('0..10', '0..11')
    elif relative.endswith('certificate/CertificateStore.java'):
        text = text.replace('Read-only version-2 witness/obstruction schema', 'Read-only TD(20) joint-2 witness/obstruction schema')
        text = text.replace('joint-selection-v2', 'joint2-selection-v1')
        text = text.replace('canonical.getBeta().size() == 16 ? 8 : canonical.getBeta().size() == 18 ? 9 : 10',
                            'obligationBudget(canonical.getBeta().size())')
        text = text.replace('return domain == 16 ? 8 : domain == 18 ? 9 : 10;',
                            'return domain == 18 ? 9 : domain == 20 ? 10 : 11;')
    elif relative.endswith('certificate/ReadOnlyWitnessSource.java') or relative.endswith('avoidance/DiskStateSet.java'):
        text = text.replace('maximumMoves > 10', 'maximumMoves > 11').replace('1..10', '1..11')
    elif relative.endswith('verification/CheckerStateSet.java'):
        text = text.replace('key[1] > 10', 'key[1] > 11').replace('key[2] > 19', 'key[2] > 21')
    elif relative.endswith('emission/WitnessEmitter.java'):
        text = text.replace('import br.unb.cic.td20.search.WitnessSearch;',
                            'import br.unb.cic.td20.search.WitnessSearch;\nimport br.unb.cic.td20.search.CpuPrefixSearch;')
        text = text.replace('    private static final long RECONSTRUCTION_CPU_PREFIX_MILLIS = reconstructionCpuPrefixMillis();\n', '')
        text = text.replace('        if (options.help) { usage(); return; }',
                            '        if (options.help) { usage(); return; }\n'
                            '        log.info("General CPU prefix deadline: {} ms before GPU fallback (roots, endpoints, reconstruction)",\n'
                            '                CpuPrefixSearch.timeoutMillis());')
        text = text.replace('                    log.info("Reconstruction CPU prefix deadline: {} ms before GPU fallback",\n'
                            '                            RECONSTRUCTION_CPU_PREFIX_MILLIS);\n', '')
        text = text.replace('        var result = WitnessSearch.searchWithCpuPrefixTimeout(\n'
                            '                pair, budget, RECONSTRUCTION_CPU_PREFIX_MILLIS);',
                            '        var result = WitnessSearch.search(pair, budget);')
        text = text.replace('    private static long reconstructionCpuPrefixMillis() {\n'
                            '        long value = Long.getLong("td20.reconstructionCpuPrefixMillis", 10L);\n'
                            '        if (value < 0) throw new IllegalArgumentException(\n'
                            '                "td20.reconstructionCpuPrefixMillis must not be negative");\n'
                            '        return value;\n'
                            '    }\n\n', '')
        text = text.replace('settleReconstructed(pair, 9, writer)', 'settleReconstructed(pair, TEN, writer)')
        text = text.replace('settleReconstructed(pair, 10, writer)', 'settleReconstructed(pair, 11, writer)')
        text = text.replace('settleReconstructed(pair, TEN, writer)', 'settleReconstructed(pair, 10, writer)')
        text = text.replace('discharge.apply(pair.canonicalRepresentative(), 10)', 'discharge.apply(pair.canonicalRepresentative(), 11)')
        text = text.replace('discharge.apply(pair.canonicalRepresentative(), 8)', 'discharge.apply(pair.canonicalRepresentative(), 9)')
        text = text.replace('residuals.add(pair, 8)', 'residuals.add(pair, 9)').replace('missingEightMoveDischarges', 'missingNineMoveDischarges')
        text = text.replace('last = 53', 'last = 83').replace('substring(13),53', 'substring(13),83').replace('last != 53', 'last != 83')
        text = text.replace('endpointLast = 104', 'endpointLast = 214').replace('substring(12),104', 'substring(12),214').replace('endpointLast != 104', 'endpointLast != 214')
        text = text.replace('arg.startsWith("--td16-db=")', '(arg.startsWith("--td18-db=") || arg.startsWith("--td16-db="))')
        text = text.replace('[--td16-db=PATH]', '[--td18-db=PATH]').replace('or a TD(16) import', 'or a witness import')
        text = text.replace('        var unoriented = new LongAdder();', '        row.requireCompleteCounts();\n        var unoriented = new LongAdder();', 1)
    elif relative.endswith('CountTD20Bracelets.java'):
        text = text.replace('last = 53', 'last = 83').replace('substring(13),53', 'substring(13),83')
        text = text.replace('endpointLast = 104', 'endpointLast = 214').replace('substring(12),104', 'substring(12),214')
    elif relative.endswith('native/core/common.cuh'):
        text = text.replace('#define MAX_DEPTH 10', '#define MAX_DEPTH 11').replace('#define MAX_N 19', '#define MAX_N 21')
    elif relative.endswith('native/CMakeLists.txt'):
        text = text.replace('TD20_NATIVE_MAX_N 19', 'TD20_NATIVE_MAX_N 21').replace('TD20_NATIVE_MAX_DEPTH 10', 'TD20_NATIVE_MAX_DEPTH 11')
    if relative.endswith('avoidance/DiskStateSet.java') or relative.endswith('verification/CheckerStateSet.java'):
        text = text.replace('byte VERSION = 1', 'byte VERSION = 2').replace('key[1] < 8', 'key[1] < 9')
    return text


def check(reference: Path) -> list[str]:
    checked = []
    paths = ['pom.xml', 'emitter/pom.xml', 'checker/pom.xml']
    for module in ('emitter', 'checker'):
        java = reference / module / 'src/main/java/br/unb/cic/td18'
        for old in sorted(java.rglob('*.java')):
            suffix = old.relative_to(java).as_posix()
            if (suffix.startswith('joint/') and suffix not in {'joint/JointReductionAnalysis.java', 'joint/JointMoves.java'}) or suffix in JOINT_ADAPTERS:
                continue
            paths.append(f'{module}/src/main/java/br/unb/cic/td20/{suffix.replace("TD18", "TD20")}')
    paths.extend(p.relative_to(reference).as_posix() for p in sorted((reference / 'emitter/src/native').rglob('*')) if p.is_file())
    for relative in paths:
        old = reference / relative.replace('td20', 'td18').replace('TD20', 'TD18')
        new = PROJECT / relative
        if not old.is_file() or not new.is_file():
            raise ValueError(f'Missing machinery source: {relative}')
        expected = adapt(relative, old.read_text())
        actual = new.read_text()
        if expected != actual:
            difference = '\n'.join(difflib.unified_diff(expected.splitlines(), actual.splitlines(),
                                                     fromfile='TD18 reference with explicit adapters', tofile=relative))
            raise ValueError(difference)
        checked.append(relative)
    return checked


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, default=PROJECT.parent / 'td18')
    options = parser.parse_args()
    checked = check(options.reference.resolve())
    print(json.dumps({'shared_machinery_matches_td18': True, 'source_files_checked': len(checked),
                      'general_cpu_prefix_deadline_adapter': True,
                      'joint_adapters_require_independent_java_tests': True}, indent=2))


if __name__ == '__main__':
    main()
