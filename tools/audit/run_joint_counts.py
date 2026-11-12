#!/usr/bin/env python3
"""Run the independent joint-reduction root counter over every partition.

Generated reports and representatives belong in an ignored data directory.
Each native job writes temporary files; only a successful, validated result is
renamed into place. The final report requires every independently regenerated
partition exactly once. This is a counting audit, not a sorting certificate.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import fcntl
import json
import math
import os
from pathlib import Path
import subprocess
import time

ROW_V1 = 'td20-joint2-residual-count-v1'
ROW_V2 = 'td20-joint2-residual-count-v2'
ROW_V3 = 'td20-joint2-residual-count-v3'
COUNT_FIELDS = ('oriented_root_classes', 'support_clearing_classes',
                'oriented_after_support_clearing', 'no_qualifying_first_move',
                'qualifying_but_no_favorable_prefix', 'prefix_nodes',
                'realizable_leaves', 'ignored_unoriented_leaves')


def partitions(n: int, maximum: int | None = None, prefix: tuple[int, ...] = ()):
    if n == 0:
        if sum(x % 2 == 0 for x in prefix) % 2 == 0:
            yield prefix
        return
    for length in range(min(n, maximum or n), 1, -1):
        yield from partitions(n - length, length, prefix + (length,))


def validate_representatives(path: Path, domain: int, expected: int) -> None:
    with path.open() as stream:
        if stream.readline().rstrip('\n') != f'TD20-JOINT2-RESIDUALS 1 N={domain} scope=oriented':
            raise ValueError(f'Invalid representative header: {path}')
        count = 0
        for line in stream:
            if line.startswith('COUNT '):
                if line.rstrip('\n') != f'COUNT {expected}' or count != expected or stream.read():
                    raise ValueError(f'Incorrect or nonterminal representative count: {path}')
                return
            values = line.split()
            if len(values) != domain:
                raise ValueError(f'Malformed representative at row {count + 1}: {path}')
            count += 1
    raise ValueError(f'Missing representative completion line: {path}')


def validate_row(row: dict, part: tuple[int, ...], shard_index: int | None = None,
                 shard_count: int | None = None, shard_depth: int | None = None) -> None:
    if row.get('format') not in (ROW_V1, ROW_V2, ROW_V3) or row.get('partition') != list(part):
        raise ValueError(f'Unexpected native report for {part}')
    index, count = row.get('shard_index'), row.get('shard_count')
    depth = row.get('shard_depth', 1)
    if type(index) is not int or type(count) is not int or not 0 <= index < count \
            or type(depth) is not int or not 1 <= depth < sum(part):
        raise ValueError(f'Invalid shard envelope for {part}')
    if shard_index is not None and index != shard_index \
            or shard_count is not None and count != shard_count \
            or shard_depth is not None and depth != min(shard_depth, sum(part) - 1):
        raise ValueError(f'Unexpected shard envelope for {part}')
    if row['format'] == ROW_V3:
        if index != 0 or count != 1 or (row.get('implementation'), row.get('scheduling')) not in {
                    ('java-joint2-td18-machinery-v1', 'td18-prefix-frontier-v1'),
                    ('java-joint2-td18-machinery-v2', 'td18-prefix-frontier-v1')} \
                or type(row.get('worker_limit')) is not int or row['worker_limit'] < 1 \
                or 'sharding' in row or 'shard_depth' in row:
            raise ValueError(f'Invalid Java count envelope for {part}')
    elif row['format'] == ROW_V2:
        expected = 'first-position-modulo-v1' if depth == 1 else 'accepted-prefix-modulo-v2'
        if row.get('sharding') != expected or 'shard_depth' not in row:
            raise ValueError(f'Invalid v2 shard scheme for {part}')
    elif depth != 1 or 'sharding' in row:
        raise ValueError(f'Invalid legacy shard scheme for {part}')
    for field in COUNT_FIELDS:
        if type(row.get(field)) is not int or row[field] < 0:
            raise ValueError(f'Invalid {field} for {part}')
    if row['oriented_root_classes'] != (row['no_qualifying_first_move']
                                        + row['qualifying_but_no_favorable_prefix']) \
            or row['oriented_root_classes'] != (row['support_clearing_classes']
                                                + row['oriented_after_support_clearing']):
        raise ValueError(f'Inconsistent class split for {part}')
    by_length = row.get('support_clearing_by_least_length')
    if set(by_length or {}) != {'9', '11', '13', '17'} or any(
            type(value) is not int or value < 0 for value in by_length.values()) \
            or sum(by_length.values()) != row['support_clearing_classes']:
        raise ValueError(f'Invalid support-clearing inventory for {part}')
    elapsed = row.get('elapsed_seconds')
    if type(elapsed) not in (int, float) or not math.isfinite(elapsed) or elapsed < 0:
        raise ValueError(f'Invalid elapsed time for {part}')


def report_name(part: tuple[int, ...], index: int, count: int, depth: int, legacy=False) -> str:
    key = '-'.join(map(str, part))
    suffix = ''
    if count != 1:
        suffix = (f'-shard-{index:02d}-of-{count:02d}' if legacy else
                  f'-depth-{depth:02d}-shard-{index:04d}-of-{count:04d}')
    return f'partition-{key}{suffix}.json'


def load_saved_rows(output: Path, inventory: list[tuple[int, ...]]) -> dict[tuple[int, ...], list[dict]]:
    result: dict[tuple[int, ...], list[dict]] = {}
    for path in sorted(output.glob('partition-*.json')):
        row = json.loads(path.read_text())
        part = tuple(row.get('partition', ()))
        if part not in inventory:
            raise ValueError(f'Unexpected persisted partition: {path}')
        validate_row(row, part)
        expected_name = report_name(part, row['shard_index'], row['shard_count'],
                                    row.get('shard_depth', 1), row['format'] == ROW_V1)
        if path.name != expected_name:
            raise ValueError(f'Incorrect persisted shard filename: {path}')
        result.setdefault(part, []).append(row)
    for part, rows in result.items():
        envelopes = {(row['format'], row['shard_count'], row.get('shard_depth', 1),
                      row.get('sharding')) for row in rows}
        if len(envelopes) != 1 or len({row['shard_index'] for row in rows}) != len(rows):
            raise ValueError(f'Mixed or duplicate persisted shards for {part}')
    return result


def run_partition(binary: Path, output: Path, part: tuple[int, ...], representatives: bool,
                  shard_index: int = 0, shard_count: int = 1, shard_depth: int = 4) -> dict:
    key = '-'.join(map(str, part))
    report = output / report_name(part, shard_index, shard_count, min(shard_depth, sum(part)-1))
    raw = report.with_name(report.name + '.tmp')
    errors = report.with_suffix('.stderr.tmp')
    reps = output / f'partition-{key}.txt'
    reps_temp = output / f'partition-{key}.txt.tmp'
    arguments = [str(binary), '--partition=' + ','.join(map(str, part)),
                 f'--shard-index={shard_index}', f'--shard-count={shard_count}',
                 f'--shard-depth={shard_depth}']
    if representatives:
        arguments.append('--representatives=' + str(reps_temp))
    with raw.open('w') as stdout, errors.open('w') as stderr:
        subprocess.run(arguments, stdout=stdout, stderr=stderr, check=True)
    row = json.loads(raw.read_text())
    if row.get('format') != ROW_V2:
        raise ValueError(f'The new runner requires native shard format v2 for {part}')
    validate_row(row, part, shard_index, shard_count, shard_depth)
    if representatives:
        validate_representatives(reps_temp, sum(part), row['oriented_root_classes'])
        reps_temp.replace(reps)
        row['representatives'] = reps.name
    raw.write_text(json.dumps(row, sort_keys=True, indent=2) + '\n')
    raw.replace(report)
    if errors.stat().st_size == 0:
        errors.unlink()
    else:
        errors.replace(report.with_suffix('.stderr'))
    return row


def merge_shards(part: tuple[int, ...], shards: list[dict]) -> dict:
    count = len(shards)
    if [row['shard_index'] for row in sorted(shards, key=lambda row: row['shard_index'])] \
            != list(range(count)) or any(row['shard_count'] != count for row in shards):
        raise ValueError(f'Incomplete shard inventory for {part}')
    envelope = {(row['format'], row.get('shard_depth', 1), row.get('sharding')) for row in shards}
    if len(envelope) != 1:
        raise ValueError(f'Mixed shard schemes for {part}')
    merged = {'format': shards[0]['format'], 'partition': list(part),
              'shard_index': 0, 'shard_count': 1}
    if shards[0]['format'] == ROW_V2:
        merged['shard_depth'] = shards[0]['shard_depth']
        merged['sharding'] = shards[0]['sharding']
    merged['source_shard_count'] = count
    for field in COUNT_FIELDS:
        merged[field] = sum(row[field] for row in shards)
    merged['support_clearing_by_least_length'] = {
        length: sum(row['support_clearing_by_least_length'][length] for row in shards)
        for length in ('9', '11', '13', '17')}
    merged['elapsed_seconds'] = max(row.get('elapsed_seconds', 0) for row in shards)
    return merged


def merge_representatives(output: Path, domain: int, rows: list[dict]) -> str:
    result = output / 'representatives.txt'
    temporary = output / 'representatives.txt.tmp'
    count = 0
    with temporary.open('w') as target:
        target.write(f'TD20-JOINT2-RESIDUALS 1 N={domain} scope=oriented\n')
        for row in rows:
            source = output / row['representatives']
            with source.open() as stream:
                stream.readline()
                for line in stream:
                    if line.startswith('COUNT '):
                        break
                    target.write(line)
                    count += 1
        expected = sum(row['oriented_root_classes'] for row in rows)
        if count != expected:
            raise ValueError('Merged representative count is incomplete')
        target.write(f'COUNT {count}\n')
    temporary.replace(result)
    return result.name


def run_count(options, available_cpus: int, inventory: list[tuple[int, ...]], output: Path) -> None:
    binary = options.binary.resolve()
    saved = load_saved_rows(output, inventory) if options.resume else {}
    results = {part: saved.get(part, []).copy() for part in inventory}
    plans = {}
    complete = set()
    for part in inventory:
        rows = results[part]
        if rows:
            shards, depth = rows[0]['shard_count'], rows[0].get('shard_depth', 1)
            if len(rows) == shards:
                complete.add(part)
            elif rows[0]['format'] == ROW_V1:
                raise ValueError(f'An incomplete legacy partition must be resharded in a separate directory: {part}')
        else:
            shards = (options.singleton_shards if len(part) == 1 and options.singleton_shards
                      else options.partition_shards)
            depth = min(options.shard_depth, sum(part) - 1)
        plans[part] = (shards, depth)
    reused = len(complete)
    publish = None
    if options.paper:
        from publish_partial_joint2_counts import load_unoriented, native_rows, render, update_paper
        expected, values, total = load_unoriented(options.unoriented)

        def publish():
            original = options.paper.read_text()
            rendered, count = render(original, expected, values, total,
                                     native_rows(output, expected))
            update_paper(options.paper, original, rendered)
            return count

        publish()
    print(json.dumps({'reused_complete_partitions': reused,
                      'remaining_partitions': len(inventory) - reused,
                      'worker_limit': options.workers, 'partition_shards': options.partition_shards,
                      'shard_depth': options.shard_depth}), flush=True)
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=options.workers) as pool:
        jobs = {}
        # Interleave partitions so a small number of expensive rows can use
        # the entire pool, without one row monopolizing the initial queue.
        for shard in range(max((count for count, _ in plans.values()), default=0)):
            for part in inventory:
                shards, depth = plans[part]
                existing = {row['shard_index'] for row in results[part]}
                if part in complete or shard >= shards or shard in existing:
                    continue
                jobs[pool.submit(run_partition, binary, output, part, options.representatives,
                                 shard, shards, depth)] = part
        completed = 0
        try:
            for job in as_completed(jobs):
                part = jobs[job]
                row = job.result()
                results[part].append(row)
                completed += 1
                if len(results[part]) == plans[part][0]:
                    complete.add(part)
                    if publish:
                        print(json.dumps({'paper_joint_rows': publish(), 'partition': part}), flush=True)
                print(json.dumps({'completed_jobs': completed, 'required_jobs': len(jobs),
                                  'completed_partitions': len(complete),
                                  'partition': part, 'shard_index': row['shard_index'],
                                  'shard_count': row['shard_count'],
                                  'classes': row['oriented_root_classes'],
                                  'elapsed_seconds': round(time.monotonic() - started, 3)}), flush=True)
        except BaseException:
            for job in jobs:
                job.cancel()
            raise
    rows = [merge_shards(part, results[part]) if len(results[part]) > 1 else results[part][0]
            for part in inventory]
    report = {'format': 'td20-joint2-count-run-v1', 'complete': True,
              'domain': options.n, 'required_partitions': len(inventory),
              'completed_partitions': len(rows),
              'implementation': 'native-cpp20-joint2-prefix-v2',
              'worker_limit': options.workers, 'available_cpu_cores': available_cpus,
              'partition_shards': options.partition_shards, 'shard_depth': options.shard_depth,
              'singleton_shards': options.singleton_shards, 'reused_complete_partitions': reused,
              'scope': ('oriented roots with no immediate gain >=2 and no qualifying gains (1,3) or (1,2)'),
              'totally_unoriented_roots': 'excluded; separate direct obligations',
              'support_clearing_by_least_length': {
                  length: sum(row['support_clearing_by_least_length'][length] for row in rows)
                  for length in ('9', '11', '13', '17')},
              'rows': rows}
    for field in COUNT_FIELDS:
        report[field] = sum(row[field] for row in rows)
    if options.merge_representatives:
        report['representatives'] = merge_representatives(output, options.n, rows)
    report['elapsed_seconds'] = round(time.monotonic() - started, 3)
    temporary = output / 'counts.json.tmp'
    temporary.write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')
    temporary.replace(output / 'counts.json')
    print(json.dumps({key: value for key, value in report.items() if key != 'rows'}, sort_keys=True), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--n', type=int, default=21)
    available_cpus = len(os.sched_getaffinity(0)) if hasattr(os, 'sched_getaffinity') else (os.cpu_count() or 1)
    parser.add_argument('--workers', type=int, default=available_cpus,
                        help='Maximum parallel subtree jobs (default: all available CPU cores)')
    parser.add_argument('--partition-shards', type=int, default=available_cpus * 12,
                        help='Subtree shards for every partition (default: 12 per available CPU)')
    parser.add_argument('--shard-depth', type=int, default=4,
                        help='Assigned ranks after the fixed anchor at the subtree cut (default: 4)')
    parser.add_argument('--singleton-shards', type=int,
                        help='Optional shard-count override for the single-cycle partition')
    parser.add_argument('--resume', action='store_true',
                        help='Validate and reuse completed partitions and completed v2 shards')
    parser.add_argument('--binary', type=Path, default=Path('/tmp/td20-joint2-count-v2'))
    parser.add_argument('--output', type=Path, default=Path('data/joint2-reduction-audit/native-v1'))
    parser.add_argument('--representatives', action='store_true')
    parser.add_argument('--merge-representatives', action='store_true')
    parser.add_argument('--paper', type=Path,
                        help='Update this source table whenever a whole partition completes')
    parser.add_argument('--unoriented', type=Path,
                        help='Complete U artifact required with --paper')
    options = parser.parse_args()
    if not 3 <= options.n <= 21 or options.workers < 1 or options.partition_shards < 1 \
            or not 1 <= options.shard_depth < 21 \
            or options.singleton_shards is not None and options.singleton_shards < 1:
        parser.error('Expected domain 3..21, depth 1..20, and positive workers/shards')
    if options.merge_representatives and not options.representatives:
        parser.error('--merge-representatives requires --representatives')
    if options.representatives and (options.partition_shards != 1 or options.singleton_shards not in (None, 1)):
        parser.error('--representatives currently requires --partition-shards=1')
    if bool(options.paper) != bool(options.unoriented) or (options.paper and options.n != 21):
        parser.error('--paper and --unoriented must be supplied together for N=21')
    inventory = list(partitions(options.n))
    if options.n == 21 and len(inventory) != 83:
        raise ValueError('The independent 21-symbol inventory must contain 83 partitions')
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if not options.resume and any(path.name != '.count-run.lock' for path in output.iterdir()):
        raise ValueError(f'Refusing to mix a new run with existing output: {output}')
    if not options.binary.is_file() or not os.access(options.binary, os.X_OK):
        parser.error(f'Native binary is not executable: {options.binary}')
    with (output / '.count-run.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError(f'Another count runner owns {output}') from None
        run_count(options, available_cpus, inventory, output)


if __name__ == '__main__':
    main()
