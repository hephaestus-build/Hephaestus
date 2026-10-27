#### 🔴 Encrypt the storage of sandbox nodes

On Kubernetes, sandboxes keep their temporary files on the node's disk instead of in memory.
A review's repository was on the node's disk already.
Encryption protects a stolen disk and a backup of it without the key.
It does not protect against a compromised running node or a privileged workload on the node.

**Operators:** before you upgrade, make sure that the disk of each node that runs sandboxes is encrypted, for example with LUKS or with the disk encryption of your cloud provider ([Kubernetes § Sandbox prerequisites](https://docs.hephaestus.build/admin/kubernetes#sandbox-prerequisites)).
