---
"hephaestus": patch
---

The one-host k3s install now keeps memory and CPU for k3s and the host, and evicts Pods before the host runs out of memory. A host of 16 GiB gives Pods about 13.9 GiB. The next run of the installer applies this and restarts k3s.
