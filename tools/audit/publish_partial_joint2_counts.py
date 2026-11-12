#!/usr/bin/env python3
"""Publish completed U rows and available J/C/O partition reports into TeX.

This permits a mixed table while the all-core Java count is in progress,
including completed independent native audits. It validates deterministic
keys and only replaces a row after its
atomic per-partition report is complete.  The final combined-summary publisher
still performs the strict aggregate reconciliation.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import tempfile


def partitions(n: int, maximum: int | None = None, prefix: tuple[int, ...] = ()):
    if n == 0:
        if sum(value % 2 == 0 for value in prefix) % 2 == 0:
            yield prefix
        return
    for value in range(min(n, maximum or n), 1, -1):
        yield from partitions(n - value, value, prefix + (value,))


def integer(row: dict, field: str) -> int:
    value = row.get(field)
    if type(value) is not int or value < 0:
        raise ValueError(f"Invalid {field}: {value!r}")
    return value


def validate_native_burnside(report: dict) -> None:
    """Replay the native v2 normalization and all Burnside arithmetic."""
    rotations = {"1": 12, "3": 6, "7": 2}
    reflections = {"0": 21}
    if report.get("implementation") != "native-cpp20-burnside-v2" \
            or report.get("term_format") != "td20-unoriented-burnside-term-v2" \
            or report.get("rotation_divisor_multiplicities") != rotations \
            or report.get("reflection_shift_multiplicities") != reflections:
        raise ValueError("Unsupported native Burnside implementation or symmetry inventory")
    for row in report["rows"]:
        partition = row["partition"]
        divisor = integer(row, "identity_fixed_divisor")
        identity = integer(row, "identity_fixed")
        if divisor != partition.count(partition[0]) \
                or integer(row, "identity_fixed_numerator") != divisor * identity:
            raise ValueError(f"Incorrect native identity normalization: {partition}")
        numerator = identity
        for field, weights in (("rotation_fixed_by_divisor", rotations),
                               ("reflection_fixed_by_shift_class", reflections)):
            terms = row.get(field)
            if not isinstance(terms, dict) or set(terms) != set(weights):
                raise ValueError(f"Incomplete native symmetry terms: {partition}")
            numerator += sum(integer(terms, key) * weights[key] for key in weights)
        if integer(row, "burnside_numerator") != numerator \
                or numerator != 42 * integer(row, "totally_unoriented_root_classes"):
            raise ValueError(f"Incorrect native Burnside average: {partition}")


def load_unoriented(path: Path) -> tuple[list[tuple[int, ...]], list[int], int]:
    report = json.loads(path.read_text())
    expected = list(partitions(21))
    rows = report.get("rows")
    if report.get("format") != "td20-unoriented-root-count-v1" \
            or report.get("complete") is not True or report.get("domain") != 21 \
            or report.get("required_partitions") != 83 \
            or report.get("completed_partitions") != 83 \
            or not isinstance(rows, list) or len(rows) != 83:
        raise ValueError("Unsupported or incomplete unoriented artifact")
    values = []
    for number, (partition, row) in enumerate(zip(expected, rows), 1):
        if row.get("number") != number or tuple(row.get("partition", ())) != partition:
            raise ValueError(f"Unoriented key mismatch at row {number}")
        values.append(integer(row, "totally_unoriented_root_classes"))
    total = integer(report, "totally_unoriented_root_classes")
    if total != sum(values):
        raise ValueError("Incorrect unoriented aggregate")
    implementation = report.get("implementation", "")
    if not isinstance(implementation, str):
        raise ValueError("Invalid unoriented implementation identifier")
    if implementation.startswith("native-"):
        validate_native_burnside(report)
    return expected, values, total


def native_integer(row: dict, field: str, partition: tuple[int, ...]) -> int:
    try:
        return integer(row, field)
    except ValueError as error:
        raise ValueError(f"Invalid native row {partition}: {error}") from error


def validate_native_row(row: dict, expected: set[tuple[int, ...]]) -> tuple[int, ...]:
    partition = tuple(row.get("partition", ()))
    if row.get("format") not in ("td20-joint2-residual-count-v1", "td20-joint2-residual-count-v2", "td20-joint2-residual-count-v3") \
            or partition not in expected:
        raise ValueError(f"Unsupported native row key {partition}")
    shard_index = row.get("shard_index")
    shard_count = row.get("shard_count")
    if type(shard_index) is not int or type(shard_count) is not int \
            or shard_count < 1 or not 0 <= shard_index < shard_count:
        raise ValueError(f"Invalid native shard envelope {partition}")
    depth = row.get("shard_depth", 1)
    if type(depth) is not int or not 1 <= depth < sum(partition):
        raise ValueError(f"Invalid native shard depth {partition}")
    if row["format"] == "td20-joint2-residual-count-v3":
        if shard_index != 0 or shard_count != 1 or (row.get("implementation"), row.get("scheduling")) not in {
                    ("java-joint2-td18-machinery-v1", "td18-prefix-frontier-v1"),
                    ("java-joint2-td18-machinery-v2", "td18-prefix-frontier-v1")} \
                or type(row.get("worker_limit")) is not int or row["worker_limit"] < 1 \
                or "sharding" in row or "shard_depth" in row:
            raise ValueError(f"Invalid Java count envelope {partition}")
    elif row["format"] == "td20-joint2-residual-count-v2":
        scheme = "first-position-modulo-v1" if depth == 1 else "accepted-prefix-modulo-v2"
        if row.get("sharding") != scheme or "shard_depth" not in row:
            raise ValueError(f"Invalid native v2 shard scheme {partition}")
    elif depth != 1 or "sharding" in row:
        raise ValueError(f"Invalid legacy shard scheme {partition}")
    joint = native_integer(row, "oriented_root_classes", partition)
    cleared = native_integer(row, "support_clearing_classes", partition)
    oriented = native_integer(row, "oriented_after_support_clearing", partition)
    no_first = native_integer(row, "no_qualifying_first_move", partition)
    other = native_integer(row, "qualifying_but_no_favorable_prefix", partition)
    if joint != cleared + oriented or joint != no_first + other:
        raise ValueError(f"Inconsistent native row {partition}")
    by_length = row.get("support_clearing_by_least_length")
    if set(by_length or {}) != {"9", "11", "13", "17"} or sum(
            native_integer(by_length, key, partition) for key in by_length) != cleared:
        raise ValueError(f"Invalid native support-clearing inventory {partition}")
    return partition


def native_rows(directory: Path, expected: list[tuple[int, ...]]) -> dict[tuple[int, ...], dict]:
    """Load complete per-partition results, including complete shard unions.

    Each input file was atomically published by ``run_joint_counts.py``.  A
    sharded partition remains absent until every distinct shard is present.
    This lets a paper snapshot expose only complete partition values while a
    production run is still filling the directory.
    """
    groups: dict[tuple[int, ...], list[dict]] = {}
    if not directory.is_dir():
        return {}
    expected_set = set(expected)
    for path in sorted(directory.glob("partition-*.json")):
        row = json.loads(path.read_text())
        partition = validate_native_row(row, expected_set)
        groups.setdefault(partition, []).append(row)

    result = {}
    for partition, rows in groups.items():
        envelopes = {(row["shard_count"], row["format"], row.get("shard_depth", 1),
                      row.get("sharding")) for row in rows}
        if len(envelopes) != 1:
            raise ValueError(f"Mixed native shard envelopes {partition}")
        shard_count = envelopes.pop()[0]
        by_index = {row["shard_index"]: row for row in rows}
        if len(by_index) != len(rows):
            raise ValueError(f"Duplicate native shard {partition}")
        if set(by_index) != set(range(shard_count)):
            continue
        ordered = [by_index[index] for index in range(shard_count)]
        result[partition] = {
            "oriented_root_classes": sum(
                row["oriented_root_classes"] for row in ordered),
            "support_clearing_classes": sum(
                row["support_clearing_classes"] for row in ordered),
            "oriented_after_support_clearing": sum(
                row["oriented_after_support_clearing"] for row in ordered),
        }
    return result


def write_new_atomic(output: Path, content: str) -> None:
    """Publish a complete new file without exposing a partial write."""
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def update_paper(path: Path, original: str, rendered: str) -> None:
    """Atomically publish rendered changes, rejecting concurrent source edits."""
    if original == rendered:
        return
    path = path.resolve()
    if path.read_text() != original:
        raise ValueError(f"Paper changed during publication: {path}")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(rendered)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, path.stat().st_mode)
        if path.read_text() != original:
            raise ValueError(f"Paper changed during publication: {path}")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def render(paper: str, partitions_: list[tuple[int, ...]], values: list[int],
           total: int, native: dict[tuple[int, ...], dict]) -> tuple[str, int]:
    macro = (r"\newcommand{\unorientedjointrow}[3]{#1 & $(#2)$ & #3 & "
             r"\multicolumn{3}{c}{\emph{pending joint-residual count}}\\}" + "\n")
    if len(native) != len(partitions_) and r"\newcommand{\unorientedjointrow}" not in paper:
        anchor = (r"\newcommand{\pendingjointrow}[2]{#1 & $(#2)$ & "
                  r"\multicolumn{4}{c}{\emph{pending exact artifacts}}\\}" + "\n")
        if paper.count(anchor) != 1:
            raise ValueError("Cannot insert partial-row macro")
        paper = paper.replace(anchor, anchor + macro)
    completed = 0
    for number, (partition, unoriented) in enumerate(zip(partitions_, values), 1):
        key = ",".join(map(str, partition))
        pending = rf"\pendingjointrow{{{number}}}{{{key}}}"
        partial = rf"\unorientedjointrow{{{number}}}{{{key}}}{{{unoriented}}}"
        row = native.get(partition)
        final = (rf"\jointrow{{{number}}}{{{key}}}{{{unoriented}}}"
                 rf"{{{row['oriented_root_classes']}}}{{{row['support_clearing_classes']}}}"
                 rf"{{{row['oriented_after_support_clearing']}}}") if row else partial
        occurrences = paper.count(pending) + paper.count(partial)
        if occurrences == 0 and final in paper:
            completed += row is not None
            continue
        if occurrences != 1:
            raise ValueError(f"Missing or duplicate paper row {number}")
        paper = paper.replace(pending if pending in paper else partial, final)
        completed += row is not None
    old_total = (r"\multicolumn{2}{l}{Total} & "
                 r"\multicolumn{4}{c}{\emph{pending exact artifacts}}\\")
    partial_total = (rf"\multicolumn{{2}}{{l}}{{Total}} & {total} & "
                     r"\multicolumn{3}{c}{\emph{pending joint-residual count}}\\")
    if completed == len(partitions_):
        totals = [sum(row[field] for row in native.values()) for field in
                  ("oriented_root_classes", "support_clearing_classes",
                   "oriented_after_support_clearing")]
        final_total = (rf"\multicolumn{{2}}{{l}}{{Total}} & {total} & "
                       + " & ".join(map(str, totals)) + r"\\")
        if old_total in paper:
            paper = paper.replace(old_total, final_total)
        elif partial_total in paper:
            paper = paper.replace(partial_total, final_total)
        elif final_total not in paper:
            raise ValueError("Unexpected aggregate row")
    elif old_total in paper:
        paper = paper.replace(old_total, partial_total)
    elif partial_total not in paper and completed != 83:
        raise ValueError("Unexpected aggregate row")
    paper = re.sub(r"Complete root-partition key inventory; exact (?:counts pending|class counts)\.",
                   "Complete root-partition inventory; completed counts are shown explicitly.", paper)
    return paper, completed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unoriented", required=True, type=Path)
    parser.add_argument("--native-directory", required=True, type=Path)
    parser.add_argument("--paper", required=True, type=Path)
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument("--output", type=Path)
    destination.add_argument("--in-place", action="store_true")
    options = parser.parse_args()
    expected, values, total = load_unoriented(options.unoriented)
    original = options.paper.read_text()
    rendered, completed = render(original, expected, values, total,
                                 native_rows(options.native_directory, expected))
    if options.in_place:
        update_paper(options.paper, original, rendered)
    else:
        write_new_atomic(options.output, rendered)
    print(json.dumps({"unoriented_rows": 83, "joint_rows": completed,
                      "remaining_joint_rows": 83 - completed}))


if __name__ == "__main__":
    main()
