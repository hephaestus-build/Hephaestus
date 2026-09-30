#### 🔴 Database baseline upgrade path

Before starting v1.0.0, follow the [upgrade-path table](https://docs.hephaestus.build/admin/compatibility-policy#upgrade-paths-to-10).
It names the required intermediate release and when
[baseline synchronization](https://docs.hephaestus.build/admin/liquibase-baseline-runbook) is needed.
Use the signed v1.0.0 target image for synchronization.

Startup and baseline synchronization refuse migration history that has not reached the v0.77.4
cut-point. Do not mark missing migrations as applied. Stop all writers and test a full backup
restoration before synchronization. Fresh databases initialize automatically.
