# Agent Notes

## Repository priorities

- Do not use the GitHub CLI (gh); use git directly.
- Treat docs/td20-reduction-count.tex as the source of truth for the TD(20)
  proof route in this repository.
- Prioritize completeness and correctness of the Java/CUDA implementation and
  its checked computational artifacts.
- Treat TD(16) = 9 and TD(18) = 10 as explicit completed premises, established
  using the same computational methodology requested for this project.
- The project establishes TD(20) = 11 by a completed computer-assisted proof.
  The reverse-permutation lower bound and the matching upper bound checked by
  full-scope run checker-20261122T183611Z-32945 establish equality.
- Preserve the checked evidence for every completion criterion in
  docs/RESEARCH_PLAN.md and the execution record in docs/REFERENCE_SIMULATION.md.
  Changes to the proof route or implementation require validation of their scope;
  the completed run certifies its recorded computation.

## Mathematical framework

- Use ../td only as the source for the shared twisted-bracelet framework and
  established mathematical conventions.
- Preserve the distinction between the shared framework, the joint-selection
  argument, completed computational conclusions, and new experimental work.
- If documentation and code differ, identify the mismatch explicitly; do not
  silently choose one.
- Explain fixed-content encoding, auxiliary standardization, the twisted
  dihedral action, orbit correspondence, counting, and direct generation when
  these objects are relevant.

## Mathematical conventions

- Use transposition for the sorting move; reserve 2-cycle for the algebraic
  object. Write odd-length cycle and even-length cycle for cycle parity.
- Introduce mathematical objects with let, set, or define, not put.
- A cyclic-target pair is (omega, beta), with target rho = omega * beta.
  Here beta is the current cycle and omega = rho * beta inverse is algebraic.
- Fixed points are first-class data. Contract them before using a
  fixed-point-free encoding or generator, and state that precondition.
- A twisted bracelet is an orbit, not a canonical word. Twisted reflection
  reverses positions and inverts oriented cyclic ranks; it is not ordinary
  word reversal.
- CyclicTargetCandidate is unchecked. Convert with asCyclicTargetPair() or
  requireCyclicTargetPair() before treating it as a valid pair.
- Use TwistedBraceletKey for candidate-orbit deduplication and
  ExtendedToricClassKey for valid-pair classes.

## Joint selection and coverage

- The active route is Eriksson reduction plus a third transposition on either
  endpoint. Keep all 83 active root partitions and 143 full endpoint
  annotations consistent with docs/td20-reduction-count.tex.
- Test immediate gains at least two on either endpoint. Otherwise enumerate
  every gain-zero/one first move and every second move producing a total of
  three fixed symbols. Seek every third move with gain at least two. Two moves
  producing four fixed symbols or three producing six close by known bounds.
- Do not impose qualifying odd-cycle restrictions on first moves. Totally
  unoriented all-odd roots still participate through gain-zero first moves.
- Active counts are retained unoriented roots, retained oriented roots, and
  support-cleared roots. Reject pending counts and verify every active inventory row.
- Use only the paper's explicit budget-valid support-clearing patterns:
  powers 9:7, 11:6, 13:7/10/11, and 17:9/13/15.
- Endpoints have 16 symbols and budget 8; direct roots have 21
  symbols and budget 11. All endpoint types are included, without an
  unjustified stricter 3-norm bound. Whole-domain diameter closures are valid.
- Reconstruct both `(1,2,2)` and `(0,3,2)` paths in three inverse waves, on both
  endpoints. Restore two, then either two/three, then one/zero fixed symbols.
  Enumerate all gaps/supports and replay actual forward gains. The after-two
  and after-one budgets are 9 and 10; final roots use 11.
- Endpoint misses are final-phase obligations. Never assume an obstruction
  list/count, omit work through missing markers, or accept incompatible checkpoints.

## Code, documentation, and proof discipline

- Keep definitions, bounds, algorithms, tables, tests, and artifacts consistent.
  Define every term and symbol, and prove or cite every nontrivial claim.
- Distinguish established premises, completed counts, checked proof conclusions,
  experimental checks, and estimates.
- Keep exhaustive generation, sorting, and verification boundaries explicit.
  A witness proves an upper bound only for its represented class.
- Every persisted set or certificate needs a versioned format, deterministic
  keys, counts, and verification that rejects missing data.
- Proof verification regenerates all obligations and replays witnesses from
  the database. Missing obstruction markers cannot omit work. Checksums and
  sidecar manifests are not required proof inputs.
- Validate small domains before large runs. Generated databases, checkpoints,
  certificates, count reports, settings and all logs remain untracked.

## Engineering

- Keep CPU computation in Java and runtime native sources in CUDA. Reuse the
  existing Java Burnside counter.
- Run emission, reconstruction and checking through the maintained local launchers.
  Use TD20 domains, 83 root partitions, 143 endpoint annotations and budgets
  8/9/10/11. Archived TD18 attempts and reconstruction checkpoints are not inputs.
