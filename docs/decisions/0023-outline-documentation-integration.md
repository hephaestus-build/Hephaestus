# ADR 0023: Outline documentation integration — a content source, not a detection surface

**Status:** Accepted (amended 2026-08-08 — a document is a reviewable artifact)
**Date:** 2026-07-04
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0015](0015-unified-integration-framework.md) (integration framework), [ADR 0020](0020-context-fabric-everything-is-an-integration.md) (context fabric), [ADR 0014](0014-per-row-aes-gcm-aad-binding.md) (credential encryption), [ADR 0007](0007-sandbox-spi-shape.md) (sandbox SPI shape)

## Context

Team wikis (Outline) hold the design docs, ADRs, and decision records that a good engineering
practice produces — yet none of that ever reaches practice reviews. Mentor feedback on
"records significant decisions with rationale" or "documents public API and behaviour changes"
is blind to the very artifacts those practices are about. Making an allow-listed set of Outline
collections available as **context** lets existing practices reason over the docs a change refers
to, without inventing any new detection surface.

Outline is the first vendor in a documentation family (Confluence and Notion share the shape), and
the first integration that is purely a **content source**: it never emits observations or findings.
This ADR records the handful of decisions where Outline deliberately diverges from the SCM and
messaging integrations already in the tree.

## Decision drivers

- A documentation vendor extends the integration framework without forcing a change to the
  detection or feedback schema.
- The server calls an admin-supplied server URL, so server-side request forgery is a first-class
  threat that must be closed with the guards already in the tree, not a new bespoke one.
- Docs are deliberately-published, team-authored artifacts read by an admin-authorized bot against
  an explicit allow-list — a materially different privacy profile from live chat ingestion.
- Adding a vendor should stay flat in cost (ADR 0015) and not re-open settled abstractions.

## Decisions

### 1. A new family `DOCUMENTATION` and kind `OUTLINE`

`IntegrationFamily` gains `DOCUMENTATION` alongside `SCM` and `MESSAGING`, and `IntegrationKind`
gains `OUTLINE`. A family — not just a kind — because the documentation category spans multiple
vendors (Outline, Confluence, Notion) that share the same content-source shape, the same
`outline_document`-style mirror, and the same agent projection seam. Family membership is what lets
cross-cutting logic (context assembly, retention) treat "a documentation source" uniformly.

### 2. Content source, not detection surface

> **Partly superseded** — see the 2026-08-08 update at the end. A document is now a reviewable
> artifact kind. The module boundary this decision protects still holds.

Outline contributes context; it produces no observations, findings, or reactions. Pre-baked
Outline doc-quality practices are an explicit non-goal. Because nothing
touches the observation/finding schema, [ADR 0021](0021-observations-feedback-synthesis-seam.md) and
[ADR 0022](0022-observation-presence-assessment-and-schema-cleanup.md) are untouched. Documents are
mirrored into `outline_document` and projected to the agent through an agent-owned
`DocumentProjection` port; the agent read path carries no raw SQL against Outline's schema.

### 3. `WEBHOOK_INGEST` capability on the unified JetStream lane

Outline change notifications ride the same durable `/webhooks/{kind}` JetStream lane as GitHub and
GitLab. `OutlineManifest.declaredCapabilities()` is `{WEBHOOK_INGEST}`, so the module binds
`OutlineWebhookSignatureVerifier` (implements `WebhookSignatureVerifier`), `OutlineSubjectKeyDeriver`
and `OutlineSubjectParser`, which the framework bootstrap requires, plus `OutlineWebhookSecretSource`
(implements `WebhookSecretSource`, `Scope.SUBSCRIPTION`), which it does not — a vendor may resolve
its signing secret inside its own verifier instead. The registrar points
the subscription's delivery URL at `POST /webhooks/outline`; the webhook-role `WebhookController`
verifies the HMAC and publishes to the `outline` JetStream stream, and the server-role consumer fleet
subscribes to `outline.<subscriptionId>.>` and hands each event to `OutlineWebhookMessageHandler`
(an `IntegrationMessageHandler`) which reconciles the named workspace. Riding the durable lane is the
point: an app-server redeploy no longer drops in-flight deliveries (the reason ADR 0008 decoupled the
webhook role), and a handler failure reaches the poison handler (NAK + backoff, ACK-after-N) instead
of a swallowed catch.

