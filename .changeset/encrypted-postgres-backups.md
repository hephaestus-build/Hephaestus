---
"hephaestus": minor
---

Operators can set up encrypted off-host PostgreSQL backups with WAL archiving. The self-host stack now includes pgBackRest backup and restore overlays and example systemd timers. Configure a dedicated S3 bucket, keep an off-host copy of the encryption passphrase, use a separate read-only key for restores, and test backup failure alerts before unattended delivery.
