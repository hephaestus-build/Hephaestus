---
"hephaestus": patch
---

On the one-host k3s platform, each gVisor sandbox now counts gVisor's own memory and CPU with its requests, so the node is not overcommitted.
