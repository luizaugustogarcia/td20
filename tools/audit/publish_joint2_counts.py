#!/usr/bin/env python3
"""Render a complete checked joint-2 summary into a new paper source."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import tempfile


def count(value: object, label: str) -> int:
    if type(value) is not int or value < 0:
        raise ValueError(f"Invalid {label}: {value!r}")
    return value


def replace_once(text: str, old: str, new: str, label: str) -> str:
    occurrences = text.count(old)
    if occurrences != 1:
        raise ValueError(f"Expected one {label}, found {occurrences}")
    return text.replace(old, new)


def partitions(n: int, maximum: int | None = None, prefix: tuple[int, ...] = ()):
    if n == 0:
        if sum(value % 2 == 0 for value in prefix) % 2 == 0:
            yield prefix
        return
    for value in range(min(n, maximum or n), 1, -1):
        yield from partitions(n - value, value, prefix + (value,))


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


def load_summary(path: Path) -> dict:
    result = json.loads(path.read_text())
    if result.get("format") != "td20-joint2-selection-summary-v1" \
            or result.get("complete") is not True or result.get("domain") != 21 \
            or result.get("root_budget") != 11 \
            or result.get("partitions_required") != 83 \
            or result.get("partitions_completed") != 83 \
            or result.get("sorting_certificates_checked") is not False \
            or result.get("td20_proved") is not False:
        raise ValueError("Unsupported, incomplete, or proof-mislabeled summary")
    rows = result.get("rows")
    if not isinstance(rows, list) or len(rows) != 83:
        raise ValueError("The summary must contain exactly 83 rows")
    expected = list(partitions(21))
    if len(expected) != 83:
        raise ValueError("Independent root inventory is not 83 partitions")
    selection = ["immediate gain >= 2", "(1,3)", "(1,2)"]
    if result.get("selection") != selection:
        raise ValueError("Incorrect joint-2 selection envelope")
    totals = {key: 0 for key in ("U", "J", "C", "O")}
    no_first_total = 0
    clearing_totals = {key: 0 for key in ("9", "11", "13", "17")}
    for number, (row, expected_partition) in enumerate(zip(rows, expected), 1):
        partition = row.get("partition")
        if row.get("number") != number or partition != list(expected_partition):
            raise ValueError(f"Invalid summary key at row {number}")
        values = {key: count(row.get(key), f"row {number} {key}") for key in totals}
        if values["J"] != values["C"] + values["O"]:
            raise ValueError(f"Inconsistent J=C+O at row {number}")
        if count(row.get("direct"), f"row {number} direct") \
                != values["U"] + values["O"]:
            raise ValueError(f"Inconsistent direct count at row {number}")
        no_first = count(row.get("no_qualifying_first_move"),
                         f"row {number} no qualifying first move")
        if no_first > values["J"]:
            raise ValueError(f"Invalid first-move split at row {number}")
        no_first_total += no_first
        by_length = row.get("support_clearing_by_least_length")
        if set(by_length or {}) != set(clearing_totals):
            raise ValueError(f"Invalid support-clearing inventory at row {number}")
        row_clearing = {key: count(by_length.get(key), f"row {number} support {key}")
                        for key in clearing_totals}
        if sum(row_clearing.values()) != values["C"]:
            raise ValueError(f"Inconsistent support-clearing count at row {number}")
        for key, value in row_clearing.items():
            clearing_totals[key] += value
        for key, value in values.items():
            totals[key] += value
    fields = {"U": "totally_unoriented_root_classes",
              "J": "oriented_joint2_residual_classes",
              "C": "support_clearing_classes",
              "O": "oriented_root_classes_after_support_clearing"}
    for key, field in fields.items():
        if count(result.get(field), field) != totals[key]:
            raise ValueError(f"Incorrect aggregate {field}")
    if count(result.get("direct_root_class_obligations"), "direct roots") \
            != totals["U"] + totals["O"]:
        raise ValueError("Incorrect direct-root aggregate")
    if count(result.get("oriented_roots_without_qualifying_first_move"),
             "roots without qualifying first move") != no_first_total:
        raise ValueError("Incorrect first-move aggregate")
    clearing = result.get("support_clearing_by_least_length")
    if set(clearing or {}) != {"9", "11", "13", "17"} \
            or any(count(clearing[key], f"support {key}") != clearing_totals[key]
                   for key in clearing):
        raise ValueError("Incorrect support-clearing aggregate")
    endpoints = result.get("endpoint_counts")
    if not isinstance(endpoints, dict) or set(endpoints) != {
            "all_endpoint_classes", "elementary_closure_classes",
            "classes_requiring_discharge"}:
        raise ValueError("Missing or invalid endpoint aggregate")
    all_endpoints = count(endpoints.get("all_endpoint_classes"), "all endpoints")
    elementary = count(endpoints.get("elementary_closure_classes"),
                       "elementary endpoints")
    requiring = count(endpoints.get("classes_requiring_discharge"),
                      "endpoints requiring discharge")
    if all_endpoints != elementary + requiring:
        raise ValueError("Inconsistent endpoint aggregate")
    return result


def render(summary: dict, paper: str) -> str:
    keyed = re.findall(r"^\\(?:pendingjointrow|unorientedjointrow|jointrow)\{[^\n]+$",
                       paper, flags=re.MULTILINE)
    if len(keyed) != 83:
        raise ValueError(f"Expected 83 keyed paper rows, found {len(keyed)}")
    for number, row in enumerate(summary["rows"], 1):
        partition = ",".join(map(str, row["partition"]))
        new = (rf"\jointrow{{{number}}}{{{partition}}}"
               rf"{{{row['U']}}}{{{row['J']}}}{{{row['C']}}}{{{row['O']}}}")
        candidates = [
            rf"\pendingjointrow{{{number}}}{{{partition}}}",
            rf"\unorientedjointrow{{{number}}}{{{partition}}}{{{row['U']}}}",
        ]
        existing = [candidate for candidate in candidates if candidate in paper]
        if new in paper:
            if existing:
                raise ValueError(f"Duplicate root row {number}")
        elif len(existing) == 1:
            paper = paper.replace(existing[0], new)
        else:
            raise ValueError(f"Missing or mismatched root row {number}")
    for macro in (
            r"\newcommand{\pendingjointrow}[2]{#1 & $(#2)$ & \multicolumn{4}{c}{\emph{pending exact artifacts}}\\}" + "\n",
            r"\newcommand{\unorientedjointrow}[3]{#1 & $(#2)$ & #3 & \multicolumn{3}{c}{\emph{pending joint-residual count}}\\}" + "\n"):
        paper = paper.replace(macro, "")

    totals = [summary[field] for field in
              ("totally_unoriented_root_classes", "oriented_joint2_residual_classes",
               "support_clearing_classes", "oriented_root_classes_after_support_clearing")]
    final_total = r"\multicolumn{2}{l}{Total} & " + " & ".join(map(str, totals)) + r"\\"
    total_candidates = [
        r"\multicolumn{2}{l}{Total} & \multicolumn{4}{c}{\emph{pending exact artifacts}}\\",
        (rf"\multicolumn{{2}}{{l}}{{Total}} & {totals[0]} & "
         r"\multicolumn{3}{c}{\emph{pending joint-residual count}}\\"),
    ]
    if final_total not in paper:
        existing = [candidate for candidate in total_candidates if candidate in paper]
        if len(existing) != 1:
            raise ValueError("Missing or duplicate root total")
        paper = paper.replace(existing[0], final_total)

    clearing = summary["support_clearing_by_least_length"]
    replacements = [
        (r"9  & $\beta_S^7$ & $5+\TD(11)=11$ & pending\\",
         r"9  & $\beta_S^7$ & $5+\TD(11)=11$ & " + str(clearing["9"]) + r"\\"),
        (r"11 & $\beta_S^6$ & $6+\TD(9)=11$ & pending\\",
         r"11 & $\beta_S^6$ & $6+\TD(9)=11$ & " + str(clearing["11"]) + r"\\"),
        (r"13 & $\beta_S^7,\beta_S^{10},\beta_S^{11}$ & $7+\TD(7)=11$ & pending\\",
         r"13 & $\beta_S^7,\beta_S^{10},\beta_S^{11}$ & $7+\TD(7)=11$ & "
         + str(clearing["13"]) + r"\\"),
        (r"17 & $\beta_S^9,\beta_S^{13},\beta_S^{15}$ & $9+\TD(3)=11$ & pending\\",
         r"17 & $\beta_S^9,\beta_S^{13},\beta_S^{15}$ & $9+\TD(3)=11$ & "
         + str(clearing["17"]) + r"\\"),
    ]
    for index, (old, new) in enumerate(replacements, 1):
        if new not in paper:
            paper = replace_once(paper, old, new, f"support row {index}")
    if "Counts are pending." in paper:
        paper = replace_once(paper, "Counts are pending.",
                             "Exact counts by least clearing length.", "support caption")
    paper = paper.replace("exact counts pending.", "exact class counts.")
    paper = paper.replace("completed counts are shown explicitly.", "exact class counts.")
    paper = paper.replace(
        "The route-dependent\ncounts are pending; completed totally unoriented counts are independent of\nselection.",
        "Joint residual counts have been reconciled row for row; completed totally\nunoriented counts are independent of selection.")
    paper = paper.replace(
        "The endpoint count is\ncomplete, and completed root-partition counts are reported alongside explicit\npending rows. Totally unoriented counts are independent of selection.",
        "The endpoint and root counts are complete and have been reconciled row for\nrow. Totally unoriented counts are independent of selection.")
    paper = paper.replace(
        "Final class totals\nawait completion and row-for-row reconciliation.",
        "Final class totals\nhave been reconciled row for row in the combined count artifact.")
    endpoints = summary["endpoint_counts"]
    formatted = {key: format(value, ",").replace(",", r"\,")
                 for key, value in endpoints.items()}
    endpoint_prose = (
        f"The full endpoint cover contains ${formatted['all_endpoint_classes']}$ classes.\n"
        f"Elementary closures discharge ${formatted['elementary_closure_classes']}$ classes, leaving\n"
        f"${formatted['classes_requiring_discharge']}$ classes requiring a nine-move discharge or inverse coverage.")
    number = r"\$[0-9]+(?:\\,[0-9]{3})*\$"
    paper, replaced = re.subn(
        r"The full endpoint cover contains " + number + r" classes\.\s+"
        + r"Elementary closures discharge " + number + r" classes, leaving\s+"
        + number + r" classes requiring a nine-move discharge or inverse coverage\.",
        lambda _: endpoint_prose, paper)
    if replaced != 1:
        raise ValueError("Missing or duplicate endpoint-count statement")
    if "\\pendingjointrow{" in paper or "\\unorientedjointrow{" in paper \
            or "pending exact artifacts" in paper or "pending joint-residual count" in paper:
        raise ValueError("Pending keyed count material remains")
    return paper


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--paper", required=True, type=Path)
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument("--output", type=Path)
    destination.add_argument("--in-place", action="store_true")
    options = parser.parse_args()
    original = options.paper.read_text()
    rendered = render(load_summary(options.summary), original)
    if options.in_place:
        from publish_partial_joint2_counts import update_paper
        update_paper(options.paper, original, rendered)
        output = options.paper.resolve()
    else:
        output = options.output.resolve()
        write_new_atomic(output, rendered)
    print(f"Published 83 checked rows to {output}")


if __name__ == "__main__":
    main()
