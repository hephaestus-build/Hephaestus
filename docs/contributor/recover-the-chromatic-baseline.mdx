---
title: Recover the Chromatic baseline
description: Restore a trusted Chromatic baseline on main after a quota limit, an errored build or a lost baseline.
page_type: how-to
---

Recover the Chromatic baseline when the visual check stops because of an account limit, or when the last `main` build is not a trusted baseline.
[Chromatic visual coverage](./ci-cd.mdx#chromatic-visual-coverage) describes the check.

## Before you start

You need an account owner for the quota and billing check, and `CHROMATIC_PROJECT_TOKEN` in your environment.

## Recover the baseline

1. With the account owner, check the project's current quota/billing and the linked build's results.
   Resolve any account limit and confirm UI tests or UI review are enabled before retrying.
2. Coordinate one full run on the current `main` commit after confirming snapshot capacity.
   From the repository root:

   ```bash
   vp run --filter webapp build-storybook
   cd webapp
   vp exec chromatic --storybook-build-dir=storybook-static --only-changed=false --force-rebuild
   ```

   Supply `CHROMATIC_PROJECT_TOKEN` through the environment, never a committed file or command
   argument. `--force-rebuild` prevents same-commit build reuse. `--only-changed=false` requests
   full snapshots instead of TurboSnap inheritance.

3. Verify in Chromatic that the build captured snapshots and completed testing. Inspect every
   difference and component/interaction error. CI never auto-accepts, including on `main`:
   manually accept only intentional, inspected differences, then rerun the check. Never accept
   a publish-only, limited, errored or untested build as the recovery baseline.
4. Reproduce component failures with the affected stories. Fix defects with story or regression
   coverage, or link a scoped defect with reproduction details. For earlier errors that no longer
   reproduce, record the verification evidence rather than assuming a successful upload fixed them.
5. Record the `main` commit, build URL, captured/inherited counts, quota check and error dispositions
   in the issue tracking the recovery. Then rerun affected PRs.

## Check that it worked

The Chromatic build for the `main` commit captured snapshots and completed testing, with no unreviewed difference and no component error.
A pull request that you rerun compares against that build.
