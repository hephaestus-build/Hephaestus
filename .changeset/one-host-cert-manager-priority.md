---
"hephaestus": patch
---

On a one-host install, cert-manager now runs at the same critical priority as Traefik. The release's roles can no longer preempt it during a restore or an upgrade, so certificate renewals keep running. The next upgrade with `k3s-install.sh` applies it.
