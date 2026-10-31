#### 🔴 Complete the 0.95.0 database upgrade first

If your database is older than 0.95.0, upgrade it to 0.95.0 with that release's signed image lock and [versioned upgrade guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/upgrade.mdx). Run that release to completion before you start the target release.

The target release checks the final 0.95.0 migration identity before update or synchronization. It refuses an older history. Do not synchronize to bypass this check. Fresh databases need no intermediate release.
