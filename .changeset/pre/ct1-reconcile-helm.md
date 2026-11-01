---
"hephaestus": minor
---

Release channels now apply only the verified Helm chart on k3s or Kubernetes.
The reconciler no longer applies Compose stacks or manages Docker images.

**Operators:** Move Compose installations to Helm before upgrading to 1.0.
Install the 0.95.1 reconciler before promoting 1.0.
Replace stack environment files with your chart values and cluster Secrets.
Remove `HEPHAESTUS_STACKS` and `HEPHAESTUS_MIN_FREE_GIB` from the reconcile environment.
