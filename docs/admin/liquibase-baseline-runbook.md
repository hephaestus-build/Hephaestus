---
title: Prepare an older database for the current release
description: Bring a database from a release before 1.0.0-rc.1 to the baseline that the current release upgrades from.
page_type: how-to
---

Current releases start from the database of 1.0.0-rc.1 and do not hold the 0.x migrations.
Use this procedure when your database is older than 1.0.0-rc.1.
The [database prerequisites](./compatibility-policy.mdx#database-baseline-prerequisites) define the minimum upgrade history.

## Before you start

1.0 supports only the [Helm chart](./compatibility-policy#supported-deployment).
A Compose installation must first move to the chart:

1. Upgrade the Compose installation to `0.95.0`.
2. Move that version to the chart, as [Move from Compose to the Helm chart](./compose-to-kubernetes) describes.
3. Apply `0.95.1` and install its reconciler tooling.

## Reach the baseline

1. Install 1.0.0-rc.1 with its signed image lock and its [versioned upgrade instructions](https://github.com/hephaestus-build/Hephaestus/blob/v1.0.0-rc.1/docs/admin/upgrade-to-1-0.mdx).
   These instructions own the stopped cutover to the database baseline.
2. Run that release until its upgrade is complete.
3. Upgrade to the target release, as its [upgrade guide](./upgrade.mdx) describes.

## Check that it worked

1. Read the log of the migration Job of the target release.
   The migration completes.
   If the database has not reached 1.0.0-rc.1, the migration stops before it writes anything.
   Its message names the release to install.
