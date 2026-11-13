#### 🔴 Stop every role before moving conversation turns to sandboxes

Stop the old conversation loop before the database migration. Drain and stop all servers, then stop the webhook role. Remove the old sandbox claims and tools Jobs. Keep PostgreSQL and NATS available until the drain ends.

1. Scale `<release>-conversation-loop` to zero and wait for its Pods to stop. Do this before upgrading the chart, which removes that Deployment.
2. Stop the servers through their normal drain. Wait until every server Pod has stopped, then stop the webhook role.
3. In this release's dedicated sandbox namespace, foreground-delete claims with `hephaestus.build/sandbox-id` and legacy Jobs with `app.kubernetes.io/part-of=hephaestus`. Wait until all sandbox Pods are gone.
4. Rename `conversationLoop.turns` to `sandbox.conversationTurns`. Remove `conversationLoop.replicas` and `conversationLoop.resources`. Remove `conversationLoop` from every values file after the rename.
5. Remove `sandbox.runtime.runtimeClassName`, `sandbox.runtime.seccompProfile`, `sandbox.runtime.probeInterval`, and `sandbox.runtime.podOverhead`. The template runtime and overhead use `sandbox.templates.runtimeClassName` and `sandbox.templates.podOverhead`; its seccomp profile is `RuntimeDefault`.
6. Remove `HEPHAESTUS_CONVERSATION_LOOP_SECRET` from the application Secret and local environment. Remove `SANDBOX_STOP_TIMEOUT` and `SANDBOX_RECONCILIATION_INTERVAL`; SandboxClaims and the common reaper own that lifecycle. No replacement loop credential is needed. Each sandbox receives its own subject-scoped token.
7. Apply the new chart and run `node scripts/kubernetes-cluster.ts verify`, or run `scripts/sandbox-isolation.ts` against each template. Read [Sandbox prerequisites](https://docs.hephaestus.build/admin/sandboxes#sandbox-prerequisites) before enabling work.

The migration drops `conversation_tools_pod`. Context-load admission still uses `mentor_turn.tools_loads`; that live limit remains.
The server's sandbox Role now permits only `get`, `list`, `watch`, `create`, and `delete` on SandboxClaims. The controller owns Pods and their lifecycle.

The old startup isolation probe is removed. Server readiness does not qualify sandbox isolation. The platform gate must pass for each eligible sandbox node.


The stopped-major reconciliation check removes owned claims and legacy Jobs only after servers stop. A same-major upgrade still requires the explicit stop procedure above.

If `sandbox.conversationTurns` is `auto`, supply `sandbox.budget.cpu` and `sandbox.budget.memory` before upgrading. The small profile already declares its existing allocatable budget. Managed clusters have no default: the operator must enter the release's allocatable aggregate. With an explicit count, the budget is optional; when supplied, it validates steady state and the drained-upgrade peak. A count that does not fit fails chart validation. Finish reviews before the upgrade's transient peak and resume them only after migration/stanza Jobs and replacement-role surge end. Read [Sandbox capacity budget](https://docs.hephaestus.build/admin/helm-chart#sandbox-capacity-budget).
