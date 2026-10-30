# Prepare an older database for the current release

The [database prerequisites](./compatibility-policy.mdx#database-baseline-prerequisites) define the minimum upgrade history.
Current releases do not replay the archived migration chain.

## Before deployment

If your database is older than the supported cut-point, first install the required intermediate release.
Use its signed image lock and its versioned upgrade instructions.
For v0.95.0, use the [v0.95.0 upgrade guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/upgrade.mdx).
That release includes the [historical baseline synchronization procedure](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/liquibase-baseline-runbook.md).
Run the intermediate release to completion before you upgrade to the target release.

Then follow the target release's [upgrade guide](./upgrade.mdx).
Do not use its synchronization command to skip an intermediate migration.
