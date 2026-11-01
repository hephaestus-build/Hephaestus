---
"hephaestus": minor
---

A new release script, `hephaestus-keys.sh`, creates the release Secret on any cluster. It generates every key that you do not choose, keeps the keys that exist, and prints no value. You no longer write each key by hand before the first `helm upgrade --install`. Run it once from your workstation, also when Git holds your release: the chart never generates a key. With your own PostgreSQL or NATS, `--external-database` and `--external-nats` take their login from the environment. For your own PostgreSQL, the script makes new encryption keys only with `--new-keys`, because new keys cannot read existing data. `--backups` generates the passphrase for a new backup repository. The one-host installer now uses the same script, so download `hephaestus-keys.sh` with the other release files.
