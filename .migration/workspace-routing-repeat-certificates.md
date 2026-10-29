#### 🔴 Select certificates on repeat one-host installations

If `site.yaml` exists, repeat `--workspace-subdomains --dns01-issuer NAME` for workspace hosts.
Otherwise, pass the intended certificate manifest with `--certificates FILE`.
Use a manifest with the correct public host and issuer, such as the installed `/var/lib/rancher/k3s/server/manifests/hephaestus-certificates.yaml`.
The installer stops before host changes when neither choice is explicit.
