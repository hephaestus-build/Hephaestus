# ADR 0011: `integration_identity` is OAuth-fed, not sync-fed (tombstone)

**Status:** Superseded by [ADR 0016](0016-unified-identity-keycloak-as-truth.md)
**Date:** 2026-05-25

## What this recorded

An attempt at a three-layer identity model:

- **Layer 1** `Connection` — workspace × kind × instance_key
- **Layer 2** `HephaestusUser` — one row per real person (Keycloak)
- **Layer 3** `IntegrationIdentity` — one row per `(kind, integration_instance_id, external_id)`

`IntegrationIdentity` was only ever populated from the OAuth-link path; no sync path
(GitHub or GitLab) wrote to it, so observed contributors never landed in
`integration_identity`. The plan was to wire sync to upsert rows with a null
`hephaestus_user_id`, populated later on OAuth-link.

## Outcome

Deferred, then deleted. The entire `integration.identity` package
(`HephaestusUser`, `IntegrationIdentity`, `JpaUserDirectory`, both repositories, the
`UserDirectory` SPI) was removed, along with its Liquibase create steps. The shipped
auth model is documented in **[ADR 0016](0016-unified-identity-keycloak-as-truth.md)**:
SCM `User` is the authoritative person row, and Keycloak `sub` is persisted on
`User.keycloak_subject` as the stable join key.

## Update — 2026-09-17

Corrects the last sentence of § Outcome. The model ADR 0016 shipped was itself replaced by
[ADR 0017](0017-replace-keycloak-with-spring-native-auth.md): Keycloak is gone, `User.keycloak_subject`
does not exist, and the person row is `core.auth.domain.Account` joined to provider identities through
`identity_link (provider_id, subject)`. The deletion this ADR records still holds — no
`integration.identity` layer of that shape exists; today's `integration/identity/connect/` holds only
`RegistrationToGitProviderResolver`.
