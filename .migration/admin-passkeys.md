#### 🔴 Admin passkey protection

Production instances now require passkey verification before instance administration. OAuth still establishes account identity. Admin accounts without a passkey can enroll through User settings but cannot use instance administration until verification succeeds.

Before upgrade, keep deployment and database access available. After upgrade, register a passkey, verify it, register a second passkey on another device, and save recovery codes. Test recovery before you depend on it.

If the SPA uses another origin, configure `HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS` and `HEPHAESTUS_AUTH_PASSKEYS_RP_ID`. Use exact trusted HTTPS origins. An RP ID change makes existing passkeys unusable.

To make instance-admin passkeys optional, explicitly set `HEPHAESTUS_AUTH_PASSKEYS_INSTANCE_ADMIN_REQUIRED=false`. This weakens protection. Workspace and personal requirements still apply.

The migration adds account-owned credential, challenge, and recovery-code tables, account protection fields, and a workspace policy field. Keep these tables in encrypted backups. Recovery codes permit credential replacement, not direct admin access.
