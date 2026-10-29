#### 🔴 Disable host swap before the one-host upgrade

Disable swap devices and files, and remove their boot activation before running `k3s-install.sh`.
The installer rejects active swap, and the kubelet now requires `failSwapOn=true` with `NoSwap`.

The installer verifies the running kubelet policy and Node allocatable after k3s starts.
If verification fails, correct conflicting k3s flags or kubelet drop-ins before retrying.
Use `sh k3s-install.sh --verify-kubelet` with the installation kubeconfig to repeat the read-only cluster checks.
