#### 🔴 Stop every server before the Slack delivery cutover

Stop every server replica before you apply this release's migration.
The previous release cannot read the new Slack action kinds or channel states.
Keep a verified pre-upgrade database backup.
Migrate and start only this release, then restore the recorded server count.
Follow [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades).

Update custom API clients to read the `ACTIVATING` and `FAILED` channel states.
Consent changes accept only `ACTIVE`, `PAUSED` or `REVOKED` as the requested state.

After the upgrade, a channel shows **Activating** until Slack confirms its announcement.
If it shows **Activation failed**, check the Slack connection and bot membership, then select **Retry activation**.
If migration fails, keep every server stopped and restore the verified backup before you restart the previous release.
