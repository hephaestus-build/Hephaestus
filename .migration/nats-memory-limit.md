#### 🔴 Give the bundled NATS one memory size, in Mi or Gi

The chart now sets the bundled broker's `GOMEMLIMIT` to 80 % of `nats.resources.limits.memory`, so it reads that limit. A render stops with `nats.resources.limits.memory must end in Mi or Gi` when the limit uses another unit, such as `3G` or a number of bytes.

The chart also requires the memory request to equal the limit. The broker's heap can grow to its soft limit during a catch-up, and with a request below that, the broker is among the first Pods that the node evicts when it runs short of memory.

**Action:** if your values set `nats.resources.requests.memory` or `nats.resources.limits.memory`, set both to the same size in `Mi` or `Gi`, or remove both to take the new 1 GiB. A values file that raised only the request, for example `requests: { memory: 3Gi }`, now stops the render with `nats.resources.requests.memory (3072Mi) must equal nats.resources.limits.memory (1024Mi)`.
