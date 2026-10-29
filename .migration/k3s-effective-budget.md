#### 🔴 Disable host swap before the one-host upgrade

Disable swap devices and files, and remove their boot activation before running `k3s-install.sh`.
The installer rejects active swap, and the kubelet now requires `failSwapOn=true` with `NoSwap`.

The installer verifies the running kubelet policy and Node allocatable after k3s starts.
If verification fails, correct conflicting k3s flags or kubelet drop-ins before retrying.
Use `sh k3s-install.sh --verify-kubelet` with the installation kubeconfig to repeat the read-only cluster checks.

The installer accepts the Ubuntu 24.04/kernel 6.8 GA baseline.
Use `--allow-unqualified-host` only for an authorized qualification of another host family.
Ubuntu 26.04/kernel 7 needs an install, repeat-install, and pressure rehearsal with the exact release assets before target-host rollout.
The override keeps effective-policy and allocatable verification active.
Soft eviction stays disabled until stateful-service request sizing and admission protection are qualified.
