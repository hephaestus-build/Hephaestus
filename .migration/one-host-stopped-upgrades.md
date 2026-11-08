#### 🔴 Use the stopped procedure for one-host release changes

The one-host installer now accepts only a first install or a reapply of the same successfully deployed release.
It refuses a different, failed, or pending release before it changes the host.

**Operators:** Follow [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades) for a release change.
Keep the current release files for the stop phase.
Verify the final restore point with all roles stopped before the target migration starts, without automatic rollback.
After a migration failure or refusal, restore that backup before you return to the old release.
