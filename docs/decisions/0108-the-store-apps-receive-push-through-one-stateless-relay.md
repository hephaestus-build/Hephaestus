# ADR 0108: The store apps receive push through one stateless Web Push relay of the publishing organization

**Status:** Proposed
**Date:** 2026-11-13
**Authors:** Hephaestus maintainers

**Builds on:** [ADR 0107](0107-a-push-is-one-web-push-message-from-the-instance.md) (a push is a Web Push message), [ADR 0105](0105-one-store-app-signs-in-to-any-instance-by-its-address.md) (one store app for every instance), [ADR 0106](0106-the-mobile-apps-release-with-the-stores-own-tools.md) (the mobile release line)

**Relates to:** [ADR 0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md): the relay image keeps its build evidence, but no instance lock names it

## Context

APNs accepts a push for an app only from a holder of the publisher's signing key, and FCM only from the app's Firebase project.
One store app serves every self-hosted instance, so a service that holds those credentials must forward each push.
Zulip, Mattermost, Nextcloud, Rocket.Chat, Bitwarden and Home Assistant all run such a relay for their store apps.
In most of them, a self-hoster who refuses the publisher's relay needs an own app build.

The relays differ in three ways:

- **How a server proves itself.** Zulip and Bitwarden issue a credential to each server ([Zulip][zulip-push]). The Mattermost push proxy and Hermes accept any sender. Home Assistant, Matrix and Mastodon let the app give the server a capability URL.
- **What the relay sees.** Content in clear (Home Assistant, and Rocket.Chat by default), IDs only (Mattermost's ID-only mode), or ciphertext (Zulip from Server 12.0, Nextcloud, Web Push).
- **What it stores.** Hermes stores nothing. Zulip keeps a registration for each device.

Hermes (`ls1intum/Hermes`) is the relay of the Artemis apps. It is small, MIT-licensed and stateless, with separate APNs and FCM send services ([Hermes][hermes]).
It cannot serve another app as it is.
Its APNs topic is one hard-coded bundle id, and it has no sender authentication, so anyone who holds a device token can push through it.
The protocol that it carries, from the Artemis server, uses AES-CBC without a MAC.

Under RFC 8292, a restricted subscription accepts only the key that made it (§4.2).
That rule binds a sender, but it does not prove who asked for the subscription.

## Decision drivers

- Store apps get push from any instance, and the instance signs up for nothing.
- A device receives pushes only from a sender key that the app on that device subscribed to.
- The relay keeps no database of devices and never sees content.
- The relay speaks the protocol that the instance already speaks ([ADR 0107](0107-a-push-is-one-web-push-message-from-the-instance.md)).
- A self-hoster can run the whole path without the publishing organization.

## Considered options

1. **No native push.** The store apps rely on the web app's push. iPhone users without the Home Screen web app get nothing.
2. **Reuse Hermes as it is.** It cannot address another bundle id, and it leaves the authentication and integrity gaps open.
3. **Contribute to Hermes, so that it becomes a shared relay.** It would need app ids, credentials for each app, sender authentication, and a Web Push entry beside its own protocol. The two products would then share one deployment, one incident process and one release process.
4. **Fork Hermes and replace its envelope.** The fork keeps the APNs and FCM send services and adds the Web Push entry, the challenge and the seals of option 6. It is feasible. It is a second repository on its own toolchain (Java 25) and its own release and signing process, for a few files of dispatch code.
5. **A registration relay, as Zulip has.** Each instance registers and gets a credential, so the relay can limit and ban an instance as an account. Each instance needs a registration step, a secret and terms to accept, and the relay needs a database.
6. **A stateless Web Push relay in this repository, which mints endpoints after a challenge (chosen).** The relay turns a Web Push request into an APNs or FCM request. An app gets an endpoint only after it proves that it receives pushes for its device token, and RFC 8292 then binds the endpoint to one sender key. The relay starts from Hermes' dispatch design and may reuse its code under the MIT license, on this repository's toolchain and release process.

## Decision

Option 6.

1. **The relay lives in this repository as `push-relay/`.** It is Java on the server's toolchain. It releases on the mobile version line ([ADR 0106](0106-the-mobile-apps-release-with-the-stores-own-tools.md) rule 3). CI builds its image once with the evidence of ADR 0092, and the release signs it, but no instance lock names it and no Helm chart deploys it. The publishing organization runs one deployment of a signed release for the official apps.
2. **An endpoint is minted after a challenge.**
   - The app asks for a challenge with its platform, app id, APNs environment and device token.
   - The relay sends the challenge as a push that shows nothing (an APNs background push, or an FCM data message at normal priority, which the app receives while it is open). The challenge is an HMAC under the relay's key, and it expires after five minutes.
   - The app returns the challenge with the instance's VAPID public key. The relay checks the HMAC, then returns `https://<relay>/v1/push/<sealed>` and its expiry.
   - The seal is AES-GCM under a key with a key id, which is associated data. It holds the platform, the app id, the APNs environment, the device token, the VAPID key, and an expiry 30 days ahead.

   The app gives the endpoint to the instance as a normal Web Push subscription. The instance never sees the device token, and a token thief cannot answer a challenge that only that device receives.
3. **Only the subscribed sender can push.** The relay accepts a push only with a VAPID token whose signature matches the sealed key, whose `aud` is the relay's origin, and whose `exp` is within 24 hours. It answers `401` when the token is absent and `403` when it is invalid.
4. **The relay forwards the RFC 8291 body unchanged, in a constrained bridge profile.**
   - APNs gets an alert with a placeholder that the app localizes, `mutable-content`, and the `Topic` as collapse id. The app's notification extension decrypts the body and shows the fixed text of its kind.
   - FCM gets a data message without a collapse key, because FCM keeps at most four collapse keys for each device. The app decrypts it and replaces an earlier notification with the same subject key.
   - `high` and `normal` urgency become APNs priority 10 and FCM high priority, and `low` and `very-low` become 5 and normal. `TTL` becomes the expiry, capped at the seal's remaining life, and for FCM also at 28 days, the longest that FCM accepts. A `TTL` of 0 stays 0.
   - The relay answers `413` when the encoded APNs or FCM payload would exceed 4,096 bytes. RFC 8030 §7.2 asks a push service to accept 4,096 bytes of body, and this relay deviates on purpose. ADR 0107 keeps the plaintext at 2,048 bytes or less.
5. **The relay answers after the upstream does.** It returns `201` when APNs or FCM accepted the push, and `504` when the upstream result is unknown. It returns `429` or `503` only before it forwards, and `429` with `Retry-After` when APNs or FCM throttles. Another definite refusal by APNs or FCM becomes `400`. An APNs `410` or `BadDeviceToken`, an FCM `UNREGISTERED`, an expired seal and a retired sealing key all become `410`, and the instance then deletes the subscription.
6. **Endpoints renew, and sealing keys rotate.** An app mints a new endpoint when less than 15 days are left, when its sealing key is retired, or when the instance no longer has its subscription. It checks on start, and from background refresh where the OS allows it. The relay publishes its current key ids. A sealing key stops minting when its successor starts, and it stops opening seals 30 days later. An emergency retirement ends all its endpoints at once.
7. **Limits bound the challenge and the push paths.**
   - Before any work for a token, the relay checks a challenge limit for each source (an IPv4 address or an IPv6 /64), sized for carrier NAT, and a global challenge limit.
   - Counters key on an HMAC of the device token. Each sender key gets 500 pushes a day for each device, and each device gets 2,000 a day in total. One sender key cannot use up another key's budget. An instance that rotates its key can hold several keys, so the device total, not the key budget, is the hard bound.
   - A challenge does not count against the token, so a stranger cannot lock a device out.
   - The counters live in a bounded in-memory table whose entries expire after 48 hours. When the table is full, the relay refuses new challenges and pushes for devices without an entry.
   - Every `429` and `503` carries `Retry-After`. The deployment runs one replica, and a restart resets the counters.
   - The deployment's configuration can block a device HMAC or a VAPID key for 30 days, and a person can ask to lift a block.
8. **After an instance is removed, its pushes are bounded, and the app drops what it cannot decrypt.** The instance can send until its endpoint expires, at most 30 days. APNs may still deliver an alert that it accepted before then. Android drops a push that it cannot decrypt. iOS shows the placeholder, unless Apple grants the notification filtering entitlement, which the publishing organization requests.
9. **Logs and metrics hold no token, endpoint or body.** The relay's logs and the access logs of its deployment omit the path after `/v1/push/`. They hold the platform, the status and a VAPID key thumbprint, and they are kept for 14 days. No metric label holds a sender key. The relay's outcome metric, its alert and its runbook row ship with it.
10. **The relay serves production apps only after its data protection role is decided.** The assumed role is an independent controller for the push service that the publishing organization offers to app users. A legal review confirms or replaces it, and a privacy notice is published first. The notice lists the challenge request (the app's IP address, device token and platform), the push metadata (the instance's IP address, VAPID key, subject key, time and size), the counters and blocks, the logs, and the transfer to Apple and Google.
11. **Self-hoster choices.** The admin guide documents three, and it tells operators that the official relay receives push metadata, which they name in their own privacy notice:
    - Use the official relay. This is the default.
    - Run the relay image with an own APNs key, FCM project and app build. Then list that app ([ADR 0105](0105-one-store-app-signs-in-to-any-instance-by-its-address.md) rule 5) and the relay's origin ([ADR 0107](0107-a-push-is-one-web-push-message-from-the-instance.md) rule 5).
    - Remove the official relay from the endpoint list. Browser push still works.

## Consequences

- Good: an instance needs no registration and no relay secret. A device gets pushes only from a sender key that its own app subscribed to.
- Good: the relay has no database. It processes a token only to answer a challenge or forward a push, and it never sees content.
- Good: the relay is generic. Another product could use it with an app entry of its own.
- Bad: the publishing organization runs a public service, with uptime, abuse handling, key custody and a privacy notice.
- Bad: the relay cannot ban an instance as an account. An instance that changes its VAPID key loses its subscriptions, but its signed-in users' apps subscribe again with the new key. A person stops an unwanted sender by signing out or by turning the kind off.
- Bad: an app that the person does not open, and that the OS gives no background time, loses push after 30 days until it is opened again. Push also ends with the mobile session ([ADR 0105](0105-one-store-app-signs-in-to-any-instance-by-its-address.md) rule 6).
- Bad: a leaked sealing key exposes the device tokens in that key's endpoints, and its holder can mint endpoints for any token it knows without a challenge. It sees no content. Both stop when the key is retired.
- Bad: one replica with counters in memory is a single point of failure, and its limits are approximate.

## Confirmation

- `RelayChallengeTest` fails when an endpoint is minted without a valid, unexpired challenge for the same token, app and environment, or when a flood of distinct tokens passes the source and global limits.
- `RelayAuthorizationTest` fails when a push without a token gets other than `401`, or when a push with another key, another `aud` or a late `exp` gets other than `403`.
- `RelaySealTest` fails when a changed seal, an expired seal or a retired key opens.
- `RelayForwardingTest` fails when the body changes, when the mapping of rule 4 breaks, when an FCM message carries a collapse key or a TTL over 28 days, or when an unknown upstream result is not answered with `504`.
- An app test fails when the app does not mint and register a new endpoint after an emergency retirement of a recently minted one.
- `RelayLoggingTest` fails when a device token, an endpoint or a body appears in the relay's logs. The deployment checklist covers the access logs.
- A contract job runs the server's `PushAdapter` against the relay image, with fake APNs and FCM endpoints.
- Rule 10 is not enforced by code. The production deployment checklist holds the legal review and the published notice.

## Revisit trigger

- Abuse that the per-device and challenge limits cannot stop. Then price option 5.
- Another product asks to share the relay. Then add an app entry, and price one deployment for both.
- Challenges often fail on a platform, for example because background pushes arrive late.
- FCM's Android configuration v2 leaves preview, or high-priority v1 data messages are delayed.
- The legal review requires a processing agreement with each instance operator.

## Sources

- IETF, [RFC 8292][rfc8292] §4.2: "A push service MUST reject a message sent to a restricted push message subscription" unless the key matches the one at subscription. This binds an endpoint to one sender (rule 3). It proves nothing about who asked, so rule 2 adds the challenge.
- Zulip, [Mobile push notification service][zulip-push]: a registration relay with end-to-end encryption, and a documented list of what the relay still sees. This prices option 5 and sets the bar for rules 9 and 10.
- Hermes, [source][hermes]: a stateless relay with one hard-coded topic, no sender authentication, and separate APNs and FCM send services. This rules out option 2, prices options 3 and 4, and gives option 6 its dispatch design.

[rfc8292]: https://www.rfc-editor.org/rfc/rfc8292
[zulip-push]: https://zulip.readthedocs.io/en/latest/production/mobile-push-notifications.html
[hermes]: https://github.com/ls1intum/Hermes
