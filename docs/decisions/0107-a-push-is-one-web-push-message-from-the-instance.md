# ADR 0107: A push is one encrypted Web Push message from the instance, sent at most once from the outbox, and it says only that something is ready

**Status:** Proposed
**Date:** 2026-11-13
**Authors:** Hephaestus maintainers

**Builds on:** [ADR 0098](0098-each-email-is-one-outbox-action-and-is-sent-at-most-once.md) (notification email and the outbox), [ADR 0079](0079-one-agent-system.md) (one outbox), [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (private practice feedback has one reader)

**Relates to:** [ADR 0108](0108-the-store-apps-receive-push-through-one-stateless-relay.md) (the relay for the store apps)

## Context

A developer sees practice feedback on the reviewed work, on their practice page, or in Slack.
Email carries a few notices ([ADR 0098](0098-each-email-is-one-outbox-action-and-is-sent-at-most-once.md)).
An answer in Ask Hephaestus can take minutes, because an agent run produces it.
When the person has closed the tab, nothing tells them that the answer is ready.
ADR 0098 names "a second notification transport, such as web push" as its revisit trigger.

The facts that set the design:

- **Browsers accept a push from any server.** With VAPID, an instance sends to a browser's push service without an account there. RFC 8291 encrypts the message with keys that the browser makes, so the push service sees only ciphertext. A subscription made with a sender key accepts only messages signed by that key ([RFC 8292][rfc8292] §4).
- **iOS delivers Web Push only to a web app on the Home Screen**, from iOS 16.4 ([WebKit][webkit-ios]).
- **A store app's push needs the publisher's APNs and FCM credentials.** A self-hosted instance cannot hold them, so [ADR 0108](0108-the-store-apps-receive-push-through-one-stateless-relay.md) adds a relay.
- **Push services cannot read back.** A `201` means that the service accepted the message ([RFC 8030][rfc8030] §5). After a gateway error or a broken connection, the sender cannot know. A `Topic` replaces only a message that the service still holds (§5.4), not one that the device shows.

## Decision drivers

- **Feedback earns trust or it is not sent.** A push never states a claim about someone's work, and never reports feedback that the pipeline withheld, paused or retracted.
- **Private practice feedback has one reader**, and a lock screen has many.
- **One sender for every endpoint:** browsers now, the store apps through the relay.
- **Minimum disclosure.** Push services and the relay see ciphertext and metadata, never content.
- **The outbox is the record of every outside effect.** Erasure and Silent Mode reach a queued push, and a duplicate costs more trust than a rare loss (ADR 0098).

## Considered options

1. **No push.** Email and Slack only. A finished answer stays unseen until the person comes back.
2. **APNs and FCM directly from each instance.** Not possible for the store apps, because the credentials belong to the publisher.
3. **A custom relay protocol.** With registered instances, as Zulip and Bitwarden have, the relay can bill and ban an instance, and Zulip's protocol is end-to-end encrypted. Each instance then needs a registration and a secret. Without registration, as Hermes and the Mattermost push proxy have, anyone with a device token can send. Either way, the envelope and its cryptography are ours to secure, and browsers still need Web Push.
4. **An ID-only alert, then a fetch on the device**, as Signal does. A visible alert wakes the app's notification extension, which fetches the content, so only an id leaves the instance. The extension needs a credential. Under strict refresh rotation ([ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md)), a refresh in the extension races the app and can revoke the session. The fetch must also finish within the extension's time budget.
5. **Web Push (RFC 8030, RFC 8291, RFC 8292) to every subscription (chosen).** Browsers receive the push from the instance directly. Store apps receive the same message through the relay of [ADR 0108](0108-the-store-apps-receive-push-through-one-stateless-relay.md). Mastodon sends push the same way.

## Decision

Option 5.

1. **The `notification` module owns push.** Each push is one `PUSH` action in the outbox, for one notice and one subscription. `PushAdapter` is its only adapter and the only writer to a push endpoint.
2. **A push is sent at most once.** `PUSH` is an `atMostOnce()` kind, like email. Only an answer that proves that nothing was accepted allows a retry.
   - Any `2xx` ends the action `DONE`.
   - `429`, and `503` with `Retry-After`, defer, but never past the kind's time to live, when the action expires. Each attempt sends as `TTL` the time that is left.
   - A connection that failed before the request was written retries.
   - `404` and `410` end it `FAILED` and delete the subscription.
   - `400`, `401`, `403` and `413` end it `FAILED`, keep the subscription, and raise the push outcome alert.
   - Any other answer, and a timeout after the request was written, end it `UNCERTAIN`.
3. **The protocol is RFC 8030, encrypted with RFC 8291 (`aes128gcm`) and signed with RFC 8292.** The instance makes its VAPID key pair at first start and keeps it in the database, encrypted like other credentials, so an operator adds no secret. An instance admin can rotate it. When the key changes, the instance deletes every subscription. Apps compare the published key with their subscription's key on start, and the web app does so when it loads. The code is about 200 owned lines on the JDK and the existing JOSE library ([ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md)).
4. **A subscription belongs to one account, one device and one session, and a device's choice outlives the session.**
   - It holds the endpoint and the device's public key and auth secret, stored encrypted like other credentials. It also holds a device label, a device id, and its session: a client session for an app, or the web session chain for a browser. The device id is random, made on the device for each instance and account, and kept there.
   - Any end of the session deletes the subscription, including expiry and another account's sign-in. A new subscription from the same session replaces the old one.
   - Settings list each device with a remove action. Removal turns push off for that device id, and the instance keeps that choice across sessions. A device asks the instance for its status first: `subscribed`, `none` or `turned off`. It asks before it makes a browser subscription or contacts a relay. A turned-off device keeps that answer and shows "Turn on notifications for this device". That separate call is the only way to turn push on again.
   - A device subscribes only when three things hold. The person has turned notifications on for it: on iOS and on Android 13 or later that is the OS permission, and on Android 12 or earlier the app's own switch. At least one kind is on, and the status is not `turned off`. A device that does not meet them makes no subscription, asks for no permission, and contacts no relay. The web app asks for the permission only on the instance origin, never on a workspace host.
   - The device checks on sign-in, start or load, and when the person turns notifications on. If the status is `none`, it reads the instance's current VAPID key, makes a new subscription with that key, and registers it. A browser unsubscribes its old subscription first. A device never registers an endpoint that the instance deleted. Each subscription records the id of its VAPID key, and the instance refuses a registration with a key that is no longer current. When the person withdraws the permission, the device deletes its subscription at its next check. In a browser, only the instance origin subscribes, and workspace hosts do not.
   - A device id ends when the device deletes its local state, for example when the person removes the instance in the app or clears the browser's site data. A device then counts as new, and an earlier removal no longer applies to it.
   - Account erasure deletes every subscription, device choice and queued push. The export holds the device labels and the choices, never an endpoint, a key or a device id.
5. **The instance sends only to allowed push services, through the core SSRF guard.** A chart value lists the allowed endpoint origins, as exact HTTPS origins or documented host suffixes. The default lists the browser push services and the official relay. `PushAdapter` uses `PrivateAddressGuard` on the URL and `SsrfGuardedResolverGroup` at connect. It follows no redirect, and it allows a private host only when the operator lists it. A subscription whose origin the operator removed stays, and is skipped until the origin returns. An empty list turns push off. A signed-in client can ask whether one relay origin is allowed. The instance answers yes or no, and it never shows its other origins. `docs/admin/network-egress.mdx` lists the destinations as optional, used only when a person subscribes, and the threat model gets a row for push endpoints.
6. **A push carries ids, and the client shows a fixed text.** The encrypted payload holds the version, the kind, the ids of the notice and its destination, and the subject key of rule 9, and no text. Each client shows the fixed text of the kind, such as "Hephaestus answered your question" or "You have new practice feedback". It builds the destination from the ids, as a destination of the Compatibility Policy § Posted links, which this change extends to push. No work title, observation, standing or quote appears in a push. The plaintext is at most 2,048 bytes, which fits the relay ([ADR 0108](0108-the-store-apps-receive-push-through-one-stateless-relay.md)).
7. **Two kinds at first, each with a person's own switch.**
   - `CONVERSATION_ANSWER`: the answer to a question that the person asked in an app or the web app. It goes out 30 seconds after the turn ends, if the person has not read the answer by then, with a time to live of one hour. A question asked in Slack keeps its Slack reply. It starts on once the person allows notifications on a device, and the app asks for that permission when the person first waits for an answer.
   - `PRACTICE_FEEDBACK`: one push for each review that delivered feedback to the person's practice page, with a time to live of one day. It starts off.

   A person turns each kind on or off for all their devices. A notice from before the latest switch-on is not sent, as for optional email. The server has no quiet hours, because Focus and Do Not Disturb on the device provide them. No kind uses Apple's `time-sensitive` level. A new kind needs an update of this ADR.
8. **The adapter checks again at send time.** The account is active and still a visible member of the workspace. The kind is on, and the subscription and its session exist. The subject is still current: the feedback is not withdrawn, and the answer is not read. Silent Mode ends the action `CANCELLED`, as for email. A subscription whose VAPID key is no longer current is deleted, and its action ends `CANCELLED`.
9. **Clients collapse by an opaque subject key.** The subject key is 32 base64url characters of an HMAC of the subject under an instance key. It goes in the `Topic` header and in the encrypted payload. Push services and the relay can link pushes about one subject, but cannot name it. The relay uses the header as the APNs collapse id. The service worker uses the decrypted key as the notification `tag`, and Android uses it as the notification id.
10. **The web app ships push first.** A service worker shows the notification and opens its destination. Settings explain that iOS needs the web app on the Home Screen, and that email and Slack stay the fallback.

## Consequences

- Good: one sender, one subscription model and one set of tests for browsers and both apps.
- Good: browser push needs no account anywhere, and no third party sees content.
- Good: a person who closed the tab learns that the answer is ready, which is the main gap today.
- Bad: a push can be lost after an `UNCERTAIN` attempt. No flow depends on a push as its only record, because the answer and the feedback stay in the app.
- Bad: browser vendors and the relay see the instance's key and IP address, the time and size of each push, and an opaque subject key.
- Bad: on iOS without the store app, push needs the Home Screen web app.
- Bad: browser push lasts only while the web session is valid. By default, it ends when the last unrenewed access credential expires, and never later than the 7-day session deadline. A later sign-in in that browser restores it, if the permission and the person's choices allow it.
- Bad: we own the RFC 8291 and RFC 8292 code.
- Bad: a new failure mode. The push outcome metric for each kind and result, its alert and its runbook row ship with rule 1 ([ADR 0071](0071-every-user-visible-failure-is-observable.md)).

## Confirmation

- `WebPushEncryptionTest` fails when the encryption does not reproduce the RFC 8291 example, or when a VAPID token lives longer than 24 hours.
- `OutboundEgressArchitectureTest` fails when a class other than `PushAdapter` writes to a push endpoint.
- `PushAdapterTest` fails when the status mapping of rule 2 breaks, when an unclear attempt is sent again, when a redirect is followed, when a name that resolves to a private address gets a request, or when Silent Mode does not cancel.
- `PushNoticeIntegrationTest` fails when withheld, paused or retracted feedback creates a push, when an answer that was read in time is sent, or when a notice from before a switch-on is sent.
- `PushSubscriptionIntegrationTest` fails when a subscription outlives its session or a key change, when a removed device subscribes again on its next start or sign-in, when a registration with an old key is accepted, when a subscription with an old key gets a push, when a device without permission or without a kind that is on subscribes, when a device removed elsewhere contacts the relay before it reads its `turned off` status, or when an erasure leaves a subscription, a device choice or a queued push.
- A client test on each platform replaces a shown notification using only the decrypted payload, and fails when a payload text would be shown.
- The kind texts of rule 6 are fixed strings in each client. Review of those strings catches a claim in a text.

## Revisit trigger

- Over four weeks, switch-offs of a kind reach a quarter of its switch-ons. The server counts both by kind, not by person.
- A feature needs a push that a person must act on within the hour, such as a request from a mentor.
- Browsers or app stores change how installed apps receive Web Push.

## Sources

- IETF, [RFC 8030][rfc8030]: `201` on acceptance, TTL, Urgency, a Topic that replaces a held message, and `404` for an expired subscription. This sets rules 2 and 9.
- IETF, [RFC 8292][rfc8292]: VAPID, and subscriptions restricted to one sender key. This sets rule 3 and lets the relay of ADR 0108 work without instance registration.
- WebKit, [Web Push for web apps on iOS and iPadOS][webkit-ios]: Web Push on iOS works for web apps on the Home Screen. This sets rule 10 and its limit.

[rfc8030]: https://www.rfc-editor.org/rfc/rfc8030
[rfc8292]: https://www.rfc-editor.org/rfc/rfc8292
[webkit-ios]: https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/
