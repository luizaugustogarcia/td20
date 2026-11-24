# Validation of the TD20 computation

The active structural inventory has 83 root partitions and 143 endpoint
annotations. Counts and tests validate their stated scopes; the complete proof
also requires the full independent checker run described in
[RESEARCH_PLAN.md](RESEARCH_PLAN.md). The checked full-scope execution is
recorded in [REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md).

## CPU tests and inventories

Use JDK 21 and load the configured shell environment for every command:

```bash
bash -ic 'mvn -q -pl emitter,checker -am compile dependency:build-classpath -Dmdep.outputFile=target/jshell-classpath'
bash -ic 'mvn -pl emitter -am test'
bash -ic 'mvn -pl checker -am test'
```

The suites compare the retained class sets for every domain from three through
eight symbols with independent arbitrary-support move oracles. They also cover
inverse gains zero through three on both endpoints, canonical keys, certificate
replay, serial/parallel counts, the 21-symbol reverse exception, bounded queues,
persistence failures, malformed records and interrupted handoffs.

Start each module through JShell with its prepared classpath:

```bash
bash -ic 'jshell --feedback concise --class-path "emitter/target/classes:$(cat emitter/target/jshell-classpath)"'
bash -ic 'jshell --feedback concise --class-path "checker/target/classes:$(cat checker/target/jshell-classpath)"'
```

In the checker session, validate the complete structural inventory:

```java
br.unb.cic.td20.CertificateChecker.main(new String[]{"--structural-only"});
```

In each module session, independently regenerate every root and endpoint count:

```java
br.unb.cic.td20.CountEriksson.main(new String[]{"--phase=all"});
```

The emitter and checker reports must agree for every route, domain, budget,
partition, annotation, count and elementary-closure flag. Missing, pending,
duplicate or malformed records fail validation.

## CUDA tests

With a configured CUDA toolkit and compatible device, build and test the native
implementation directly:

```bash
bash -ic 'cmake -S emitter/src/native -B emitter/target/native -DCMAKE_BUILD_TYPE=Release -DTD20_CUDA_ARCHITECTURES=89-real -DTD20_BUILD_NATIVE_TESTS=ON'
bash -ic 'cmake --build emitter/target/native --parallel "$(nproc)"'
bash -ic 'ctest --test-dir emitter/target/native --output-on-failure'
bash -ic 'mvn -pl emitter -am test -Dtd20.gpuSmoke=true'
```

Select the CUDA architecture for the executing device. A skipped device test is
not a passed test; record the architecture and executed device-test scope.

## Full independent verification

Close the emitter, use the completed certificate database read-only, and create
a fresh checker work path. Start the checker as documented in
[RUNNING.md](RUNNING.md), then execute:

```java
int status = 1;
try { br.unb.cic.td20.CertificateChecker.main(new String[]{"--phase=all", "--database=certificates", "--work=local-runtime/checker-fresh"}); status = 0; } catch (Throwable error) { error.printStackTrace(); }
/exit status
```

Require the success marker
`All regenerated obligations in the complete joint-selection scope are verified.`
and exit zero. The checker must independently regenerate all roots, endpoints and
three inverse waves, replay every available result at budgets 8, 9, 10 or 11,
and report zero unresolved final roots. A partial phase or range validates only
its requested scope.

Keep databases, scratch work, count reports, settings and logs untracked. Record
the source revision, exact scope, effective configuration, timings, report paths,
success marker and exit status for a reproduced full run.
