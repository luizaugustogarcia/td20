#!/usr/bin/env python3
"""Run the native totally-unoriented Burnside count on every CPU core.

The identity fixed set is partition-specific.  Each nonidentity symmetry is
classified for every partition in one native traversal, then split into
independent shards.  The final artifact is published only after all shards and
all deterministic partition keys have been validated.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
import itertools
import json
import math
import os
from pathlib import Path
import subprocess
import time

TERM_FORMAT = "td20-unoriented-burnside-term-v2"
ARTIFACT_FORMAT = "td20-unoriented-root-count-v1"
IMPLEMENTATION = "native-cpp20-burnside-v2"


def partitions(n: int, maximum: int | None = None,
               prefix: tuple[int, ...] = ()):  # deterministic, decreasing
    if n == 0:
        if sum(value % 2 == 0 for value in prefix) % 2 == 0:
            yield prefix
        return
    for value in range(min(n, maximum or n), 1, -1):
        yield from partitions(n - value, value, prefix + (value,))


def partition_key(partition: tuple[int, ...]) -> int:
    result = 0
    for value in sorted(partition):
        result = (result << 5) | value
    return result


def totient(value: int) -> int:
    result = value
    prime = 2
    remaining = value
    while prime * prime <= remaining:
        if remaining % prime == 0:
            while remaining % prime == 0:
                remaining //= prime
            result -= result // prime
        prime += 1
    if remaining > 1:
        result -= result // remaining
    return result


@dataclass(frozen=True)
class Term:
    mode: str
    shard_index: int
    shard_count: int
    partition: tuple[int, ...] | None = None
    divisor: int | None = None
    shift: int | None = None

    def command(self, binary: Path, domain: int) -> list[str]:
        result = [str(binary), f"--mode={self.mode}", f"--n={domain}",
                  f"--shard-index={self.shard_index}",
                  f"--shard-count={self.shard_count}"]
        if self.partition is not None:
            result.append("--partition=" + ",".join(map(str, self.partition)))
        if self.divisor is not None:
            result.append(f"--divisor={self.divisor}")
        if self.shift is not None:
            result.append(f"--shift={self.shift}")
        return result


def run_term(binary: Path, domain: int, term: Term
             ) -> tuple[Term, tuple[int, int] | int | dict[int, int]]:
    completed = subprocess.run(term.command(binary, domain), text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               check=True)
    if completed.stderr:
        raise ValueError(f"Native term wrote stderr for {term}: {completed.stderr}")
    try:
        record = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise ValueError(f"Malformed native output for {term}: {completed.stdout!r}") from error
    expected = {
        "format": TERM_FORMAT,
        "mode": term.mode,
        "n": domain,
        "shard_index": term.shard_index,
        "shard_count": term.shard_count,
    }
    for field, value in expected.items():
        if record.get(field) != value:
            raise ValueError(f"Incorrect {field} for {term}: {record.get(field)!r}")
    if term.divisor is not None and record.get("divisor") != term.divisor:
        raise ValueError(f"Incorrect divisor for {term}")
    if term.shift is not None and record.get("shift") != term.shift:
        raise ValueError(f"Incorrect shift for {term}")
    if term.partition is not None:
        if record.get("partition") != list(term.partition):
            raise ValueError(f"Incorrect partition for {term}")
        if term.mode == "identity":
            numerator = record.get("count_numerator")
            divisor = record.get("count_divisor")
            if type(numerator) is not int or numerator < 0 \
                    or type(divisor) is not int or divisor < 1:
                raise ValueError(f"Invalid identity quotient for {term}: "
                                 f"{numerator!r}/{divisor!r}")
            return term, (numerator, divisor)
        value = record.get("count")
        if type(value) is not int or value < 0:
            raise ValueError(f"Invalid count for {term}: {value!r}")
        return term, value
    raw = record.get("counts_by_key")
    if not isinstance(raw, dict):
        raise ValueError(f"Missing fixed-set map for {term}")
    result: dict[int, int] = {}
    for key_text, value in raw.items():
        try:
            key = int(key_text)
        except ValueError as error:
            raise ValueError(f"Invalid partition key for {term}: {key_text!r}") from error
        if key <= 0 or type(value) is not int or value <= 0 or key in result:
            raise ValueError(f"Invalid fixed-set entry for {term}: {key_text!r}={value!r}")
        result[key] = value
    return term, result


def classify_unoriented(permutation: tuple[int, ...]) -> tuple[int, ...] | None:
    unseen = set(range(len(permutation)))
    lengths: list[int] = []
    while unseen:
        first = min(unseen)
        cycle: list[int] = []
        value = first
        while value in unseen:
            unseen.remove(value)
            cycle.append(value)
            value = permutation[value]
        if value != first or len(cycle) == 1:
            return None
        support = sorted(cycle)
        previous = {support[index]: support[index - 1]
                    for index in range(len(support))}
        if any(permutation[value] != previous[value] for value in support):
            return None
        lengths.append(len(cycle))
    return tuple(sorted(lengths, reverse=True))


def is_full_cycle(permutation: tuple[int, ...]) -> bool:
    seen: set[int] = set()
    value = 0
    for _ in permutation:
        if value in seen:
            return False
        seen.add(value)
        value = permutation[value]
    return value == 0 and len(seen) == len(permutation)


def verify_small(binary: Path, maximum: int) -> None:
    if not 3 <= maximum <= 9:
        raise ValueError("The exhaustive native oracle supports --verify-small=3..9")
    started = time.monotonic()
    checks = 0
    for domain in range(3, maximum + 1):
        inventory = list(partitions(domain))
        verification_shards = min(3, domain)
        identity: Counter[tuple[int, ...]] = Counter()
        divisors = [value for value in range(1, domain) if domain % value == 0]
        rotations = {value: Counter() for value in divisors}
        reflections = {shift: Counter() for shift in range(domain)}
        realizable = 0
        for permutation in itertools.permutations(range(domain)):
            cycle_type = classify_unoriented(permutation)
            if cycle_type is None:
                continue
            target = tuple(permutation[(value + 1) % domain]
                           for value in range(domain))
            if not is_full_cycle(target):
                continue
            realizable += 1
            identity[cycle_type] += 1
            for divisor in divisors:
                if all(permutation[(value + divisor) % domain]
                       == (permutation[value] + divisor) % domain
                       for value in range(domain)):
                    rotations[divisor][cycle_type] += 1
            inverse = [0] * domain
            for value, image in enumerate(permutation):
                inverse[image] = value
            for shift in range(domain):
                if all((shift - inverse[(shift - value) % domain]) % domain
                       == permutation[value] for value in range(domain)):
                    reflections[shift][cycle_type] += 1
        for partition in inventory:
            identity_numerator = 0
            identity_divisor = None
            for shard in range(verification_shards):
                term = Term("identity", shard, verification_shards,
                            partition=partition)
                _, actual = run_term(binary, domain, term)
                if not isinstance(actual, tuple):
                    raise ValueError(
                            f"Missing identity quotient for N={domain} {partition}")
                numerator, divisor = actual
                if identity_divisor is None:
                    identity_divisor = divisor
                elif identity_divisor != divisor:
                    raise ValueError(
                            f"Inconsistent identity divisor for N={domain} {partition}")
                identity_numerator += numerator
            expected = identity[partition]
            quotient, remainder = divmod(identity_numerator, identity_divisor)
            if remainder or quotient != expected:
                raise ValueError(f"Identity mismatch N={domain} {partition}: "
                                 f"native={identity_numerator}/{identity_divisor}, "
                                 f"brute={expected}")
            checks += 1
        expected_keys = {partition_key(partition) for partition in inventory}
        for divisor in divisors:
            actual: Counter[int] = Counter()
            for shard in range(verification_shards):
                term = Term("rotation", shard, verification_shards,
                            divisor=divisor)
                _, shard_counts = run_term(binary, domain, term)
                if not isinstance(shard_counts, dict):
                    raise ValueError(f"Missing rotation map for N={domain} d={divisor}")
                actual.update(shard_counts)
            expected = {partition_key(partition): count
                        for partition, count in rotations[divisor].items() if count}
            if set(actual) - expected_keys or dict(actual) != expected:
                raise ValueError(f"Rotation mismatch N={domain} d={divisor}: "
                                 f"native={actual}, brute={expected}")
            checks += len(inventory)
        for shift in range(domain):
            actual: Counter[int] = Counter()
            for shard in range(verification_shards):
                term = Term("reflection", shard, verification_shards,
                            shift=shift)
                _, shard_counts = run_term(binary, domain, term)
                if not isinstance(shard_counts, dict):
                    raise ValueError(
                            f"Missing reflection map for N={domain} shift={shift}")
                actual.update(shard_counts)
            expected = {partition_key(partition): count
                        for partition, count in reflections[shift].items() if count}
            if set(actual) - expected_keys or dict(actual) != expected:
                raise ValueError(f"Reflection mismatch N={domain} shift={shift}: "
                                 f"native={actual}, brute={expected}")
            checks += len(inventory)
        print(json.dumps({"verified_domain": domain,
                          "permutations": math.factorial(domain),
                          "realizable_totally_unoriented": realizable,
                          "partitions": len(inventory)}), flush=True)
    print(json.dumps({"small_domain_verification": "passed",
                      "maximum_domain": maximum, "fixed_set_checks": checks,
                      "elapsed_seconds": round(time.monotonic() - started, 3)}),
          flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--n", type=int, default=21)
    available = (len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity")
                 else (os.cpu_count() or 1))
    parser.add_argument("--workers", type=int, default=available,
                        help="simultaneous native processes (default: affinity-visible CPUs)")
    parser.add_argument("--identity-shards", type=int, default=0,
                        help="maximum shards per identity term (default 0: worker count)")
    parser.add_argument("--binary", type=Path,
                        default=Path("/tmp/td20-unoriented-burnside"))
    parser.add_argument("--output", type=Path,
                        default=Path("data/joint3-reduction-audit/unoriented-native-v1.json"))
    parser.add_argument("--verify-small", type=int, default=0,
                        help="first brute-force every fixed set through N (maximum 9)")
    options = parser.parse_args()
    if not 3 <= options.n <= 21 or options.workers < 1 or options.identity_shards < 0:
        parser.error("Expected N=3..21, positive workers, and nonnegative shards")
    binary = options.binary.resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        parser.error(f"Native binary is not executable: {binary}")
    output = options.output.resolve()
    if output.exists() or output.with_name(output.name + ".tmp").exists():
        parser.error(f"Refusing to overwrite output or temporary file: {output}")
    if options.verify_small:
        verify_small(binary, options.verify_small)

    inventory = list(partitions(options.n))
    if options.n == 21 and len(inventory) != 83:
        raise ValueError("The 21-symbol inventory must contain 83 partitions")
    keys = {partition_key(partition): partition for partition in inventory}
    if len(keys) != len(inventory):
        raise ValueError("Partition-key collision")
    divisors = [value for value in range(1, options.n) if options.n % value == 0]
    shifts = [0] if options.n % 2 else [0, 1]
    maximum_identity_shards = options.identity_shards or options.workers
    identity_shards: dict[tuple[int, ...], int] = {}
    terms: list[Term] = []
    for partition in inventory:
        largest = partition[0]
        if largest > options.n // 2:
            shard_count = 1
        else:
            # Number of largest-size independent supports on an N-cycle.
            first_supports = (options.n * math.comb(options.n - largest - 1,
                                                    largest - 1) // largest)
            shard_count = min(maximum_identity_shards, first_supports)
        identity_shards[partition] = shard_count
        terms.extend(Term("identity", shard, shard_count,
                          partition=partition)
                     for shard in range(shard_count))
    for divisor in divisors:
        terms.extend(Term("rotation", shard, options.workers,
                          divisor=divisor)
                     for shard in range(options.workers))
    for shift in shifts:
        terms.extend(Term("reflection", shard, options.workers, shift=shift)
                     for shard in range(options.workers))

    identity_numerators = {partition: 0 for partition in inventory}
    identity_divisors: dict[tuple[int, ...], int] = {}
    rotations = {divisor: {key: 0 for key in keys} for divisor in divisors}
    reflections = {shift: {key: 0 for key in keys} for shift in shifts}
    started = time.monotonic()
    completed_count = 0
    progress_interval = max(1, len(terms) // 100)
    with ThreadPoolExecutor(max_workers=options.workers) as pool:
        futures = {pool.submit(run_term, binary, options.n, term): term for term in terms}
        for future in as_completed(futures):
            term, value = future.result()
            completed_count += 1
            if term.mode == "identity":
                assert term.partition is not None and isinstance(value, tuple)
                numerator, divisor = value
                previous = identity_divisors.setdefault(term.partition, divisor)
                if previous != divisor:
                    raise ValueError(f"Inconsistent identity divisor for {term.partition}")
                identity_numerators[term.partition] += numerator
            else:
                assert isinstance(value, dict)
                unexpected = set(value) - keys.keys()
                if unexpected:
                    raise ValueError(f"Unexpected partition keys for {term}: {unexpected}")
                target = rotations[term.divisor] if term.mode == "rotation"                     else reflections[term.shift]
                for key, count in value.items():
                    target[key] += count
            if completed_count % progress_interval == 0 or completed_count == len(terms):
                print(json.dumps({"completed_terms": completed_count,
                                  "required_terms": len(terms), "last_mode": term.mode,
                                  "last_partition": term.partition,
                                  "last_divisor": term.divisor, "last_shift": term.shift,
                                  "elapsed_seconds": round(time.monotonic() - started, 3)}),
                      flush=True)

    rows = []
    total = 0
    for number, partition in enumerate(inventory, 1):
        key = partition_key(partition)
        identity_divisor = identity_divisors.get(partition)
        if identity_divisor is None:
            raise ValueError(f"Missing identity shards for {partition}")
        identity_count, identity_remainder = divmod(
                identity_numerators[partition], identity_divisor)
        if identity_remainder:
            raise ValueError(f"Nonintegral identity count for {partition}: "
                             f"{identity_numerators[partition]}/{identity_divisor}")
        rotation_terms = {str(divisor): rotations[divisor][key]
                          for divisor in divisors}
        reflection_terms = {str(shift): reflections[shift][key]
                            for shift in shifts}
        weighted_rotations = sum(rotation_terms[str(divisor)]
                                 * totient(options.n // divisor)
                                 for divisor in divisors)
        if options.n % 2:
            weighted_reflections = reflection_terms["0"] * options.n
        else:
            weighted_reflections = sum(reflection_terms.values()) * (options.n // 2)
        numerator = identity_count + weighted_rotations + weighted_reflections
        denominator = 2 * options.n
        quotient, remainder = divmod(numerator, denominator)
        if remainder:
            raise ValueError(f"Nonintegral Burnside average for {partition}: "
                             f"{numerator}/{denominator}")
        total += quotient
        rows.append({"number": number, "partition": list(partition),
                     "identity_shards": identity_shards[partition],
                     "identity_fixed_numerator": identity_numerators[partition],
                     "identity_fixed_divisor": identity_divisor,
                     "identity_fixed": identity_count,
                     "rotation_fixed_by_divisor": rotation_terms,
                     "reflection_fixed_by_shift_class": reflection_terms,
                     "burnside_numerator": numerator,
                     "totally_unoriented_root_classes": quotient})

    elapsed = time.monotonic() - started
    artifact = {"format": ARTIFACT_FORMAT, "implementation": IMPLEMENTATION,
                "term_format": TERM_FORMAT,
                "identity_fixed_encoding": "sum(shard numerators) / row divisor",
                "complete": True, "domain": options.n,
                "required_partitions": len(inventory),
                "completed_partitions": len(rows),
                "worker_limit": options.workers,
                "available_cpu_cores": available,
                "maximum_identity_shards": maximum_identity_shards,
                "symmetry_shards": options.workers,
                "rotation_divisor_multiplicities":
                    {str(divisor): totient(options.n // divisor) for divisor in divisors},
                "reflection_shift_multiplicities":
                    ({"0": options.n} if options.n % 2
                     else {"0": options.n // 2, "1": options.n // 2}),
                "rows": rows,
                "totally_unoriented_root_classes": total,
                "elapsed_seconds": round(elapsed, 3)}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    with temporary.open("x") as stream:
        json.dump(artifact, stream, sort_keys=True, indent=2)
        stream.write("\n")
    os.replace(temporary, output)
    print(json.dumps({key: value for key, value in artifact.items() if key != "rows"},
                     sort_keys=True), flush=True)
    print(f"Native unoriented Burnside artifact: {output}", flush=True)


if __name__ == "__main__":
    main()
