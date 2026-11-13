#!/usr/bin/env python3
"""Reconcile checked U,J,C,O reports with every active paper R row.

The retained joint2 summary supplies census and oriented counts. Its superseded
U+O total is discarded. This report counts obligations; it verifies no witness.
"""
import argparse
import copy
import json
from pathlib import Path
import re
from publish_joint2_counts import load_summary


def reconcile(summary, paper):
    rows = summary['rows']
    source = paper.read_text()
    root_pattern = re.compile(r'\\jointrow\{(\d+)\}\{([0-9,]+)\}'
                              r'\{([0-9]+)\}\{([0-9]+)\}\{([0-9]+)\}\{([0-9]+)\}')
    residual_pattern = re.compile(r'\\unorientedresidualrow\{(\d+)\}\{([0-9]+)\}')
    roots, residuals = [], []
    for line in source.splitlines():
        line = line.strip()
        if line.startswith(r'\jointrow'):
            match = root_pattern.fullmatch(line)
            if match is None: raise ValueError('Malformed or pending root row')
            roots.append((int(match[1]), list(map(int, match[2].split(','))),
                          tuple(map(int, match.group(3,4,5,6)))))
        if line.startswith(r'\unorientedresidualrow'):
            match = residual_pattern.fullmatch(line)
            if match is None: raise ValueError('Malformed or pending R row')
            residuals.append((int(match[1]), int(match[2])))
    numbers = list(range(1,84))
    if [r[0] for r in roots] != numbers or [r[0] for r in residuals] != numbers:
        raise ValueError('Missing, duplicate, or reordered root/R inventory')
    result = copy.deepcopy(summary)
    total_r = 0
    for row, (number, partition, counts), (_, r) in zip(result['rows'],roots,residuals):
        if row['number'] != number or row['partition'] != partition or tuple(row[k] for k in ('U','J','C','O')) != counts:
            raise ValueError('Summary disagrees with paper row '+str(number))
        if r > row['U']: raise ValueError('R exceeds U at row '+str(number))
        if all(k%2 for k in partition) and r != row['U']:
            raise ValueError('All-odd unoriented row must have R=U')
        row['R'] = r
        row['direct'] = r + row['O']
        total_r += r
    result['format'] = 'td20-joint-selection-summary-v3'
    result['source_summary_format'] = summary['format']
    result['totally_unoriented_residual_root_classes'] = total_r
    result['direct_root_class_obligations'] = sum(row['direct'] for row in result['rows'])
    result['scope'] = 'Complete root counts; no sorting or inverse verification'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report',type=Path,help='Complete retained U,J,C,O summary')
    parser.add_argument('--paper',type=Path,default=Path('docs/td20-reduction-count.tex'))
    parser.add_argument('--output',type=Path)
    args = parser.parse_args()
    result = reconcile(load_summary(args.report),args.paper)
    if args.output:
        with args.output.open('x') as output: json.dump(result,output,indent=2);output.write('\n')
    else: print(json.dumps(result,indent=2))


if __name__ == '__main__': main()
