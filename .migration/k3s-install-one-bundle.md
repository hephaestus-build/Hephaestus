#### 🔴 Download the k3s bundle to install or upgrade a one-host deployment

A release no longer publishes `k3s-install.sh` and its seven sibling files as separate assets.
To install or upgrade, download `hephaestus-k3s-<version>.tar.gz` and its `.sigstore.json` signature.
Verify the bundle with `cosign verify-blob`, unpack it with `tar xzf`, and run `sh k3s-install.sh` from the unpacked directory.
[Install on one host](/admin/kubernetes#install-on-one-host-k3s) gives the commands.
A script that downloads the single `k3s-*` assets fails on a new release until you change it to download the bundle.
