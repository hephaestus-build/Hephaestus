#### 🔴 Stop every role before the repository enumeration upgrade

Stop every server before upgrading, then start all servers with the new release.
The new code records repository access events in the connection revision, including events that do not change a monitor.
Every replica must record that revision before repository discovery can trust it.
An older server does not record a removal for a repository that is not monitored yet.
Mixing releases can therefore restore a revoked repository monitor from an earlier discovery answer.

This change adds no database migration.
Use the stopped upgrade procedure in [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).
After startup, confirm that GitHub discovery completes on the current connection.
A superseded discovery ends with a warning, and a restart repeats startup discovery.
