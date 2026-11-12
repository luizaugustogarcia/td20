#!/usr/bin/env python3
"""Validate and combine the complete TD(20) joint-2 counting artifacts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def partitions(n: int, maximum: int | None = None, prefix: tuple[int, ...] = ()):
    if n == 0:
        if sum(value % 2 == 0 for value in prefix) % 2 == 0:
            yield prefix
        return
    for value in range(min(n, maximum or n), 1, -1):
        yield from partitions(n - value, value, prefix + (value,))


def require_integer(row: dict, field: str) -> int:
    value = row.get(field)
    if type(value) is not int or value < 0:
        raise ValueError(f'Invalid {field}: {value!r}')
    return value


def endpoint_annotations() -> list[tuple[tuple[int, ...], tuple[bool, ...]]]:
    result = []
    for partition in partitions(18):
        if sum(length // 2 for length in partition) > 8:
            continue
        annotations = [()]
        start = 0
        while start < len(partition):
            end = start + 1
            while end < len(partition) and partition[end] == partition[start]:
                end += 1
            size = end - start
            maximum = size if partition[start] >= 3 else 0
            annotations = [prefix + (False,) * (size - oriented) + (True,) * oriented
                           for prefix in annotations for oriented in range(maximum + 1)]
            start = end
        result.extend((partition, annotation) for annotation in annotations)
    if len(result) != 214 or sum(
            any(length == 3 and oriented for length, oriented in zip(partition, annotation))
            for partition, annotation in result) != 104:
        raise ValueError('Independent endpoint inventory is inconsistent')
    return result


def load_native(path: Path, expected: list[tuple[int, ...]]) -> dict:
    report = json.loads(path.read_text())
    if report.get('format') != 'td20-joint2-count-run-v1' or report.get('complete') is not True:
        raise ValueError('Unsupported or incomplete native joint-2 report')
    if report.get('domain') != 21 or report.get('required_partitions') != 83 \
            or report.get('completed_partitions') != 83:
        raise ValueError('Incorrect native inventory envelope')
    rows = report.get('rows')
    if not isinstance(rows, list) or [tuple(row.get('partition', ())) for row in rows] != expected:
        raise ValueError('Native rows do not match the deterministic 83-partition inventory')
    from run_joint_counts import validate_row
    for row in rows:
        validate_row(row, tuple(row["partition"]))
        joint = require_integer(row, 'oriented_root_classes')
        cleared = require_integer(row, 'support_clearing_classes')
        oriented = require_integer(row, 'oriented_after_support_clearing')
        no_first = require_integer(row, 'no_qualifying_first_move')
        other = require_integer(row, 'qualifying_but_no_favorable_prefix')
        if joint != cleared + oriented or joint != no_first + other:
            raise ValueError(f'Inconsistent native split at {row["partition"]}')
        lengths = row.get('support_clearing_by_least_length')
        if set(lengths or {}) != {'9', '11', '13', '17'} \
                or sum(require_integer(lengths, key) for key in lengths) != cleared:
            raise ValueError(f'Invalid support-clearing lengths at {row["partition"]}')
    aggregates = {
        'oriented_root_classes': 'oriented_root_classes',
        'support_clearing_classes': 'support_clearing_classes',
        'oriented_after_support_clearing': 'oriented_after_support_clearing',
        'no_qualifying_first_move': 'no_qualifying_first_move',
        'qualifying_but_no_favorable_prefix': 'qualifying_but_no_favorable_prefix',
    }
    for report_field, row_field in aggregates.items():
        if require_integer(report, report_field) != sum(
                require_integer(row, row_field) for row in rows):
            raise ValueError(f'Incorrect native aggregate {report_field}')
    lengths = report.get('support_clearing_by_least_length')
    if set(lengths or {}) != {'9', '11', '13', '17'}:
        raise ValueError('Invalid native support-clearing aggregate')
    for key in lengths:
        if require_integer(lengths, key) != sum(
                require_integer(row['support_clearing_by_least_length'], key) for row in rows):
            raise ValueError(f'Incorrect native support-clearing aggregate {key}')
    return report


def load_unoriented(path: Path, expected: list[tuple[int, ...]]) -> dict:
    report = json.loads(path.read_text())
    if report.get('format') != 'td20-unoriented-root-count-v1' or report.get('complete') is not True:
        raise ValueError('Unsupported or incomplete unoriented report')
    if report.get('domain') != 21 or report.get('required_partitions') != 83 \
            or report.get('completed_partitions') != 83:
        raise ValueError('Incorrect unoriented inventory envelope')
    rows = report.get('rows')
    if not isinstance(rows, list) or [tuple(row.get('partition', ())) for row in rows] != expected:
        raise ValueError('Unoriented rows do not match the deterministic inventory')
    for number, row in enumerate(rows, 1):
        if row.get('number') != number:
            raise ValueError('Incorrect unoriented row numbering')
    total = sum(require_integer(row, 'totally_unoriented_root_classes') for row in rows)
    if total != require_integer(report, 'totally_unoriented_root_classes'):
        raise ValueError('Incorrect unoriented total')
    return report


def load_endpoints(path: Path) -> dict:
    report = json.loads(path.read_text())
    if report.get('format') != 'td20-joint2-endpoint-count-v1' \
            or report.get('complete') is not True or report.get('domain') != 18 \
            or report.get('required_annotations') != 214 \
            or report.get('completed_annotations') != 214:
        raise ValueError('Unsupported or incomplete endpoint report')
    expected = endpoint_annotations()
    rows = report.get('rows')
    if not isinstance(rows, list) or len(rows) != len(expected):
        raise ValueError('Incomplete endpoint rows')
    total = elementary = requiring = 0
    for number, (row, (partition, orientation)) in enumerate(zip(rows, expected), 1):
        closure = any(length == 3 and oriented
                      for length, oriented in zip(partition, orientation))
        if row.get('number') != number or tuple(row.get('partition', ())) != partition \
                or tuple(row.get('oriented_by_part', ())) != orientation \
                or row.get('elementary_closure') is not closure:
            raise ValueError(f'Endpoint inventory mismatch at annotation {number}')
        classes = require_integer(row, 'classes')
        total += classes
        if closure:
            elementary += classes
        else:
            requiring += classes
    for field, actual in (('all_endpoint_classes', total),
                          ('elementary_closure_classes', elementary),
                          ('classes_requiring_discharge', requiring)):
        if require_integer(report, field) != actual:
            raise ValueError(f'Incorrect endpoint aggregate {field}')
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', required=True, type=Path)
    parser.add_argument('--unoriented', required=True, type=Path)
    parser.add_argument('--endpoints', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    options = parser.parse_args()
    expected = list(partitions(21))
    if len(expected) != 83:
        raise ValueError('Independent root inventory is not 83 partitions')
    native = load_native(options.native, expected)
    unoriented = load_unoriented(options.unoriented, expected)
    endpoints = load_endpoints(options.endpoints)
    rows = []
    for number, (partition, native_row, unoriented_row) in enumerate(
            zip(expected, native['rows'], unoriented['rows']), 1):
        u = unoriented_row['totally_unoriented_root_classes']
        j = native_row['oriented_root_classes']
        c = native_row['support_clearing_classes']
        o = native_row['oriented_after_support_clearing']
        rows.append({'number': number, 'partition': list(partition), 'U': u, 'J': j,
                     'C': c, 'O': o, 'direct': u + o,
                     'no_qualifying_first_move': native_row['no_qualifying_first_move'],
                     'support_clearing_by_least_length':
                         native_row['support_clearing_by_least_length']})
    result = {
        'format': 'td20-joint2-selection-summary-v1', 'complete': True,
        'domain': 21, 'root_budget': 11, 'partitions_required': 83,
        'partitions_completed': 83,
        'selection': ['immediate gain >= 2', '(1,3)', '(1,2)'],
        'totally_unoriented_root_classes': sum(row['U'] for row in rows),
        'oriented_joint2_residual_classes': sum(row['J'] for row in rows),
        'support_clearing_classes': sum(row['C'] for row in rows),
        'oriented_root_classes_after_support_clearing': sum(row['O'] for row in rows),
        'direct_root_class_obligations': sum(row['direct'] for row in rows),
        'oriented_roots_without_qualifying_first_move':
            sum(row['no_qualifying_first_move'] for row in rows),
        'support_clearing_by_least_length': {
            key: sum(row['support_clearing_by_least_length'][key] for row in rows)
            for key in ('9', '11', '13', '17')},
        'rows': rows,
        'sorting_certificates_checked': False,
        'td20_proved': False,
    }
    result['endpoint_counts'] = {
        key: require_integer(endpoints, key) for key in
        ('all_endpoint_classes', 'elementary_closure_classes',
         'classes_requiring_discharge')}
    options.output = options.output.resolve()
    options.output.parent.mkdir(parents=True, exist_ok=True)
    with options.output.open('x') as destination:
        json.dump(result, destination, indent=2)
        destination.write('\n')
    print(json.dumps({key: value for key, value in result.items() if key != 'rows'}, indent=2))


if __name__ == '__main__':
    main()
