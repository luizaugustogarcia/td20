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
    if relative.endswith('base/GPUSortingSearch.java'):
        text = text.replace('maxMoves > 10', 'maxMoves > 11').replace('beta.length > 19', 'beta.length > 21')
    elif relative.endswith('search/WitnessSearch.java'):
        text = text.replace('between 1 and 10', 'between 1 and 11')
    elif relative.endswith('certificate/CertificateCodec.java'):
        text = text.replace('size() > 10', 'size() > 11').replace('exceeds ten moves', 'exceeds eleven moves').replace('count > 10', 'count > 11')
    elif relative.endswith('certificate/AsyncCertificateWriter.java'):
        text = text.replace('joint-selection-v2', 'joint3-selection-v1')
        text = text.replace('    private static int obligationBudget(int domain) { return domain == 16 ? 8 : domain == 18 ? 9 : 10; }',
                            '    private static int obligationBudget(int domain) {\n        return domain == 16 ? 8 : domain == 18 || domain == 19 ? 9 : domain == 20 ? 10 : 11;\n    }')
        text = text.replace('maximumMoves > 10', 'maximumMoves > 11').replace('0..10', '0..11')
    elif relative.endswith('certificate/CertificateStore.java'):
        text = text.replace('Read-only version-2 witness/obstruction schema', 'Read-only TD(20) joint-3 witness/obstruction schema')
        text = text.replace('joint-selection-v2', 'joint3-selection-v1')
        text = text.replace('canonical.getBeta().size() == 16 ? 8 : canonical.getBeta().size() == 18 ? 9 : 10',
                            'obligationBudget(canonical.getBeta().size())')
        text = text.replace('return domain == 16 ? 8 : domain == 18 ? 9 : 10;',
                            'return domain == 16 ? 8 : domain == 18 || domain == 19 ? 9 : domain == 20 ? 10 : 11;')
    elif relative.endswith('certificate/ReadOnlyWitnessSource.java') or relative.endswith('avoidance/DiskStateSet.java'):
        text = text.replace('maximumMoves > 10', 'maximumMoves > 11').replace('1..10', '1..11')
    elif relative.endswith('verification/CheckerStateSet.java'):
        text = text.replace('key[1] > 10', 'key[1] > 11').replace('key[2] > 19', 'key[2] > 21')
    elif relative.endswith('emission/WitnessEmitter.java'):
        text = text.replace('pair -> settleReconstructed(pair, 10, writer),\n                                    WitnessEmitter::lookupReconstructionBatch',
                            'pair -> settleReconstructed(pair, 10, writer),\n                                    pair -> settleReconstructed(pair, 11, writer),\n                                    WitnessEmitter::lookupReconstructionBatch')
        text = text.replace('discharge.apply(pair.canonicalRepresentative(), 10)', 'discharge.apply(pair.canonicalRepresentative(), 11)')
        text = text.replace('last = 53', 'last = 83').replace('substring(13),53', 'substring(13),83').replace('last != 53', 'last != 83')
    elif relative.endswith('CountTD20Bracelets.java'):
        text = text.replace('last = 53', 'last = 83').replace('substring(13),53', 'substring(13),83')
    elif relative.endswith('native/core/common.cuh'):
        text = text.replace('#define MAX_DEPTH 10', '#define MAX_DEPTH 11').replace('#define MAX_N 19', '#define MAX_N 21')
    elif relative.endswith('native/CMakeLists.txt'):
        text = text.replace('TD20_NATIVE_MAX_N 19', 'TD20_NATIVE_MAX_N 21').replace('TD20_NATIVE_MAX_DEPTH 10', 'TD20_NATIVE_MAX_DEPTH 11')
    return text


def check(reference: Path) -> list[str]:
    checked = []
    paths = ['pom.xml', 'emitter/pom.xml', 'checker/pom.xml']
    for module in ('emitter', 'checker'):
        java = reference / module / 'src/main/java/br/unb/cic/td18'
        for old in sorted(java.rglob('*.java')):
            suffix = old.relative_to(java).as_posix()
            if suffix.startswith('joint/') or suffix in JOINT_ADAPTERS:
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
                      'joint_adapters_require_independent_java_tests': True}, indent=2))


if __name__ == '__main__':
    main()
