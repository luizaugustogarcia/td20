# Building and running directly

The former tools/ launchers have been removed. References to them in older
operational and experimental documentation describe historical workflows.
Use JDK 21 and run the following commands from the repository root.

Build Java and prepare independent module classpaths:

```bash
bash -ic 'mvn -q -pl emitter,checker -am compile dependency:build-classpath -Dmdep.outputFile=target/jshell-classpath'
bash -ic 'mvn -pl emitter -am test'
bash -ic 'mvn -pl checker -am test'
```

The checker can be built independently with `-pl checker -am`. For CUDA emission,
with a configured toolkit and compatible device, also run:

```bash
bash -ic 'mvn -Pgpu -pl emitter -am package'
```

The native build includes SM 89 and SM 120. A custom native build can use:

```bash
bash -ic 'cmake -S emitter/src/native -B emitter/target/native -DCMAKE_BUILD_TYPE=Release -DTD20_CUDA_ARCHITECTURES=89-real'
bash -ic 'cmake --build emitter/target/native --parallel "$(nproc)"'
```

For native tests, add `-DTD20_BUILD_NATIVE_TESTS=ON` to CMake configuration and
run `bash -ic 'ctest --test-dir emitter/target/native --output-on-failure'`.

Start the checker through JShell:

```bash
bash -ic 'jshell --feedback concise --class-path "checker/target/classes:$(cat checker/target/jshell-classpath)"'
```

At the JShell prompt, validate the inventory:

```java
br.unb.cic.td20.CertificateChecker.main(new String[]{"--structural-only"});
```

For full verification, close the emitter and use a new scratch path. Run:

```java
int status = 1;
try { br.unb.cic.td20.CertificateChecker.main(new String[]{"--phase=all", "--database=certificates", "--work=local-runtime/checker-fresh"}); status = 0; } catch (Throwable error) { error.printStackTrace(); }
/exit status
```

Require the application success marker
`All regenerated obligations in the complete joint-selection scope are verified.`
and exit zero. JShell exit alone does not establish successful verification.
The certificate database is generated and untracked; cloning does not supply it.

Start the emitter with its own classpath and native library path:

```bash
bash -ic 'jshell --feedback concise --class-path "emitter/target/classes:$(cat emitter/target/jshell-classpath)" -R-Djava.library.path="$PWD/emitter/target/native"'
```

Then run:

```java
int status = 1;
try { br.unb.cic.td20.WitnessEmitter.main(new String[]{"--phase=all", "--database=certificates", "--work=local-runtime/emitter-fresh"}); status = 0; } catch (Throwable error) { error.printStackTrace(); }
/exit status
```

Both applications accept `--help`. To regenerate counts, invoke
`br.unb.cic.td20.CountEriksson.main(new String[]{"--phase=all"})` in either
module session. Before checker counts, set
`System.setProperty("td20.countModule", "checker")`.

CPU workers default to automatic detection. Tune heap and CPU/GPU memory
settings to the allocation with remote-VM options such as `-R-Xmx32g` and
`-R-Dtd20.gpuSlots=8`. Add `-R-XX:+UseNUMA` on multiple sockets or NUMA nodes.
Maven execution-profile properties are not automatically applied to JShell.
Record logs and effective settings explicitly; automatic launcher recording
has been removed. Preserve all generated evidence untracked and consult
[the execution record](REFERENCE_SIMULATION.md) for the successful run settings.

