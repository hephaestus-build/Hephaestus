#### 🔴 Account for the single-node worker deployment outage

The `small` profile now uses `Recreate` for the worker. The `small-16gib` profile keeps `Recreate`. Each upgrade drains and stops the old worker before starting its replacement. Reviews and conversation turns pause in that interval. Measure the outage against the 99.5% availability budget before promotion.

Both profiles admit 32 resident turns from the declared CPU budget. The conversations quota keeps its existing ten tools-Pod slots, including up to two warm Pods. A turn uses tools only on demand and waits when that quota is full. No Pod request or limit is reduced. An explicit `worker.conversationTurns` still overrides the automatic count.
