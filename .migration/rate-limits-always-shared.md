#### 🔴 Remove the auth rate-limit store setting

Rate limits on sign-in and other sensitive endpoints now always use PostgreSQL, so all server replicas share them.
The limits and their defaults do not change.

| Removed | Action |
| --- | --- |
| `HEPHAESTUS_AUTH_RATE_LIMIT_POSTGRES_BACKED` (`hephaestus.auth.rate-limit.postgres-backed`) | Remove it from your configuration. It no longer has any effect. |
