---
"hephaestus": minor
---

On Kubernetes, sandboxes request the memory they use instead of the memory of files they rarely write, so one node holds many more of them. A review sandbox requests 640 MiB instead of 3 GiB, the Heph host 256 MiB instead of 2 GiB, and a turn sandbox 160 MiB instead of 1.6 GiB. On the 16 GiB profile, the sandbox quota now holds three reviews beside the Heph host and its idle turn sandboxes, where it held one, and none while a turn ran. Temporary files now sit on the node's disk, and only `/run` stays in memory. A sandbox that writes past a volume's size limit is evicted and tried again. `SANDBOX_MEMORY_BYTES` now applies to Docker only.

**Operators:** sandbox nodes must use encrypted storage, because a review's repository and temporary files are now on their disk.
