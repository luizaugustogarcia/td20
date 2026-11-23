# TD20 computational handoff

> The former `tools/` utilities have been removed. Launcher commands and links
> below are historical; use [RUNNING.md](RUNNING.md) for current direct build
> and execution instructions. Recorded configurations and evidence remain valid.

TD(20) = 11 is proved by the completed independent verification of
22 November 2026. The authoritative completed-run handoff is
[REFERENCE_SIMULATION.md](REFERENCE_SIMULATION.md), with checked completion
criteria in [RESEARCH_PLAN.md](RESEARCH_PLAN.md). Retain the official evidence
under `logs/official-verification/checker-20261122T183611Z-32945/` and the
certificate database. The transfer below records an earlier execution setup.

Instance 61821972 (host 51010, machine 153507) uses
[its checker launcher](../tools/run-61821972-checker.sh). Its configuration is
in [OPERATIONS.md](OPERATIONS.md).

The certificate transfer to root@211.21.106.84:/root/td20/certificates/
completed with 988 files totaling 89,848,327,975 bytes. Transfer records are
under local-runtime/transfers/61821972/. The local certificates/ database
is the source for that transfer.

The instance stores checker work, logs and scheduling records under
/root/td20/local-runtime/. Independent verification regenerates every
obligation and replays its witness. The completed full-scope run was executed
on EC2 as recorded in the execution record linked above.
