---
title: Connect Slack
description: Create the Slack app of your instance from its manifest, give its secrets to the Helm chart, and let workspace admins connect Slack.
page_type: how-to
---

Create one Slack app for your instance so that workspace admins can connect their Slack workspaces.
Members then talk with Hephaestus in direct messages, and a workspace admin chooses the channels that Hephaestus reads.
After this setup, a workspace admin connects Slack in the web app, as [Connect Slack and Outline to a workspace](/user/integrations#connect-slack) describes.

## Before you start

- An installed instance that serves its host over HTTPS ([Install on Kubernetes](./kubernetes)).
- A Slack account that can create apps in the Slack workspace that owns the app.
- `kubectl` with access to the release namespace, and `jq`.

The commands use the release namespace `hephaestus` and the release Secret `hephaestus-app`.
Replace these names if your release uses other names.

## Create the Slack app

1. Download [`slack-app-manifest-template.yml`](./slack-app-manifest-template.yml).
2. Replace each `<host>` in the file with the host of your instance.
3. Open [api.slack.com/apps](https://api.slack.com/apps), and select **Create New App**.
4. Select **From a manifest**, then the Slack workspace that owns the app.
5. Paste the manifest, and create the app.
6. Under **Basic Information → Display Information**, upload [`slack-app-icon-512.png`](/img/brand/slack-app-icon-512.png) as the app icon.
   Slack rounds the corners, so do not round the image.
7. Under **Basic Information → App Credentials**, copy the **Client ID**, the **Client Secret**, and the **Signing Secret**.

The manifest sets these addresses:

| Manifest field | Address | Use |
| --- | --- | --- |
| `oauth_config.redirect_urls` | `https://<host>/api/oauth/callback/slack` | A workspace admin installs the app in a Slack workspace. |
| `oauth_config.redirect_urls` | `https://<host>/api/login/oauth2/code/slack` | A member links their Slack account. |
| `settings.event_subscriptions.request_url` | `https://<host>/webhooks/slack` | Slack sends messages and app events. |
| `settings.interactivity.request_url` | `https://<host>/webhooks/slack/interactivity` | Slack sends the buttons that members press in the App Home tab. |

## Set the Helm values and Secret keys

1. Type the client secret, then the signing secret. The shell does not show them:

   ```bash
   read -rs SLACK_CLIENT_SECRET && export SLACK_CLIENT_SECRET
   read -rs SLACK_SIGNING_SECRET && export SLACK_SIGNING_SECRET
   ```

2. Add both secrets to the release Secret:

   ```bash
   jq -n --arg client "$SLACK_CLIENT_SECRET" --arg signing "$SLACK_SIGNING_SECRET" \
     '{stringData: {HEPHAESTUS_INTEGRATION_SLACK_CLIENT_SECRET: $client, HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET: $signing}}' |
     kubectl -n hephaestus patch secret hephaestus-app --type merge --patch-file /dev/stdin
   ```

3. Add Slack to `site.yaml`:

   ```yaml title="values.yaml"
   config:
     slack:
       enabled: true
       clientId: "1234567890.1234567890123"
   ```

   With `config.slack.enabled`, a server without `HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET` does not start.

4. Apply `site.yaml`.
   On a cluster, run the `helm upgrade` command of [Install](./kubernetes#install) again.
   On one host, run the installer again ([Run the installer](./k3s#2-run-the-installer)).

At its next start, the server also creates the Slack login provider if it does not exist.
Members use it only to link their Slack account, and it never shows on the sign-in page.
After that, the server ignores the client ID and secret of the chart for account linking.
[Slack secrets](./secret-custody#slack-secrets) describes how to replace each secret.

## Check that it worked

1. In a workspace, open **Administration → Integrations**.
   The sidebar lists **Slack**.
2. Open **Slack**, and select **Connect Slack workspace**.
3. Allow the app in Slack.
   Slack returns you to Hephaestus.
   The Slack page shows **Connected**.
4. In Slack, send the app a direct message.
   Hephaestus answers when the member linked their Slack account and the workspace has a ready **Conversations** model ([Assign models](/user/ai-models#assign-models)).

## Undo

1. In each workspace with a Slack connection, select **Disconnect Slack** if its Slack data must be erased.
   A connection cannot disconnect while Slack is off for the instance.
2. Set `config.slack.enabled` to `false` in `site.yaml`.
3. Apply `site.yaml`.
4. Delete the app on [api.slack.com/apps](https://api.slack.com/apps) if no other instance uses it.

## What the app can read

The manifest asks for these bot scopes, and Hephaestus uses no other scope:

| Scope | Use |
| --- | --- |
| `chat:write` | Answers in direct messages, weekly digest notes, and channel announcements |
| `im:history` | Direct messages with their edits and deletions |
| `channels:history`, `groups:history` | Messages in the public and private channels that a workspace admin activates |
| `channels:read`, `groups:read` | The channel list for the channel picker, and channel names |
| `channels:join` | A workspace admin adds a public channel from the picker. |
| `users:read` | The match of a Slack user to a workspace member |

Account linking uses the user scopes `openid`, `profile` and `email`.
Hephaestus reads no reactions.

When a later release needs another scope, its release notes say so.
Then apply the new manifest to the app, and a workspace admin selects **Reconnect Slack** in each workspace.
Until then, the Slack page of the workspace shows **Permissions missing** and names each missing scope.
