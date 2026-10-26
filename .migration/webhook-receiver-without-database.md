#### 🔴 Register Outline subscriptions on their new address

Outline deliveries no longer go to `/webhooks/outline`. Each subscription now posts to its own address under `/webhooks/outline/`, and the receiver refuses the old address.

After the upgrade, open each workspace's Outline connection in workspace administration and choose **Sync now**. The sync registers the subscription on its new address and deletes the old one. Without this step, the next scheduled sync does it, at most 6 hours later, and Outline changes reach Hephaestus only through syncs until then.

The webhook receiver no longer reads the database. You can remove `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` and `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY` from the webhook container. It still needs `WEBHOOK_ROUTING_SECRET`, with the same value as the server.
