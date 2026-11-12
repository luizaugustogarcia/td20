"""Quick end-to-end checks for partial and final TD(20) table publishing."""
from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parent
PROJECT = DIRECTORY.parent.parent
PARTIAL_SCRIPT = DIRECTORY / "publish_partial_joint2_counts.py"
FINAL_SCRIPT = DIRECTORY / "publish_joint2_counts.py"
PAPER = PROJECT / "docs" / "td20-reduction-count.tex"


def load(name: str, path: Path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


partial = load("partial_joint2_publisher", PARTIAL_SCRIPT)
final = load("final_joint2_publisher", FINAL_SCRIPT)


def pending_paper() -> str:
    """Tests use a pending fixture regardless of production count progress."""
    paper = PAPER.read_text()
    paper = re.sub(r"^\\(?:pendingjointrow|unorientedjointrow|jointrow)\{(\d+)\}\{([^}]+)\}[^\n]*$",
                   lambda match: rf"\pendingjointrow{{{match[1]}}}{{{match[2]}}}",
                   paper, flags=re.MULTILINE)
    paper = re.sub(r"^\\newcommand\{\\unorientedjointrow\}[^\n]*\n", "", paper, flags=re.MULTILINE)
    if r"\newcommand{\pendingjointrow}" not in paper:
        anchor = r"\newcommand{\jointrow}[6]{#1 & $(#2)$ & #3 & #4 & #5 & #6\\}"
        paper = paper.replace(anchor, anchor + "\n" +
            r"\newcommand{\pendingjointrow}[2]{#1 & $(#2)$ & \multicolumn{4}{c}{\emph{pending exact artifacts}}\\}")
    paper = re.sub(r"^\\multicolumn\{2\}\{l\}\{Total\}[^\n]*$",
        lambda _: r"\multicolumn{2}{l}{Total} & \multicolumn{4}{c}{\emph{pending exact artifacts}}\\",
        paper, flags=re.MULTILINE)
    paper = re.sub(r"^((?:9  |11 |13 |17 )[^\n]+ & )(?:pending|[0-9]+)(\\\\)$",
                   lambda match: match[1] + "pending" + match[2], paper, flags=re.MULTILINE)
    return paper.replace("Exact counts by least clearing length.", "Counts are pending.")


class JointTwoPublisherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.native_directory = self.directory / "native"
        self.native_directory.mkdir()
        self.paper = self.directory / "source.tex"
        self.paper.write_text(pending_paper())
        self.partitions = list(partial.partitions(21))
        self.assertEqual(83, len(self.partitions))
        self.unoriented = {
            "format": "td20-unoriented-root-count-v1",
            "complete": True,
            "domain": 21,
            "required_partitions": 83,
            "completed_partitions": 83,
            "rows": [
                {"number": number, "partition": list(partition),
                 "totally_unoriented_root_classes": 2 * number}
                for number, partition in enumerate(self.partitions, 1)
            ],
        }
        self.unoriented["totally_unoriented_root_classes"] = sum(
            row["totally_unoriented_root_classes"] for row in self.unoriented["rows"])
        self.unoriented_path = self.write_json("unoriented.json", self.unoriented)
        self.summary = self.make_summary()

    def write_json(self, name: str, value: object) -> Path:
        path = self.directory / name
        path.write_text(json.dumps(value))
        return path

    @staticmethod
    def native_row(partition: tuple[int, ...], joint: int, cleared: int,
                   no_first: int, shard_index: int = 0, shard_count: int = 1) -> dict:
        return {
            "format": "td20-joint2-residual-count-v1",
            "partition": list(partition),
            "shard_index": shard_index,
            "shard_count": shard_count,
            "oriented_root_classes": joint,
            "support_clearing_classes": cleared,
            "oriented_after_support_clearing": joint - cleared,
            "no_qualifying_first_move": no_first,
            "qualifying_but_no_favorable_prefix": joint - no_first,
            "support_clearing_by_least_length":
                {"9": cleared, "11": 0, "13": 0, "17": 0},
        }

    def make_summary(self) -> dict:
        rows = []
        for number, partition in enumerate(self.partitions, 1):
            u = 2 * number
            c = number % 2
            o = number
            j = c + o
            rows.append({
                "number": number, "partition": list(partition),
                "U": u, "J": j, "C": c, "O": o, "direct": u + o,
                "no_qualifying_first_move": number // 2,
                "support_clearing_by_least_length":
                    {"9": c, "11": 0, "13": 0, "17": 0},
            })
        result = {
            "format": "td20-joint2-selection-summary-v1",
            "complete": True, "domain": 21, "root_budget": 11,
            "partitions_required": 83, "partitions_completed": 83,
            "selection": ["immediate gain >= 2", "(1,3)", "(1,2)"],
            "rows": rows, "sorting_certificates_checked": False,
            "td20_proved": False,
            "endpoint_counts": {"all_endpoint_classes": 11,
                                "elementary_closure_classes": 3,
                                "classes_requiring_discharge": 8},
        }
        fields = {
            "totally_unoriented_root_classes": "U",
            "oriented_joint2_residual_classes": "J",
            "support_clearing_classes": "C",
            "oriented_root_classes_after_support_clearing": "O",
            "direct_root_class_obligations": "direct",
            "oriented_roots_without_qualifying_first_move":
                "no_qualifying_first_move",
        }
        for field, row_field in fields.items():
            result[field] = sum(row[row_field] for row in rows)
        result["support_clearing_by_least_length"] = {
            key: sum(row["support_clearing_by_least_length"][key] for row in rows)
            for key in ("9", "11", "13", "17")
        }
        return result

    def run_partial(self, paper: Path, output: Path) -> None:
        subprocess.run([
            sys.executable, str(PARTIAL_SCRIPT),
            "--unoriented", str(self.unoriented_path),
            "--native-directory", str(self.native_directory),
            "--paper", str(paper), "--output", str(output),
        ], check=True, capture_output=True, text=True)

    def test_partial_end_to_end_is_incremental_and_idempotent(self):
        first = self.partitions[0]
        second = self.partitions[1]
        (self.native_directory / "partition-21-shard-00-of-02.json").write_text(
            json.dumps(self.native_row(first, 2, 1, 1, 0, 2)))
        (self.native_directory / "partition-17-2-2.json").write_text(
            json.dumps(self.native_row(second, 4, 1, 2)))

        snapshot_one = self.directory / "snapshot-one.tex"
        self.run_partial(self.paper, snapshot_one)
        text_one = snapshot_one.read_text()
        self.assertIn(r"\unorientedjointrow{1}{21}{2}", text_one)
        self.assertIn(r"\jointrow{2}{17,2,2}{4}{4}{1}{3}", text_one)
        self.assertIn("pending joint-residual count", text_one)

        (self.native_directory / "partition-21-shard-01-of-02.json").write_text(
            json.dumps(self.native_row(first, 3, 0, 2, 1, 2)))
        snapshot_two = self.directory / "snapshot-two.tex"
        self.run_partial(snapshot_one, snapshot_two)
        text_two = snapshot_two.read_text()
        self.assertIn(r"\jointrow{1}{21}{2}{5}{1}{4}", text_two)
        self.assertIn(r"\jointrow{2}{17,2,2}{4}{4}{1}{3}", text_two)
        self.assertIn(r"\unorientedjointrow{3}{16,3,2}{6}", text_two)

        snapshot_three = self.directory / "snapshot-three.tex"
        self.run_partial(snapshot_two, snapshot_three)
        self.assertEqual(text_two, snapshot_three.read_text())

    def test_partial_rejects_corrupt_completed_row_without_output(self):
        row = self.native_row(self.partitions[0], 3, 1, 1)
        row["oriented_after_support_clearing"] = 3
        (self.native_directory / "partition-21.json").write_text(json.dumps(row))
        output = self.directory / "must-not-exist.tex"
        result = subprocess.run([
            sys.executable, str(PARTIAL_SCRIPT),
            "--unoriented", str(self.unoriented_path),
            "--native-directory", str(self.native_directory),
            "--paper", str(self.paper), "--output", str(output),
        ], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())

    def test_in_place_publishing_preserves_proof_status_and_reconciles_totals(self):
        paper = self.directory / "paper.tex"
        paper.write_text(self.paper.read_text())
        for row in self.summary["rows"]:
            partition = tuple(row["partition"])
            name = "partition-" + "-".join(map(str, partition)) + ".json"
            (self.native_directory / name).write_text(json.dumps(self.native_row(
                partition, row["J"], row["C"], row["no_qualifying_first_move"])))
        subprocess.run([
            sys.executable, str(PARTIAL_SCRIPT),
            "--unoriented", str(self.unoriented_path),
            "--native-directory", str(self.native_directory),
            "--paper", str(paper), "--in-place",
        ], check=True, capture_output=True, text=True)
        partial_text = paper.read_text()
        self.assertEqual(83, partial_text.count("\\jointrow{"))
        total = r"\multicolumn{2}{l}{Total} & " + " & ".join(str(self.summary[field]) for field in
            ("totally_unoriented_root_classes", "oriented_joint2_residual_classes",
             "support_clearing_classes", "oriented_root_classes_after_support_clearing")) + r"\\"
        self.assertIn(total, partial_text)
        summary_path = self.write_json("in-place-summary.json", self.summary)
        subprocess.run([
            sys.executable, str(FINAL_SCRIPT), "--summary", str(summary_path),
            "--paper", str(paper), "--in-place",
        ], check=True, capture_output=True, text=True)
        final_text = paper.read_text()
        self.assertIn("td20_proved=false", final_text)
        self.assertIn("sorting_certificates_checked=false", final_text)
        self.assertNotIn("pending exact artifacts", final_text)
        self.assertIn(r"All endpoint classes & 11\\", final_text)
        self.assertIn(r"Elementary endpoint closures & 3\\", final_text)
        self.assertIn(r"Endpoint classes requiring discharge (budget nine) & 8\\", final_text)
        subprocess.run([
            sys.executable, str(FINAL_SCRIPT), "--summary", str(summary_path),
            "--paper", str(paper), "--in-place",
        ], check=True, capture_output=True, text=True)
        self.assertEqual(final_text, paper.read_text())
        subprocess.run([
            sys.executable, str(PARTIAL_SCRIPT), "--unoriented", str(self.unoriented_path),
            "--native-directory", str(self.native_directory), "--paper", str(paper), "--in-place",
        ], check=True, capture_output=True, text=True)
        self.assertEqual(final_text, paper.read_text())

    def test_native_burnside_v2_arithmetic_is_checked_and_v1_rejected(self):
        report = copy.deepcopy(self.unoriented)
        report.update({
            "implementation": "native-cpp20-burnside-v2",
            "term_format": "td20-unoriented-burnside-term-v2",
            "rotation_divisor_multiplicities": {"1": 12, "3": 6, "7": 2},
            "reflection_shift_multiplicities": {"0": 21},
        })
        for row in report["rows"]:
            identity = 42 * row["totally_unoriented_root_classes"]
            divisor = row["partition"].count(row["partition"][0])
            row.update({
                "identity_fixed": identity, "identity_fixed_divisor": divisor,
                "identity_fixed_numerator": divisor * identity,
                "rotation_fixed_by_divisor": {"1": 0, "3": 0, "7": 0},
                "reflection_fixed_by_shift_class": {"0": 0},
                "burnside_numerator": identity,
            })
        path = self.write_json("native-burnside.json", report)
        self.assertEqual(self.partitions, partial.load_unoriented(path)[0])
        corrupt = copy.deepcopy(report)
        corrupt["rows"][0]["identity_fixed_numerator"] += 1
        with self.assertRaises(ValueError):
            partial.load_unoriented(self.write_json("bad-normalization.json", corrupt))
        corrupt = copy.deepcopy(report)
        corrupt["rows"][0]["rotation_fixed_by_divisor"].pop("7")
        with self.assertRaises(ValueError):
            partial.load_unoriented(self.write_json("missing-term.json", corrupt))
        corrupt = copy.deepcopy(report)
        corrupt["term_format"] = "td20-unoriented-burnside-term-v1"
        with self.assertRaises(ValueError):
            partial.load_unoriented(self.write_json("rejected-v1.json", corrupt))

    def test_final_accepts_complete_summary_and_renders_all_rows(self):
        summary_path = self.write_json("summary.json", self.summary)
        output = self.directory / "final.tex"
        subprocess.run([
            sys.executable, str(FINAL_SCRIPT), "--summary", str(summary_path),
            "--paper", str(self.paper), "--output", str(output),
        ], check=True, capture_output=True, text=True)
        rendered = output.read_text()
        self.assertEqual(83, rendered.count("\\jointrow{"))
        self.assertNotIn("pending joint-residual count", rendered)
        self.assertNotIn("pending exact artifacts", rendered)

    def test_final_rejects_incomplete_or_inconsistent_summaries(self):
        changes = []

        incomplete = copy.deepcopy(self.summary)
        incomplete["rows"].pop()
        changes.append(incomplete)

        wrong_key = copy.deepcopy(self.summary)
        wrong_key["rows"][1]["partition"] = wrong_key["rows"][0]["partition"]
        changes.append(wrong_key)

        wrong_direct = copy.deepcopy(self.summary)
        wrong_direct["rows"][0]["direct"] += 1
        changes.append(wrong_direct)

        wrong_clearing = copy.deepcopy(self.summary)
        wrong_clearing["rows"][0]["support_clearing_by_least_length"]["9"] += 1
        changes.append(wrong_clearing)

        wrong_endpoint = copy.deepcopy(self.summary)
        wrong_endpoint["endpoint_counts"] = {
            "all_endpoint_classes": 3, "elementary_closure_classes": 1,
            "classes_requiring_discharge": 1,
        }
        changes.append(wrong_endpoint)

        missing_endpoints = copy.deepcopy(self.summary)
        del missing_endpoints["endpoint_counts"]
        changes.append(missing_endpoints)

        for invalid_endpoints in (None, {}, []):
            changed = copy.deepcopy(self.summary)
            changed["endpoint_counts"] = invalid_endpoints
            changes.append(changed)

        for index, changed in enumerate(changes):
            path = self.write_json(f"invalid-{index}.json", changed)
            output = self.directory / f"invalid-{index}.tex"
            result = subprocess.run([
                sys.executable, str(FINAL_SCRIPT), "--summary", str(path),
                "--paper", str(self.paper), "--output", str(output),
            ], capture_output=True, text=True)
            with self.subTest(index=index):
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
