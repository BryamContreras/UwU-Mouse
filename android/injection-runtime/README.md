# Touch injection runtime

This Android library owns the isolated `InjectionWorkerMain` entry point, public
`WorkerConnection` pipe transport and exceptional dispatcher-touch reconciliation.
It depends only on `mapper-core` models and `injection-api`; it does not depend on
physical capture, native mouse code, window discovery or presentation.

`WorkerConnection` preserves the existing wire frames, process watchdogs,
generation/session checks and release behavior. Moving its class into a public
file changes module visibility, not the process protocol. The privileged
`backend-host` composes the transport with capture and window providers and owns
session authorization and recovery supervision.