Each delivery names its subscription in the body. That subscription id is an **untrusted routing
key**: `OutlineWebhookSecretSource` parses it out of the body (not a header, so the secret source is
`Scope.SUBSCRIPTION`) and resolves the stored signing secret of the ACTIVE Outline Connection that
registered it. A forged id simply matches no connection, so the HMAC over the exact request bytes
fails. Every document event drives the same whole-workspace reconcile — Outline has no per-document
refresh API — so `OutlineSubjectParser` collapses the flat event space onto one logical handler key
(`EventTypeKey(OUTLINE, "document")`) rather than registering identical per-event handlers, while the
specific event still rides the subject and dedup key for observability. The periodic sync remains the
source of truth; a webhook only improves freshness between reconciles.

**Signing-secret storage — encrypted at rest.** The per-subscription HMAC secret is a server-generated
256-bit random key, encrypted with AES-256-GCM (`EncryptedStringConverter`) before it is stored in the
Connection's `config` JSONB (`OutlineConfig.webhookSecret`), so the ciphertext sits next to the
subscription id it is keyed by in one atomically-updated record. The registrar encrypts before storing and
the subscription-scoped secret source decrypts at verification time; the plaintext exists only in memory
for the length of a delivery's HMAC check and is never logged. This matches the API token's at-rest posture
(both rely on `hephaestus.security.encryption-key`), so an operator with raw DB read access sees only
ciphertext. Outline is the first integration with a *per-tenant* webhook secret — GitLab and Slack verify
against one app-global `hephaestus.webhook.secret` property, so they never stored per-Connection signing
material — which is why the secret rides its own encrypted field rather than the credential bundle
(`credentials_encrypted`), which models only the single API token. Rotation is a de-register/re-register
of the subscription.

**Multi-stream consumer fleet.** A workspace may bind to more than one JetStream stream — its SCM
stream (`github`/`gitlab`) for repository/organization events and, independently, the `outline` stream
for documentation change notifications. `NatsSubscriptionProvider.NatsSubscriptionInfo` therefore
carries a `List<StreamSubscription>` (one `{streamName, subjects}` per stream) instead of a single
stream name, and the consumer fleet creates one durable consumer per `(scope, stream)` with a
stream-suffixed durable name (`…-scope-<id>-outline`) so SCM and Outline durables never collide. An
Outline-only workspace binds solely to the `outline` stream (a single-stream fallback would
mislabel it onto `github`).

### 4. SSRF posture is reuse, not new work

The admin-supplied server URL is validated with `ServerUrlValidator` and every request is issued
through the SSRF-guarded WebClient connector (`WebClientConnectors.ssrfGuarded()`, which closes the
DNS-rebind bypass). This is the same stack `GitLabPreflightService` uses for the identical
admin-supplied-host threat model. Recorded here so no future reviewer re-implements an Outline-specific
guard: the SSRF defense is a reuse of existing components, not bespoke code.

### 5. ADR 0020 reconciliation — Connector-superset promotion deferred

[ADR 0020](0020-context-fabric-everything-is-an-integration.md) §3 nominated Outline to trigger the
promotion of the content-source seam into a single `Connector` superset SPI. Outline instead uses the
shipped `ContentSource` seam plus the agent-owned `DocumentProjection` inversion, and consciously
**defers** that promotion: Slack already set the ad-hoc precedent for a non-SCM content source, and
the superset promotion is a separate cross-cutting refactor that this integration does not need to
carry. This closes the open consistency question rather than reopening it.

### 6. Privacy divergence from messaging ingestion

Outline docs are deliberately-published, team-authored artifacts read by an admin-authorized bot
against an explicit collection allow-list — legally distinct from ingesting private live
conversation. Consequently:

- **No per-person opt-out.** A document author cannot opt a shared ADR out of the team knowledge
  base; consent is the admin authorization plus the allow-list plus the connection audit trail.
- **No in-product announcement.** That would require a write scope the integration deliberately does
  not hold. The admin allow-list and audit record are the disclosure.
- **No body encryption at rest.** Only the API token is encrypted (per-row AES-GCM, ADR 0014).
  Document bodies are governed by retention and erasure, the same class as any mirrored content;
  app-encrypting a churning cache would break size accounting and debuggability.
- **Erasure parity is retained.** A workspace purge cascades into a bulk delete of the mirrored
  documents via a purge contributor ordered ahead of the connection teardown, and every sync cycle
  tombstones documents that vanished upstream.

### 7. Retention driver is upstream freshness plus a size cap, not a wall clock

A document that still exists upstream is live, admin-authorized reference material; deleting it on an
age clock only forces a re-sync. Retention is therefore driven by reconciling to upstream (tombstone
on delete) and a per-workspace least-recently-materialized size cap, with a hard staleness ceiling on
tombstoned rows as defense-in-depth. This is a deliberate divergence from the wall-clock retention the
messaging integration runs.

