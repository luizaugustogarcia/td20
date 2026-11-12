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

- Use the joint-2 selection from ../td18 with the TD(20) domain and budgets.
- Keep all 83 paper root partitions and the independently generated 214
  endpoint annotations consistent with the implementation.
- First test all moves creating at least two fixed points. Otherwise inspect
  every qualifying gain-one first move and every second move creating at least
  two fixed points. Gain three closes; gain two reaches an 18-symbol endpoint.
  Include supports within even-length cycles and across cycles.
- Totally unoriented roots containing only odd-length cycles have no qualifying
  first move. Test roots with even-length cycles. Stop totally unoriented
  successors because they have no second move creating at least two fixed
  points. Retain every root without a favorable (1,2) or (1,3) prefix.
- Keep the selection-independent unoriented census U separate from the new
  unoriented residual count R. Direct root obligations total R + O; reject
  pending R counts. The gain-one inverse wave admits roots of either orientation.
- Use only the paper's explicit budget-valid support-clearing patterns.
- Endpoint sorting failures are final-phase obligations; never assume a
  predicted obstruction list or count.
- Reconstruct every compatible root through two inverse waves. Restore the
  gain-two second move, then the gain-one first move. Enumerate every cyclic
  gap and support and verify actual forward gains and odd-cycle increase.
- Direct roots use budget eleven; endpoints use nine; inverse waves use
  budgets ten and eleven on twenty and twenty-one symbols respectively.
- Reject superseded route counts and checkpoints. Explicit pending paper
  rows support structural inventory checks; proof verification requires counts.

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
  logs, and certificates remain untracked.

## Engineering

- Keep CPU computation in Java and runtime native sources in CUDA. Reuse the
  existing Java Burnside counter.
- Keep generation, counting, sorting, scheduling, persistence, and checking
  machinery identical to ../td18, except for the TD(20) domain and budgets
  and the budget-valid support clearings. Use TD(18) worker detection,
  bounded queues, and prefix scheduling; do not hardcode machine-specific
  worker counts.
- JDK 21 is the baseline. Prefer immutable keys and deterministic iteration
  at persistence boundaries.
- Keep the checker independent of the emitter artifact.
- Use disk-backed deduplication for large inverse waves and bounded queues
  for asynchronous persistence.
- Run mvn -pl emitter -am test and mvn -pl checker -am test for CPU-side work.
  Run CUDA-dependent tests only when the environment is configured for them.
