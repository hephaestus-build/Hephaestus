# ADR 0135: Every client and channel uses one Hephaestus API

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0113](0113-the-mobile-apps-are-native-clients-of-the-server-api.md) rules 4 and 5 (native clients use the account stream)

**Builds on:** [ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) (addition only after 1.0), [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md) (installed clients), [ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md) (the one list of breaks before 1.0)

**Relates to:**

- ADR 0120 (proposed in #4862): threads and inbox items.
- ADR 0121 (proposed in #4862): typed commands on item cards.
- ADR 0123 (proposed in #4862): Slack as a channel.
- ADR 0134 (proposed in #4863): the scope epoch and what changes it.
- ADR 0137 (proposed in #4863): where an On request result may show.
- [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md): the push payload.

## Context

The conversation now has threads, item cards, tasks and messages that Hephaestus starts.
The web app, the extension, the Slack adapter and server jobs all show or change these objects.
On main, the conversation has its own stream for each workspace, and a turn carries its idempotency key in the body.
Some errors carry their reason only as English text.

[ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) promises each public operation from 1.0 until 2.0.
So every public operation that 1.0 adds is a promise for years.
The real constraint is one contract that every client and channel can use, which is small enough to keep.

## Decision drivers

- One contract: no client or channel has a private path to the data.
- A client can always bring a cached object back to the truth. This includes a revoke and an erasure.
- A retry never creates a second object.
- No public shape exists without a caller at 1.0.
- Clients in Swift and Kotlin can generate code from the spec with no transform.

## Considered options

1. **An API for each client or channel.** A backend for the extension, one for Slack and one for mobile. Each has its own rules for revokes and retries, and each is a second contract to keep until 2.0.
2. **GraphQL with subscriptions.** It adds a second schema system beside OpenAPI. The generators and the compatibility gate of ADR 0103 do not cover it.
3. **Replay by entry sequence for each conversation.** A client replays entries after its last sequence. A delete or a scope revoke has no entry. So a client keeps data that its person may no longer see.
4. **Reserve the 1.x shapes now.** Voice, the member MCP endpoint and actions get empty operations and fields in 1.0. This is future-proofing for callers that do not exist, and each reserved shape is a promise.
5. **One API, one change feed for each account, standard HTTP mechanisms, and no reserved shapes (chosen).**

## Decision

Option 5.

1. **Every client and channel uses the Hephaestus API.** The web app, the extension, the Slack adapter and server jobs use the same operations. The Slack adapter and jobs call the commands behind them. `server/openapi.yaml` is the one contract.
2. **Every 1.0 operation names its caller.** The caller is the web app, the extension, the Slack adapter, a server job or an operator script. An operation without a 1.0 caller is not in 1.0. 1.x adds it as an addition.
3. **An operation is public when an installed client or a third party calls it.** At 1.0 these callers are the extension and operator scripts. All of today's public operations stay public. New administration operations are internal.
4. **No reserved shapes.** 1.0 keeps stable IDs and open enums only. Voice, the member MCP endpoint, native apps and actions arrive in 1.x as additions.
5. **One change feed and one account stream.**
   - Durable objects, item cards and tasks included, travel only as typed changes. An eviction is a real feed operation.
   - `GET /api/user/events` replaces the conversation stream of each workspace.
   - Every frame carries the feed epoch and the scope epoch. This includes each answer delta.
   - A narrowing changes the scope epoch (ADR 0134, proposed in #4863). The gateway then drops every answer delta of the old epoch.
   - The eviction frame goes out before any frame of the new epoch.
   - ADR 0137 (proposed in #4863) decides where an On request result may show. Item cards, practice pages and changes follow it.
6. **Standard sync and retry mechanisms.**
   - The stream resumes with `Last-Event-ID`.
   - One problem type, `must-refetch`, tells a client that either epoch moved.
   - Every `POST` that creates an object or records an act needs an `Idempotency-Key` header. A reused key with a different payload gets 422. A key whose first request still runs gets 409.
   - A key is scoped to the actor, the operation and the workspace. The server keeps it for 72 hours after its first request.
   - A retry in that window applies nothing again. It re-authorizes the caller and renders the current data that the caller may read.
   - After 72 hours the key is gone, and the request is a new request. This holds for every create operation.
   - `PUT` and `DELETE` are idempotent by HTTP. `If-Match` guards a revision, with 412 for a stale tag and 428 for a missing header.
7. **Problem `type` slugs are the only error codes.** Labels are a code with parameters and a fallback text. Every new enum is open, and no public schema uses `oneOf`.
8. **Cards carry typed commands.** A control returns a preview with an operation ID. The confirmation binds the exact facts of that preview. A changed fact gets `preview-stale`.
9. **No model-made URL is fetched, unfurled or shown as an image** in any client or channel. Slack posts set `unfurl_links` and `unfurl_media` to false.
10. **A generated diff counts the contract.** A script compares the spec of the packaged JAR with the public list. Nobody counts operations by hand. The API reference publishes the retry rules of every operation when the API ships.
11. **Native apps use the account stream when they ship.**
    - The route is `GET /api/user/events`. It stays outside OpenAPI, as the conversation stream does on main.
    - The `data` of each frame is a typed change or an answer delta. Each is a schema of the spec, and the app decodes it by its event name.
    - The app resumes with `Last-Event-ID`. After `must-refetch`, it reads its objects again and opens the stream with no ID.
    - The app skips an unknown event name, as ADR 0103 rule 5 says.

### What this changes in other ADRs

- [ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md) gets three breaks on its list when this ADR is accepted. The turn's idempotency key moves from the body to the header. The conversation stream of each workspace goes. `requestReview` needs an authorization context for the act on the work and an `Idempotency-Key`.
- [ADR 0113](0113-the-mobile-apps-are-native-clients-of-the-server-api.md) rule 4 says that the native apps use the conversation stream and its resume rules. When this ADR is accepted, they use the account stream of decision 11.
- ADR 0113 rule 5 lists the conversation stream among the documented routes outside OpenAPI. The account stream takes its place.
- ADR 0103 stays as it is. This ADR applies its rules to the new operations.

## Consequences

- Good: a client stays correct after a revoke, an eviction or a restore. Every change travels as a feed operation.
- Good: a Swift or Kotlin client generates from the spec. An unknown enum value or problem type does not break it.
- Good: the public contract grows only with callers. A 1.x feature is an addition.
- Bad: three breaks before 1.0. The extension updates in the same release.
- Bad: the Slack adapter must use the API's commands, not its own repository calls.
- Bad: an object that has no change-feed type cannot reach an installed client.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- The closure diff script fails when the public list, the generated spec and the documented counts differ.
- The compatibility gate of ADR 0103 decodes an unknown change operation, object type and problem type.
- IdempotencyKeyIntegrationTest fails when a create `POST` without the header succeeds, or a reused key with another payload does not get 422. It also fails when a retry within 72 hours creates a second object.
- A stream test fails when an answer delta of an old scope epoch reaches a client.
- A stream test fails when an eviction frame follows a frame of the new epoch.
- A test for each client and channel fails when a model-made URL is fetched, unfurled or shown as an image.

## Revisit trigger

- A client needs an object that the change feed cannot carry.
- A 1.x feature cannot ship as an addition.

## Sources

- IETF, [The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/). It gives the header, 422 for a reused key with another payload and 409 for a request in progress.
- RFC 9457, [Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457). The `type` member is the machine-readable code. This gives decision 7.
- WHATWG, [Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html). `Last-Event-ID` resumes a stream. This gives decisions 6 and 11.
