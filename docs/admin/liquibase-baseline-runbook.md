# Prepare an older database for the current release

The [database prerequisites](./compatibility-policy.mdx#database-baseline-prerequisites) define the minimum upgrade history.
Current releases start from 1.0.0-rc.1 and do not hold the 0.x migrations.

## Before deployment

If your database is older than 1.0.0-rc.1, install that release first.
Use its signed image lock and its [versioned upgrade instructions](https://github.com/hephaestus-build/Hephaestus/blob/v1.0.0-rc.1/docs/admin/upgrade-to-1-0.mdx).
Run that release to completion before you upgrade to the target release.

Then follow the target release's [upgrade guide](./upgrade.mdx).
