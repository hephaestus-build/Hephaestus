---
"hephaestus": minor
---

The server now records how each sandbox ended, so you can tell an out-of-memory kill, an eviction or a deleted Pod apart after the sandbox is gone. When the server deletes a sandbox, it logs `agent.sandbox.ended` with the sandbox, the last run that it served, the exit code, the Kubernetes reason and the restart count. The new metric `agent_sandbox_ended_total` counts each end by sandbox kind and reason. It replaces `sandbox_exits_total`, which an earlier release note announced but no release emitted. The record holds no output of the sandbox.

To read the Pod's status, the chart's server Role now grants `get` on Pods in the sandbox namespace, and nothing more on Pods. The person who installs the release must hold that permission too. The built-in `admin` ClusterRole, which [Run on a managed cluster](https://docs.hephaestus.build/admin/managed-cluster) asks for, already grants it, so a release installed as that guide shows needs no action.
