#### 🔴 Set `WEBHOOK_ROUTING_SECRET` for GitLab group webhooks

GitLab group webhooks now carry a token that names the one workspace they deliver for, signed with a
new secret. The application server and the webhook receiver refuse to start without it.

1. Generate a value of at least 32 printable characters, for example with `openssl rand -hex 32`. It
   must differ from `WEBHOOK_SECRET` and from both encryption keys.
2. Set it as `WEBHOOK_ROUTING_SECRET` for the application server and the webhook receiver; the
   reference Compose files forward it to both. The self-host `setup.sh` generates it when it is empty.
3. Deploy. Each GitLab workspace registers its new group webhook on its next sync, or at once with
   **Sync now**. The group webhook earlier versions registered keeps working; delete it on GitLab once
   the new one appears, so each event is not received twice.

To rotate the secret later, move the current value to `WEBHOOK_ROUTING_PREVIOUS_SECRET`, set a new
`WEBHOOK_ROUTING_SECRET`, deploy, let every GitLab workspace sync once, then clear
`WEBHOOK_ROUTING_PREVIOUS_SECRET` and deploy again.