- Tune each machine from its allocated CPU, RAM, GPU and VRAM and observed
  utilization. Record effective profiles locally. Multiple CPU sockets or NUMA
  nodes require the JShell remote-JVM flag -R-XX:+UseNUMA.
- Use fine-grained generation prefixes and reconstruction batches with bounded
  queues. Keep one compact progress snapshot every ten seconds; raw output
  remains in per-task/setup logs. Show unknown future totals explicitly.
- Keep generation, counting, sorting, scheduling, persistence, and checking
  machinery identical to ../td18, except for TD20 domains, budgets, the
  budget-valid support clearings and documented compatibility safeguards.
- Use automatic CPU worker detection, bounded queues and prefix scheduling;
  do not hardcode machine-specific worker counts. Maintained launchers record
  their actual configuration and use explicit resource/architecture overrides.
  Preserve the generic SM 120 build and configurable local native build.
- Always load ~/.bashrc for every shell command, locally and on servers,
  including inspection, profiling, builds and launches. Use bash -ic locally
  and for the remote command passed to SSH so the configured toolchain is
  available. Use plain ssh for server access.

- Run Java computations through JShell and use all available CPU cores.
  Keep CPU-bound runs saturated with useful work, bounded queues and enough
  independent prefix tasks. Check actual utilization and adjust task granularity
  when worker imbalance leaves cores idle.

- JDK 21 is the baseline. Prefer immutable keys and deterministic iteration
  at persistence boundaries.
- Keep the checker independent of the emitter artifact.
- Disable WAL for every RocksDB write. Flush completed persistence boundaries
  explicitly; missing results after interruption must be regenerated.
- Use disk-backed deduplication for large inverse waves and bounded queues
  for asynchronous persistence.
- Run mvn -pl emitter -am test and mvn -pl checker -am test for CPU-side work.
- Run CUDA-dependent tests only when the environment is configured for them.
- Run Java entry points through JShell; use tools/td20 for prepared classpaths.

# Java programming style

Apply these conventions throughout this repository. They are inferred from the existing Java sources, especially `Cycle`, `Configuration`, `BaseAlgorithm`, `Silvaetal`, and the proof utilities. The preference against defensive programming is an explicit user instruction. Follow the surrounding code when a convention varies between files.

## Blank lines and layout

- Use four spaces for indentation and put opening braces on the declaration or control-flow line.
- Leave one blank line after the package declaration, between import groups, and before the class declaration. Imports generally group project and third-party classes first, Java classes next, and static imports last. Both explicit and wildcard imports occur.
- Separate methods and constructors with one blank line. Most classes also have a blank line before their first member; follow the local layout.
- Use blank lines inside methods to separate logical steps: preparation, searching or iteration, applying a move, updating state, and returning the result.
- Keep closely related declarations and assignments together. Constructor field assignments and related index calculations usually form a compact group.
- A computed value and the condition or loop that immediately uses it can stay adjacent. Add a blank line when the next statement starts a new step.
- Usually leave a blank line before the final return after a substantial calculation or loop. Short forwarding methods and immediate guard returns stay compact.
- Put annotations directly above the field, method, or class they annotate. Separate individually annotated fields with a blank line; related unannotated fields can be contiguous.
- Wrap long argument lists, conditions, and stream chains onto indented continuation lines. Follow the nearby alignment; the sources do not enforce a uniform line-length limit.

The grouping in `Cycle.startingBy` is representative:

```java
public Cycle startingBy(final int symbol) {
    if (this.symbols[0] == symbol) {
        return this;
    }

    val index = indexOf(symbol);
    val symbols = new int[this.symbols.length];
    System.arraycopy(this.symbols, index, symbols, 0, symbols.length - index);
    System.arraycopy(this.symbols, 0, symbols, symbols.length - index, index);

    return Cycle.create(symbols);
}
```

## Local declarations and variable names

- Prefer Lombok's `val` for local references and values that are assigned once; replace `final var` wherever Lombok supports the declaration. Import `lombok.val`. Use `var` for reassigned state and loop counters, such as `var current`, `var pi`, and `for (var i = 0; ...)`. Explicit local types also occur when the declared type matters, including collection interfaces, arrays, and variables initialized with `null`.
- Prefer `final` on parameters that are not reassigned. Keep explicit types on fields, parameters, and return values. Enhanced loops commonly use `for (val move : moves)`.
- A `final` reference can still hold a mutable collection or array. Filling an array or adding to a list fits the existing style.
- Use lower camel case for ordinary names: `initialNumberOfEvenCycles`, `nonBadSmallComponents`, `translatedSorting`, and `numberOfTrivialCycles`.
- Preserve the mathematical vocabulary: `pi`, `sigma`, `spi`, `mu`, `gamma`, and `bigGamma`. Use `a`, `b`, `c` for symbols, `i`, `j`, `k` for indices, and `n` for a size when the formula makes their meaning clear.
- Use descriptive names around those formulas, such as `piInverse`, `cycleIndex`, `spiCycleIndex`, `piInverseIndex`, `aPos`, `openGates`, and `configSymbols`. Keep position, symbol, and index meanings distinct.
- Leading underscores commonly identify a derived or alternative state, such as `_pi`, `_spi`, `_config`, and `_bigGamma`. Existing code also uses suffixes such as `spi_`; keep the local convention.
- Encode mathematical move and sequence names directly: `_2Move`, `_2_2Seq`, `_11_8Seq`, `searchFor3_2_Seq`, and `_11_8_sortings`. Preserve the notation and existing casing when extending related code.
- Short contextual names such as `cycle`, `move`, `symbols`, `index`, `config`, `sorting`, `pair`, and `triplet` are common. Use longer names when several similar values coexist.
- Use uppercase names for ordinary constants, such as `CANONICAL_PI` and `EMPTY_LIST`; mathematical constants may retain numeric notation, such as `_11_8_SEQS`.

