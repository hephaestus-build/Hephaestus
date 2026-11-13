---
"hephaestus": patch
---

Sandboxes run with a read-only root filesystem. A sandbox writes only to its workspace and to `/tmp`, so no tool in a sandbox can change the files of the agent image.
