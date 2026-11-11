# ADR 0095: Each provider has one adapter behind a vendor-neutral SPI, and Outline is a documentation source with a link-only identity

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0015](0015-unified-integration-framework.md), [ADR 0023](0023-outline-documentation-integration.md)

**Builds on:** [ADR 0010](0010-outbound-oauth-state-handrolled.md), [ADR 0014](0014-per-row-aes-gcm-aad-binding.md), [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md), [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md)

**Relates to:** [ADR 0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md), [ADR 0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md)

## Context

Hephaestus connects to GitHub, GitLab, Slack and Outline. Before ADR 0015, each provider had its own route, handler registry and credential columns, and other modules imported provider classes.
ADR 0015 bundled four decisions: the package layout, the SPI, credential encryption and a one-time migration. ADR 0023 bundled eight for Outline, and its own update contradicted its title.
Both named classes that no longer exist, and their webhook parts went with ADR 0066 and then [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md).

This ADR states the integration framework at 1.0 as rules. [Integrations](../admin/integrations.mdx) and [Sync lifecycle](../contributor/sync-lifecycle.md) keep the detail.
A rule that main does not satisfy yet says "1.0 target".

## Decision drivers

- A product module must not learn a provider's vocabulary. Review, Heph and evidence code stay the same for every provider.
- A boundary must fail the build, not rely on convention.
- The cost of a new provider must stay close to the cost of its adapter.
- Each server URL that an admin enters is an SSRF risk, and each webhook URL is public.

## Considered options

1. **Extend `gitprovider/` for each new family** (the state before ADR 0015). The package name says "git", and every consumer translates it.
2. **One root for each family, with providers under it.** About 600 file moves, and the shared pipeline still needs a cross-cutting home.
3. **A vendor-neutral `core`, family kernels, and one adapter for each provider (chosen).** Webhook and OAuth code stay apart, because their security models differ.

## Decision

1. **The layout is fixed.** `integration/` holds `core`, `scm`, `chat`, `docs`, `slack`, `outline` and `identity`, and nothing else. `core` is the vendor-neutral substrate. `scm`, `chat` and `docs` hold the kernel and item contracts of a family. `scm/github`, `scm/gitlab`, `slack` and `outline` are the adapters. `identity` holds the login adapters.
   Enforced by: `IntegrationStructuralRulesTest#integrationTopLevelHasOnlyExpectedSubpackages`.
2. **The SPI is vendor-neutral.** `integration.core.spi` and the consumer wiring name no provider and import no provider SDK.
   Enforced by: `IntegrationStructuralRulesTest#spiHasNoVendorLiteralIdentifiers` and `#coreConsumerHasNoVendorLiteralIdentifiers`, `IntegrationSpiBoundariesTest#spiHasNoVendorSdkDependencies`.
3. **Adapters stand alone.** No adapter imports another. The SCM kernel imports no SCM adapter.
   Enforced by: `IntegrationSpiBoundariesTest#kindModulesDoNotImportEachOther`, `IntegrationStructuralRulesTest#scmDomainDoesNotDependOnVendorAdapters`.
4. **Product modules reach a provider only through neutral ports.** No class outside `integration` imports an adapter. `core`, `workspace`, `practices`, `agent`, `evidence`, `mentor` and `integration.core` import no family or adapter package. `agent` never branches on a provider constant.
   Enforced by: `ExternalVendorImportAllowlistTest#noExternalVendorImporter`, `PracticeReviewModuleBoundaryTest#shouldKeepDomainAndProviderTypesOutOfCoreProducts`, `IntegrationSpiBoundariesTest#agentDoesNotReadProviderEnumConstants`.
5. **The ArchUnit rules are the boundary, not Modulith closure.** `integration.core`, `integration.scm`, `scm.github`, `scm.gitlab` and `slack` are `Type.OPEN`. Spring Modulith still refuses cycles and checks named interfaces.
   Enforced by: `ModulithVerificationTest#modulesAreCycleFreeAndRespectNamedInterfaces` and rules 2 to 4.
6. **A manifest declares what an adapter can do, and boot checks it.** Every kind has a credential provider and a lifecycle listener. Each declared capability needs its beans, or boot fails. Every shipped manifest has a contract test.
   Enforced by: `IntegrationFrameworkBootstrapTest#declaredCapabilityWithoutItsBeanFailsLoud`, `IntegrationManifestConformanceCoverageTest#everyShippedManifestHasAContractTest`.
