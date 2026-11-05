#### 🔴 Upgrade to 1.0.0: stop every writer before the backup

**Affected**: every installation on a release candidate of 1.0.0.

[Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0) gives each step and command. Do them in this order:

1. Pause the reconcile timer, if the host uses pull-based deployment.
2. Stop every role with the chart of the release that the host runs.
3. Back up PostgreSQL and JetStream, and verify the restore.
4. Migrate with the 1.0.0 chart while every role stays stopped. Do not use `--rollback-on-failure`.
5. Start every role, then promote 1.0.0 and start the timer.

With every writer stopped, the backup holds every write before the outage.
This order replaces "back up, then stop" in the 1.0.0-rc.2 entry **Stop every role before you upgrade from 1.0.0-rc.1**.
A backup from before the stop misses the writes between the backup and the stop.
