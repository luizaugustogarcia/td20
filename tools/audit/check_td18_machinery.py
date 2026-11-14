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
# Domain constants and admitted local support powers are checked against the
# source specification and independent Java tests rather than copied verbatim.
CONFIGURATION_ADAPTERS = {
    'joint/KnownDiameters.java',
    'joint/SupportClearing.java',
    'joint/ErikssonRoute.java',
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
        text = text.replace('joint-selection-v3', 'joint2-selection-v2')
        text = text.replace('maximumMoves > 10', 'maximumMoves > 11').replace('0..10', '0..11')
    elif relative.endswith('certificate/CertificateStore.java'):
        text = text.replace('joint-selection-v3', 'joint2-selection-v2')
    elif relative.endswith('certificate/ReadOnlyWitnessSource.java') or relative.endswith('avoidance/DiskStateSet.java'):
        text = text.replace('maximumMoves > 10', 'maximumMoves > 11').replace('1..10', '1..11')
    elif relative.endswith('verification/CheckerStateSet.java'):
        text = text.replace('key[1] > 10', 'key[1] > 11').replace('key[2] > 19', 'key[2] > 21')
    elif relative.endswith('emission/ErikssonWitnessEmitter.java'):
        text = text.replace('import br.unb.cic.td20.search.WitnessSearch;',
                            'import br.unb.cic.td20.search.WitnessSearch;\nimport br.unb.cic.td20.search.CpuPrefixSearch;')
        text = text.replace('    private static final long RECONSTRUCTION_CPU_PREFIX_MILLIS = reconstructionCpuPrefixMillis();\n', '')
        text = text.replace('        if (options.help) { usage(); return; }',
                            '        if (options.help) { usage(); return; }\n'
                            '        log.info("General CPU prefix deadline: {} ms for all phases",CpuPrefixSearch.timeoutMillis());')
        text = text.replace('                    log.info("Reconstruction CPU prefix deadline: {} ms before GPU fallback",\n'
                            '                            RECONSTRUCTION_CPU_PREFIX_MILLIS);\n', '')
        text = text.replace('        var result = WitnessSearch.searchWithCpuPrefixTimeout(\n'
                            '                pair, budget, RECONSTRUCTION_CPU_PREFIX_MILLIS);',
                            '        var result = WitnessSearch.search(pair, budget);')
        begin = text.find('    private static long reconstructionCpuPrefixMillis() {')
        if begin >= 0:
            end = text.index('\n    }', begin) + len('\n    }')
            text = text[:begin] + text[end:]
    elif relative.endswith('native/core/common.cuh'):
        text = text.replace('#define MAX_DEPTH 10', '#define MAX_DEPTH 11').replace('#define MAX_N 19', '#define MAX_N 21')
    elif relative.endswith('native/CMakeLists.txt'):
        text = text.replace('TD20_NATIVE_MAX_N 19', 'TD20_NATIVE_MAX_N 21').replace('TD20_NATIVE_MAX_DEPTH 10', 'TD20_NATIVE_MAX_DEPTH 11')
    if relative.endswith('avoidance/DiskStateSet.java') or relative.endswith('verification/CheckerStateSet.java'):
        text = text.replace('byte VERSION = 2', 'byte VERSION = 3')
    return text


def check(reference: Path) -> list[str]:
    checked = []
    paths = ['pom.xml', 'emitter/pom.xml', 'checker/pom.xml']
    for module in ('emitter', 'checker'):
        java = reference / module / 'src/main/java/br/unb/cic/td18'
        for old in sorted(java.rglob('*.java')):
            suffix = old.relative_to(java).as_posix()
            if suffix in CONFIGURATION_ADAPTERS:
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
                      'configuration_adapters_require_independent_java_tests': True}, indent=2))


if __name__ == '__main__':
    main()
