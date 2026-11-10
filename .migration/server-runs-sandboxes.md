#### 🔴 Move the worker values to the server and the sandboxes

The server now runs the sandboxes, their gateway and the model proxy. The chart has no worker Deployment, and it refuses every `worker` value.

**Affected**: every Kubernetes installation, and every installation that starts the application image outside the chart with `HEPHAESTUS_RUNTIME_WORKER_ENABLED`.

**Operators:**

1. Stop every role, as phase 1 of [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades) describes.
   The 1.0 upgrade stops every role in any case.
2. In your values, move the keys:

   | Before                                                                                                               | After                                                                             |
   | -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
   | `worker.enabled`                                                                                                     | `sandbox.enabled`                                                                 |
   | `worker.sandbox.namespace`, `worker.sandbox.reviewMemory`, `worker.sandbox.quota`, `worker.sandbox.templates`, `worker.sandbox.controller`        | `sandbox.namespace`, `sandbox.reviewMemory`, `sandbox.quota`, `sandbox.templates`, `sandbox.controller` |
   | `worker.runtime`                                                                                                     | `sandbox.runtime`                                                                 |
   | `worker.sandboxGateway.maxConnections`                                                                               | `sandbox.gateway.maxConnections`                                                  |
   | `networkPolicy.workerGatewayPorts`                                                                                   | `networkPolicy.modelGatewayPorts`                                                 |
   | `worker.drainTimeoutSeconds`, `worker.databasePool`, `worker.gitCache`, `worker.resources` | The same keys under `server`                                                      |

   Remove `worker.replicas`. `server.replicas` counts the servers.
   Add the worker's memory and its git cache to the server's, or keep the server's new defaults.
   Remove `worker.rolloutStrategy`. The server supports only `Recreate` until provider events have ordering across replicas.
   Keep a review drain window: `server.drainTimeoutSeconds` defaults to 1800, or 600 in the small profile.
   The Pod grace is the drain plus two minutes; the Helm timeout must also cover the new Pod's start.

3. Outside the chart, remove `HEPHAESTUS_RUNTIME_WORKER_ENABLED` and the `worker` Spring profile.
   Set `AGENT_ENABLED=true`, a valid `HEPHAESTUS_CONVERSATION_LOOP_SECRET` and the sandbox settings on the server instead.
   Replace `HEPHAESTUS_WORKER_DRAIN_TIMEOUT` with `HEPHAESTUS_AGENT_DRAIN_TIMEOUT`. Other `hephaestus.worker.*` settings are removed.
4. Continue with phase 2 of the procedure.
   The upgrade deletes the worker Deployment, its ServiceAccount, its Role and its network policies.
