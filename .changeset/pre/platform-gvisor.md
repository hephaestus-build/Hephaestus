---
"hephaestus": minor
---

On the one-host k3s platform, the agent sandbox templates now use gVisor, which puts a kernel of its own between a sandbox's code and the host. The installer downloads the pinned gVisor release, checks it, and installs it with the platform.

**Operators:** the host must reach `storage.googleapis.com` during the installer run, and the first run with gVisor restarts k3s once.
