# ADR 0066: The webhook receiver has no database

**Status:** Accepted
**Date:** 2026-10-26
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0008](0008-webhook-runtime-role.md) § Consequences (DB connection retained on the webhook pod) and § Residual risks accepted (DataSource on the webhook pod), and [ADR 0023](0023-outline-documentation-integration.md) § 3 (the Outline signing secret).

## Context

The webhook role accepts provider deliveries and publishes them to NATS JetStream (ADR 0008).
It could not start while PostgreSQL was down.
A restart of the receiver during a database restore, a PostgreSQL upgrade or a slow node reboot therefore stopped reception.
GitHub does not retry a failed delivery by itself, and GitLab drops a failed push event.

The role started the whole application minus a few gated beans, and most of those beans needed the database.
Three things made the role depend on PostgreSQL:

1. **The context itself.** The DataSource, JPA, Liquibase, the event publication registry and the start-up schema wait connect at start.
   Nearly every ungated bean of the application depends on a repository.
2. **Outline.** The signing secret of each change-notification subscription was stored encrypted on the connection.
   The receiver looked it up by the subscription id in the delivery body before it verified the signature.
3. **Slack.** Before it published a channel message, the receiver read the channel's consent state.
   It drops a message without consent so that its content is never stored.

GitHub and GitLab verify with instance configuration only: the shared webhook secret, and the GitLab route credential, which already binds a connection without a database.

## Decision drivers

- The receiver must survive a server restart (ADR 0008) and a database outage.
- Tenant secrets never go to NATS in clear text.
- Channel content without consent is not stored, not even in a stream.
- Prefer the smallest design that keeps the receiver stateless.

## Considered options

For each reason the receiver read PostgreSQL:

1. **(a) Verify at the edge with instance configuration only.**
2. **(b) Publish the raw body and headers, and verify in the server consumer**, where the tenant data lives.
   Unverified deliveries stay bounded by the stream limits.
3. **(c) A read-only projection that the server publishes** to NATS, which the receiver reads.
4. **Ask the server synchronously**, as GitLab Workhorse asks Rails before it takes over a request.
   Rejected: the receiver exists to keep receiving while the server restarts.
5. **A lazy DataSource**: start without a connection and connect on the first Outline or Slack delivery.
   Rejected: it keeps every database bean in a process that has no use for them, and an Outline or Slack delivery still fails during an outage.

Comparable edges:
[Sentry Relay](https://docs.sentry.io/product/relay/modes/) in managed mode reads project configuration that it polls from Sentry, never from Sentry's database, and discards a project's data when Sentry cannot provide that configuration (option c).
In proxy mode it forwards events with minimal processing (option b).
[Svix](https://www.svix.com/resources/glossary/webhook-secret/) gives each endpoint its own signing secret, so a leak or a rotation touches one endpoint only.
[GitLab Workhorse](https://docs.gitlab.com/development/workhorse/) has no database and asks Rails before it takes over a request (option 4).

## Decision

**The webhook role has no database by construction.**
A process that runs only the webhook role scans only the receiver's components (`core.runtime.WebhookReceiverScope`).
`application-webhook.yml` removes the DataSource, JPA, Liquibase and the event publication registry.
The receiver needs no database settings and no encryption keys.

**Outline: (a).** The signing secret of a subscription is `HMAC-SHA256(routing secret, label ‖ route id)`.
The routing secret is the instance secret `hephaestus.webhook.routing.secret`; the route id is 128 random bits.
HMAC-SHA256 is a pseudorandom function keyed by the routing secret, the construction that HKDF-Expand uses.
Thus, knowing the URL does not let anyone compute the secret, and one route's secret reveals nothing about another route's secret.
The subscription posts to `/webhooks/outline/{keyId}/{routeId}`, which is the only Outline endpoint.
The receiver recomputes the secret from the routing secret and the URL, and verifies the delivery.
The connection stores the route, not a secret.
The receiver publishes the verified route in the `Hephaestus-Route` header.
The server drops a delivery whose route is not the one that its subscription registered, so a delivery signed for one connection cannot name another connection's subscription.
The full sync replaces a subscription that is not on a current route: one without a route, on another URL, or on a retired routing key.
This is the same model as the GitLab route credential, and like Svix every subscription has its own secret.

**Slack: (c).** The server writes the `ACTIVE` channels to the NATS key-value bucket `slack-channel-consent`, keyed `<teamId>.<channelId>`.
A consent change updates its key after commit.
A rebuild at start and every minute repairs erasures, purges and updates lost to a NATS outage.
The receiver fails closed: a missing key drops the channel message, and a missing or unreadable bucket fails the delivery, so nothing is published and Slack retries.
A consent change through the consent service reaches the receiver right after its commit.
An erasure, a disconnection, or an update lost to a NATS outage reaches it through the rebuild, at most one minute later.
Updates and rebuilds run one at a time, so a rebuild never restores a key that a later change removed.
The bucket holds Slack team and channel ids with the state `ACTIVE`: no message content, no token, no secret.
The server still checks consent when it stores a message.
Option (b) was rejected for Slack: a webhook stream keeps a message for 7 days, so content without consent would be stored for that long.

**GitHub and GitLab: (a), unchanged.**

## Consequences

- The receiver starts and accepts deliveries of every provider while PostgreSQL is down.
  `WebhookRoleContextIntegrationTest` boots the role with no reachable database and posts GitHub, Outline and Slack deliveries.
- The webhook Pod needs no database credentials, no encryption keys and no network path to PostgreSQL.
- A component that the receiver needs must be in `WebhookReceiverScope`.
  `WebhookReceiverScopeArchitectureTest` fails when a webhook-role component, verifier, deriver, publish gate or secret source falls outside it, or when a listed name no longer names a class.
- The receiver no longer waits for the schema migration. It reads no schema.
- The shared `/webhooks/outline` path is gone. An Outline subscription registered before this change posts there and is refused; the next full sync of its workspace registers it again on a route. The release note tells operators how to start that sync at once.
- A Slack consent change reaches the receiver after its commit, or after at most one minute through the rebuild.
  Until then, a paused channel's message can be published, and the server drops it when it checks consent.
- Rotating `hephaestus.webhook.routing.secret` moves Outline subscriptions to the new key at the next reconcile, while `previous-secret` keeps the old routes verifiable.

## Revisit trigger

A provider whose deliveries cannot be verified without per-tenant data that the server can neither derive from instance configuration nor publish to NATS without a secret.