7. **A connection row is the only record of which provider a workspace uses.** Its credentials are encrypted as [ADR 0014](0014-per-row-aes-gcm-aad-binding.md) decides.
8. **A workspace connects through its adapter's `ConnectionStrategy`.** GitHub and Slack redirect to the provider with a signed, single-use state, as [ADR 0010](0010-outbound-oauth-state-handrolled.md) decides. GitLab and Outline take a token that the admin pastes. GitHub treats `installation_id` as a claim. The connect needs the `code`, and it proves that the authorizing person may use the installation.
   Enforced by: `GitHubConnectionStrategyTest#shouldRefuseAForgedInstallationIdThatCarriesNoAuthorization` and `#shouldRefuseAnOrganizationInstallationWhenTheAuthorizingPersonIsOnlyAMember`, `HmacOAuthStateServiceTest#tamperedStateRejected`.
9. **Provider events** go to the PostgreSQL inbox, as [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) decides.
10. **Slack and Outline are opt-in.** `hephaestus.integration.slack.enabled` and `hephaestus.integration.outline.enabled` default to `false`.
    Not enforced. Review of `application.yml` catches a changed default.
11. **Outline is the documentation family's first provider.** Its kind is `OUTLINE` in the family `DOCUMENTATION`. A document is the item kind `docs.document`, and it is reviewed through the same item path as a pull request. Product modules reach documents only through the neutral item ports of `integration.core.spi`.
    Enforced by: `PracticeReviewModuleBoundaryTest#shouldKeepDomainAndProviderTypesOutOfCoreProducts`.
12. **An admin-supplied server URL passes the operator's allowlist and the SSRF guard.** The Outline client reuses `WebClientConnectors.ssrfGuarded()`, as GitLab does. No adapter writes its own guard.
    Enforced by: `OutlineConnectionStrategyTest#initiate_rejectsAnOriginOutsideTheOperatorAllowlist`. Review catches a client without the guard.
13. **The Outline identity link is link-only OAuth 2.0.** It never starts a sign-in. It asks for the `read` scope and never `openid`. The identity key is the Outline user and its team. An email never resolves an account.
    Enforced by: `OutlineIdentityIntegrationTest#outlineLoginModeIsRefusedBecauseTheProviderIsLinkOnly` and `#relinkingAnOutlineIdentityOwnedByAnotherAccountIsRefused`, `LoginProviderServiceTest#seedsOutlineFromItsBaseUrlWithTheReadScope`.
14. **Each Outline webhook route has its own derived signing secret.** A route id is 128 random bits. The secret is an HMAC-SHA256 of the route id under the routing secret, with a label of its own. Hephaestus stores no Outline signing secret. Verification reads the exact bytes in constant time, with a 300-second window. A route of the previous key stays valid while the key rotates.
    Enforced by: `OutlineRouteCredentialTest#shouldDeriveTheSecretFromTheRoutingSecretWhenTheUrlIsKnown` and `#shouldKeepThePreviousKeysRoutesVerifiableWhenTheKeyRotates`, `OutlineWebhookSignatureVerifierTest#verify_rejectsATamperedBody` and `#verify_rejectsAReplayedStaleTimestamp`.
15. **The routing secret is a random key of at least 256 bits.** Each route secret is only as strong as this key.
    Not enforced. `WebhookProperties.Routing` checks only a length of 32 characters. No pull request adds an entropy check.
16. **Outline retention follows upstream, not a clock.** A document stays while it exists upstream. A size cap for each workspace evicts the bodies that were fetched least recently. A tombstone stays 30 days by default. ADR 0094 owns tombstones and erasure.
    Enforced by: `OutlineDocumentSyncServiceTest#syncPendingCollections_enforcesTheSizeCapAfterThePass`.
17. **A document author has no opt-out.** The admin's connection, the registered collections and the connection audit are the consent and the disclosure. Person erasure still reaches documents ([ADR 0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md)).
    Not enforced.

## What changed, and why

