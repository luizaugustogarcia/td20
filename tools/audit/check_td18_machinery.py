#!/usr/bin/env python3
"""Check every production source against TD18 with explicit, reviewed adapters.

The adjacent patch records only domain/budget/support changes, persisted format
versions, the general CPU deadline and the corrected checker buffer reporting.
It is a checked-in specification, never generated from the files being checked.
No algorithm or route implementation is excluded from this comparison.
"""
from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[2]
REFERENCE_COMMIT = "7a729c4"
ADAPTERS = Path(__file__).with_name("td18-domain-adapters.patch")


def git(reference: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", *arguments], cwd=reference, text=True)


def machinery(path: str) -> bool:
    return (path in {"pom.xml", "emitter/pom.xml", "checker/pom.xml"}
            or "/src/main/" in path or path.startswith("emitter/src/native/"))


def renamed(path: str) -> str:
    return path.replace("td18", "td20").replace("TD18", "TD20")


def normalized(text: str) -> str:
    return renamed(text).replace("TD(18)", "TD(20)")


def check(reference: Path) -> tuple[list[str], str]:
    revision = git(reference, "rev-parse", REFERENCE_COMMIT).strip()
    sources = [p for p in git(reference, "ls-tree", "-r", "--name-only", revision).splitlines()
               if machinery(p)]
    expected_paths = {renamed(p) for p in sources}
    actual_paths = {p.relative_to(PROJECT).as_posix()
                    for module in ("emitter", "checker")
                    for p in (PROJECT / module / "src/main").rglob("*") if p.is_file()}
    actual_paths.update(p.relative_to(PROJECT).as_posix()
                        for p in (PROJECT / "emitter/src/native").rglob("*") if p.is_file())
    actual_paths.update({"pom.xml", "emitter/pom.xml", "checker/pom.xml"})
    if actual_paths != expected_paths:
        raise ValueError(f"Machinery file set differs: missing={sorted(expected_paths-actual_paths)}, "
                         f"extra={sorted(actual_paths-expected_paths)}")
    with tempfile.TemporaryDirectory(prefix="td20-machinery-") as directory:
        mirror = Path(directory)
        for source in sources:
            destination = mirror / renamed(source)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(normalized(git(reference, "show", f"{revision}:{source}")))
        # git apply rejects missing context and never guesses at fuzzy matches.
        for flags in (("--check",), ()):
            subprocess.run(["git", "apply", *flags, str(ADAPTERS)], cwd=mirror,
                           check=True, capture_output=True, text=True)
        failures = []
        for path in sorted(expected_paths):
            expected, actual = (mirror / path).read_text(), (PROJECT / path).read_text()
            if expected != actual:
                failures.extend(difflib.unified_diff(expected.splitlines(), actual.splitlines(),
                                fromfile="TD18 with explicit adapters", tofile=path))
        if failures:
            raise ValueError(chr(10).join(failures))
    return sorted(expected_paths), revision


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, default=PROJECT.parent / "td18")
    args = parser.parse_args()
    checked, revision = check(args.reference.resolve())
    print(json.dumps({"shared_machinery_matches_td18": True,
                      "reference_commit": revision,
                      "source_files_checked": len(checked),
                      "includes_active_route": True,
                      "adapter_specification": str(ADAPTERS.relative_to(PROJECT))}, indent=2))


if __name__ == "__main__":
    main()
