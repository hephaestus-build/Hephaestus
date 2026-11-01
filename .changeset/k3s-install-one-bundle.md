---
"hephaestus": minor
---

The one-host install now uses one signed download. Each release publishes `hephaestus-k3s-<version>.tar.gz` and its `.sigstore.json` signature in place of the eight `k3s-*` files. Download the two files, run one `cosign verify-blob` with the release workflow identity, then run `tar xzf hephaestus-k3s-<version>.tar.gz && sh k3s-install.sh`, as [Install on one host](https://docs.hephaestus.build/admin/kubernetes#install-on-one-host-k3s) shows. **Operators:** a script that downloads the single `k3s-*` files of a release must download the bundle instead.
