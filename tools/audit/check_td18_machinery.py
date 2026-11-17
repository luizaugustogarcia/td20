#!/usr/bin/env python3
"""Check every shared production, test and tool file against reviewed TD18.

The reviewed patches record domain/budget/support changes, persisted format
versions, the general CPU deadline, test adaptations and generated-file rules.
The frozen specifications are never derived from the files being checked at
verification time. The commit ledger and all retired shared paths are checked
as well; no runtime algorithm, upstream test or launcher is excluded.
"""
from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path
import re
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[2]
LOCAL_BASELINE = "965b47475b483925db26ef72a0e5199d68225dda"
REFERENCE_BASELINE = "6b1585c943f4c74b12cc13fbff804be59c2b93f3"
REFERENCE_COMMIT = "22042d8d2274ce05ce1c7868cedab66937eb3ebe"
ADAPTERS = (Path(__file__).with_name("td18-domain-adapters.patch"),
            Path(__file__).with_name("td18-test-tool-adapters.patch"),
            Path(__file__).with_name("td20-execution-adapters.patch"))
AUDIT_ONLY = {
    "tools/audit/check_td18_machinery.py",
    "tools/audit/test_td18_machinery.py",
    "tools/audit/test_eriksson_root_publication.py",
    *(p.relative_to(PROJECT).as_posix() for p in ADAPTERS),
}
SHARED_ROOTS = (
    ".gitignore", "pom.xml", "emitter/pom.xml", "checker/pom.xml",
    "emitter/src/main", "checker/src/main", "emitter/src/test", "checker/src/test",
    "emitter/src/native", "tools",
)


