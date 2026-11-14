---
title: Connect GitLab
description: Register the GitLab OAuth application, turn on GitLab in the Helm chart, and let workspace admins create GitLab workspaces.
page_type: how-to
---

Connect your GitLab instance so that people sign in with GitLab and workspace admins create GitLab workspaces.
An install connects one GitLab instance, gitlab.com or a self-managed one.
After this setup, a workspace admin creates the workspace in the web app, as [Set up your workspace](/user/workspace#create-a-workspace) describes.

## Before you start

- An installed instance that serves its host over HTTPS ([Install on Kubernetes](./kubernetes)).
- An account on the GitLab instance that can create an OAuth application.
- The GitLab instance can reach `https://<host>/webhooks`.
- `kubectl` with access to the release namespace, and `jq`.
- For a GitLab instance on a private network or with a private CA, follow [Private network](./private-network) first.

The commands use the release namespace `hephaestus` and the release Secret `hephaestus-app`.
Replace these names if your release uses other names.

## Register the GitLab OAuth application

1. On the GitLab instance, open **Admin area → Applications**, and add an application.
   Without the administrator role, use the **Applications** page of your user settings.
2. Set **Name** to the name of your instance.
3. Set **Redirect URI** to `https://<host>/api/login/oauth2/code/gitlab`.
4. Select **Confidential**.
5. Select the `read_user` scope only.
6. Save the application.
7. Copy the **Application ID** and the **Secret**.

## Set the Helm values and Secret keys

1. Type the secret of the application. The shell does not show it:

   ```bash
   read -rs GITLAB_OAUTH_CLIENT_SECRET && export GITLAB_OAUTH_CLIENT_SECRET
   ```

2. Add the secret to the release Secret:

   ```bash
   jq -n --arg secret "$GITLAB_OAUTH_CLIENT_SECRET" '{stringData: {GITLAB_OAUTH_CLIENT_SECRET: $secret}}' |
     kubectl -n hephaestus patch secret hephaestus-app --type merge --patch-file /dev/stdin
   ```

3. Add GitLab to `site.yaml`:

   ```yaml title="values.yaml"
   config:
     gitlab:
       enabled: true
       oauthClientId: 0123456789abcdef
       oauthBaseUrl: https://gitlab.example.com
       oauthDisplayName: Example GitLab
   ```

   | Value | What it sets | Default |
   | --- | --- | --- |
   | `config.gitlab.enabled` | GitLab workspaces, their sync, and repository checkout for practice reviews | `false` |
   | `config.gitlab.oauthClientId` | The **Application ID** of the OAuth application | Empty |
   | `config.gitlab.oauthBaseUrl` | The GitLab instance, for sign-in and for every GitLab workspace | `https://gitlab.com` |
   | `config.gitlab.oauthDisplayName` | The name on the sign-in button | `GitLab` |

4. Apply `site.yaml`.
   On a cluster, run the `helm upgrade` command of [Install](./kubernetes#install) again.
   On one host, run the installer again ([Run the installer](./k3s#2-run-the-installer)).

At its next start, the server creates the GitLab login provider if it does not exist.
After that, the server ignores the client ID and secret of the chart.
Change them under **Instance administration → Login providers**, as [Login provider secrets](./secret-custody#login-provider-secrets) describes.

## Token rotation

Hephaestus checks the token of each GitLab workspace daily and rotates it before it expires.
Only one server at a time checks the token of a workspace, and it holds no database transaction during GitLab calls.
A server that stops responding keeps that lock until its database connection closes, so no other server can rotate the token in the meantime.
The server stores a rotated token only if nobody paused, resumed or replaced the token during the call.
It discards a late answer after a token replacement, a pause or a resume.
The connection audit records an uncertain attempt as `GITLAB_TOKEN_ROTATION_UNCERTAIN`.

## Check that it worked

1. Sign out of Hephaestus.
   The sign-in page offers **Continue with** and your `oauthDisplayName`.
2. Sign in with GitLab.
3. Open **Create workspace**.
   It offers **GitLab**.

After a workspace admin creates a GitLab workspace, open the group in GitLab, then **Settings → Webhooks**.
The list shows the webhook that Hephaestus registered.
A change to a merge request then shows in the workspace without a wait for the scheduled sync.

## Undo

1. Set `config.gitlab.enabled` to `false` in `site.yaml`.
2. Apply `site.yaml`.
3. Open **Instance administration → Login providers**.
4. Select **Disable** for the GitLab provider.

GitLab workspaces then stop their sync, their practice reviews, and their feedback delivery.
Their synced work stays.

## GitLab instances and workspaces

`config.gitlab.oauthBaseUrl` names the one GitLab instance of the install.
Hephaestus reads every GitLab project, member, and event from that instance, and it refuses a workspace on another instance.
An instance admin can add a login provider for another GitLab instance under **Instance administration → Login providers**.
That provider allows sign-in only, not workspaces.

For two GitLab instances, run one install for each instance, each with its own release name, database, and host.
The installs share no data, so each person has a separate account on each install.

A workspace admin creates a GitLab workspace with their own GitLab account on that instance.
Thus, they must sign in with GitLab, or link their GitLab account, before they create it.

## GitLab without group webhooks

Hephaestus registers a webhook on the GitLab group of each workspace, with a token that `WEBHOOK_ROUTING_SECRET` signs.
`hephaestus-keys.sh` generates that key, and you set nothing for it.
The webhook needs the Owner role on the group, and a GitLab tier with group webhooks.
GitLab Community Edition and the Free tier have no group webhooks.

Without the webhook, the sync of the workspace reports as incomplete, and Hephaestus sees changes only when a sync runs:

- The scheduled sync reads new and changed work. `config.sync.syncCron` sets it, hourly by default ([Values](./helm-chart#values)).
- **Sync now** on the connection runs a sync at once.
- A person can request a review only of work that a sync has read.
- Feedback delivery does not need the webhook. Hephaestus posts its comments through the GitLab API.

Do not add a project webhook instead: Hephaestus accepts only the webhooks that it registers.
If new work must show sooner, schedule the sync more often.

GitLab 18.6 or later sends the review state of each reviewer in merge request events.
On an earlier GitLab, approvals and change requests arrive only with the scheduled sync.
