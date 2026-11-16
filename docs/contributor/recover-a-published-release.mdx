---
title: Recover from a bad published release
description: Contain a release incident, publish a forward fix, and verify recovery without changing published artifacts.
page_type: how-to
---

Use this procedure when a published release has a defect that requires an urgent fix.
[Release management](./release-management) owns the normal publication gates.
Operators use [Upgrade](../admin/upgrade) for changes to an installed instance.
No end-to-end rehearsal is recorded for this procedure.

:::caution[CAUTION: Untrusted release pipeline]
A valid signature does not prove that a release is safe.
If the pipeline, signer identity, or artifact authenticity is in doubt, stop normal publication.
Use the security process to establish trust and the last accepted release before continuing.
:::

## Before you start

Assign an incident owner, a release maintainer, and an operator for each affected installation.
Record the affected version, image digests, chart digest, source commit, and observed symptom in a restricted incident record.
An affected installation includes a host whose channel names the bad release, even before that host applies it.
Keep confidential data and unannounced security findings out of public release text.
For a security incident, follow [Security policy](https://github.com/hephaestus-build/Hephaestus/blob/main/SECURITY.md) before disclosure.

## Contain the incident

1. Ask operators to use the [first-response controls](../admin/contain-an-incident) for the observed symptom.
2. Hold further Production promotion approvals.
3. Ask each affected operator to pause reconciliation with [Operating it](../admin/pull-based-deployment#operating-it).
4. Pause automatic Staging promotions with **freeze** in [Release / Promote](../admin/pull-based-deployment#operating-it).
5. Record whether the release started a migration, changed stored data, or sent external feedback.
6. Preserve the failed run's diagnostics and the signed locks of the installed and previous releases.
7. Add a factual warning and recovery pointer to the affected release's public notes after the incident owner approves disclosure.
8. Notify self-hosters through the project's normal release communication.
9. If update discovery must return the last accepted release, mark that release as **Latest** in GitHub.

GitHub permits edits to [release notes and latest-release metadata](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases) after immutable publication.
This changes discovery metadata, not the signed assets or installed images.

A release publication does not deploy it.
Moving a channel does not stop a reconcile service that already runs.
Confirm that each active run has finished before an operator changes the installation.

Do not delete a published release, move its tag, replace its assets, or overwrite its image digests.
The published release is immutable.
Keep its identity available for diagnosis and recovery.

The bad version's `X.Y.Z` image tag stays fixed.
An environment channel move does not roll back the `latest` or `X.Y` image aliases.
A later eligible publication moves those aliases.
Operators use signed image digests for recovery.

## Choose the recovery path

| Observed state                                                             | Recovery path                                                                                                                                                   |
| -------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The bad release was never installed                                        | Keep the installed release. Promote only the verified correction when it is available.                                                                          |
| The new schema and stored data remain compatible with the previous release | The operator checks [schema compatibility](../admin/runtime-roles#rollouts-and-rollback) before following [Rollback](../admin/upgrade#rollback).                |
| Compatibility is uncertain, or the release required every role stopped     | Keep affected roles stopped. Use a forward fix, or restore the matching recovery set before an older release starts.                                            |
| A migration failed or remains active                                       | Use [Failed-migration recovery](../admin/upgrade#if-migration-fails) and [Recover a stopped cutover](../admin/pull-based-deployment#recover-a-stopped-cutover). |

A signed `allow-rollback` request permits only the [rollback paths that the reconciler accepts](../admin/pull-based-deployment#recover-a-stopped-cutover).
It does not reverse a migration or prove that an older image can read the database.
Do not resume a timer while its channel still names the bad release.

## Publish the correction

1. If a candidate is required, enter [prerelease mode](./release-management#release-candidates) before the fix merges.
2. Make the smallest fix on a branch from `main`.
3. Compare `main` with the bad release and review all other changes and migrations that the correction would include.
4. Add a focused regression test and an operator-voice changeset.
5. If recovery requires action, add its migration fragment as [Writing changesets](./release-management#writing-changesets) describes.
6. Review the fix and merge it through the normal pull request gates.
7. Wait for the successful `main` build and the updated Version PR.
8. Review the Version PR's version, release notes, and migration instructions.
9. Merge the Version PR through its normal gates.
10. Wait for **Release / Publish** to publish the patch or candidate after [host qualification](./release-management#supported-host-qualification).
11. For a candidate, follow [Promote the qualified candidate](./release-management#promote-the-qualified-candidate), including the [Staging and Production qualification](./release-management#release-candidates) before stable publication.
12. Use **Release / Publish**, with `promote-candidate`, only after that qualification passes.
    Skip this step for a patch that already published as stable.

**Release / Publish** creates the stable release.
**Release / Promote** changes an environment's signed channel.

Every correction comes from `main` and has a new version.
There is no separate hotfix branch, emergency signing identity, or emergency gate bypass.
The normal reviewed exception policies still apply.
An urgent fix still waits for image builds, evidence, and host qualification.
No fixed emergency-release duration is qualified.

A failed unpublished draft uses [When a release fails](./release-management#when-a-release-fails).
That procedure does not permit reuse of a published version.

## Coordinate installation of the correction

For managed environments, first qualify the correction in Staging.
Then arrange the Production recovery window with its operator and required approver.

1. Obtain operator confirmation that manual recovery, migrations, and role checks are complete, with reconciliation still paused.
   [Upgrade the host](../admin/upgrade#upgrade-the-host) owns that work before its final channel and timer step.
2. Start **Release / Promote** for the intended environment and already installed recovery release.
3. After the signed channel names that target, ask the operator to resume reconciliation.
4. Wait for the promotion's public webapp version check.

Do not wait for that check before the operator resumes the timer.
The check waits up to 15 minutes for the public webapp to report the expected version.
It does not verify other runtime roles, the database.
The operator's separate recovery checks establish their state.

The operator restores normal feedback and network access only after the recovery checks pass.
The [first-response controls](../admin/contain-an-incident#undo) own those reverse actions.

After incident acceptance, the release maintainer lets Staging follow `main` again with [Operating it](../admin/pull-based-deployment#operating-it).

Self-hosters outside the managed environments use their own channel and the [upgrade procedure](../admin/upgrade).
**Release / Promote** selects only this repository's Staging or Production environment.

## Check that it worked

1. Verify that the correction has a new immutable release identity and the required signed assets.
2. Confirm that its release notes state each operator action.
3. Obtain operator acceptance through [Upgrade the host](../admin/upgrade#upgrade-the-host) and the incident's failing scenario.
4. Record the verified release, remaining impact, recovery duration, and acceptance evidence in the incident record.

A green publication workflow proves release gates passed.
It does not prove recovery on an affected installation.
A rehearsal must record containment, publication, and operator recovery in an isolated test environment.
