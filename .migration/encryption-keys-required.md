#### 🔴 Set both encryption keys on a server outside the chart

The server now refuses to start without its system encryption key and its credential encryption key, in every profile. A container of the server image that sets no `SPRING_PROFILES_ACTIVE` also refuses to start.

**Operators:** A deployment through the chart already sets both keys and its profile, and needs no change. Before you upgrade a server outside the chart, set these variables on every role:

- `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` and `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY`, as [Secret custody](https://docs.hephaestus.build/admin/secret-custody) describes. If the server already uses a key, keep that value: a new key cannot open what the old key sealed.
- `SPRING_PROFILES_ACTIVE`, such as `prod` for the server and `prod,migrate` for the migration run.

The `e2e` profile no longer supplies default keys. A run with that profile must pass both keys.

This change has no schema change, so old and new servers can run together.
