---
"hephaestus": patch
---

The worker's Role in the sandbox namespace no longer lets it patch or delete a Pod, or read single events. A cancelled or timed-out sandbox now stops when the worker deletes its Job, and Kubernetes deletes the Pod first. A compromised worker can no longer change the image of a sandbox that runs.
