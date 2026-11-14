---
title: Maintain nodes and the database
description: Drain a node for planned maintenance, and stop the server to change PostgreSQL, its configuration or its CA.
page_type: how-to
---

Use these procedures for planned work on a node or on PostgreSQL.
Each one stops the server, and starts it again after the work.
For a release change, use [Upgrade](./upgrade).

The commands use the release and namespace `hephaestus`, and the sandbox namespace `hephaestus-sandboxes` of the one-host installation.
Use your own names: `sandbox.namespace` in your values names the sandbox namespace.

## Planned node maintenance

PodDisruptionBudgets block voluntary eviction of the bundled PostgreSQL Pod and the server Pods.
With two or more servers, at most one server can be unavailable.
Each budget selects one component of one release. Sandbox Pods have no budget.

These budgets do not prevent pressure eviction or OOM, and they do not provide high availability on one node.
Deployment rollouts and direct scaling do not use the eviction API, so the budgets do not block those operations.
See Kubernetes' [disruption budget guide](https://kubernetes.io/docs/tasks/run-application/configure-pdb/).

Plan an outage before you drain the only node.
Keep PostgreSQL running until the servers have drained:

1. Suspend [pull-based reconciliation](./pull-based-deployment#operating-it) and wait for an active reconciliation to finish.
   Suspend any other automation that restores the release.
   Record the replica counts and take the [database backup](./backup-restore#back-up-the-database).
2. Stop the servers: run `helm upgrade` with the verified chart, your saved values and `server.replicas=0`.
   Wait for their Pods to disappear: `kubectl --namespace hephaestus wait --for=delete pod -l app.kubernetes.io/instance=hephaestus,app.kubernetes.io/component=server --timeout=35m`.
   Their termination grace is the configured drain plus 120 seconds.
3. Cordon the node: `kubectl cordon <node>`.
4. Remove the remaining sandbox workloads after all servers have stopped.
   A sandbox claim can outlive its server. The server's reaper does not run after shutdown.

   ```bash
   kubectl --namespace hephaestus-sandboxes delete sandboxclaims.extensions.agents.x-k8s.io --selector=hephaestus.build/sandbox-id --cascade=foreground --wait=true --timeout=5m
   kubectl --namespace hephaestus-sandboxes wait --for=delete pods --all --timeout=5m
   ```

   Check the sandbox namespace: `kubectl --namespace hephaestus-sandboxes get pods`. Expect no Pods.
   When the servers start again, their first reaper pass takes back each ready sandbox whose claim you deleted. Its review or conversation then starts in a new sandbox.
   Do not proceed while server Pods or sandbox Pods remain.
5. List the release's budgets: `kubectl --namespace hephaestus get pdb -l app.kubernetes.io/instance=hephaestus`.
   Remove only these budgets for this maintenance: `kubectl --namespace hephaestus delete pdb -l app.kubernetes.io/instance=hephaestus`.
6. Drain the node: `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data --timeout=10m`.
   The emptyDir data is disposable sandbox and cache data. Persistent volumes remain.
   If another workload's budget blocks the drain, follow its maintenance procedure.
7. Complete the host work and uncordon the node: `kubectl uncordon <node>`.
   Reapply the verified release with its saved values and replica counts, as [Upgrade](./upgrade#upgrade-a-schema-compatible-release) describes.
   This restores the budgets. Verify that the Pods and budgets are ready before you resume reconciliation.

If you set a longer drain in `server.drainTimeoutSeconds`, increase the wait timeout of step 2.
The `small` profile drains for ten minutes.
After a reboot or an interrupted drain, the next server queues the reviews that a stopped server held again.

## Database maintenance

Use this procedure before you change PostgreSQL itself, its configuration, or the CA in its TLS Secret.
The server writes to the database and receives provider deliveries. Reception stops during these phases.
GitHub redelivers failed deliveries, Slack retries them, and the routine sync repairs GitLab and Outline.
Each phase is one `helm upgrade` with the replica counts of that phase:

| Phase | `server.replicas` | Before the next phase |
| --- | --- | --- |
| 1. Stop the server | 0 | The old server Pods and their sandbox Pods are gone. |
| 2. Change the database | 0 | PostgreSQL is ready and accepts connections. |
| 3. Migrate | 0 | The migration Job of the revision completed. |
| 4. Restore | The prior count | The server is ready and receives provider deliveries. |

Phase 1 uses the verified chart and the values of the current release, with only the server count changed.
After the servers stop, remove the remaining sandbox workloads as in step 4 of [Planned node maintenance](#planned-node-maintenance) before phase 2.
Phase 2 applies the change: a new PostgreSQL configuration, or a new CA in the TLS Secret and a restart of PostgreSQL.
Phase 3 waits for the migration Job that each revision runs, while the server stays stopped.

Before phase 1, record the current `server.replicas`, and back up the database.
On a host with [pull-based reconciliation](./pull-based-deployment#settings-for-the-chart), pause the timer before phase 1 and resume it after phase 4.
A tick applies `/etc/hephaestus/chart.yaml` with its own replica counts.

A count of 0 is a desired state.
Before the next phase, check on the cluster that the Pods stopped, that the sandbox Pods are gone, and that the migration Job completed.
Set `server.replicas=0` to stop the servers. Do not use `sandbox.enabled=false`: it removes the sandbox objects and keeps the server running.

## Check that it worked

1. Run `kubectl --namespace hephaestus get pods -l app.kubernetes.io/instance=hephaestus`. Each Pod is ready.
2. Run `kubectl --namespace hephaestus get pdb -l app.kubernetes.io/instance=hephaestus`. The release's budgets exist again.
3. Sign in to Hephaestus.
