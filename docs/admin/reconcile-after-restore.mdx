---
title: Reconcile provider writes after a restore
description: Find the provider deliveries and the feedback that a database restore lost, recover what you can, and record the gap.
page_type: how-to
---

A database restore to a recovery point loses what happened between that point and the restore.
The provider deliveries of that interval were in the provider event inbox, so the restored database does not have them.
Feedback that Hephaestus posted in that interval stays on the provider, but the restored database has no record of it.
Use this procedure after each restore of a live instance.

## Before you start

- You did steps 1 to 10 of [Recover the live installation](./backup-restore#recover-the-live-installation). The server runs with silent mode on.
  Keep silent mode on until the end of this procedure.
- You know the recovery point and the time of the restore.

Work that was open at the recovery point runs again.
Its feedback on the work is withheld with the silent mode reason, so it posts no second copy.

## Reconcile

1. For each workspace, list the comments, reviews and Slack messages that Hephaestus wrote between the recovery point and the restore.
   Use the provider's web interface or API, and search for the author of the Hephaestus app.
2. For each copy that must not stay, remove it, as [Remove feedback from a provider](./remove-feedback-from-a-provider) describes.
3. Redeliver the GitHub deliveries of the interval under **Advanced → Recent Deliveries** of the GitHub App.
   GitHub keeps deliveries for three days.
4. For GitLab, Slack and Outline, let the reconciliation sync recover the interval ([Which providers deliver again](./webhook-ingestion-operations#which-providers-deliver-again)).
5. In your incident record, write down each interval and each provider that you cannot recover.
6. Continue with step 12 of [Recover the live installation](./backup-restore#recover-the-live-installation).

The reconciliation sync restores the current state of the provider.
It does not restore every past event, and it does not restore a source that was deleted.

## Check that it worked

1. Open a sample of the work that changed in the interval.
   The workspace shows its current state.
2. Compare the copies that you listed in step 1 with your incident record.
   Each copy is removed or recorded as kept.
