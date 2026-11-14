---
title: Prove silence, then turn off silent mode
description: After an install, upgrade or restore with silent mode on, prove that the instance sends nothing, then turn silent mode off.
page_type: how-to
---

Prove silence when a new instance, a release or a restored database must show that it works before it writes to people.
Silent mode stops new writes to GitHub, GitLab and Slack, and all email ([Contain an incident](./contain-an-incident) lists what it does not stop).
Reviews still run, so a test review shows the feedback that the instance would send.

## Before you start

- You are an instance admin.
- Silent mode is on, and you read it back after the deployment:
  - A new instance starts with silent mode on.
  - For an upgrade, you turned it on before the upgrade, as [Upgrade the release](./upgrade#upgrade-the-host) describes.
  - For a restore, you turned it on in the database before the server started, as [Recover the live installation](./backup-restore#recover-the-live-installation) describes.
- You have a controlled repository, and a controlled Slack channel if you use Slack, for test reviews.

## Prove silence

1. Open **Instance administration → Instance settings**.
   The **Silent mode** card shows **On**.
2. Start a few reviews on the controlled repository, and a conversation in the controlled Slack channel.
3. Open each review in the web app.

   Its feedback on the work is withheld with the reason **Silent mode was on for the whole instance.**

4. On the provider, check that Hephaestus wrote no comment, review, reaction or Slack message.
5. Read the capture failures, the withheld feedback, the queue age and the spend of the test reviews.

If a write is unexplained, a migration failed, or the spend is unexplained, keep silent mode on.
Contain or roll back, as [Contain an incident](./contain-an-incident) and [Upgrade](./upgrade#rollback) describe.

## Turn off silent mode

1. Check the review scope, the account consent and the feedback mode of each workspace.
   For a first pilot, set the feedback mode to **Approve first** ([Feedback mode, review scope and delivery](./practice-review#feedback-mode-review-scope-and-delivery)).
2. Open **Instance administration → Instance settings**.
3. Select **Turn off silent mode…**.
4. Enter `turn off`.
5. Select **Turn off silent mode**.

## Check that it worked

1. Open a review that ran while silent mode was on.
   Its feedback stays withheld with the silent mode reason.
2. Push a new change to the controlled repository.
   Its review posts its feedback once, on the work.

## Undo

1. Open **Instance administration → Instance settings**.
2. Select **Turn on silent mode…**.
3. Enter the reason, and select **Turn on silent mode**.
