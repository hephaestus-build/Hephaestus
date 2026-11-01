---
"hephaestus": minor
---

Rate limits on sign-in and other sensitive endpoints are always shared across server replicas, because PostgreSQL is now their only store.
`HEPHAESTUS_AUTH_RATE_LIMIT_POSTGRES_BACKED` (`hephaestus.auth.rate-limit.postgres-backed`) is gone.
The limits and their defaults do not change.
**Operators:** remove `HEPHAESTUS_AUTH_RATE_LIMIT_POSTGRES_BACKED` from your configuration if you set it. It no longer has any effect.

The sign-in page takes each provider's type only from the provider an administrator configured, and no longer guesses it from the provider's address.
