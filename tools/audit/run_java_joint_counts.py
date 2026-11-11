#!/usr/bin/env python3
"""Resume exact Java joint-3 counting; CPU concurrency is detected by the JVM."""
from __future__ import annotations
import argparse
import fcntl
import json
import os
import signal
from pathlib import Path
import shlex
import subprocess
import tempfile
from run_joint_counts import COUNT_FIELDS, ROW_V3, load_saved_rows, merge_shards, partitions, validate_row

PROJECT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--n', type=int, default=21)
    parser.add_argument('--output', type=Path, default=PROJECT / 'data/joint3-reduction-audit/native-v1')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--paper', type=Path)
    parser.add_argument('--unoriented', type=Path)
    options = parser.parse_args()
    if not 3 <= options.n <= 21 or bool(options.paper) != bool(options.unoriented) \
            or options.paper and options.n != 21:
        parser.error('Expected N=3..21; paper and unoriented artifact are paired for N=21')
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with (output / '.count-run.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if not options.resume and any(path.name != '.count-run.lock' for path in output.iterdir()):
            raise ValueError('A nonempty output directory requires --resume')
        inventory = list(partitions(options.n))
        saved = load_saved_rows(output, inventory)
        complete = {}
        for part, rows in saved.items():
            if len(rows) != rows[0]['shard_count']:
                raise ValueError(f'Archive incomplete legacy shard groups before Java resumption: {part}')
            complete[part] = merge_shards(part, rows) if len(rows) > 1 else rows[0]
        missing = [part for part in inventory if part not in complete]
        reused = len(complete)
        available = len(os.sched_getaffinity(0)) if hasattr(os, 'sched_getaffinity') else (os.cpu_count() or 1)
        print(json.dumps({'reused_complete_partitions': reused, 'remaining_partitions': len(missing),
                          'scheduling': 'td18-partition-pool-v1', 'available_cpu_cores': available}), flush=True)

        def publish():
            if options.paper:
                from publish_partial_joint3_counts import load_unoriented, native_rows, render, update_paper
                expected, values, total = load_unoriented(options.unoriented)
                original = options.paper.read_text()
                rendered, count = render(original, expected, values, total, native_rows(output, expected))
                update_paper(options.paper, original, rendered)
                print(json.dumps({'paper_joint_rows': count}), flush=True)

        publish()
        if missing:
            with tempfile.TemporaryDirectory(prefix='td20-java-count-') as temporary:
                listing = Path(temporary) / 'partitions.txt'
                listing.write_text(''.join(','.join(map(str, part)) + '\n' for part in missing))
                arguments = [f'--n={options.n}', f'--partitions-file={listing}', f'--output={output}']
                command = [str(PROJECT / 'tools/td20'), 'count-j', '-Dexec.args=' + shlex.join(arguments)]
                process = subprocess.Popen(command, cwd=PROJECT, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
                try:
                    for line in process.stdout:
                        print(line, end='', flush=True)
                        if not line.startswith('{"format":"td20-joint3-residual-count-v3"'):
                            continue
                        row = json.loads(line)
                        part = tuple(row.get('partition', ()))
                        if part not in missing or part in complete or row.get('format') != ROW_V3:
                            raise ValueError('Unexpected or duplicate Java partition result')
                        validate_row(row, part)
                        path = output / ('partition-' + '-'.join(map(str, part)) + '.json')
                        if json.loads(path.read_text()) != row:
                            raise ValueError('Java stdout and persisted row disagree')
                        complete[part] = row
                        publish()
                    if process.wait():
                        raise subprocess.CalledProcessError(process.returncode, command)
                except BaseException:
                    try:
                        os.killpg(process.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    process.wait()
                    raise
        if set(complete) != set(inventory):
            raise ValueError('Java count finished with missing partitions')
        rows = [complete[part] for part in inventory]
        report = {'format': 'td20-joint3-count-run-v1', 'complete': True, 'domain': options.n,
                  'required_partitions': len(inventory), 'completed_partitions': len(rows), 'rows': rows,
                  'implementation': 'java-joint3-td18-machinery-v1', 'worker_limit': available,
                  'available_cpu_cores': available, 'reused_complete_partitions': reused,
                  'scheduling': 'td18-partition-pool-v1',
                  'retained_native_audit_partitions': sum(row['format'] != ROW_V3 for row in rows),
                  'sorting_certificates_checked': False, 'td20_proved': False}
        for field in COUNT_FIELDS:
            report[field] = sum(row[field] for row in rows)
        report['support_clearing_by_least_length'] = {
            key: sum(row['support_clearing_by_least_length'][key] for row in rows)
            for key in ('9', '11', '13', '17')}
        temporary = output / 'counts.json.tmp'
        temporary.write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')
        temporary.replace(output / 'counts.json')
        print(json.dumps({'count_complete': True, 'completed_partitions': len(rows)}), flush=True)


if __name__ == '__main__':
    main()
