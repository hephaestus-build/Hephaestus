---
title: Contain an incident
description: Stop external writes, model spend, reviews, conversations or the whole server, and replace leaked credentials and sessions.
page_type: how-to
---

Contain an incident when Hephaestus sends wrong feedback, spends without control, or loses a credential.
Use the smallest switch that stops the harm.
No switch recalls a write that a provider already accepted.

## Before you start

- You are an instance admin.
- For the chart switches, you have your site values and Helm access to the release namespace.
- Record the instance URL, the Helm release, the incident owner, and the start time in your incident record.
  Keep credentials and feedback text out of the record.

Each switch stops one kind of work:

| Switch | What it stops | What continues |
| --- | --- | --- |
| Silent mode, in **Instance administration → Instance settings** | New writes to GitHub, GitLab and Slack, and all email. This includes comments, edits, approvals, reactions, Hephaestus replies, digests and their retries. | Reviews, model calls, sync, feedback in the web app, sign-in, webhook registration and GitHub redelivery |
| A budget of $0, or a connection turned off | Model calls that the budget pays for or that go through the connection, also the calls of running reviews | Sync, provider writes, and other budgets and connections |
| `sandbox.enabled: false` | Practice reviews and conversation turns. The chart sets `AGENT_ENABLED` from this value. | The API, sync and provider deliveries |
| `sandbox.conversationTurns: 0` | New conversation turns | Practice reviews |
| `config.slack.enabled: false` | Slack deliveries, conversations in Slack, channel reading and Slack messages | GitHub, GitLab and Outline |
| `server.replicas: 0` | The server: the API, provider deliveries, reviews and every write | The web app and PostgreSQL |

