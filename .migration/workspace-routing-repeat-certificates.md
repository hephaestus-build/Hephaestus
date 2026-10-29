#### 🔴 Select certificates on repeat one-host installations

If `site.yaml` exists, select certificates explicitly before a repeat install:

- Standard apex: `--standard-certificates`.
- Standard workspace hosts: `--workspace-subdomains --dns01-issuer NAME`.
- Custom certificates, including workspace hosts: `--certificates FILE`.

Use the correct public names and issuer. Omit `--staging` when you switch the standard certificates to the production issuer.

The installed manifest is root-only. Use `sudo` to copy it to a mode-0600 file that you own before reuse.

[Run the installer](https://docs.hephaestus.build/admin/kubernetes#2-run-the-installer) gives the copy command and choices.
The installer stops before host changes when no choice is explicit. A certificate preview does not change the host.
