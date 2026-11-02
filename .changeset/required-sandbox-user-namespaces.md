---
"hephaestus": minor
---

Sandbox workers now require user namespaces. Every sandbox Pod uses a private user mapping, including when an optional RuntimeClass is configured.

**Operators:** Before upgrading, ensure that every sandbox node supports Kubernetes user namespaces: Linux 6.3 or later and a compatible container runtime, such as containerd 2.0 or later. If you use an unqualified RuntimeClass, including gVisor, clear `worker.runtime.runtimeClassName` to use the default runtime or qualify a compatible runtime first. A worker starts no sandbox until its isolation probe confirms a private user mapping.
