"""Regression checks for subtree partitioning and checkpoint resumption."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parent
SCRIPT = DIRECTORY / "run_joint_counts.py"
SPEC = importlib.util.spec_from_file_location("joint3_runner", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
BINARY = Path(os.environ.get("TD20_NATIVE_AUDIT_BINARY", "/tmp/td20-joint3-count-v2"))
BASELINE = Path("/tmp/td20-joint3-count")


@unittest.skipUnless(BINARY.is_file(), "Build the native v2 counter first")
class JointThreeShardingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def count(self, part, index=0, shards=1, depth=4, representatives=None, binary=BINARY):
        arguments = [str(binary), "--partition=" + ",".join(map(str, part)),
                     f"--shard-index={index}", f"--shard-count={shards}"]
        if binary == BINARY:
            arguments.append(f"--shard-depth={depth}")
        if representatives:
            arguments.append(f"--representatives={representatives}")
        return json.loads(subprocess.check_output(arguments, text=True))

    @staticmethod
    def representative_set(path, domain, expected):
        lines = path.read_text().splitlines()
        if lines[0] != f"TD20-JOINT3-RESIDUALS 1 N={domain} scope=oriented" \
                or lines[-1] != f"COUNT {expected}":
            raise AssertionError("Invalid representative boundary")
        values = set(lines[1:-1])
        if len(values) != expected or len(lines) != expected + 2:
            raise AssertionError("Duplicate or missing representative")
        return values

    def test_subtree_shards_have_the_same_counts_and_disjoint_class_sets(self):
        total_classes = 0
        for part in ((11,), (7, 2, 2), (5, 3, 3), (13,), (7, 3, 3)):
            baseline_path = self.directory / "baseline.txt"
            baseline = self.count(part, representatives=baseline_path,
                                  binary=BASELINE if BASELINE.is_file() else BINARY)
            expected = self.representative_set(baseline_path, sum(part), baseline["oriented_root_classes"])
            total_classes += len(expected)
            rows, union = [], set()
            for shard in range(7):
                path = self.directory / f"shard-{shard}.txt"
                row = self.count(part, shard, 7, 4, path)
                values = self.representative_set(path, sum(part), row["oriented_root_classes"])
                self.assertFalse(union & values)
                union.update(values)
                rows.append(row)
            self.assertEqual(expected, union)
            merged = runner.merge_shards(part, rows)
            for field in runner.COUNT_FIELDS:
                self.assertEqual(baseline[field], merged[field], (part, field))
        self.assertGreater(total_classes, 0)

    def test_resume_preserves_completed_partitions_and_shards(self):
        output = self.directory / "native"
        output.mkdir()
        inventory = list(runner.partitions(9))
        runner.run_partition(BINARY, output, inventory[0], False, 0, 1, 4)
        runner.run_partition(BINARY, output, inventory[1], False, 0, 7, 4)
        legacy = self.count(inventory[2], binary=BASELINE if BASELINE.is_file() else BINARY)
        legacy_path = output / runner.report_name(inventory[2], 0, 1, legacy.get("shard_depth", 1),
                                                  legacy["format"] == runner.ROW_V1)
        legacy_path.write_text(json.dumps(legacy))
        cached = {path: path.read_bytes() for path in output.glob("partition-*.json")}
        arguments = [sys.executable, "-B", str(SCRIPT), "--n=9", "--workers=3",
                     "--partition-shards=7", "--shard-depth=4", "--resume",
                     f"--binary={BINARY}", f"--output={output}"]
        subprocess.run(arguments, check=True, capture_output=True, text=True)
        report = json.loads((output / "counts.json").read_text())
        self.assertTrue(report["complete"])
        self.assertEqual(len(inventory), report["completed_partitions"])
        self.assertEqual(2, report["reused_complete_partitions"])
        for path, content in cached.items():
            self.assertEqual(content, path.read_bytes())
        all_cached = {path: path.read_bytes() for path in output.glob("partition-*.json")}
        result = subprocess.run(arguments, check=True, capture_output=True, text=True)
        self.assertIn('"remaining_partitions": 0', result.stdout)
        for path, content in all_cached.items():
            self.assertEqual(content, path.read_bytes())
        result = subprocess.run([argument for argument in arguments if argument != "--resume"],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)

    def test_mixed_depths_and_incomplete_shard_unions_are_rejected(self):
        output = self.directory / "mixed"
        output.mkdir()
        part = (9,)
        rows = []
        for shard, depth in ((0, 2), (1, 4)):
            row = self.count(part, shard, 7, depth)
            rows.append(row)
            path = output / runner.report_name(part, shard, 7, depth)
            path.write_text(json.dumps(row))
        with self.assertRaises(ValueError):
            runner.load_saved_rows(output, [part])
        with self.assertRaises(ValueError):
            runner.merge_shards(part, rows)


if __name__ == "__main__":
    unittest.main()
