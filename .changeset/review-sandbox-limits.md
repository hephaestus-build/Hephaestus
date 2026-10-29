---
"hephaestus": minor
---

Docker review sandboxes now use the limits that `SANDBOX_MEMORY_BYTES`, `SANDBOX_CPUS` and `SANDBOX_PIDS_LIMIT` set. Before, every review got 4 GiB of memory, 2 CPUs and 512 processes, whatever these settings said. The new defaults are 3 GiB, 1 CPU and 256 processes. The memory limit covers the sandbox's temporary file systems plus room for its processes, measured on one review on release 0.82.0. The CPU limit is a share of CPU time, not a reserved core. Each concurrent review now needs 1 GiB less memory headroom on the host. The number of reviews that run at once does not change. The Compose file now also passes `SANDBOX_PIDS_LIMIT` to the worker.

**Operators:** with the Docker sandbox runtime, if you set `SANDBOX_MEMORY_BYTES` below 2,751,463,424 bytes (about 2.6 GiB), raise it before you upgrade. The worker now refuses to start with less.

On Kubernetes, remove `SANDBOX_MEMORY_BYTES` and use the chart's [sandbox sizes](https://docs.hephaestus.build/admin/kubernetes#sandbox-sizes).
