---
"hephaestus": minor
---

On Kubernetes, sandboxes request the memory they use instead of the memory of files they rarely write, so one node holds many more of them. A review sandbox requests 640 MiB instead of 3 GiB, the Heph host 256 MiB instead of 2 GiB, and a turn sandbox 160 MiB instead of 1.6 GiB. On the 16 GiB profile, the sandbox quota now holds three reviews beside the Heph host and its idle turn sandboxes, and two while a turn runs, where it held one and none; its CPU limit of 8 sets this, so another quota holds another number. Temporary files now sit on the node's disk, and only `/run` stays in memory. Every sandbox volume on the node's disk has a size limit and every sandbox container a disk limit, so no sandbox can fill a node's disk; a review whose sandbox passes them is evicted and spends a retry. `SANDBOX_MEMORY_BYTES` now applies to Docker only, and the worker warns when it is set on Kubernetes.

**Operators:** sandbox nodes must use encrypted storage, because a review's repository and temporary files are now on their disk. If you set `SANDBOX_MEMORY_BYTES` on Kubernetes, remove it.
