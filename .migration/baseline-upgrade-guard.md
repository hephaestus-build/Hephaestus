#### 🔴 Database baseline upgrade path

Before upgrading to v1.0.0, follow the [upgrade-path table](https://docs.hephaestus.build/admin/compatibility-policy#upgrade-paths-to-10).
Installations older than v0.77.4 must install v0.77.4 and start it once, then complete the
[baseline synchronization runbook](https://docs.hephaestus.build/admin/liquibase-baseline-runbook)
with the signed v1.0.0 target image. Installations on v0.77.4 must complete the runbook.
Installations on v0.78.0 or later can upgrade directly. Fresh databases initialize automatically.

Startup and baseline synchronization now refuse migration history that has not reached v0.77.4.
Do not mark missing migrations as applied. Stop all writers and test a full backup restoration
before synchronization.
