# ADR 0045: A native app on the existing sign-in, push as a nudge, and a contract per released version

**Status:** Accepted
**Date:** 2026-09-26

## Context

Developers read practice feedback and talk to Heph where they are, which is often not at a desk. A
native iOS and Android app has to sign in to any self-hosted instance, stay signed in across process
death and days of idleness, reach a developer when feedback is waiting, and keep working against
servers that upgrade on their own schedule — while the app on a phone does not. The web session
([ADR 0017](0017-replace-keycloak-with-spring-native-auth.md)) is an HttpOnly cookie a native client must not
extract, refreshed by the access token itself, which expires after a day of idleness.

## Decision drivers

- Reuse the instance's sign-in providers, revocation, session deadline and consent, with no second
  source of "this session is live".
- No token in a URL; a browser credential never becomes an app credential.
- A notification can appear on a locked screen and must not turn into a delivery nobody saw.
- Self-hosted instances cannot hold the app publisher's push credentials by default.
- An installed binary cannot be upgraded by the server that breaks it.

## Considered options

1. A PKCE-bound, single-use handoff from the existing Spring OAuth login, then a native refresh
   secret with strict rotation.
2. An embedded OAuth authorization server (Spring Authorization Server).
3. Native OAuth directly against GitHub and GitLab, exchanged for a Hephaestus token.
4. Refresh by access token only, as the web does.

## Decision

**Sign-in is option 1.** The app opens the instance's own login in the system browser with an S256
challenge and state; the server returns a 60-second, one-use code to an exactly allowlisted callback,
and the app redeems it with the verifier for an access token and a rotating refresh secret. A second
authorization server (2) would be a second session store beside `issued_jwt`; per-instance provider
registrations (3) break self-hosted GitLab and duplicate provisioning; access-token refresh (4) signs
a developer out after a day away although their seven-day session is live. [Auth architecture § Native
app sessions](../auth-architecture.md#native-app-sessions) owns the protocol, including strict rotation
with no grace period: a lost refresh response means signing in again.

**Push is an optional nudge, not a channel.** A notification says only that feedback is waiting,
coalesced per device, workspace and hour, and is checked against the session, consent and membership
when sent. Opening the feedback delivers it, as it does without push. Push goes straight to Expo with
enhanced push security, from instances that hold the publishing project's Expo access token: the
publisher's own, or an organisation's that publishes its own build. That token is an account
credential with no push-only or per-instance scope, so it is never handed to other self-hosters; their
instances run without push. A restricted delivery service for them would be a separate decision. [Mobile app § Push notifications](../admin/mobile-app.mdx#push-notifications) owns the
behaviour, and the [feedback language](../contributor/practice-feedback-language.md) the words.

**The app leads with where the developer stands in their practices, and practice feedback has its own page.**
Two tabs, Practice and Heph; the account opens from the avatar on each rather than taking a tab. The
Practice tab shows where the developer stands in each practice group, from the catalog and their own
standings, down to each observation's evidence. Reading in-app feedback delivers it, so only the
feedback page reads it, and nothing else counts or previews it. Android keeps two destinations
although Material asks for three to five in a navigation bar: a third tab would be invented to meet the
count. [Mobile § Tabs](../contributor/mobile.mdx#tabs) owns the structure.

**Each released app version records the API it calls,** and oasdiff's breaking-change rules run from
every recorded version to the current spec. Comparing only with `main` would lose a break that landed
between releases. A version's contract is retired only by raising the server's minimum app version.
[Mobile § API compatibility](../contributor/mobile.mdx#api-compatibility) owns the procedure. Signed
over-the-air updates use a fingerprint runtime version, which guards native compatibility only.

## Consequences

- The web session is unchanged; native endpoints refuse cookies, and browser refresh refuses native
  tokens.
- A server that must break an old app says so by raising its minimum app version, and the old app
  shows an update prompt rather than failing on a request.
- Self-hosted instances using the store app get no notifications; the app says so, and feedback still
  waits under Practice feedback.
- The mentor stream is hidden from the spec, so its wire shape is outside the contract gate.
- Releases need accounts the project does not yet hold: [Mobile § Releases](../contributor/mobile.mdx#releases)
  lists them, and the release workflows refuse to run without them.

## Revisit trigger

A publisher-operated push relay for self-hosted instances; a server that can resume a Heph turn after
the app is backgrounded; or an app release that needs an API break the minimum-version mechanism
cannot express.
