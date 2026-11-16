---
---

Source control provisioning, repository monitoring, mirror erasure and membership sync reach workspaces only through the workspace module. No module reads another module's repositories any more, and the architecture check now refuses any such access. Behaviour is unchanged.
