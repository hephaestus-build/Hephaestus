# ADR 0104: The system key and the signing key rotate without a stop

**Status:** Accepted
**Date:** 2026-11-14
**Authors:** Felix T.J. Dietrich

**Extends:** [ADR 0014](0014-per-row-aes-gcm-aad-binding.md) and [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md)

## Context

`HEPHAESTUS_SECURITY_ENCRYPTION_KEY`, the system key, seals four kinds of values: the JWT signing keys, the client secrets of the login providers, the API keys of the AI provider connections, and the key that keeps erased personal namespaces reserved.
Operators replace keys on a schedule and after a suspected disclosure, and the production runbook includes that step.
This decision adds key identifiers, a prior key and a re-seal pass for the system key, and a setting that makes a new JWT signing key.

Two more facts shaped the decision:

- ADR 0014 binds each integration credential to its row, so a value copied to another row does not open. This decision applies the same binding to the values of the system key. A JPA converter cannot see the row, so the entities seal through an explicit call.
- The key of the namespace reservations derived from the system key. A new system key must not release the reserved erased slugs, and an erased slug cannot be hashed again.

## Decision drivers

- An operator replaces a key after a suspected disclosure, and the instance keeps serving while they do.
- Each server can open each value at each step of a rotation, with two or more replicas and a rolling update.
- One sealing path for every value of the system key, as for the integration credentials.
- A sealed value that moves to another row does not open.
- No runtime code reads an earlier envelope ([ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md) rule 7).

## Considered options

1. **A key identifier in each sealed value, an active and a prior key, and a re-seal pass (chosen).** This is the design of the credential key, applied to the system key. The identifier derives from the key, as in Tink's wire format, so no setting can pair a key with the wrong identifier.
2. **A configured key version, in the envelope or in a column of each table.** The credential key does this. The version lives in the values file and the key in the Secret, so a restart between two changes can pair a key with the wrong version. Four tables would also each need a column.
3. **Re-seal at server start.** A new server that starts during a rolling update writes values that an older server cannot open, unless every server already holds the new key. The explicit switch gives the operator that order.
4. **An instance-admin action for a new signing key.** It needs an endpoint, a screen in each console and an audit event. A setting in the values file does the same with one `helm upgrade`, it is idempotent, and it stays in the operator's change history.

## Decision

1. **`SystemSecrets` seals every value of the system key.** The envelope holds a format byte, the key identifier (the first four bytes of HMAC-SHA256 of a fixed label under the key), a nonce and the AES-GCM ciphertext. The additional authenticated data holds the format byte, the key identifier, the `table.column` and the immutable natural key of the row:

   | Value | Row key |
   | --- | --- |
   | `login_provider.client_secret` | `registration_id` |
   | `llm_connection.api_key` | `slug` |
   | `workspace_llm_connection.api_key` | `workspace_id`, `slug` |
   | `jwt_signing_key.private_key_pem` | `kid` |
   | `system_secret.sealed_value` | `name` |

   The server seals with the active key (`hephaestus.security.encryption-key`). It opens a value with the key that the value names, which is the active key or the prior key (`prior-encryption-key`).
2. **A re-seal pass moves each value to the active key.** With `hephaestus.security.encryption-rotation-enabled`, the server locks every row that holds a value and seals each value again that names another key, in one transaction. A second pass changes nothing. A value that does not open rolls the pass back, and the next pass tries again. A pass that finds nothing to seal again reports completion. The update paths of these rows lock the row too, so an update cannot write back a value under the earlier key.
3. **The rotation needs no stop.** The operator adds the new key as the prior key first, so each server can open values under it. Then the operator makes it the active key, with the old key as the prior key, and turns on the pass. An old server and a new server can each open what the other writes. [Secret custody § System key](../admin/secret-custody.mdx#system-key) owns the procedure.
4. **The key of the namespace reservations is a stored system secret.** It is a random value in `system_secret`, sealed like the other values. The upgrade stores the key that the earlier release derived, so each existing reservation stays.
5. **The upgrade into 1.0 seals each value once.** The change set `SystemSecretSealingChange` reads the earlier envelopes and writes the new one, while every role is stopped. The migration Job gets the active system key for this, because a change set that migrates sealed values must open them. A database without an earlier value needs no key.
6. **A server checks its system key at start.** It opens each active signing key and each stored system secret. If one does not open, it stops before it reports ready, and the message names the identifier of the key that sealed the value and the identifiers that it holds. Without this check, a server with another key starts and refuses every session.
7. **A new JWT signing key comes from a setting.** `hephaestus.auth.signing-key-not-before` is an instant. A server that starts after it and finds no signing key created at or after it makes a new key. The newest key signs. Every older active key still verifies until `access-ttl` after a newer key was created, and then it retires. A server that sees a token from a key that it does not know reloads the keys, at most once in five seconds, so a server with an older key set does not refuse it. Each token names its key in the `kid` header.

## Consequences

- An operator can replace the system key and the signing key while the instance serves.
- The login provider secrets and the AI provider keys are bound to their rows, as the integration credentials are.
- The migration Job reads the system key.
- The upgrade into 1.0 needs the system key that sealed the stored values. A wrong key stops the migration, and the migration writes nothing.
- A rotation of the system key needs three rollouts: add, switch, remove.

## Confirmation

- `SystemSecretsTest`: a value opens under the prior key after the switch, a value moved to another row or column does not open, and a second re-seal changes nothing.
- `SystemSecretSealingChangeDatabaseTest`: the upgrade seals each earlier value again and keeps the reservation key. A wrong key stops it and writes nothing.
- `SystemKeyRotationIntegrationTest`: a pass moves every value to the active key, a second pass changes nothing, and a client secret copied to another provider does not open.
- `JwtSigningKeyServiceSealingTest#shouldNameBothKeyIdsWhenTheSystemKeyDoesNotOpenTheSigningKey` and `AuthJwtConfigTest#shouldStopStartupWhenTheSystemKeyDoesNotOpenTheStoredKeys`: a server with another system key stops at start and names both key identifiers, never a key.
- `JwtSigningKeyRotationTest`: a token from the earlier signing key verifies during the overlap, and a server with an older key set accepts a token from the new key.

## Revisit trigger

A sealed value that is not a row of a small table, for example one value for each workspace member. The pass then needs batches, as the credential rotation has.
