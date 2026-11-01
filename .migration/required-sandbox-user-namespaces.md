#### 🔴 Enable user namespaces on sandbox nodes

Before upgrading, ensure that every sandbox node supports [Kubernetes user namespaces](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/).
The nodes need Linux 6.3 or later and a compatible container runtime, such as containerd 2.0 or later.
If you use gVisor (`runsc`) or another RuntimeClass that is not qualified with user namespaces, clear `worker.runtime.runtimeClassName` to use the default runtime or qualify a compatible runtime before upgrading.
A RuntimeClass does not replace user namespaces.
Every sandbox and probe Pod now sets `hostUsers: false`, including when an optional RuntimeClass is configured.
The worker starts no sandbox until its isolation probe confirms a private user mapping.
Check that `sandbox_isolation_passed` and `sandbox_isolation_user_namespaces` are both `1` after the worker starts.
See [Sandbox prerequisites](https://docs.hephaestus.build/admin/kubernetes#sandbox-prerequisites).
