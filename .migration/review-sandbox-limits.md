#### 🔴 Keep Docker `SANDBOX_MEMORY_BYTES` at 2.6 GiB or more

Docker review sandboxes now use `SANDBOX_MEMORY_BYTES`, `SANDBOX_CPUS` and `SANDBOX_PIDS_LIMIT`.
Before this release, these settings had no effect.
The memory limit must hold the sandbox's temporary file systems, which can grow to 2.06 GiB, plus 512 MiB for its processes.
The worker refuses to start if `SANDBOX_MEMORY_BYTES` is smaller.

**Operators:** do this step only if you use the Docker sandbox runtime and set `SANDBOX_MEMORY_BYTES` yourself.

1. Set `SANDBOX_MEMORY_BYTES` to 2751463424 or more, or remove it to use the default of 3 GiB.

On Kubernetes, remove `SANDBOX_MEMORY_BYTES` and use the chart's [sandbox sizes](https://docs.hephaestus.build/admin/kubernetes#sandbox-sizes).
