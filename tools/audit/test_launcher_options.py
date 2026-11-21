#!/usr/bin/env python3
"""Exercise launch arguments at the shell/JShell boundary without computation."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]


class LauncherOptionsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="td20-launcher-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "tools").mkdir()
        for name in ("td20", "Launch.jsh", "launcher-options.sh", "local-profile.sh",
                     "run-local-checker.sh", "local-work-cleanup.py"):
            shutil.copy2(REPOSITORY / "tools" / name, self.root / "tools" / name)
        profile = self.root / "tools/local-profile.sh"
        profile.write_text(profile.read_text().replace("print(cpu, memory // (1024**3))", "print(16, 64)"))
        for module in ("checker", "emitter"):
            target = self.root / module / "target"
            (target / "classes").mkdir(parents=True)
            (target / "jshell-classpath").write_text("")
        (self.root / "certificates").mkdir()
        (self.root / "certificates/CURRENT").write_text("")
        (self.root / "emitter/target/native").mkdir()
        (self.root / "emitter/target/native/libtd20_jni.so").write_bytes(b"test library")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.record = self.root / "invocation.json"
        self.executable("jshell", """#!/usr/bin/env python3
import json, os, pathlib, sys
arguments = os.environ.get('TD20_LAUNCH_ARGS')
fields = pathlib.Path(arguments).read_text().split('\\0') if arguments else []
pathlib.Path(os.environ['TD20_TEST_INVOCATION']).write_text(json.dumps(dict(options=sys.argv[1:], fields=fields)))
print(os.environ.get('TD20_TEST_COMPLETION', 'All regenerated obligations in the complete joint-selection scope are verified.'))
""")
        self.executable("java", "#!/usr/bin/env bash\nprintf 'openjdk version \"21.0.1\"\\n' >&2\n")
        self.executable("git", "#!/usr/bin/env bash\nif [[ $1 == rev-parse ]]; then printf 'test-revision\\n'; fi\n")
        self.executable("nvidia-smi", """#!/usr/bin/env python3
import os, pathlib, sys
device = next((argument[5:] for argument in sys.argv[1:] if argument.startswith('--id=')), None)
if device is None:
    print('fixture GPU')
else:
    with pathlib.Path(os.environ['TD20_TEST_GPU_QUERIES']).open('a') as output:
        output.write(device + '\\n')
    print(os.environ['TD20_TEST_GPU_FREE'].split(',')[int(device)])
