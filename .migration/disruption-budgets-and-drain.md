#### 🔴 Plan node drains before removing disruption budgets

The chart now blocks voluntary eviction of bundled PostgreSQL and NATS, the server, and a singleton worker. A node drain waits until you remove those budgets for planned maintenance.

Pause reconciliation, record the replica counts, and back up the database and broker. Cordon the node, scale workers to zero, and wait for their Pods and sandboxes to stop while the database and broker remain available. Then stop the server and receiver, remove only this release's PodDisruptionBudgets, and drain the node. After maintenance, uncordon the node and reapply the verified release with its saved values to restore the replicas and budgets. Verify them before you resume reconciliation.

Follow [Planned node maintenance](https://docs.hephaestus.build/admin/kubernetes#planned-node-maintenance) for the commands. The default worker drain is now thirty minutes, with termination grace of drain plus 120 seconds. The 16 GiB profile keeps its ten-minute drain and Recreate updates. A host shutdown still has only five minutes, so it relies on durable retry for interrupted reviews.