def git(reference: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", *arguments], cwd=reference, text=True)


def machinery(path: str) -> bool:
    return (path in {"pom.xml", "emitter/pom.xml", "checker/pom.xml"}
            or "/src/main/" in path or path.startswith("emitter/src/native/"))


def shared_file(path: str) -> bool:
    return (path not in AUDIT_ONLY and (machinery(path) or "/src/test/" in path
            or path.startswith("tools/") or path == ".gitignore"))


def renamed(path: str) -> str:
    return path.replace("td18", "td20").replace("TD18", "TD20")


def normalized(text: str) -> str:
    return renamed(text).replace("TD(18)", "TD(20)")


def source_paths(repository: Path, *selection: str) -> set[str]:
    # Ignored source files still affect builds and must not bypass this audit.
    # Limit Git's scan to source roots so databases and logs are never inputs.
    return {p for p in git(repository, "ls-files", *selection, "--",
                          *SHARED_ROOTS).splitlines()
            if shared_file(p) and (repository / p).is_file()
            and "__pycache__" not in Path(p).parts and Path(p).suffix not in {".pyc", ".pyo"}}


def reviewed_commits(reference: Path, revision: str) -> list[str]:
    commits = git(reference, "rev-list", "--reverse",
                  f"{REFERENCE_BASELINE}..{revision}").splitlines()
    ledger = (PROJECT / "docs/TD18_PORT.md").read_text()
    if LOCAL_BASELINE not in ledger:
        raise ValueError("Port ledger does not identify the requested TD20 baseline")
    prefixes = re.findall(r"^\| `([0-9a-f]{7,40})` \| [^|]+ \| [^|]+ \|$",
                          ledger, re.MULTILINE)
    resolved = []
    for prefix in prefixes:
        matches = [commit for commit in commits if commit.startswith(prefix)]
        if len(matches) != 1:
            raise ValueError(f"Port ledger has an unknown or ambiguous TD18 commit: {prefix}")
        resolved.append(matches[0])
    if resolved != commits:
        raise ValueError("Port commit ledger differs: "
                         f"missing={sorted(set(commits)-set(resolved))}, "
                         f"duplicates={len(resolved)-len(set(resolved))}; "
                         "every commit must appear once in history order")
    return commits


def retired_shared_files(reference: Path, revision: str, expected: set[str]) -> list[str]:
    # Include temporary tools added and removed inside the range, not only
    # deletions visible in the final baseline-to-HEAD diff.
    deleted = git(reference, "log", "--format=", "--diff-filter=D",
                  "--name-only", "--no-renames",
                  f"{REFERENCE_BASELINE}..{revision}").splitlines()
    return sorted({renamed(path) for path in deleted if path
                   and shared_file(renamed(path)) and renamed(path) not in expected})


def check(reference: Path) -> tuple[list[str], str]:
    revision = git(reference, "rev-parse", REFERENCE_COMMIT).strip()
    if git(reference, "rev-parse", "HEAD").strip() != revision:
        raise ValueError("TD18 HEAD differs from the reviewed TD18 revision; "
                         "review new changes and update the ledger and adapters")
    if git(reference, "status", "--porcelain=v1", "--untracked-files=no").strip():
        raise ValueError("TD18 has uncommitted tracked changes outside the reviewed revision")
    if source_paths(reference, "--others"):
        raise ValueError("TD18 has uncommitted source files outside the reviewed revision")
    reviewed_commits(reference, revision)
    modes = dict(line.split(" ", 1)[::-1] for line in
                 git(reference, "ls-tree", "-r", "--format=%(objectmode) %(path)",
                     revision).splitlines())
    sources = [p for p in modes if shared_file(p)]
    expected_paths = {renamed(p) for p in sources}
    # Project-specific execution files must be specified in a frozen adapter
    # and compared exactly, including their executable mode.
    additions = {}
    for adapter in ADAPTERS:
        for path, mode in re.findall(r"^diff --git a/(\S+) b/\1\nnew file mode (100644|100755)\n",
                                     adapter.read_text(), re.MULTILINE):
            if not shared_file(path) or path in expected_paths or path in additions:
                raise ValueError(f"Invalid execution adapter addition: {path}")
            additions[path] = mode
    expected_paths.update(additions)
    survivors = [p for p in retired_shared_files(reference, revision, expected_paths)
                 if (PROJECT / p).exists()]
    if survivors:
        raise ValueError(f"Retired TD18 shared files survive in TD20: {survivors}")
    actual_paths = source_paths(PROJECT, "--cached", "--others")
    if actual_paths != expected_paths:
        raise ValueError(f"Shared file set differs: missing={sorted(expected_paths-actual_paths)}, "
                         f"extra={sorted(actual_paths-expected_paths)}")
    for source in sources:
        actual = PROJECT / renamed(source)
        if actual.is_symlink() or modes[source] not in {"100644", "100755"}:
            raise ValueError(f"Shared file must be a regular source file: {renamed(source)}")
        if bool(actual.stat().st_mode & 0o100) != (modes[source] == "100755"):
            raise ValueError(f"Shared file executable mode differs: {renamed(source)}")
    for path, mode in additions.items():
        actual = PROJECT / path
        if actual.is_symlink():
            raise ValueError(f"Shared file must be a regular source file: {path}")
        if bool(actual.stat().st_mode & 0o100) != (mode == "100755"):
            raise ValueError(f"Shared file executable mode differs: {path}")
    with tempfile.TemporaryDirectory(prefix="td20-machinery-") as directory:
        mirror = Path(directory)
        for source in sources:
            destination = mirror / renamed(source)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(normalized(git(reference, "show", f"{revision}:{source}")))
        # git apply rejects missing context and never guesses at fuzzy matches.
        for adapter in ADAPTERS:
            for flags in (("--check",), ()):
                applied = subprocess.run(["git", "apply", *flags, str(adapter)], cwd=mirror,
                                         capture_output=True, text=True)
                if applied.returncode:
                    raise ValueError(f"Reviewed adapter does not apply: {adapter.name}\n"
                                     + applied.stderr)
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
    reference = args.reference.resolve()
    try:
        checked, revision = check(reference)
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps({"format": "td20-td18-port-audit-v1",
                      "shared_machinery_matches_td18": True,
                      "td20_baseline": LOCAL_BASELINE,
                      "td18_baseline": REFERENCE_BASELINE,
                      "reference_commit": revision,
                      "upstream_commits_accounted_for": len(reviewed_commits(reference, revision)),
                      "source_files_checked": sum(machinery(p) for p in checked),
                      "test_files_checked": sum("/src/test/" in p for p in checked),
                      "tool_files_checked": sum(p.startswith("tools/") for p in checked),
                      "support_files_checked": sum(p == ".gitignore" for p in checked),
                      "shared_files_checked": len(checked),
                      "retired_shared_files_checked": len(retired_shared_files(reference, revision, set(checked))),
                      "includes_active_route": True,
                      "includes_tests_and_tools": True,
                      "adapter_specifications": [str(p.relative_to(PROJECT)) for p in ADAPTERS]}, indent=2))


if __name__ == "__main__":
    main()
