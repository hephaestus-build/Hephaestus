#### 🔴 Set a PID limit for each sandbox Pod

The sandbox isolation gate now fails when a sandbox can hold more than 4096 tasks (processes and threads).
A Pod without a PID limit can use every free process ID of its node.

The one-host platform sets 4096 in `k3s-kubelet.yaml`, so a one-host install needs no action.

On another cluster, before you run the gate for this release:

1. Set the kubelet's `podPidsLimit` (or the flag `--pod-max-pids`) to 4096 or lower on each sandbox node.
   On a managed cluster, set it in the node pool's kubelet configuration.
2. Restart the kubelet of each node, or replace the nodes, so that the new limit applies.
3. Run the [sandbox isolation gate](https://docs.hephaestus.build/admin/sandboxes#sandbox-isolation-gate) for each template.

The gate checks the limit only for templates whose runtime is not gVisor.
For a gVisor template, read `podPidsLimit` in the kubelet configuration of each sandbox node.
