# Agent Notes

## Repository priorities

- Do not use the GitHub CLI (gh); use git directly.
- Treat docs/td20-reduction-count.tex as the source of truth for the TD(20)
  proof route in this repository.
- Prioritize completeness and correctness of the Java/CUDA implementation and
  its checked computational artifacts.
- Treat TD(18) = 10 as an explicit completed premise requested for this project.
- The project objective is TD(20) = 11. Treat the reverse-permutation lower
  bound TD(20) >= 11 as established and the checked computation as the program
  for proving the matching upper bound.
- Never present TD(20) = 11 as proved until every exit criterion in
  docs/RESEARCH_PLAN.md has a checked artifact.

## Mathematical framework

- Use ../td only as the source for the shared twisted-bracelet framework and
  established mathematical conventions.
- Preserve the distinction between the shared framework, the joint-selection
  argument, and pending computational conclusions.
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
- Distinguish established premises, completed counts, experimental checks,
  estimates, and conditional proof conclusions.
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
