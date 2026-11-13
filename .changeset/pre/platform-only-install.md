---
"hephaestus": minor
---

The one-host installer can install the platform alone. `k3s-install.sh --platform-only` installs the pinned k3s and the platform files, and no release, for a host where pull-based deployment applies the chart. `k3s-install.sh --check-platform` changes nothing: it names each installed platform file that differs from the files beside the installer, and it stops with an error when one differs.