""")

    def executable(self, name, text):
        path = self.bin / name
        path.write_text(text)
        path.chmod(0o755)

    def run_launcher(self, script, arguments, environment=None):
        settings = {"PATH": str(self.bin) + ":" + os.environ["PATH"],
                    "JAVA_HOME": str(self.root),
                    "TD20_TEST_INVOCATION": str(self.record),
                    "TD20_HEAP_GIB": "1", "TD20_MIN_FREE_GIB": "0"}
        settings.update(environment or {})
        command = 'unset "${!TD20_@}"\n'
        command += "export " + " ".join(shlex.quote(key + "=" + value) for key, value in settings.items()) + "\n"
        if script in ("tools/run-local-checker.sh", "tools/run-local-emitter.sh"):
            command += "source " + shlex.quote(str(self.root / "tools/local-profile.sh")) + "\n"
            module = "checker" if script.endswith("checker.sh") else "emitter"
            command += shlex.join(["td20_local_main", module, *arguments])
        else:
            command += shlex.join([str(self.root / script), *arguments])
        return subprocess.run(["bash", "-ic", command], cwd=self.root, capture_output=True, text=True)

    def test_exec_arguments_reach_execution_vm_with_exact_values(self):
        literal = "space ' quote $(touch should-not-exist)"
        nested = shlex.join(["--phase=all", "-Dtd20.label=" + literal,
                             "-Dtd20.certificateReadWorkers=32", "-Dtd20.checkerVerboseProgress",
                             "-R-Xmx2g", "-J-Xmx256m"])
        result = self.run_launcher("tools/td20", ["verify", "-Dexec.args=" + nested],
                                   {"TD20_JSHELL_OPTIONS": "-R-Dtd20.certificateReadWorkers=2"})
        self.assertEqual(0, result.returncode, result.stderr)
        invocation = json.loads(self.record.read_text())
        self.assertIn("-R-Dtd20.label=" + literal, invocation["options"])
        self.assertLess(invocation["options"].index("-R-Dtd20.certificateReadWorkers=2"),
                        invocation["options"].index("-R-Dtd20.certificateReadWorkers=32"))
        self.assertIn("-R-Xmx2g", invocation["options"])
        self.assertIn("-J-Xmx256m", invocation["options"])
        self.assertIn("-R-Dtd20.checkerVerboseProgress=true", invocation["options"])
        self.assertEqual(["br.unb.cic.td20.CertificateChecker", "verify", "--phase=all", ""],
                         invocation["fields"])
        self.assertFalse((self.root / "should-not-exist").exists())

    def test_local_profile_records_effective_command_line_overrides(self):
        run_root = self.root / "runtime with spaces"
        database = self.root / "certificate data"
        database.mkdir()
        (database / "CURRENT").write_text("")
        nested = shlex.join(["--cpu-workers=3", "--heap-gib=1",
                             "--run-root=" + str(run_root), "--database=" + str(database),
                             "--setting=TD20_READ_WORKERS=2", "--setting=TD20_BUFFER_KEYS=8192",
                             "-Dtd20.certificateReadWorkers=7",
                             "-Dtd20.certificateEndpointFillCache=true",
                             "--jvm-option=-Dtd20.label=two words"])
        result = self.run_launcher("tools/run-local-checker.sh", ["-Dtd20.args=" + nested],
                                   {"TD20_ROOT_FILL_CACHE": "false",
                                    "TD20_ENDPOINT_FILL_CACHE": "false",
                                    "TD20_JSHELL_OPTIONS": "-R-Dtd20.certificateReadWorkers=4"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        paths = list((run_root / "logs").glob("checker-*.json"))
        self.assertEqual(1, len(paths))
        settings = json.loads(paths[0].read_text())
        self.assertEqual("7", settings["systemProperties"]["td20.certificateReadWorkers"])
        self.assertEqual("3", settings["systemProperties"]["td20.checkerWorkers"])
        self.assertEqual("8192", settings["systemProperties"]["td20.reconstructionBufferKeys"])
        self.assertEqual("true", settings["systemProperties"]["td20.certificateEndpointFillCache"])
        self.assertEqual("two words", settings["systemProperties"]["td20.label"])
        self.assertEqual(str(database), settings["database"])
        invocation = json.loads(self.record.read_text())
        for option in settings["jshellOptions"]:
            self.assertIn(option, invocation["options"])
        self.assertEqual("complete", settings["status"])
        self.assertEqual(["./tools/td20", "verify", "--phase=all"], settings["executionArguments"][:3])

    def test_script_commands_accept_properties_and_jvm_options(self):
        result = self.run_launcher("tools/td20", ["count-roots", "-Dtd20.label=script",
                                                     "--jvm-option=-Xmx2g"])
        self.assertEqual(0, result.returncode, result.stderr)
        invocation = json.loads(self.record.read_text())
        self.assertIn("-R-Dtd20.label=script", invocation["options"])
        self.assertIn("-R-Xmx2g", invocation["options"])
        self.assertEqual("tools/audit/CountErikssonRoots.jsh", invocation["options"][-1])

    def test_resource_arguments_override_inherited_jvm_values(self):
        result = self.run_launcher("tools/run-local-checker.sh",
                                   ["--heap-gib=1", "--cpu-workers=3", "--setting=TD20_READ_WORKERS=2"],
                                   {"TD20_JSHELL_OPTIONS": "-R-Xmx8g -R-Dtd20.checkerWorkers=17 -R-Dtd20.certificateReadWorkers=4"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        settings = json.loads(next((self.root / "local-runtime/logs").glob("checker-*.json")).read_text())
        self.assertEqual("3", settings["systemProperties"]["td20.checkerWorkers"])
        self.assertEqual("2", settings["systemProperties"]["td20.certificateReadWorkers"])
        heap_options = [option for option in settings["jshellOptions"] if option.startswith("-R-Xmx")]
        self.assertEqual("-R-Xmx1073741824", heap_options[-1])

    def test_explicit_heap_options_drive_validation_and_sizing(self):
        result = self.run_launcher("tools/run-local-checker.sh", ["--dry-run", "--heap-gib=1", "-R-Xmx80g"])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Heap must leave at least 8 GiB", result.stderr)
        result = self.run_launcher("tools/run-local-checker.sh",
                                   ["--dry-run", "--heap-gib=1", "-Dexec.args=--jvm-option=-Xmx1536m -R-Xms512m"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("heapGiB=1.5", result.stdout)
        self.assertIn("certificateBufferedMB=512", result.stdout)
        result = self.run_launcher("tools/run-local-checker.sh",
                                   ["--dry-run", "-R-XX:MaxHeapSize=512m"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("heapGiB=0.5", result.stdout)
        self.assertIn("-R-Xms536870912", result.stdout)

    def test_gpu_preflight_uses_explicit_devices_slots_and_budget(self):
        queries = self.root / "gpu-queries.txt"
        environment = {"TD20_TEST_GPU_QUERIES": str(queries), "TD20_TEST_GPU_FREE": "2048,2048",
                       "TD20_TEST_COMPLETION": "Requested emission scope completed: fixture"}
        nested = "-Dtd20.gpuDevices=0,1 -Dtd20.gpuSlots=1 -Dtd20.gpuBudgetMB=512"
        result = self.run_launcher("tools/run-local-emitter.sh", ["-Dexec.args=" + nested], environment)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["0", "1"], queries.read_text().splitlines())
        queries.unlink()
        self.record.unlink()
        result = self.run_launcher("tools/run-local-emitter.sh",
                                   ["-Dtd20.gpuDevices=1", "-Dtd20.gpuSlots=1", "-Dtd20.gpuBudgetMB=8192"],
                                   dict(environment, TD20_TEST_GPU_FREE="16384,8192"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Insufficient free VRAM on device 1", result.stderr)
        self.assertEqual(["1"], queries.read_text().splitlines())
        self.assertFalse(self.record.exists())

    def test_partial_scope_and_explicit_fresh_work_are_recorded(self):
        work = self.root / "selected work"
        result = self.run_launcher("tools/run-local-checker.sh",
                                   ["--phase=endpoints", "--endpoints=2-2", "--paper=/tmp/paper.tex", "--work=" + str(work)],
                                   {"TD20_TEST_COMPLETION": "Requested partial verification scope completed; this is not a complete TD(20) certificate check."})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        settings = json.loads(next((self.root / "local-runtime/logs").glob("checker-*.json")).read_text())
        self.assertEqual("partial-complete", settings["status"])
        self.assertEqual("partial", settings["completionScope"])
        self.assertEqual("endpoints", settings["phase"])
        self.assertEqual(str(work), settings["work"])
        self.assertIn("--endpoints=2-2", settings["executionArguments"])
        work.mkdir()
        result = self.run_launcher("tools/run-local-checker.sh", ["--work=" + str(work)])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Checker work must be fresh", result.stderr)

    def test_numeric_parameters_never_execute_shell_text(self):
        marker = self.root / "numeric-command-executed"
        value = "command_jvm[$(touch " + shlex.quote(str(marker)) + ")0]"
        for option in ("--cpu-workers=", "--heap-gib=", "--setting=TD20_GPU_SLOTS=",
                       "--setting=TD20_ROCKSDB_MEMORY_MB="):
            result = self.run_launcher("tools/run-local-checker.sh", ["--dry-run", option + value])
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(marker.exists())
        result = self.run_launcher("tools/run-local-checker.sh", ["--dry-run", "--cpu-workers=08", "--heap-gib=08"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("cpuWorkers=8 heapGiB=8", result.stdout)

    def test_bad_quoting_and_duplicate_properties_fail_before_start(self):
        for arguments in (["verify", '-Dexec.args="unfinished'],
                          ["verify", "-Dexec.args=--phase=all", "-Dtd20.args=--phase=all"]):
            with self.subTest(arguments=arguments):
                result = self.run_launcher("tools/td20", arguments)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.record.exists())


if __name__ == "__main__":
    unittest.main()
