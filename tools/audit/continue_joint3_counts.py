#!/usr/bin/env python3
"""Finish the CPU counting stages and publish the paper as results arrive.

The Java counters detect CPU concurrency themselves. A completed independent
native audit can be reused on resumption. No sorting or certificate emission
is invoked.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import select
import subprocess
import sys

from publish_partial_joint3_counts import load_unoriented
from summarize_joint3_counts import load_native, load_endpoints, partitions


DIRECTORY = Path(__file__).resolve().parent
PROJECT = DIRECTORY.parent.parent


def run(*arguments: str) -> None:
    print(json.dumps({"command": list(arguments)}), flush=True)
    subprocess.run(arguments, cwd=PROJECT, check=True)


def wait_for_unoriented(pid: int, output: Path) -> None:
    """Wait on the exact producer's exit event, rejecting a different job."""
    if output.exists():
        return
    try:
        descriptor = os.pidfd_open(pid)
    except ProcessLookupError:
        if output.exists():
            return
        raise ValueError("Burnside producer exited without publishing its artifact")
    try:
        process = Path(f"/proc/{pid}")
        arguments = process.joinpath("cmdline").read_bytes().decode().split("\0")
        working_directory = process.joinpath("cwd").resolve()
        outputs = [argument[9:] for argument in arguments if argument.startswith("--output=")]
        if not any(argument.endswith("run_unoriented_burnside.py") for argument in arguments) \
                or len(outputs) != 1 \
                or (working_directory / outputs[0]).resolve() != output.resolve():
            raise ValueError(f"PID {pid} is not the expected Burnside producer")
        print(json.dumps({"waiting_for_burnside_pid": pid, "output": str(output)}), flush=True)
        select.select([descriptor], [], [])
    finally:
        os.close(descriptor)
    if not output.exists():
        raise ValueError("Burnside producer exited without publishing its artifact")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wait-unoriented-pid", type=int)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--unoriented", type=Path,
                        default=Path("data/joint3-reduction-audit/unoriented-native-v1.json"))
    parser.add_argument("--joint-directory", "--native-directory", dest="native_directory", type=Path,
                        default=Path("data/joint3-reduction-audit/native-v1"))
    parser.add_argument("--endpoints", type=Path,
                        default=Path("data/joint3-reduction-audit/endpoints-v1.json"))
    parser.add_argument("--summary", type=Path,
                        default=Path("data/joint3-reduction-audit/summary-v1.json"))
    parser.add_argument("--paper", type=Path, default=Path("docs/td20-reduction-count.tex"))
    options = parser.parse_args()
    for field in ("unoriented", "native_directory", "endpoints", "summary", "paper"):
        path = getattr(options, field)
        setattr(options, field, (PROJECT / path).resolve())
    if not options.resume and options.native_directory.exists() and any(options.native_directory.iterdir()):
        parser.error(f"Refusing to mix runs in {options.native_directory}")
    if not options.resume and (options.endpoints.exists() or options.summary.exists()):
        parser.error("Refusing to overwrite an existing endpoint or summary artifact")
    if options.wait_unoriented_pid:
        wait_for_unoriented(options.wait_unoriented_pid, options.unoriented)
    if not options.unoriented.exists():
        run(str(PROJECT / "tools/td20"), "count-u", f"-Dexec.args=--output={options.unoriented}")
    load_unoriented(options.unoriented)
    run(sys.executable, "-B", str(DIRECTORY / "publish_partial_joint3_counts.py"),
        "--unoriented", str(options.unoriented),
        "--native-directory", str(options.native_directory),
        "--paper", str(options.paper), "--in-place")
    native_report = options.native_directory / "counts.json"
    if options.resume and native_report.exists():
        load_native(native_report, list(partitions(21)))
    else:
        run(sys.executable, "-B", str(DIRECTORY / "run_java_joint_counts.py"),
            "--n=21", f"--output={options.native_directory}",
            f"--unoriented={options.unoriented}", f"--paper={options.paper}",
            *(["--resume"] if options.resume else []))
    if options.resume and options.endpoints.exists():
        load_endpoints(options.endpoints)
    else:
        run(str(PROJECT / "tools/td20"), "count-e",
            f"-Dexec.args=--output={options.endpoints}")
    if not options.summary.exists():
        run(sys.executable, "-B", str(DIRECTORY / "summarize_joint3_counts.py"),
            "--native", str(native_report), "--unoriented", str(options.unoriented),
            "--endpoints", str(options.endpoints), "--output", str(options.summary))
    run(sys.executable, "-B", str(DIRECTORY / "publish_joint3_counts.py"),
        "--summary", str(options.summary), "--paper", str(options.paper), "--in-place")
    run("mvn", "-pl", "emitter", "-am", "test")
    run("mvn", "-pl", "checker", "-am", "test")
    run("latexmk", "-pdf", "-interaction=nonstopmode", "-halt-on-error",
        f"-outdir={options.paper.parent}", str(options.paper))
    print(json.dumps({"count_pipeline_complete": True, "summary": str(options.summary),
                      "sorting_certificates_checked": False, "td20_proved": False}), flush=True)


if __name__ == "__main__":
    main()