The broker is the exception. An Outline webhook envelope carries the document body, so a JetStream
stream retaining it for the platform default would keep document text that SQL erasure cannot reach.
The `outline` stream therefore takes the same 72-hour `max-age` as the Slack stream: long enough to
replay a delivery, short enough that the broker is a transit buffer and never a second copy of the
wiki. The mirror in Postgres is the only durable store, and it is what erasure acts on.

### 8. Identity linking is link-only OAuth, never a sign-in

Attributing a document to a Hephaestus member needs an Outline identity on the account. Outline
(v0.77+) exposes an **OAuth2** authorization-code provider — *not* OIDC: it issues no `id_token`, and
serves no userinfo or discovery endpoint. Identity therefore comes from `POST /api/auth.info` with the
member's access token, which returns `{data:{user:{id,…}, team:{id,…}}}`. The link is keyed on
`(OUTLINE, user-uuid, team-id)` — an Outline user UUID is only unique within its team, so the tenant
key is mandatory and a missing one fails the flow closed.

The provider is **link-only**, exactly like Slack: it never appears on the login picker and can only
attach an identity to an already-signed-in account (`ProviderType.isLinkOnly()`, re-checked at the
callback). A wiki is not an identity authority for this instance, and the scope requested (`read`) is
minimal. Email is captured for forensics and **never** matched to resolve an account — matching on it
would reopen the nOAuth takeover class (ADR 0017).

The scope grammar (`read | write | create | <ns>:<verb> | /api/<ns>.<method>`) admits `openid` as a
literal token, but Outline is not an OIDC provider: requesting it flips Spring to the OIDC path and
breaks the callback. The scope is therefore pinned to `read` by provider type, and `openid` is
rejected on write.

## Mechanism refinements

Two mechanisms refine the decisions above without changing them:

- **The collection allow-list is a live admin registry, not a static list.** "Allow-listed set of
  Outline collections" (Context, Decision 6) is implemented as the `outline_collection` table: an
  admin registers/pauses/resumes/removes individual collections through a dedicated control plane
  (`OutlineCollectionAdminService` + its REST surface), each row tracking its own sync status and the
  time of its last clean pass. The registry table — not a JSONB config field — is the single source of truth for which
  collections are mirrored; a paused collection stops syncing without losing its registration, and
  removal hard-deletes both the row and its mirrored documents.
- **Archive is tracked as a distinct, soft/recoverable state.** Decision 6 and 7's "tombstones
  documents that vanished upstream" describes the delete case only. `OutlineDocument`
  carries an `archivedAt` column separate from `deletedAt`: an Outline `documents.archive` event (or a
  sync pass) stamps `archivedAt` in place — the content stays mirrored and un-tombstoned — while
  `documents.unarchive` (or the document reappearing live) clears it. The sync service enumerates
  archived documents on a second pass so the tombstone-by-absence sweep never mistakes "archived" for
  "permanently deleted".

## Consequences

- A documentation vendor extends the framework with a family, a kind, a config subtype, a content
  source, and an agent projection — no schema change to detection or feedback.
- The webhook is authenticated by a per-subscription HMAC resolved in process and rides the unified
  `/webhooks/{kind}` JetStream lane on its own `outline` stream (Decision 3).
- The SSRF surface is covered by existing, tested guards.
- The next documentation vendor (Confluence, Notion) reuses the family, the mirror shape, the
  projection seam, and this privacy and retention posture.

## Update — 2026-08-08: a document is a reviewable artifact

Decision 2 said Outline "produces no observations, findings, or reactions" and named pre-baked
doc-quality practices an explicit non-goal. That no longer holds, and the code is the authority:
`DocumentArtifactDescriptor.reviewable()` is `true`, `docs.document.published` and
`docs.document.updated` are signals practices bind to, the bundled catalog ships practices bound to
them, and `DocumentReviewHandler` records observations against `docs.document`.

What changed is the framing, not the schema. A document is now one artifact kind among several,
reviewed through the same signal → binding → observation path as a pull request or an issue, and the
Outline module still produces nothing on its own: it mirrors documents and records signals, and the
agent module composes the review off that ledger. So the module boundary Decision 2 protects still
stands — `ADR 0021` and `ADR 0022` remain untouched, and the agent read path still goes through
`DocumentProjection`. Only the "not a detection surface" clause is superseded.

The privacy posture is unchanged: reviews read allow-listed collections only, and a document review
sees the document and nothing else.
