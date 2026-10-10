#### 🔴 Register exact apex callbacks and check workspace names

Before the upgrade, make and verify a database backup.
Register each login provider's exact apex callback from instance administration.
The callback uses the configured auth issuer's origin, the public API prefix, and `/login/oauth2/code/<registrationId>`.
Turn off GitHub wildcard matching for each callback.
Keep the separate Slack connection callback on the apex.

The upgrade changes invalid or reserved workspace slugs to `workspace-<id>`, with a numeric suffix if needed.
Old paths that match the previous slug format redirect to the new name.
Check the workspace addresses after upgrade.
Used names cannot be reused after a rename or deletion.
Previously pruned history cannot be recovered automatically.

Workspace subdomains remain off by default.
Deploy edge and SPA support before you set `HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED=true`.
Set `HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN` to the apex domain.
Check that this domain is not on the Public Suffix List.
See the [workspace subdomains guide](https://docs.hephaestus.build/admin/workspace-subdomains).

Restore the verified pre-upgrade database backup to reverse the slug migration.
