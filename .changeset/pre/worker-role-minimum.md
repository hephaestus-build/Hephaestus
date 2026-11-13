---
"hephaestus": patch
---

The server's Role in the sandbox namespace no longer lets it patch or delete a Pod, or read single events. A cancelled or timed-out sandbox now stops when the server deletes its Job, and Kubernetes deletes the Pod first. A compromised server can no longer change the image of a sandbox that runs.