- **A document is a reviewed item** (ADR 0023 update of 2026-08-08). The title "not a detection surface" no longer holds. Rule 11 keeps the module boundary that ADR 0023 protected.
- **The layout has family modules.** `chat` and `docs` hold item contracts. ADR 0015 listed only `core`, `scm` and `slack`.
- **Five modules are open, not only Slack.** ADR 0015 said Slack was open "by empirical necessity". Spring Modulith calls open modules a sign of "sub-optimal modularization", so rules 2 to 4 carry the boundary.
- **A new provider is not "zero changes elsewhere".** It also needs a value in three enums: `IntegrationKind`, `IdentityProviderType` and `LoginProvider.ProviderType`. It needs a place in the ArchUnit lists, a manifest test and an inbox subscription ([ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md)).
- **The Outline secret is derived, not stored** (ADR 0066, kept by ADR 0080). Outline signs with one secret for each subscription, so Hephaestus derives it from the route. The shared `/webhooks/{kind}` lane, the `outline` stream and its 72-hour age go with NATS.
- **The Connector superset SPI is declined, not deferred** (ADR 0023 § 5). ADR 0090 removed the Context Fabric register.
- **Encryption and the cutover migration leave this record.** ADR 0014 owns encryption. The 1.0 baseline replaced the migration.

## Consequences

- Good: review, Heph and evidence code never change for a new provider. The build fails when a provider type leaks.
- Good: one boot check tells an operator which bean a declared capability lacks.
- Good: a leaked database holds no Outline signing secret.
- Bad: the open modules let a class inside `integration` reach another module's internals. Only the ArchUnit rules stop it.
- Bad: a new provider still touches three enums and several ArchUnit lists.
- Bad: an operator who sets a weak routing secret weakens every Outline and GitLab route.

## Confirmation

Each rule names its test or says that it is not enforced. Rules 10, 15 and 17 rely on review.

## Revisit trigger

- A provider that fits none of the families, for example one that pushes events through a third-party broker.
- A third cross-cutting trait that needs its own package beside `oauth` and `webhook`.
- An open module can become closed with no new named interface. Then close it.
- Outline ships a per-delivery key or an asymmetric signature.

## Sources

- Spring Modulith, [Fundamentals § Open application modules](https://docs.spring.io/spring-modulith/reference/fundamentals.html): an open module allows "access to application module internal types from other modules". It "usually hints at sub-optimal modularization". This is why rule 5 does not rely on closure.
- Outline source, [`WebhookSubscription.ts`](https://github.com/outline/outline/blob/main/server/models/WebhookSubscription.ts): the signature is `t=<timestamp>,s=<hex>`, an HMAC-SHA256 over `timestamp.payload` "keyed by the `secret` field". With no secret, Outline sends no signature. Hephaestus therefore sets a secret for each subscription and derives it (rule 14).
- GitHub Docs, [About the setup URL](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-setup-url): "Bad actors can hit this URL with a spoofed `installation_id`." GitHub advises to "check that the installation is associated with that user". This is rule 8.

## Already priced and declined

- [ADR 0015](0015-unified-integration-framework.md#considered-options): extend `gitprovider/` for each new family.
- [ADR 0015](0015-unified-integration-framework.md#considered-options): one root for each family, with providers under it.
- [ADR 0015](0015-unified-integration-framework.md#considered-options): one `ingest/` package for webhook, OAuth, consumer and handler code.
- [ADR 0015](0015-unified-integration-framework.md#decision): a cross-vendor `IntegrationEvent` envelope. The inbox rows of ADR 0080 carry every provider.
- [ADR 0015](0015-unified-integration-framework.md#revisit-trigger): a generic graph for links across providers.
- [ADR 0023](0023-outline-documentation-integration.md#3-webhook_ingest-capability-on-the-unified-jetstream-lane): Outline on the shared `/webhooks/{kind}` JetStream lane, with a consumer for each stream.
- [ADR 0023](0023-outline-documentation-integration.md#3-webhook_ingest-capability-on-the-unified-jetstream-lane): a signing secret for each subscription, stored encrypted in the connection.
- [ADR 0023](0023-outline-documentation-integration.md#4-ssrf-posture-is-reuse-not-new-work): an SSRF guard only for Outline.
- [ADR 0023](0023-outline-documentation-integration.md#5-adr-0020-reconciliation--connector-superset-promotion-deferred): one `Connector` superset SPI for every content source.
- [ADR 0023](0023-outline-documentation-integration.md#6-privacy-divergence-from-messaging-ingestion): an opt-out for each author, an announcement in Outline, and app-level encryption of document bodies.
- [ADR 0023](0023-outline-documentation-integration.md#7-retention-driver-is-upstream-freshness-plus-a-size-cap-not-a-wall-clock): wall-clock retention of documents.
- [ADR 0023](0023-outline-documentation-integration.md#8-identity-linking-is-link-only-oauth-never-a-sign-in): Outline as a sign-in provider, the `openid` scope, and account match by email.