For one workspace, a workspace admin can pause sending or set the feedback mode to **Off**, as [Feedback mode, review scope and delivery](./practice-review#feedback-mode-review-scope-and-delivery) describes.
Silent mode does not stop model calls or spending.

## Stop external writes

1. Open **Instance administration → Instance settings**.
2. Select **Turn on silent mode…**.
3. Enter the incident reference as the reason.
4. Select **Turn on silent mode**.

   The **Silent mode** card shows **On**.
   On-work feedback that a review sends while silent mode is on stays withheld.

:::tip
A script can turn on silent mode with `PATCH /api/admin/settings/silent-mode` and the body `{"engaged":true,"reason":"<incident reference>"}`.
[Call the API](./instance-admin#call-the-api) gives the access token.
:::

## Stop model spend

A budget stops the model calls of one workspace.
The model proxy also refuses the next call of a review that already runs.

1. For the shared models of a workspace, open **Instance administration → AI usage**.
   In the row of the workspace, select **Set budget** or **Change budget**, and set the budget to $0.
2. For a workspace that uses its own provider, a workspace admin sets the provider cap to $0.
   The cap is under **AI usage → Set provider cap** in the workspace administration.
3. To stop every model on one connection, open **Instance administration → AI models**.
   Turn off **Active** for the connection, and select **Turn off connection**.
4. To stop new reviews in a workspace, a workspace admin turns off **Review settings → Automated review → Start practice reviews**.
   Reviews that already run can finish.

To stop all model calls at once, stop reviews and conversations, as the next section describes.

## Stop reviews and conversations

Stop the server and its sandboxes first.
`sandbox.enabled: false` also removes the network policies of the sandbox namespace, so no sandbox may run when you apply it.

1. In your site values, set `server.replicas: 0`.
2. Run `helm upgrade` with your site values.
3. Remove the sandboxes that still run, as step 4 of [Planned node maintenance](./maintenance#planned-node-maintenance) describes.
4. Wait until no server Pod and no sandbox Pod runs.
5. In your site values, set `sandbox.enabled: false`, and restore `server.replicas`.
6. Run `helm upgrade` with your site values.

   The server starts again and runs no review and no conversation turn.

To stop only conversations, set `sandbox.conversationTurns: 0` and run `helm upgrade`.
To stop only Slack, set `config.slack.enabled: false` and run `helm upgrade`.

## Stop the server

Stop the server when every write must stop at once, or when you cannot trust the server.

1. In your site values, set `server.replicas: 0`.
2. Run `helm upgrade` with your site values.

Provider deliveries fail while no server runs.
After the server starts again, recover them, as [Which providers deliver again](./webhook-ingestion-operations#which-providers-deliver-again) describes.

## Replace a leaked credential

Revoke the leaked value at its provider, then give Hephaestus a new one.

| Leaked credential | Replace it |
| --- | --- |
| GitHub App private key or client secret | [GitHub App keys](./secret-custody#github-app-keys) |
| Slack signing secret or client secret | [Slack secrets](./secret-custody#slack-secrets) |
| `WEBHOOK_SECRET` | [Webhook secret](./secret-custody#webhook-secret) |
| Client secret of a login provider | [Login provider secrets](./secret-custody#login-provider-secrets) |
| Database password | [Database passwords](./secret-custody#database-passwords) |
| GitHub or GitLab token of a workspace | Revoke it at the provider. A workspace admin enters a new token, as [Replace a refused token](/user/workspace#replace-a-refused-token) describes. |
| API key of an AI provider connection | Revoke it at the provider. Enter the new key in the **API key** field of the connection ([Connection fields](./ai-providers#2-connection-fields)). |
| The database, a backup, or many session cookies | [End every session on the instance](#end-every-session-on-the-instance) |

## End every session on the instance

End every session after a leak of the database, the JWT signing key, or many session cookies.
Every person and every installed client must then sign in again.

Run this transaction in the bundled database:

```bash
kubectl --namespace <release namespace> exec -i <release>-postgresql-0 -c postgresql -- \
  sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
BEGIN;
LOCK TABLE account IN EXCLUSIVE MODE;
UPDATE client_sign_in_handoff SET consumed_at = now() WHERE consumed_at IS NULL;
UPDATE client_session SET revoked_at = now(), revoked_reason = 'ADMIN_REVOKE' WHERE revoked_at IS NULL;
UPDATE issued_jwt SET revoked_at = now(), revoked_reason = 'ADMIN_REVOKE' WHERE revoked_at IS NULL;
COMMIT;
SQL
```

Replace `<release namespace>` and `<release>` with the namespace and the name of your Helm release.
The lock waits for sign-ins in progress, so the updates include their sessions.
The server checks each request against `issued_jwt`, so every session ends at once.

A leaked JWT signing key lets its holder sign new sessions.
Hephaestus has no procedure that replaces the signing key.
If it leaked, report it as the [security policy](https://github.com/hephaestus-build/Hephaestus/blob/main/SECURITY.md) describes.

Record the action in your incident record.

## Check that it worked

1. Open **Instance administration → Instance settings**.
   The **Silent mode** card shows **On**.
2. If you set a budget of $0, open **Instance administration → AI usage**.
   The row of the workspace shows **Paused**.
3. If you stopped reviews, run `kubectl --namespace <sandbox namespace> get pods`.
   The command lists no Pod.
4. If you stopped the server, run `kubectl --namespace <release namespace> get deployment <release>-server`.
   The Deployment shows `0/0` ready replicas.
5. If you ended every session, sign in again.
   The web app asks for a new sign-in.

## Undo

1. Before you turn off silent mode, fix the cause and check that the next review gives correct feedback.
2. Open **Instance administration → Instance settings**.
3. Select **Turn off silent mode…**.
4. Enter `turn off`.
5. Select **Turn off silent mode**.

   Feedback that was withheld while silent mode was on stays withheld.
   The next completed review posts its feedback, in every workspace.

6. Restore the budgets, provider caps, connections and review settings that you changed.
7. In your site values, restore `sandbox.enabled`, `sandbox.conversationTurns`, `config.slack.enabled` and `server.replicas`.
8. Run `helm upgrade` with your site values.

Ended sessions cannot be restored.
If the instance must restart from a backup, follow [Back up and restore](./backup-restore#restore).