## Lombok

Use the Lombok dependency already declared in `pom.xml`, following these established patterns:

- Use `@Getter` on individual fields when only selected state is exposed, as in `Configuration` and `MulticyclePermutation`. Class-level `@Getter` is used for simple holders such as `Pair`.
- Use `@AllArgsConstructor` for classes whose constructor only assigns their fields, as in `SearchParams`, `SortOrExtend`, and the combination jobs.
- Keep explicit constructors when they compute derived state, call initialization helpers, or provide overloads. Examples include `Cycle` and `Configuration`.
- Use `@ToString` for generated diagnostic representations. Use `@ToString.Exclude` for cached or recursive state such as `Configuration.canonical`, and `@ToString.Include` for useful computed properties such as the norm and number of open gates.
- `@SneakyThrows` is common on file-loading, proof-generation, and experiment methods. Follow that pattern where checked exceptions are allowed to propagate; preserve explicit handling where the method performs recovery or cleanup.
- Keep custom `equals`, `hashCode`, and domain-specific `toString` implementations where their mathematical semantics matter. The code uses individual Lombok annotations rather than a general `@Data` convention.

Annotated fields are laid out like this:

```java
@Getter
private final MulticyclePermutation spi;

@Getter
private final Cycle pi;

@ToString.Exclude
private Configuration canonical;
```

## Comments and documentation

- Write short English comments close to the code they explain. Existing comments emphasize mathematical meaning, assumptions, and the reason for a step.
- Use brief Javadoc for a method or class assumption, representation, or limit. Examples include assuming `sigma = (0,1,2,...,n)`, describing a cycle index, and limiting supported oriented-cycle lengths. Full parameter and return tags are uncommon.
- Keep the paper's terminology in comments: oriented cycles, open gates, trivial cycles, 3-norm, and `(3,2)` or `(11,8)` sequences.
- Mark algorithm phases with short labels such as `// Type 1 extension`, `// Type 2 extension`, and apply/rollback comments in recursive search. Keep those labels near the corresponding block.
- Explain complexity shortcuts and bounds where they are used, for example `O(1)` because the configuration norm is bounded at that point.
- Document proof-backed guarantees and operational constraints when they explain a branch, failure, or ordering requirement. Existing code explicitly links guaranteed sequences to the article's proof.
- Preserve useful mathematical and phase comments when changing the associated code, and update them when the assumption or behavior changes.

## Try, catch, and cleanup

- Use try-with-resources for scoped readers, writers, streams, scanners, and random-access files, declaring the resource with `val` where Lombok supports it: `try (val writer = new PrintWriter(outputFile)) { ... }`. Nested resource scopes occur when a resource wraps or depends on another resource.
- Use `@SneakyThrows` on methods where checked failures simply propagate. An explicit `throws` declaration also fits existing entry points and file utilities.
- Use a catch block when it handles a concrete condition or crosses a checked-exception boundary, such as an `IOException` inside a stream callback. Existing callbacks use `Throwables.propagate(e)` to let the failure surface.
- Catch specific exceptions for a defined response. For example, case cleanup catches `IllegalStateException` to mark an invalid case file for deletion.
- Use `finally` for cleanup that must run after success, failure, or an early return, such as deleting a working-case marker or releasing a lock. Try-with-resources can sit inside that outer try/finally.
- Put `catch` and `finally` on the closing-brace line, as in `} catch (IOException e) {` and `} finally {`. Caught exceptions are commonly named `e`.
- Let unexpected failures propagate. Avoid empty catches, catch-all recovery, and returning a success-shaped default after a failure.

## No defensive programming

- Assume valid inputs and satisfied method preconditions. Write the direct algorithm.
- Do not add speculative null checks, bounds checks, argument validation, defensive copies, or fallback values. Add validation only when it is part of explicitly requested behavior.
- Keep conditions that express algorithm behavior: applicability checks, recursion base cases, search outcomes, and mathematical case distinctions.
- Preserve proof-backed invariants and let impossible states fail visibly. Do not add recovery paths for conditions the algorithm guarantees cannot occur.
