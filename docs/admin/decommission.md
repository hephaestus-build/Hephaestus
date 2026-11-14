---
title: Decommission an instance
description: Stop an instance, revoke its provider and model access, and delete its data when retention permits.
page_type: how-to
---

Decommission an instance when it stops for good.
The procedure stops every write first, then removes the access that the instance holds at each provider, and deletes data last.

:::caution[CAUTION: Shared integrations and retained data]
Do not delete a GitHub App, an OAuth application or a Slack app that another instance still uses.
Delete data only when your retention rules permit it.
:::

## Before you start

- You are an instance admin, with Helm access to the release.
- You know the retention period of each kind of data ([Data retention](./data-retention)) and of your backups.
- You know which feedback copies on providers must go before the data does.

## Stop the instance

1. Turn on silent mode, as [Contain an incident](./contain-an-incident#stop-external-writes) describes.
2. If feedback copies must go, remove them, as [Remove feedback from a provider](./remove-feedback-from-a-provider) describes.
3. If pull-based deployment runs, stop its timer, as [Operating it](./pull-based-deployment#operating-it) describes.
4. In your site values, set `server.replicas: 0`, and run `helm upgrade`.
5. If your retention rules require a final backup, take it, as [Back up the database](./backup-restore#back-up-the-database) describes.

## Revoke provider and model access

1. Uninstall the GitHub App from each installation, or delete the App if no other instance uses it.
2. Revoke the GitLab tokens, and delete the GitLab webhooks of the instance.
3. Remove the Slack app from each Slack workspace, and revoke its tokens.
4. Revoke the Outline API keys and webhooks of the instance.
5. Revoke the OAuth applications of the sign-in providers.
6. Revoke the API keys of the AI providers.
7. Remove the DNS record, the certificate and the alert routes of the instance.

## Delete the data

1. Delete the sandbox namespace, then remove the release, as [Undo](./kubernetes#undo) describes.
2. When your retention rules permit it, delete the release namespace, its volumes, and the Secrets that you created.
3. When your retention rules permit it, delete the off-host backups and the copies of the keys.
4. In your record of processing, write down the evidence that you keep, its owner and its deletion date.

## Check that it worked

1. Open the installation settings of each provider.
   No installation, webhook or token of the instance remains.
2. Check the billing of each AI provider and storage provider.
   No new charge appears.
