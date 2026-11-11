"""Java counting resumption and actual persisted-format integration checks."""
from __future__ import annotations
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import run_joint_counts as rows
from publish_partial_joint3_counts import validate_native_row

PROJECT = Path(__file__).resolve().parents[2]
CLASSES = PROJECT / 'emitter/target/classes'


@unittest.skipUnless((CLASSES / 'br/unb/cic/td20/CountTD20Joint.class').exists(), 'Compile the Java counter first')
class JavaJointWorkflowTest(unittest.TestCase):
    def test_actual_java_rows_resume_legacy_cache_without_rewriting_it(self):
        with tempfile.TemporaryDirectory(prefix='td20-java-resume-test-') as directory:
            output = Path(directory) / 'native'
            listing = Path(directory) / 'partitions.txt'
            listing.write_text('9\n')
            subprocess.run(['java', '-cp', str(CLASSES), 'br.unb.cic.td20.CountTD20Joint',
                            '--n=9', f'--partitions-file={listing}', f'--output={output}'],
                           check=True, capture_output=True, text=True)
            cached = output / 'partition-9.json'
            row = json.loads(cached.read_text())
            validate_native_row(row, {(9,)})
            invalid = dict(row, worker_limit=0)
            with self.assertRaises(ValueError):
                validate_native_row(invalid, {(9,)})
            with self.assertRaises(ValueError):
                rows.validate_row(invalid, (9,))
            # A complete historical envelope, using actual
            # small-domain Java counts, exercises the production migration boundary.
            row['format'] = rows.ROW_V1
            for field in ('implementation', 'scheduling', 'worker_limit'):
                row.pop(field)
            cached.write_text(json.dumps(row) + '\n')
            before = cached.read_bytes()
            command = [sys.executable, '-B', str(PROJECT / 'tools/audit/run_java_joint_counts.py'),
                       '--n=9', '--resume', f'--output={output}']
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            self.assertIn('"reused_complete_partitions": 1', result.stdout)
            self.assertEqual(before, cached.read_bytes())
            report = json.loads((output / 'counts.json').read_text())
            self.assertEqual(len(list(rows.partitions(9))), report['completed_partitions'])
            self.assertEqual(1, report['retained_native_audit_partitions'])
            for item in report['rows']:
                rows.validate_row(item, tuple(item['partition']))
            saved = {p: p.read_bytes() for p in output.glob('partition-*.json')}
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            self.assertIn('"remaining_partitions": 0', result.stdout)
            self.assertEqual(saved, {p: p.read_bytes() for p in output.glob('partition-*.json')})

    def test_incomplete_legacy_shards_cannot_omit_a_partition(self):
        with tempfile.TemporaryDirectory(prefix='td20-java-incomplete-test-') as directory:
            output = Path(directory)
            row = dict(format=rows.ROW_V1, partition=[9], shard_index=0, shard_count=2,
                       elapsed_seconds=0, support_clearing_by_least_length={'9':0,'11':0,'13':0,'17':0})
            row.update({field:0 for field in rows.COUNT_FIELDS})
            (output / 'partition-9-shard-00-of-02.json').write_text(json.dumps(row))
            result = subprocess.run([sys.executable, '-B', str(PROJECT / 'tools/audit/run_java_joint_counts.py'),
                                     '--n=9', '--resume', f'--output={output}'], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('incomplete legacy shard', result.stderr)
            self.assertFalse((output / 'counts.json').exists())


if __name__ == '__main__':
    unittest.main()
