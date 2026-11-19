#!/usr/bin/env python3
"""Remove launcher-owned abandoned work after acquiring the run directory lock."""

import argparse
import datetime
import fcntl
import json
import os
from pathlib import Path
import re
import shutil


def overlaps(first, second):
    return first == second or first in second.parents or second in first.parents


def resume_source(path):
    source = Path(path).resolve(strict=True)
    lines = (source / 'eriksson-reconstruction-v1.txt').read_text().splitlines()
    legacy = len(lines) == 9 and lines[0] == 'td-eriksson-reconstruction-v1'
    current = len(lines) == 10 and lines[0] == 'td-eriksson-reconstruction-v2'
    deferred = len(lines) == 10 and lines[0] == 'td-eriksson-reconstruction-v3'
    filtered = len(lines) == 11 and lines[0] == 'td-eriksson-reconstruction-v4'
    if not (legacy or current or deferred or filtered) or lines[1:4] != [
            'route=td20-eriksson-three-v1', 'rootSymbols=21', 'rootBudget=11']:
        raise ValueError('Missing or incompatible resume checkpoint')
    fields = {}
    for index, name in enumerate(('stage', 'endpoints', 'afterTwo', 'afterOne', 'roots'), 4):
        if not lines[index].startswith(name + '='):
            raise ValueError('Missing or incompatible checkpoint field: ' + name)
        fields[name] = lines[index][len(name) + 1:]
    stage = fields['stage']
    if stage not in ('AFTER_TWO', 'AFTER_ONE', 'ROOTS'):
        raise ValueError('Missing or incompatible resume stage')
    mode = 'FULL_SEARCH'
    if not legacy:
        if lines[9] not in ('mode=FULL_SEARCH', 'mode=RESIDUAL_EXPANSION'):
            raise ValueError('Missing or incompatible resume mode')
        mode = lines[9][5:]
    if filtered and (stage != 'ROOTS' or
                     lines[10] != 'rootInventory=ELEMENTARY_RESIDUALS'):
        raise ValueError('Missing or incompatible filtered root inventory')
    inventories = [fields[name] for name in ('endpoints', 'afterTwo', 'afterOne', 'roots')]
    if any(re.fullmatch(r'[+-]?[0-9]+', value) is None for value in inventories):
        raise ValueError('Malformed resume inventory')
    endpoints, after_two, after_one, roots = map(int, inventories)
    if any(value < -(2**63) or value >= 2**63 for value in
            (endpoints, after_two, after_one, roots)):
        raise ValueError('Resume inventory exceeds signed 64-bit counts')
    pending = stage == 'AFTER_ONE' and mode == 'RESIDUAL_EXPANSION' and after_one == -1
    if (endpoints < 0 or after_two < 0 or
            stage != 'AFTER_TWO' and after_one < 0 and not pending or
            stage == 'ROOTS' and roots < 0 or
            stage == 'AFTER_TWO' and (after_one != -1 or roots != -1) or
            stage == 'AFTER_ONE' and roots != -1 or deferred != pending):
        raise ValueError('Incomplete or inconsistent resume inventory')
    wave = {'AFTER_TWO': 'after-two', 'AFTER_ONE': 'after-one', 'ROOTS': 'roots'}[stage]
    if not (source / wave / 'CURRENT').is_file():
        raise ValueError('The checkpoint does not have its completed source database')
    return source


def cleanup(base, database, resume=None, dry_run=False):
    base = Path(base).resolve(strict=True)
    protected = [Path(database).resolve()]
    if resume:
        protected.append(resume_source(resume))
    work_root = base / 'work'
    candidates = {}
    for record in sorted((base / 'logs').glob('*.json')):
        settings = json.loads(record.read_text())
        if settings.get('format') not in ('td20-local-run-v1', 'td20-4080s-run-v1'):
            continue
        module = settings.get('module')
        if module not in ('emitter', 'checker'):
            continue
        path = Path(settings.get('work', '')).absolute()
        if path.is_symlink() or not path.is_dir():
            continue
        real = path.resolve(strict=True)
        if real.parent != work_root.resolve() or not real.name.startswith(module + '-'):
            continue
        if record.name != real.name + '.json':
            continue
        if any(overlaps(real, keep) for keep in protected):
            continue
        # Completed boundaries remain available until a later resume selects
        # the appropriate source. Only disposable interrupted outputs qualify.
        if (real / 'eriksson-reconstruction-v1.txt').exists():
            continue
        recorded_database = settings.get('database')
        if recorded_database and overlaps(real, Path(recorded_database).resolve()):
            raise ValueError('Recorded certificate database overlaps abandoned work')
        candidates[real] = record, settings

    # Plan every deletion first, so malformed settings cannot cause a partial
    # cleanup followed by a validation failure. Never follow directory links.
    temporary = base / 'tmp'
    scratch = []
    if temporary.is_dir() and not temporary.is_symlink():
        for path in sorted(temporary.iterdir()):
            if not any(overlaps(path.resolve(), keep) for keep in protected):
                scratch.append(path)
    removed = []
    for path, (record, settings) in sorted(candidates.items()):
        print(f'INFO abandoned work cleanup: {path}', flush=True)
        if not dry_run:
            shutil.rmtree(path)
            settings['workRemovedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            record.write_text(json.dumps(settings, indent=2) + '\n')
        removed.append(path)
    for path in scratch:
        if not dry_run:
            if path.is_dir() and not path.is_symlink():
                shutil.rmtree(path)
            else:
                path.unlink()
    print(f'INFO restart cleanup: abandonedWorkDirectories={len(removed)} '
          f'temporaryEntries={len(scratch)} resumeSource={resume or "none"}', flush=True)
    return removed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--database', required=True)
    parser.add_argument('--resume-work')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--lock-fd', type=int)
    args = parser.parse_args()
    if not args.dry_run:
        if args.lock_fd is None:
            parser.error('Run cleanup through a launcher holding its run.lock')
        held = os.fstat(args.lock_fd)
        expected = (Path(args.base) / 'run.lock').stat()
        if (held.st_dev, held.st_ino) != (expected.st_dev, expected.st_ino):
            parser.error('The inherited descriptor is not this run directory lock')
        fcntl.flock(args.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    cleanup(args.base, args.database, args.resume_work, args.dry_run)


if __name__ == '__main__':
    main()
