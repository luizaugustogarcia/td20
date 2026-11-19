#!/usr/bin/env python3
"""Exercise restart cleanup against tiny disposable directory trees."""

import importlib.util
import fcntl
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('local_work_cleanup',
        Path(__file__).resolve().parents[1] / 'local-work-cleanup.py')
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class LocalWorkCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.base = self.root / 'run'
        for name in ('logs', 'work', 'tmp'):
            (self.base / name).mkdir(parents=True)
        self.database = self.root / 'certificates'
        self.database.mkdir()
        (self.database / 'CURRENT').write_text('kept')

    def work(self, name, checkpoint=False):
        path = self.base / 'work' / ('emitter-' + name)
        (path / 'roots').mkdir(parents=True)
        (path / 'roots' / 'data.sst').write_text('small partial output')
        record = self.base / 'logs' / (path.name + '.json')
        record.write_text(json.dumps(dict(format='td20-local-run-v1', module='emitter',
                work=str(path), database=str(self.database))))
        if checkpoint:
            self.checkpoint(path)
        return path

    def checkpoint(self, path):
        (path / 'after-one').mkdir(exist_ok=True)
        (path / 'after-one' / 'CURRENT').write_text('kept')
        (path / 'eriksson-reconstruction-v1.txt').write_text(
                'td-eriksson-reconstruction-v3\nroute=td20-eriksson-three-v1\n'
                'rootSymbols=21\nrootBudget=11\nstage=AFTER_ONE\nendpoints=2036\n'
                'afterTwo=970331\nafterOne=-1\nroots=-1\nmode=RESIDUAL_EXPANSION\n')

    def test_deletes_abandoned_output_but_preserves_completed_sources(self):
        abandoned = self.work('old')
        source = self.work('source', checkpoint=True)
        other_completed = self.work('completed', checkpoint=True)
        (self.base / 'tmp' / 'leftover').write_text('scratch')
        removed = cleanup.cleanup(self.base, self.database, source)
        self.assertEqual([abandoned], removed)
        self.assertFalse(abandoned.exists())
        self.assertTrue((source / 'after-one' / 'CURRENT').is_file())
        self.assertTrue(other_completed.is_dir())
        self.assertTrue((self.database / 'CURRENT').is_file())
        self.assertEqual([], list((self.base / 'tmp').iterdir()))
        settings = json.loads((self.base / 'logs' / 'emitter-old.json').read_text())
        self.assertIn('workRemovedUtc', settings)
        self.assertEqual([], cleanup.cleanup(self.base, self.database, source))

    def test_missing_resume_database_rejects_cleanup_before_any_deletion(self):
        abandoned = self.work('old')
        source = self.work('source', checkpoint=True)
        (source / 'after-one' / 'CURRENT').unlink()
        with self.assertRaises(ValueError):
            cleanup.cleanup(self.base, self.database, source)
        self.assertTrue(abandoned.exists())

    def test_dry_run_does_not_remove_data(self):
        abandoned = self.work('old')
        self.assertEqual([abandoned], cleanup.cleanup(self.base, self.database, dry_run=True))
        self.assertTrue(abandoned.exists())

    def test_filtered_root_checkpoint_preserves_its_inventory_semantics(self):
        source = self.work('filtered')
        (source / 'roots' / 'CURRENT').write_text('kept')
        checkpoint = source / 'eriksson-reconstruction-v1.txt'
        valid = ('td-eriksson-reconstruction-v4\nroute=td20-eriksson-three-v1\n'
                 'rootSymbols=21\nrootBudget=11\nstage=ROOTS\nendpoints=2036\n'
                 'afterTwo=970331\nafterOne=4010256622\nroots=42\n'
                 'mode=RESIDUAL_EXPANSION\nrootInventory=ELEMENTARY_RESIDUALS\n')
        checkpoint.write_text(valid)
        self.assertEqual(source.resolve(), cleanup.resume_source(source))
        checkpoint.write_text(valid.replace('mode=RESIDUAL_EXPANSION', 'mode=FULL_SEARCH'))
        self.assertEqual(source.resolve(), cleanup.resume_source(source))
        for altered in (valid.replace('rootInventory=ELEMENTARY_RESIDUALS\n', ''),
                        valid.replace('ELEMENTARY_RESIDUALS', 'ALL_PARENTS'),
                        valid.replace('stage=ROOTS', 'stage=AFTER_ONE'),
                        valid.replace('reconstruction-v4', 'reconstruction-v2')):
            checkpoint.write_text(altered)
            with self.assertRaises(ValueError):
                cleanup.resume_source(source)

    def test_foreign_resume_route_rejects_cleanup_before_any_deletion(self):
        abandoned = self.work('old')
        source = self.work('source', checkpoint=True)
        checkpoint = source / 'eriksson-reconstruction-v1.txt'
        checkpoint.write_text(checkpoint.read_text().replace('td20-eriksson-three-v1',
                'td18-eriksson-three-v1'))
        with self.assertRaises(ValueError):
            cleanup.cleanup(self.base, self.database, source)
        self.assertTrue(abandoned.exists())

    def test_inconsistent_resume_inventory_rejects_cleanup_before_any_deletion(self):
        abandoned = self.work('old')
        source = self.work('source', checkpoint=True)
        checkpoint = source / 'eriksson-reconstruction-v1.txt'
        checkpoint.write_text(checkpoint.read_text().replace('reconstruction-v3', 'reconstruction-v2'))
        with self.assertRaises(ValueError):
            cleanup.cleanup(self.base, self.database, source)
        self.assertTrue(abandoned.exists())

    def test_malformed_and_negative_counts_reject_before_any_deletion(self):
        abandoned = self.work('old')
        source = self.work('source', checkpoint=True)
        checkpoint = source / 'eriksson-reconstruction-v1.txt'
        valid = checkpoint.read_text()
        for count in ('-1', '970_331', ' 970331', '9223372036854775808'):
            with self.subTest(count=count):
                checkpoint.write_text(valid.replace('afterTwo=970331', 'afterTwo=' + count))
                with self.assertRaises(ValueError):
                    cleanup.cleanup(self.base, self.database, source)
                self.assertTrue(abandoned.exists())

    def test_incomplete_and_unordered_checkpoints_reject_before_any_deletion(self):
        abandoned = self.work('old')
        source = self.work('source', checkpoint=True)
        checkpoint = source / 'eriksson-reconstruction-v1.txt'
        valid = checkpoint.read_text().splitlines()
        reordered = valid[:]
        reordered[5], reordered[6] = reordered[6], reordered[5]
        for lines in (valid[:-1], valid + ['unexpected=value'], reordered):
            with self.subTest(lines=lines):
                checkpoint.write_text('\n'.join(lines) + '\n')
                with self.assertRaises(ValueError):
                    cleanup.cleanup(self.base, self.database, source)
                self.assertTrue(abandoned.exists())

    def test_certificate_database_overlaps_are_preserved(self):
        work = self.work('database')
        protected_database = work / 'roots'
        self.assertEqual([], cleanup.cleanup(self.base, protected_database))
        self.assertTrue(protected_database.is_dir())

    def test_outside_paths_and_directory_links_are_not_followed(self):
        external = self.root / 'emitter-external'
        external.mkdir()
        (external / 'valuable').write_text('kept')
        (self.base / 'logs' / 'emitter-external.json').write_text(json.dumps(dict(
                format='td20-local-run-v1', module='emitter', work=str(external))))
        link = self.base / 'work' / 'emitter-link'
        link.symlink_to(external, target_is_directory=True)
        (self.base / 'logs' / 'emitter-link.json').write_text(json.dumps(dict(
                format='td20-local-run-v1', module='emitter', work=str(link))))
        temporary_link = self.base / 'tmp' / 'link'
        temporary_link.symlink_to(external, target_is_directory=True)
        self.assertEqual([], cleanup.cleanup(self.base, self.database))
        self.assertTrue((external / 'valuable').is_file())
        self.assertTrue(link.is_symlink())
        self.assertFalse(temporary_link.exists())

    def test_malformed_settings_reject_cleanup_before_any_deletion(self):
        abandoned = self.work('old')
        (self.base / 'logs' / 'z-broken.json').write_text('{')
        with self.assertRaises(json.JSONDecodeError):
            cleanup.cleanup(self.base, self.database)
        self.assertTrue(abandoned.exists())

    def test_cli_requires_the_inherited_run_lock(self):
        abandoned = self.work('old')
        command = [sys.executable, str(Path(cleanup.__file__)), '--base', str(self.base),
                '--database', str(self.database)]
        rejected = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, rejected.returncode)
        self.assertTrue(abandoned.exists())
        with (self.base / 'run.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            accepted = subprocess.run(command + ['--lock-fd', str(lock.fileno())],
                    pass_fds=[lock.fileno()], capture_output=True, text=True)
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertFalse(abandoned.exists())


if __name__ == '__main__':
    unittest.main()
