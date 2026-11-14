---
"hephaestus": minor
---

The sandbox isolation gate now fails when a sandbox can start more than 4096 processes and threads. A sandbox without that limit can use every free process ID of its node, so that no other Pod on the node can start a process. The admin guide now names the kubelet's PID limit as a sandbox prerequisite. Under gVisor, the gate cannot see the limit from inside the sandbox and does not check it.

**Operators:** set the kubelet's `podPidsLimit` to 4096 or lower on each sandbox node before you run the gate for this release. The one-host platform already sets 4096.
