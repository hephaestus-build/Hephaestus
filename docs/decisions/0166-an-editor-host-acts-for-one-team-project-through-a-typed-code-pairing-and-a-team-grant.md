# ADR 0166: An editor host acts for one team project through a typed-code pairing and a team grant

**Status:** Proposed
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md)

**Builds on:** [ADR 0165](0165-a-team-reviews-immutable-submitted-versions-within-its-own-scope.md), [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md)

**Relates to:** [ADR 0110](0110-a-workspace-admins-mcp-client-configures-practices-through-the-workspace-mcp-endpoint.md)

## Context

A team member works in an editor: FigJam, Figma Design, and later the Apollon editor.
The editor plugin (the host) must request a team review and show its status (ADR 0165).
A host has no redirect address, so the handoff of ADR 0048 does not fit it.
A plugin runs in an iframe whose origin is `null`, and it must not hold the member's account credentials.
Each host must not get its own sign-in.

## Decision drivers

- One mechanism for every host.
- A host can act only for one team project, and only while the member is a current member of that team.
- The member's account session never leaves the browser.
- A stolen host credential gives little and expires soon.
- Every path that ends access ends the host's access too.

## Considered options

1. **A new kind of installed-client session (ADR 0048).** It would reuse the revocation of account sessions, but it would act as the account in every workspace. A team-scoped credential would then depend on a route check, not on its own store. Rejected.
2. **The device authorization grant (RFC 8628) through Spring Authorization Server.** It is a standard, but ADR 0088 rule 2 keeps Hephaestus free of an authorization server, and it adds stores that no other client needs. We take its typed-code design, not the server.
3. **A token that the member copies into the plugin.** It skips the browser sign-in, it can be long-lived, and it is copied by hand. Rejected, as in ADR 0048 option 3.
4. **A typed-code pairing that issues a team grant in its own store (chosen).**

## Decision

1. **Two credentials, kept apart.**
   - The **account session** (ADR 0048 and ADR 0088) only approves a pairing. It never reaches the host.
   - The **team grant** is its own credential in its own store. It is not an account client session, and it has no `client_session` row.
   - The host API refuses every account session, cookie or bearer. Every other route refuses a team grant.
2. **Pairing.**
   - The host creates a pairing request with an S256 code challenge and its host kind. The request lives 5 minutes.
   - The server returns a pairing id and an 8-character user code from an alphabet without look-alike characters.
   - The host shows the code and opens `/pair`. The address carries no code.
3. **Approval.**
   - The member signs in in the browser and types the code. The page never prefills it.
   - The page shows the host kind and the request time. The member picks one team project of which they are a current member, then approves or refuses.
   - Approval is a cookie request with the CSRF token. A code is approved at most once.
4. **Grant.**
   - The host redeems the approved request once, with its code verifier. The pairing request ends there.
   - The grant binds one account, one workspace and one team project. It allows two capabilities: submit, and read the team's own reviews.
   - The grant lives 60 minutes. It has no refresh. The host keeps it in memory only: never in plugin storage, the file, a widget or a model.
5. **Every call rechecks the team.** Each host API request checks that the grant is active and that the account is a current member of the team and of the workspace. A failed check answers like an unknown team: 404.
6. **Revocation.** A grant ends when:
   - the member revokes it in Settings, which lists the active grants;
   - the member signs out everywhere;
   - an instance admin blocks the account;
   - the account is erased, or it leaves the team or the workspace;
   - the host disconnects it.

   The grant store has no link to account sessions, so each of these events is wired to the grant store and tested on its own.
7. **Cross-origin rule.** The host API is one path prefix, `/api/host/v1/`. Its CORS rule allows any origin, also `null`, without credentials. It accepts only `Authorization: Bearer`. A request that carries the session cookie is refused. The origin proves nothing.
8. **Limits.** Pairing creation, code entry and grant redemption are rate-limited. Code entry has an attempt limit for each pairing request and for each account.
9. **Person data.** The pairing and grant store holds person data. It follows the rolling release rule: table and deleters in one release, erasure registration in the next, the first writer after that.
10. **ADR 0048 is amended only for installed clients without a redirect.** Such a client pairs by typed code and gets a team grant under this decision. Installed clients with a redirect keep ADR 0048 unchanged.

### Pairing states

| State | Event | Next state |
| --- | --- | --- |
| `PENDING` | the member types the right code and approves one team | `APPROVED` |
| `PENDING` | the member refuses | `REFUSED` |
| `PENDING` | the code is typed wrong too often | `REFUSED` |
| `PENDING` or `APPROVED` | 5 minutes pass | `EXPIRED` |
| `APPROVED` | the host redeems with the right verifier | `REDEEMED`, and one grant is issued |
| `APPROVED` | the host presents a wrong verifier | `REFUSED`; nothing is issued |
| `REDEEMED`, `REFUSED` or `EXPIRED` | any further approval or redemption | unchanged; refused |

### Threats

| Threat | Control |
| --- | --- |
| An attacker sends a victim their own pairing request (RFC 8628 § 5.4) | The code is typed, never prefilled. The page shows the host kind and the time. The request lives 5 minutes. The grant reaches one team project only |
| Code guessing | 8 characters, an attempt limit for each request and each account, and a rate limit |
| A host iframe has origin `null` | A separate CORS rule for the host API: any origin, no credentials, bearer only, and the session cookie is refused |
| Token theft | Memory only, 60 minutes, one team project, membership rechecked at every call |
| One credential used as the other | Separate stores and separate checks, with a refusal test in each direction |

## Consequences

- Good: every host, now and later, pairs the same way. A host never holds an account credential.
- Good: a stolen grant reads one team project for at most 60 minutes, and only while the member stays in the team.
- Bad: a member pairs again every hour. The host keeps no refresh secret, so a closed plugin also needs a new pairing.
- Bad: the grant store does not inherit the revocation of account sessions. Each ending event is wired and tested separately.
- Bad: one more person-data store, so the first writer ships two releases after the table.

## Confirmation

The implementation pull requests add these tests. Until then, review against this ADR catches a change.

- A pairing state table test: one test for each row of the table above.
- A grant authority test: an outsider, a removed member, an expired grant and a revoked grant read nothing, and a grant for team project A reads nothing of team project B.
- A credential separation test: the host API refuses an account session as a cookie and as a bearer, and a route outside the host API refuses a team grant.
- A revocation test for each event in rule 6.
- The erasure inventory test covers the pairing and grant store.

## Revisit trigger

- A host gains a redirect address or a system browser flow.
- A host must act for more than one team project, or for longer than one working session.
- A third party builds a host for Hephaestus.

## Sources

- IETF, [RFC 8628](https://www.rfc-editor.org/rfc/rfc8628.html) § 5.1 and § 5.4: a user code must resist guessing, and a remote attacker can send a victim a code. This gives the typed code, the attempt limit and the approval page content. We do not run an authorization server for it.
- IETF, [RFC 7636](https://www.rfc-editor.org/rfc/rfc7636.html): the code verifier binds the redemption to the host that created the request.
