"""Regression checks for strict TD(20) joint-3 artifact validation."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


MODULE = Path(__file__).with_name('summarize_joint3_counts.py')
SPEC = importlib.util.spec_from_file_location('joint3_summary', MODULE)
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


class JointThreeSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.partitions = list(summary.partitions(21))
        native_rows = []
        unoriented_rows = []
        for number, partition in enumerate(self.partitions, 1):
            cleared = number % 2
            no_first = number // 2
            native_rows.append({
                'partition': list(partition),
                'oriented_root_classes': number,
                'support_clearing_classes': cleared,
                'oriented_after_support_clearing': number - cleared,
                'no_qualifying_first_move': no_first,
                'qualifying_but_no_favorable_prefix': number - no_first,
                'support_clearing_by_least_length':
                    {'9': cleared, '11': 0, '13': 0, '17': 0},
            })
            unoriented_rows.append({
                'number': number, 'partition': list(partition),
                'totally_unoriented_root_classes': 2 * number,
            })
        self.native = {
            'format': 'td20-joint3-count-run-v1', 'complete': True, 'domain': 21,
            'required_partitions': 83, 'completed_partitions': 83, 'rows': native_rows,
        }
        for field in ('oriented_root_classes', 'support_clearing_classes',
                      'oriented_after_support_clearing', 'no_qualifying_first_move',
                      'qualifying_but_no_favorable_prefix'):
            self.native[field] = sum(row[field] for row in native_rows)
        self.native['support_clearing_by_least_length'] = {
            key: sum(row['support_clearing_by_least_length'][key] for row in native_rows)
            for key in ('9', '11', '13', '17')
        }
        self.unoriented = {
            'format': 'td20-unoriented-root-count-v1', 'complete': True, 'domain': 21,
            'required_partitions': 83, 'completed_partitions': 83,
            'rows': unoriented_rows,
            'totally_unoriented_root_classes':
                sum(row['totally_unoriented_root_classes'] for row in unoriented_rows),
        }
        endpoint_rows = []
        for number, (partition, orientation) in enumerate(summary.endpoint_annotations(), 1):
            closure = any(length == 3 and oriented
                          for length, oriented in zip(partition, orientation))
            endpoint_rows.append({
                'number': number, 'partition': list(partition),
                'oriented_by_part': list(orientation), 'classes': number,
                'elementary_closure': closure,
            })
        self.endpoints = {
            'format': 'td20-joint3-endpoint-count-v1', 'complete': True, 'domain': 16,
            'required_annotations': 104, 'completed_annotations': 104, 'rows': endpoint_rows,
            'all_endpoint_classes': sum(row['classes'] for row in endpoint_rows),
            'elementary_closure_classes':
                sum(row['classes'] for row in endpoint_rows if row['elementary_closure']),
            'classes_requiring_discharge':
                sum(row['classes'] for row in endpoint_rows if not row['elementary_closure']),
        }

    def write(self, name, value):
        path = self.directory / name
        path.write_text(json.dumps(value))
        return path

    def test_complete_artifacts_accept(self):
        self.assertEqual(self.native, summary.load_native(
            self.write('native.json', self.native), self.partitions))
        self.assertEqual(self.unoriented, summary.load_unoriented(
            self.write('unoriented.json', self.unoriented), self.partitions))
        self.assertEqual(self.endpoints, summary.load_endpoints(
            self.write('endpoints.json', self.endpoints)))

    def test_missing_duplicate_and_inconsistent_rows_reject(self):
        for field, report, loader in (
                ('native', self.native, lambda path: summary.load_native(path, self.partitions)),
                ('unoriented', self.unoriented,
                 lambda path: summary.load_unoriented(path, self.partitions)),
                ('endpoints', self.endpoints, summary.load_endpoints)):
            for rows in (report['rows'][:-1], report['rows'] + [report['rows'][0]]):
                changed = copy.deepcopy(report)
                changed['rows'] = rows
                with self.subTest(field=field, size=len(rows)), self.assertRaises(ValueError):
                    loader(self.write(f'{field}-{len(rows)}.json', changed))
            changed = copy.deepcopy(report)
            aggregate = next(key for key in report if key.endswith('_classes'))
            changed[aggregate] += 1
            with self.subTest(field=field, aggregate=aggregate), self.assertRaises(ValueError):
                loader(self.write(f'{field}-aggregate.json', changed))


if __name__ == '__main__':
    unittest.main()
