#!/usr/bin/env python3
"""Regression checks for complete TD18 port auditing, using isolated Git fixtures."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "td18_port_audit", Path(__file__).with_name("check_td18_machinery.py"))
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class CompletePortAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="td20-port-regression-")
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.reference, self.project = root / "td18", root / "td20"
        for repository in (self.reference, self.project):
            repository.mkdir()
            self.git(repository, "init", "-q")
            self.git(repository, "config", "user.name", "Port audit fixture")
            self.git(repository, "config", "user.email", "port-audit@example.invalid")
        self.write(self.reference, ".gitignore", "logs/\n")
        self.write(self.reference, "tools/run-4x5080.sh", "#!/usr/bin/env bash\n", 0o755)
        baseline = self.commit("baseline")
        self.main = "emitter/src/main/java/br/unb/cic/td18/Example.java"
        self.test = "emitter/src/test/java/br/unb/cic/td18/ExampleTest.java"
        self.write(self.reference, self.main,
                   "package br.unb.cic.td18;\nclass Example { static final int ROOT = 19; }\n")
        self.write(self.reference, self.test,
                   "package br.unb.cic.td18;\nclass ExampleTest {}\n")
        self.write(self.reference, "tools/td18", "#!/usr/bin/env bash\necho TD18\n", 0o755)
        self.write(self.reference, "tools/transient.sh", "#!/usr/bin/env bash\n", 0o755)
        (self.reference / "tools/run-4x5080.sh").unlink()
        first = self.commit("add maintained workflow")
        (self.reference / "tools/transient.sh").unlink()
        self.head = self.commit("remove transient deployment")
        self.commits = [first, self.head]
        self.expected = {audit.renamed(p) for p in (self.main, self.test, "tools/td18", ".gitignore")}
        for path in (self.main, self.test, "tools/td18", ".gitignore"):
            content = audit.normalized((self.reference / path).read_text())
            if path == self.main:
                content = content.replace("ROOT = 19", "ROOT = 21")
            self.write(self.project, audit.renamed(path), content,
                       0o755 if path == "tools/td18" else 0o644)
        self.ledger(self.commits)
        adapter = root / "reviewed.patch"
        adapter.write_text(
            "--- a/" + audit.renamed(self.main) + "\n+++ b/" + audit.renamed(self.main) + "\n"
            "@@ -1,2 +1,2 @@\n package br.unb.cic.td20;\n"
            "-class Example { static final int ROOT = 19; }\n"
            "+class Example { static final int ROOT = 21; }\n")
        self.adapter = adapter
        replaced = patch.multiple(audit, PROJECT=self.project, REFERENCE_BASELINE=baseline,
                                  REFERENCE_COMMIT=self.head, ADAPTERS=(adapter,))
        replaced.start()
        self.addCleanup(replaced.stop)

    @staticmethod
    def git(repository, *arguments):
        return subprocess.check_output(["git", *arguments], cwd=repository, text=True,
                                       stderr=subprocess.STDOUT).strip()

    @staticmethod
    def write(repository, path, text, mode=0o644):
        destination = repository / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
        destination.chmod(mode)
        return destination

    def commit(self, message):
        self.git(self.reference, "add", "-A")
        self.git(self.reference, "commit", "-q", "-m", message)
        return self.git(self.reference, "rev-parse", "HEAD")

    def ledger(self, commits):
        rows = "".join(f"| `{commit[:7]}` | Improvement | Reviewed adaptation |\n"
                       for commit in commits)
        self.write(self.project, "docs/TD18_PORT.md", audit.LOCAL_BASELINE + "\n\n" + rows)

    def test_complete_scope_includes_tests_tools_and_history(self):
        checked, revision = audit.check(self.reference)
        self.assertEqual(self.expected, set(checked))
        self.assertEqual(self.head, revision)
        self.assertEqual(self.commits, audit.reviewed_commits(self.reference, revision))
        self.assertEqual(["tools/run-4x5080.sh", "tools/transient.sh"],
                         audit.retired_shared_files(self.reference, revision, self.expected))

    def test_missing_test_is_rejected(self):
        (self.project / audit.renamed(self.test)).unlink()
        with self.assertRaisesRegex(ValueError, "Shared file set differs: missing=.*ExampleTest"):
            audit.check(self.reference)

    def test_execution_addition_requires_exact_content_mode_and_presence(self):
        path = "tools/run-special-emitter.sh"
        content = "#!/usr/bin/env bash\necho configured\n"
        self.adapter.write_text(self.adapter.read_text() +
            f"diff --git a/{path} b/{path}\nnew file mode 100755\n"
            f"--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,2 @@\n"
            "+#!/usr/bin/env bash\n+echo configured\n")
        launcher = self.write(self.project, path, content, 0o755)
        checked, _ = audit.check(self.reference)
        self.assertEqual(self.expected | {path}, set(checked))
        launcher.write_text(content.replace("configured", "changed"))
        with self.assertRaisesRegex(ValueError, path):
            audit.check(self.reference)
        launcher.write_text(content)
        launcher.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "executable mode differs"):
            audit.check(self.reference)
        launcher.unlink()
        with self.assertRaisesRegex(ValueError, "Shared file set differs: missing="):
            audit.check(self.reference)

    def test_extra_source_is_rejected(self):
        self.write(self.project, "checker/src/main/java/Unreviewed.java", "class Unreviewed {}\n")
        with self.assertRaisesRegex(ValueError, "extra=.*Unreviewed.java"):
            audit.check(self.reference)

    def test_ignored_extra_source_is_rejected(self):
        self.write(self.project, "checker/src/main/java/Unreviewed.java", "class Unreviewed {}\n")
        exclude = self.project / ".git/info/exclude"
        exclude.write_text(exclude.read_text() + "\nUnreviewed.java\n")
        with self.assertRaisesRegex(ValueError, "extra=.*Unreviewed.java"):
            audit.check(self.reference)

    def test_changed_test_is_rejected(self):
        self.write(self.project, audit.renamed(self.test), "class DifferentTest {}\n")
        with self.assertRaisesRegex(ValueError, "ExampleTest.java"):
            audit.check(self.reference)

    def test_lost_launcher_executable_mode_is_rejected(self):
        for mode in (0o644, 0o654):
            with self.subTest(mode=mode):
                (self.project / "tools/td20").chmod(mode)
                with self.assertRaisesRegex(ValueError, "executable mode differs: tools/td20"):
                    audit.check(self.reference)

    def test_symlink_source_is_rejected(self):
        destination = self.project / audit.renamed(self.test)
        destination.unlink()
        destination.symlink_to(self.project / audit.renamed(self.main))
        with self.assertRaisesRegex(ValueError, "regular source file"):
            audit.check(self.reference)

    def test_missing_duplicate_and_reordered_commits_are_rejected(self):
        for commits in (self.commits[1:], [self.commits[0], *self.commits], self.commits[::-1]):
            with self.subTest(commits=commits):
                self.ledger(commits)
                with self.assertRaisesRegex(ValueError, "Port commit ledger differs"):
                    audit.check(self.reference)

    def test_unknown_commit_is_rejected(self):
        self.ledger([*self.commits, "deadbeef"])
        with self.assertRaisesRegex(ValueError, "unknown or ambiguous TD18 commit"):
            audit.check(self.reference)

    def test_missing_requested_baseline_is_rejected(self):
        self.write(self.project, "docs/TD18_PORT.md", "No baseline recorded\n")
        with self.assertRaisesRegex(ValueError, "requested TD20 baseline"):
            audit.check(self.reference)

    def test_new_reference_commit_is_rejected(self):
        self.write(self.reference, "README.md", "New upstream work\n")
        self.commit("unreviewed upstream improvement")
        with self.assertRaisesRegex(ValueError, "HEAD differs from the reviewed TD18 revision"):
            audit.check(self.reference)

    def test_uncommitted_reference_change_is_rejected(self):
        self.write(self.reference, self.test, "class UncommittedTest {}\n")
        with self.assertRaisesRegex(ValueError, "uncommitted tracked changes"):
            audit.check(self.reference)

    def test_untracked_reference_source_is_rejected(self):
        self.write(self.reference, "emitter/src/test/java/NewTest.java", "class NewTest {}\n")
        with self.assertRaisesRegex(ValueError, "uncommitted source files"):
            audit.check(self.reference)

    def test_ignored_transient_retired_tool_is_rejected(self):
        self.write(self.project, "tools/transient.sh", "#!/usr/bin/env bash\n", 0o755)
        exclude = self.project / ".git/info/exclude"
        exclude.write_text(exclude.read_text() + "\ntools/transient.sh\n")
        with self.assertRaisesRegex(ValueError, "Retired TD18 shared files survive.*transient.sh"):
            audit.check(self.reference)

    def test_incompatible_adapter_is_rejected(self):
        self.adapter.write_text(self.adapter.read_text().replace("ROOT = 19", "ROOT = 17"))
        with self.assertRaisesRegex(ValueError, "Reviewed adapter does not apply"):
            audit.check(self.reference)


if __name__ == "__main__":
    unittest.main()

