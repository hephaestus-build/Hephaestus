---
title: Connect Outline
description: Approve the Outline instances that workspaces may mirror, register the Outline OAuth application, and turn on Outline in the Helm chart.
page_type: how-to
---

Turn on Outline so that workspace admins can mirror Outline collections into a workspace.
Practice reviews and conversations then use the mirrored documents as context.
You approve each Outline instance by its origin. A workspace admin cannot approve one by entering its address.
After this setup, a workspace admin connects Outline in the web app, as [Connect Slack and Outline to a workspace](/user/integrations#connect-outline) describes.

## Before you start

- An installed instance that serves its host over HTTPS ([Install on Kubernetes](./kubernetes)).
- The HTTPS origin of each Outline instance, such as `https://wiki.example.com`, on a public address.
- For each origin, the entry in the [processor checklist](./dsms/processor-checklist.md): legal role, hosting region, transfer basis, retention terms and processing agreement.
- Optional: the admin role in Outline, to register the OAuth application that links member accounts.
- `kubectl` with access to the release namespace, and `jq`.

The commands use the release namespace `hephaestus` and the release Secret `hephaestus-app`.
Replace these names if your release uses other names.

## Register the Outline OAuth application

Members use this application to link their Outline account, so that Hephaestus knows who wrote a document.
Without it, workspaces mirror collections, but Hephaestus cannot match a document to the member who wrote it.

1. In Outline, open **Settings → Applications**, and add an application.
2. Set **Redirect URI** to `https://<host>/api/login/oauth2/code/outline`, without a final slash.
   Outline compares the address exactly.
3. Set the scope to `read`.
4. Save the application.
5. Copy its client ID and client secret.

## Set the Helm values and Secret keys

1. Optional: type the client secret of the OAuth application. The shell does not show it:

   ```bash
   read -rs OUTLINE_OAUTH_CLIENT_SECRET && export OUTLINE_OAUTH_CLIENT_SECRET
   ```

2. Optional: add the client secret to the release Secret:

   ```bash
   jq -n --arg secret "$OUTLINE_OAUTH_CLIENT_SECRET" '{stringData: {OUTLINE_OAUTH_CLIENT_SECRET: $secret}}' |
     kubectl -n hephaestus patch secret hephaestus-app --type merge --patch-file /dev/stdin
   ```

3. Add Outline to `site.yaml`:

   ```yaml title="values.yaml"
   config:
     outline:
       enabled: true
       allowedOrigins:
         - https://wiki.example.com
       oauthBaseUrl: https://wiki.example.com
       oauthClientId: 0123456789abcdef
       oauthDisplayName: Example wiki
   ```

   | Value | What it sets | Default |
   | --- | --- | --- |
   | `config.outline.enabled` | The Outline connection, its sync, and its webhook receiver | `false` |
   | `config.outline.allowedOrigins` | The HTTPS origins that a workspace can connect to | Empty |
   | `config.outline.oauthBaseUrl` | The Outline instance of the OAuth application. Required with `oauthClientId`. | Empty |
   | `config.outline.oauthClientId` | The client ID of the OAuth application. Empty creates no account linking. | Empty |
   | `config.outline.oauthDisplayName` | The name on the account-linking button | `Outline` |

   An origin has no path, query, fragment or user name.
   An origin on a private or site-local address is refused.
   The server does not start with an entry that is not valid.

4. Apply `site.yaml`.
   On a cluster, run the `helm upgrade` command of [Install](./kubernetes#install) again.
   On one host, run the installer again ([Run the installer](./k3s#2-run-the-installer)).

At its next start, the server creates the Outline login provider if it does not exist.
If `oauthBaseUrl` is empty or not valid, the server logs the reason at `ERROR` and creates no provider.
After that, the server ignores the client ID and secret of the chart.
An instance admin can add or change the provider under **Instance administration → Login providers**.

## Check that it worked

1. In a workspace, open **Administration → Integrations**.
   The sidebar lists **Outline**.
2. Open **Outline**.
3. Enter an approved origin as **Server URL**, and an Outline API key as **API token**.
4. Select **Connect Outline**.
5. Add a collection.
   Its documents show after the first sync.

When a workspace connects, Hephaestus registers a webhook subscription in Outline.
Its address is `https://<host>/webhooks/outline/…`, so Outline must reach your host.
`WEBHOOK_ROUTING_SECRET` signs each subscription, and you set nothing for it.

## Undo

1. In each workspace with an Outline connection, select **Disconnect Outline** if its mirror must be erased.
   A connection cannot disconnect while Outline is off for the instance.
2. Set `config.outline.enabled` to `false` in `site.yaml`.
3. Apply `site.yaml`.

With Outline off, the receiver answers `404` to each Outline delivery.
Outline turns off a subscription after 25 failed deliveries.

To withdraw one Outline instance only, remove its origin from `config.outline.allowedOrigins` and apply `site.yaml`.
Its connections then stop their sync, webhooks, account linking and review context.
Their mirror stays until a workspace admin disconnects them.

## Replace the API key of a workspace

Outline API keys do not renew, and Hephaestus cannot make a new key.
The **Outline** page of a workspace shows when its key expires, but it has no field for a new key.
**Disconnect Outline** erases the mirror.
To keep the mirror, a workspace admin or owner sends the new key to the API.

1. In Outline, create an API key for the same Outline team.
2. Sign in to Hephaestus as a workspace admin or owner.
3. In the developer tools of the browser, copy the value of the `__Host-HEPHAESTUS_AT` cookie.
   The value signs you in, so keep it out of tickets and logs.
4. Type the cookie value, then the new key. The shell does not show them:

   ```bash
   read -rs HEPHAESTUS_SESSION && export HEPHAESTUS_SESSION
   read -rs OUTLINE_API_KEY && export OUTLINE_API_KEY
   ```

5. Send the key with the origin of the connection.
   Replace `<host>`, `<workspace-slug>` and the origin:

   ```bash
   jq -n --arg key "$OUTLINE_API_KEY" \
     '{kind: "OUTLINE", userInput: {server_url: "https://wiki.example.com", token: $key}}' |
     curl -sS -X POST "https://<host>/api/workspaces/<workspace-slug>/connections" \
       -H "Authorization: Bearer $HEPHAESTUS_SESSION" -H "Content-Type: application/json" --data @-
   ```

   A request with a bearer token and no cookie needs no CSRF token.

6. Make sure that the response has `"type":"LINKED"` and the `connectionId` of the existing connection.
7. On the **Outline** page, select **Sync now**.
   The collections refresh.
8. Revoke the old key in Outline.

The request keeps the documents and the collections of the connection.
It resets the configuration of the connection, and Hephaestus registers its webhook subscription again.
