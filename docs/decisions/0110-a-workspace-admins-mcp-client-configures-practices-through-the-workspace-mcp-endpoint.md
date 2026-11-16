# ADR 0110: A workspace admin's MCP client configures practices through the workspace MCP endpoint

**Status:** Accepted
**Date:** 2026-11-15
**Authors:** Hephaestus maintainers

**Amended by:** [ADR 0118](0118-a-workspace-keeps-its-practices-in-one-repository-and-a-trial-reviews-a-draft-practice-without-an-observation.md) (trial output, and practices that a repository writes)

**Builds on:** [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md) (client sessions, and its revisit trigger: a third-party client), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (one server role), [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) (tenancy), [ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md) (dependencies), [ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) (contract growth)

## Context

Workspace admins configure practices in the web app: they add practices from the catalog, customize and reset them, set feedback modes, choose the repositories and people that Hephaestus reviews, pause feedback, and request reviews.
Admins now ask to do this work from an MCP client, for example Claude Code, that acts for them.
The [Model Context Protocol](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization) defines how such a client calls tools on a server over HTTP, and how the server authorizes it with OAuth 2.1.

In this ADR, an **MCP client** is the external program that calls the tools.
It is not an agent: the product vocabulary reserves that word for the sandboxed runtime (`AGENTS.md` § Product vocabulary).

Four constraints set the shape:

1. **The client is third-party.** ADR 0048 serves first-party installed clients through a PKCE handoff. Its revisit trigger names this case: a third-party client needs consent and registration.
2. **The client is a model with tools.** Whatever a tool returns enters the context of a language model. Text that a developer wrote, such as a pull request title or an Outline document title, can carry instructions to that model. Feedback, observations, source code and conversation content are about named people. None of it is needed to configure a workspace.
3. **Every rule that the HTTP API enforces must hold on the new path.** Role checks sit on controller methods (`@RequireAtLeastWorkspaceAdmin`), not on application services. The admin passkey policy hangs off the same annotation (`AdminPasskeyAuthorizationConfiguration`). Audit rows are written by the services, inside their transactions, with the actor taken from the security context (`ConfigAuditRecorder`). Tenancy is enforced in SQL (ADR 0084).
4. **Instance-admin elevation is break-glass.** `WorkspaceContextFilter` elevates any token whose `roles` claim holds `app_admin`, and `@workspaceSecure.isAdmin()` counts that elevation.

The HTTP surface that an admin uses today, all of it on `@WorkspaceScopedController` classes:

| Area | Controller | Operations | Role | Audit |
|---|---|---|---|---|
| Workspace practices | `PracticeCatalogController` (`/practices`) | list, get, create, update (`expectedVersionId`), delete (409 with review history), versions, feedback mode, group, reset (`catalog-customization`), script needs | admin | `CONFIG_AUDIT` `PRACTICE_DEFINITION`, `PRACTICE_USAGE` |
| Practice groups | `PracticeGroupController` (`/practice-groups`) | CRUD, feedback mode, reorder, reset | admin | `PRACTICE_GROUP` |
| Catalog adoption | `CatalogAdoptionController` (`/practice-catalog/adoption`) | list, preview, adopt practice, adopt group | admin | `PRACTICE_DEFINITION` |
| Catalog updates | `PracticeReleaseController` (`/practices/releases`) | list, get, accept, decline | admin | `PRACTICE_DEFINITION` |
| Review settings | `PracticeReviewSettingsController` (`/practices/review-settings`) | get, scope preview, update (`If-Match`): review scope, delivery `ACTIVE`/`PAUSED`, default feedback mode, cooldown, deliver to merged | admin | `PRACTICE_REVIEW_SETTINGS` |
| Items | `ItemController` (`/practices/items`) | list (the review scope editor reads repositories here) | admin | none |
| Reviews | `ReviewRequestController`, `ReviewSummaryController` | request (member), list, get, queue, cancel | admin, request: member | request and cancel: `@AuditExempt`, the review row records the requester |
| Members | `MembershipController` (`/members`) | list, assign role, hide, remove | admin for writes | `WORKSPACE_ROLE` |
| Catalog include/exclude | `CatalogAdminController` (`/admin/practice-catalog`) | status, override, reset | instance admin | `CATALOG_PRACTICE` |

Observations, problems, marks, feedback, scan reports, traces and conversations have their own controllers. They carry evidence and feedback about developers.

## Decision drivers

- **One path for each fact.** A tool calls the application service that the HTTP handler calls. It adds no second way to change a practice.
- **Every action has its reverse** (`AGENTS.md` § Hit every surface).
- **A tool does what its name says, or it refuses and says why.** It never maps a request to a different effect.
- **Least data.** A tool returns what configuration needs and nothing about a developer's work.
- **No second revocation authority.** Signing out everywhere, suspension, deletion and a revoke end an MCP client's access in the same transaction as every other session (ADR 0048 driver 2).
- **A framework feature over a hand-rolled one, when it fits; a few owned lines over a package for a sliver** (ADR 0072).
- **Standard clients connect with no setup at the provider.** An admin adds one URL to the client.

## Considered options

### Protocol implementation

1\. **Spring AI MCP server Boot starter** (`spring-ai-starter-mcp-server-webmvc`). Spring AI 2.0 targets Spring Boot 4.0 and 4.1. The starter brings auto-configuration, annotation scanning (`@McpTool`) and Spring AI's model modules, which we do not use. It infers tool schemas from Java types, so the data contract would follow a refactor silently. Rejected.

2\. **MCP Java SDK with Spring AI's WebMVC transport** (`io.modelcontextprotocol.sdk:mcp-core` and `mcp-json-jackson3` 2.1.0, `org.springframework.ai:mcp-spring-webmvc` 2.0.2). The SDK is the protocol's reference implementation for Java. Its servlet transport for WebMVC moved to Spring AI in 2.0. It already validates `Origin` and `Host` (`DefaultServerTransportSecurityValidator`). New packages: these three and `com.networknt:json-schema-validator`; `reactor-core` and Jackson 3 are already on the classpath. Chosen.

3\. **Own JSON-RPC over HTTP.** The protocol changes with each revision (2025-06-18, 2025-11-25, 2026-07-28), so owned code would chase a moving specification. Rejected (ADR 0072 § When to own code instead).

4\. **`spring-ai-community/mcp-security`.** Community-driven, marked work in progress, "not officially endorsed yet by Spring AI or the MCP project". No RFC 9728 metadata, no client ID metadata documents. Rejected.

### Authorization server

5\. **Spring Security's authorization server with its own stores.** Since Spring Security 7 it is part of Spring Security. With its default stores it keeps a second client, authorization and consent store and a second token record beside `issued_jwt`. ADR 0048 option 1 rejected it for exactly that. Rejected.

6\. **Spring Security's authorization server behind Hephaestus's stores.** A spike against the `spring-security-oauth2-authorization-server` 7.1.1 sources found that the module supplies the endpoints' parameter handling but none of the parts that this case needs. It has no client ID metadata documents. It reads `resource` only in `OAuth2TokenExchangeAuthenticationConverter`, not for the authorization code grant. It neither emits nor advertises `iss` in the authorization response. `OAuth2AuthorizationCodeRequestAuthenticationValidator` lets the port vary only for IP loopback literals, not `localhost`. Those are owned code in either option. Its `OAuth2AuthorizationService` contract would also have to carry ADR 0048's strict refresh rotation: a finder that detects reuse must end the session as a side effect, and the access and refresh tokens are generated separately while `issued_jwt` stores them in one row. Rejected after the spike. Its pending consent needs no HTTP session, so statelessness was not the reason.

7\. **Standard OAuth endpoints over the ADR 0048 client session.** Hephaestus serves the authorization endpoint, the token endpoint and the server metadata itself. `ClientSessionService` keeps doing what it does for the extension: it consumes the code before the PKCE check, rotates strictly, ends the session on reuse, and keeps the lock order account → session → `issued_jwt`. The owned parts are the endpoints' request validation and error responses (RFC 6749 § 4.1.2.1, § 5.2), the metadata document resolver, and the consent flow. Chosen.

8\. **Personal access tokens.** Long-lived, copied by hand, not standard for MCP clients, rejected by ADR 0048 option 3. Rejected.

### Where the endpoint lives

9\. **One endpoint `/api/mcp`, with a workspace argument on each tool.** One token reaches every workspace of the account, and each tool resolves and checks the workspace by hand. Rejected.

10\. **One endpoint for each workspace, `/api/workspaces/{slug}/mcp`.** The resource indicator names the workspace endpoint, `WorkspaceContextFilter` resolves the slug and answers a non-member with 404, and each request checks that its grant belongs to the resolved workspace (decision 10). Chosen.

11\. **A separate role or container.** ADR 0081 keeps one server role. Rejected.

## Decision

### Endpoint and protocol

1\. **The workspace MCP endpoint is `POST /api/workspaces/{slug}/mcp` on the instance host.** It speaks MCP Streamable HTTP in stateless mode: each request stands alone, no MCP session exists, and any replica can answer. It serves the protocol revision that the SDK implements (2025-11-25 for SDK 2.x); authorization follows the 2026-07-28 text, which is HTTP-level and compatible. The server sends no request to the client. A workspace address (ADR 0053) does not serve it. A renamed slug gets no redirect on this path: the old path answers 404, and the client authorizes again for the new one. The endpoint accepts a browser request only from the instance's own origin, as the MCP transport requires against DNS rebinding. Thus, no MCP client's origin gets CORS. The endpoint's chain and the discovery chain for `/.well-known/oauth-*` send none. The authorization and token endpoints sit on the main chain, which answers CORS only for the operator's configured web-app origins and the registered browser extension; an MCP client is never in that registry. A browser page on another origin could read discovery with CORS and still never call a tool. The supported clients, such as Claude Code, are not browser pages and need no CORS.

2\. **The server is an explicit bean on the server role.** `McpStatelessSyncServer`, the WebMVC stateless transport with `DefaultServerTransportSecurityValidator` (allowed origin: the instance origin), no Spring AI auto-configuration and no annotation scanning. Tool handlers run on the request thread (`immediateExecution`), so the security context and the workspace context hold.

3\. **Each tool has a hand-written input and output JSON Schema.** The output schema is the data contract of § Data contract. Input records are validated with Bean Validation before the service call, as `@Valid` does over HTTP. A unit test fails when an output record and its schema disagree.

4\. **An instance property turns the endpoint on.** `hephaestus.mcp.enabled` is `false` by default. When it is false, the endpoint, the metadata documents and the authorization endpoints answer 404. The endpoint ships in whichever release contains it. It does not block 1.0, and 1.0 does not block it.

5\. **The tool set grows by addition** after its first release, with the rules of ADR 0103 applied to tool names, input fields and output fields.

### Authorization

6\. **Hephaestus is the authorization server, with standard endpoints over the client session of ADR 0048** (option 7).

- **Server metadata** at `/.well-known/oauth-authorization-server` (RFC 8414). The issuer is `hephaestus.auth.issuer`. It lists `response_types_supported: ["code"]`, `grant_types_supported: ["authorization_code", "refresh_token"]`, `code_challenge_methods_supported: ["S256"]`, `token_endpoint_auth_methods_supported: ["none"]`, `client_id_metadata_document_supported: true`, `authorization_response_iss_parameter_supported: true` and the two scopes. It has no registration endpoint: dynamic registration is deprecated in the specification.
- **Protected resource metadata** for each workspace endpoint (RFC 9728) at `/.well-known/oauth-protected-resource/api/workspaces/{slug}/mcp`, with the issuer and `scopes_supported: ["practices:read", "practices:write"]`. The 401 challenge names no scope, so a client asks for the scopes it needs from this list, and the consent page shows each one. Every 401 and every 403 carries `WWW-Authenticate: Bearer resource_metadata="…"`.
- **Clients register only by client ID metadata document.** A `client_id` is an HTTPS URL with a path and no userinfo, fragment or dot segment (CIMD § 3). The fetch runs through `SsrfGuardedResolverGroup`, which checks every resolved address at connect time and thus stops DNS rebinding, without `PrivateHostAllowlist`. It follows no redirect, reads at most 5 KB, and runs only for a signed-in account under the sign-in rate limit. The document is fetched for every authorization request and never cached, so every check runs on what the client publishes now. The document's `client_id` must equal the URL, and its `token_endpoint_auth_method` must be `none`. `client_name` is cut to 80 characters, and `logo_uri` is never rendered.
- **Redirects match the document.** A document lists only HTTPS redirects and loopback HTTP redirects (OAuth 2.1 § 2.3.1); any other scheme makes the whole document invalid. A redirect must equal one in the document, except that a listed loopback redirect (`http://127.0.0.1`, `http://[::1]` or `http://localhost`) matches with any port when scheme, host and path are equal (RFC 8252 § 7.3). A document that lists no loopback redirect gets no loopback match.
- **PKCE `S256` is required.** A wrong verifier burns the code. The authorization response carries `iss` (RFC 9207), errors included.
- **The `resource` parameter is required** on the authorization and token requests. It must be the workspace endpoint of this issuer that the grant names. The access token's `aud` is exactly that URI.
- **The authorization request is checked in a fixed order.** (1) The form of `client_id`. (2) Sign-in, which returns only through a relative path. (3) The metadata document and the redirect. Until step 3 passes, every failure ends on an error page and never redirects (RFC 9700 § 4.11). (4) `response_type`, the S256 challenge, `resource` (`invalid_target`) and `scope` (`invalid_scope`); these failures redirect with `error`, `state` and `iss`. A resource that names a workspace where the account has no membership gives the same `invalid_target` as an unknown one. `prompt=none` answers `consent_required`.
- **The token endpoint answers as RFC 6749 § 5.2 says,** with `Cache-Control: no-store`. Every code failure is the same `invalid_grant`, so it reveals nothing about which check failed. A `resource` other than the grant's is `invalid_target`; another grant type is `unsupported_grant_type`. A refresh presents the session's `client_id` (OAuth 2.1 § 4.3.1).

7\. **Consent is asked for every authorization.** The authorization endpoint stores a pending `mcp_client_grant` row (decision 8) bound to the signed-in account, single use, valid ten minutes. It sends the browser to the web app's consent page with the row's id. An account that is not signed in signs in first. The page shows the client's host (from the `client_id` URL) above the client's own name, the redirect host, a warning when the redirect is loopback only, the workspace, and the scopes in plain words: `practices:read` also lists members (provider login and role) and review status. The page says what the client can never read. Approval (`PUT …/approval`) and refusal (`DELETE`) are cookie-authenticated requests with the CSRF token. Approval grants access, so it requires a recent sign-in (`@RequiresRecentSignIn`) within the instance's recent sign-in window, 5 minutes by default. The recent sign-in gate owns that window for every action; MCP adds no setting of its own. A stale session confirms access on the page and returns to the same request. Refusal and a console disconnect only take access away, so they are exempt. An approval that the account cannot give answers 409, and the page shows why. The page sets `frame-ancestors 'none'`. Approval and refusal each record an `auth_event` with the client id, the workspace and the scopes.

8\. **Who can approve.** Only an account with a real ADMIN or OWNER membership of the workspace. Elevation does not count. If the admin passkey policy would require a passkey for this account in this workspace, consent is refused and names that reason: an MCP client cannot hold a passkey proof, and the policy gains no exception. Consent asks the policy's own predicate, extracted from `PasskeyAssurancePolicy`, and does not restate it. That predicate also refuses an instance admin with a real membership when the instance requires passkeys for instance admins, and an account whose passkey recovery is pending. If the policy turns on later, each call that it refuses returns its reason.

9\. **The grant is a workspace-owned row, the code is a handoff, and the tokens belong to a client session.** The table `mcp_client_grant` carries a NOT NULL `workspace_id` from its first migration, the account, the client id and the client name as fetched, the redirect, the literal resource URI, the scopes, the client's `state`, the linked `client_session` (nullable, `sfk_`), and an explicit status: `PENDING` (awaiting consent), `APPROVED` (code issued), `ACTIVE` (code exchanged), `REFUSED`, `REVOKED`, `EXPIRED`. The code has one home, `client_sign_in_handoff`, as for the extension. Approval takes `lockForIssuance`, then re-checks that the approving cookie token is still active (as `AuthSessionService.refresh` does), and only then writes the handoff with the kind `MCP_CLIENT`, the URL `client_id`, the redirect, the challenge and the grant id. Account-wide revocation therefore burns an approved code before it is exchanged, and an approval that raced the revocation writes nothing. The exchange keeps `ClientSessionService`'s order (consume the code, then check), but resolves an `MCP_CLIENT` code's client and redirect from its grant instead of `InstalledClientRegistry`. It locks the grant's session, if any, and the grant before it consumes the code, as a console revoke does. A used code that comes back ends its grant and the session it issued (OAuth 2.1 § 4.1.2). Only the MCP token endpoint redeems an `MCP_CLIENT` code. The exchange refuses unless the grant is `APPROVED`, creates a `client_session` of the kind `MCP_CLIENT` with the usual absolute deadline, and links the grant. `client_session` stays an account-scoped table and gains only that kind. The lock order is account → session → grant → code, on every path. A revoke or a returning code that finds a session linked only after it took the grant lock does not lock that session, which would invert the order. It revokes the grant. Every MCP call and every refresh requires an active grant, so the session's tokens stop working at once, and its row ends at its deadline. A revoke from the workspace console locks the grant's session and then the grant, and ends that session; revoking an `APPROVED` grant burns its handoff. A revoked grant ends its session, and an ended session revokes its grant, in one transaction. Account-wide revocation also marks the account's grants `REVOKED`, so the console never lists a grant whose session is gone. A grant stores the literal resource URI, so a refresh after a slug rename fails and the client authorizes again. Grants keep their row for 12 months after they end, so the config history can still name the client. Purging the account deletes its grants and ends their sessions.

10\. **Every request to the endpoint checks its grant.** The MCP chain accepts only `Authorization: Bearer` and ignores the session cookie. The token's `aud` must be the endpoint of the request. Its `sid` must name a live `MCP_CLIENT` session whose `ACTIVE` grant has `workspace_id` equal to the resolved `WorkspaceContext`. The scopes come from the grant, never from the token alone. Revocation is the same indexed `issued_jwt` lookup as on every other route.

11\. **The session kind shapes the token.** For an `MCP_CLIENT` session, `HephaestusJwtIssuer` sets `aud` to the grant's resource alone, read from the grant under the session lock, and sets no `roles` claim, on the exchange and on every refresh. The MCP chain refuses a token that carries `roles`. Elevation therefore cannot happen on this path, and `@workspaceSecure.isAdmin()` means a real membership. The REST API refuses MCP tokens, because `RevocationAwareJwtDecoder` requires `hephaestus-spa` in `aud`.

12\. **Refresh is bound to the grant.** An `MCP_CLIENT` session refreshes only at the MCP token endpoint, and an extension session only at `/auth/client/refresh`. A refresh that names a `resource` or a scope beyond the grant fails (RFC 8707 § 2.2). Rotation stays strict, as in ADR 0048.

13\. **No token passes through.** A tool calls application services in the server. It never forwards the MCP token, and it never reads or uses a provider token, an LLM key or another secret.

14\. **Scopes.** `practices:read` admits the read tools. `practices:write` admits the write tools and implies `practices:read`. A call of a write tool under a grant without `practices:write` gets HTTP 403 with `error="insufficient_scope"`, `scope="practices:write"` and `resource_metadata` before the tool runs, so the client can ask the admin for the scope again (step-up).

15\. **Each tool declares the HTTP handler it stands for (`@McpToolFor`), and an ArchUnit test compares them.** Every tool requires at least the workspace admin role, because only an admin can connect a client and a grant outlives a demotion. A tool's handler may require at most that role, so the tool is never weaker than its handler. The test resolves the effective authorization as `AdminPasskeyAuthorizationConfiguration.scope` resolves it: on the method, then on the class. A tool never maps to an operation that needs `@RequiresRecentSignIn` or the instance-admin role. Roles are checked on every call, so a demotion, a removal or a suspension takes effect on the next call.

### Tools

Every tool runs under the workspace of its endpoint and maps to one existing service operation.
"Reverse" names the tool that undoes it.

**Read tools** (`practices:read`, workspace admin):

| Tool | Maps to | Returns |
|---|---|---|
| `list_practices` | `PracticeCatalogController.list` | each workspace practice: slug, name, kind, group slug, position, version number and id, reviewed by, feedback mode (override, effective, source), catalog state and locally edited fields |
| `get_practice` | `PracticeCatalogController.get` | one practice with its full definition and the `expectedVersionId` for an update |
| `list_practice_groups` | `PracticeGroupController.list` | slug, name, position, feedback mode |
| `list_catalog_practices` | `CatalogAdoptionController.list` | slug, name, kind, why it matters, group, availability, reviewed by |
| `preview_catalog_practice` | `CatalogAdoptionController.preview` | the catalog definition that adoption would copy, and the `previewEtag` that adoption names |
| `preview_catalog_group` | `CatalogAdoptionController.previewGroup` | each practice of the catalog group with its availability, the action for each, and the `previewEtag` |
| `preview_practice_reset` | `PracticeReleaseController.get` | the catalog definition that a reset restores, and the `resetEtag` that the reset names |
| `get_review_settings` | `PracticeReviewSettingsController.get` | review scope (modes, repository ids and branches, account ids), delivery status, default feedback mode, cooldown, deliver to merged, scope counts, `etag` |
| `list_repositories` | `ItemController.list` for repository items | repository item id, provider, `owner/name` built from the structured fields, removed at |
| `list_members` | `MembershipController.listMembers` | account id, provider login, role, review selected |
| `check_practice_setup` (with the write tools) | review settings, practice definitions and the model assignments for reviews | for each practice, its [setup gaps](../contributor/practice-feedback-language.mdx#practices): no model assigned for a slot and tier, reviewed by people or nobody, an empty review scope (these stop reviews); feedback mode Off, delivery paused (these stop feedback) |
| `list_reviews` | `ReviewSummaryController.list` | review id, kind, origin, status, created and completed times, stop reason code, item reference, and for each practice: slug, status, reason code |
| `get_review` | `ReviewSummaryController.get` | the same for one review, with job status codes and timing. A practice withheld for its [practice readiness](../contributor/practice-review-glossary.mdx#runtime-terms) carries the readiness reason code, in the sense of ADR 0069 only |

**Write tools** (`practices:write`):

| Tool | Maps to | Role | Reverse |
|---|---|---|---|
| `adopt_catalog_practice` | `CatalogAdoptionController.adopt` | admin | `remove_practice`; with review history, `update_practice` to reviewed by nobody |
| `adopt_catalog_group` | `CatalogAdoptionController.adoptGroup` | admin | the same, for each adopted practice |
| `create_practice` | `PracticeCatalogController.create` | admin | `remove_practice` |
| `update_practice` | `PracticeCatalogController.update` | admin | `update_practice` with the earlier definition, or `reset_practice` |
| `reset_practice` | `PracticeCatalogController.reset` | admin | `update_practice` with the local definition, which `get_practice` returned before |
| `remove_practice` | `PracticeCatalogController.delete` | admin | `adopt_catalog_practice` or `create_practice` |
| `set_practice_feedback_mode` | `PracticeCatalogController.mode` | admin | the same tool with the earlier value; `null` inherits |
| `move_practice_to_group` | `PracticeCatalogController.bind` | admin | the same tool with the earlier group |
| `set_review_scope` | `PracticeReviewSettingsController.update` | admin | the same tool with the scope that `get_review_settings` returned |
| `set_default_feedback_mode` | the same | admin | the same tool, or reset to inherit |
| `set_feedback_delivery` | the same, `deliveryStatus` | admin | `PAUSED` and `ACTIVE` reverse each other |
| `request_review` | `ReviewRequestController.requestReview` | member | `cancel_review` |
| `cancel_review` | `ReviewSummaryController.cancelReview` | admin | `request_review` |

`adopt_catalog_practice`, `adopt_catalog_group` and `reset_practice` name the validator of the preview that the client read, as the web app sends it in `If-Match`. A change since the preview changes nothing.
`update_practice` takes the changed fields of the definition and the `expectedVersionId`.
It applies them to the current definition and calls the same update, so the service still validates the whole definition, writes a new version and audits it.
A practice with review history cannot be deleted (409).
`remove_practice` then fails with an error that names this reason and the tool to use instead: `update_practice` with reviewed by nobody.

Not in the first tool set, and added by addition when needed: group writes, catalog update accept and decline, scans, batches and schedules, and member role changes.
Never offered without a new ADR: catalog include and exclude (instance admin), observations, problems, marks, feedback, scan reports, traces, conversations, practice profiles, model connections and their keys, integration connections and their tokens, and the config audit log.

### Data contract

Every field that a tool returns is in one of these classes:

| Class | Fields | Why it is not sensitive |
|---|---|---|
| **Catalog content** | catalog slugs, names, kinds, why it matters, catalog definitions | The instance offers it to every workspace. Hephaestus ships most of it. |
| **Workspace configuration** | practice and group slugs and names, definitions (criteria, guidance, practice script, reads, review on, applies when, about), feedback modes, review scope modes, delivery status, cooldown, positions, version numbers and ids, `etag` | Workspace admins write it, and only admins read it over HTTP today. It describes how to review, not anyone's work. |
| **Configuration identifiers of people** | account id, provider login, role, review selected | The review scope needs them, and admins see them today. A provider login is public at its provider and has a restricted character set. Personal data under the [data map](/admin/dsms/personal-data-map), not sensitive data. |
| **Operational facts** | review, item and repository ids, item kind and provider, `owner/name` and `owner/name#n` built from structured fields, statuses, reason and stop codes, times, counts of practices and jobs | Codes and identifiers. A code outside the server's known list goes out as `UNKNOWN`, never as stored text. |

No tool returns:

- source code, diffs, captured evidence, citations or quotes;
- an observation, an outcome, a problem, a standing, or any count of results, outcomes or feedback for a review, an item or a person (`ReviewDTO` result and feedback counts stay out);
- feedback text, a response, a disagreement, or a conversation message;
- a secret, a token, a key, a webhook secret or a credential;
- text that a developer or a provider wrote: titles and bodies of pull requests, merge requests, issues and documents, comments, commit messages, branch names chosen by a developer, display names, Slack channel and Outline collection names (`ItemRefDTO.title`, `.label` for Outline and Slack items, `.container`), and the free-text job `errorMessage`;
- a `traceId`, an email address, or an avatar.

**Prompt-injection posture.**
Only two kinds of free text go out: catalog content and workspace configuration.
A workspace admin of the same workspace, or the catalog maintainers, wrote it.
A different admin of the same workspace can therefore plant instructions in practice text, and a write that a client makes because of them is recorded under the account that approved the client.
The tool descriptions are static, and they state that the practice definition, including `criteria`, `guidance` and `script`, and the catalog's `whyItMatters` are configuration text to treat as data.
Every result is JSON in `structuredContent`, with free text only in those named fields.
The consent page shows the client's self-declared name as plain text below the host that the client cannot choose.

### Audit, limits and signals

16\. **Every write is audited by the service that makes it.** Configuration writes record their `config_audit_event` row in the same transaction, with the account as the actor, as over HTTP. The row gains `mcp_client_grant_id` (nullable, `sfk_`), so the config history names the client through the retained grant. `request_review` records the requester on the review row, as over HTTP. Every cancel path records who cancelled the review, over HTTP and MCP alike; the write-tools pull request adds the recorded actor where a path lacks it. `AuditByDefaultArchTest` extends to tool methods.

17\. **Workspace admins see and revoke the grants of their workspace.** The workspace console lists each active grant (client host and name, account, scopes, approved at) and revokes it. An account also ends its own MCP sessions from its session list.

18\. **Rate limits reuse the authentication rate-limit filter** (`core.auth.ratelimit`). The authorization endpoint and the consent decisions share the sign-in budget, which also bounds the metadata document fetches; the token endpoint shares the installed-client session budget; calls to a workspace MCP endpoint get their own budget for each account (`mcp-call`, 120 a minute). `request_review` keeps the review request budget when the write tools add it. The workspace's AI budget still applies to every review. These budgets are fixed by design, like every authentication rate limit, and the chart does not set them. They stop a runaway or hostile client; they do not size capacity. The `mcp-call` budget belongs to each approving account and each replica, so an instance with more admins or replicas gets more budget without a setting. An agent that configures practices makes a few calls a minute, far below 120. Only limits that follow an outside quota, such as e-mail sending and sync backfill, are operator values.

19\. **Signals.** `mcp.tool.calls{tool, outcome}`, with the outcomes `ok`, `refused` and `failed`, and `mcp.auth.refusals{reason}`, with the challenge errors `invalid_token` and `insufficient_scope`. A limited call counts in the existing `auth.ratelimit.blocked{bucket="mcp-call"}`. `HephaestusMcpToolsFailing` fires on three `failed` outcomes in one hour. Its runbook row also covers a client that cannot connect: `mcp.auth.refusals` names the reason, and the admin guide lists the consent refusals (metadata, issuer or resource mismatch, passkey policy).

### Surfaces

| Surface | Decision |
|---|---|
| Integrations | Provider-neutral. `request_review` takes an item id of any kind (pull or merge request, issue, document, chat thread), and the review service accepts or refuses it as over HTTP, for GitHub, GitLab, Outline and Slack alike. No tool parses provider URLs. References to Outline and Slack items carry no label (§ Data contract). |
| Runtime roles | Server role only (ADR 0081). The `migrate` profile starts no MCP bean. |
| Wire contract | MCP tools are not OpenAPI operations. The consent read and approval, the workspace grant list and revoke, and the session list's new client kind are OpenAPI DTOs, regenerated into both clients. |
| Schema | New workspace-owned table `mcp_client_grant` (NOT NULL `workspace_id`, under the tenancy inspector from its first migration). Lookups by code hash and by session run before a workspace context exists and carry `@WorkspaceAgnostic` with their reason. `client_session` and `client_sign_in_handoff`: the `MCP_CLIENT` kind and a `client_id` long enough for a URL; the handoff also gains the grant id (`sfk_`). `config_audit_event`: `mcp_client_grant_id`. The review row: who cancelled it, if absent. Data map rows for the new table and columns. Before 1.0.0 the new stored kind ships under ADR 0070; after it, under the two-release rule. |
| Destinations | No change. The tools change settings that govern all three destinations with the same meaning as in the web app. No tool reads feedback. |
| Reverse states | The Reverse column above. Pause and resume are `set_feedback_delivery`, and feedback mode Off and back. A grant ends from the workspace console or the session list. |
| Both admin consoles | Workspace console: the grant list, and the config history shows the client. Instance console: configuration readiness shows whether the MCP endpoint is on. |
| UI states | The consent page, the grant list and the session row ship stories for loading, error, empty and refused (not an admin, passkey required, request expired). |
| Observability | § Audit, limits and signals. |
| Docs | Admin guide: turn the endpoint on, connect an MCP client to a workspace and approve it, what it exposes, list and revoke grants. Contributor guide: add an MCP tool (map to one handler, schema and contract test, data class row, reverse tool, rate class). Product vocabulary: setup gap. Auth glossary: MCP client, workspace MCP endpoint, MCP client grant. `docs/auth-architecture.md`: the third-party client flow. |
| Release note | One changeset: operators can turn on the endpoint; workspace admins can connect an MCP client and revoke it. |

## Consequences

- Good: an admin configures a workspace from an MCP client with one URL, and every HTTP rule holds: role, passkey policy, tenancy, audit, budget.
- Good: one revocation authority stays. An MCP client session is a `client_session`, and the grant binds it to one workspace on every request.
- Good: a token reaches one workspace endpoint, carries no roles, and the REST API refuses it.
- Good: the data contract is a reviewed schema with a test, not whatever a DTO grows into.
- Bad: three new Gradle modules (the MCP SDK core, its Jackson 3 module, Spring AI's WebMVC transport) and `json-schema-validator`. The first pull request gives the lockfile delta.
- Bad: we own the OAuth endpoints' request validation, the metadata document resolver and the consent flow. They are small, follow stable RFCs, and are tested over HTTP against PostgreSQL.
- Bad: an MCP client cannot inspect outcomes or feedback, and it cannot work in a workspace that requires an admin passkey. Admins use the web app for both.
- Bad: consent on every authorization. An admin approves again after the session ends.
- Neutral: the write tools and their audit column, `check_practice_setup`, and the actor of every cancel path arrive with the write-tools pull request, with its own integration test.

## Confirmation

- `McpAuthorizationConformanceIntegrationTest` checks one rule in each test, written from OAuth 2.1, RFC 8707, RFC 9207, RFC 8252 § 7.3 and the client ID metadata document draft. It fails when plain PKCE or a missing challenge is accepted, when a code works twice or after it expires, when a returning code leaves its session alive, when the first-party token endpoint redeems an MCP code, when a wrong verifier does not burn the code, when a redirect matches other than exactly or by the loopback port alone, when a token's audience is other than its resource, when a code is exchanged for another resource, when an authorization response lacks `iss`, when an old refresh secret does not end the session, when an MCP session refreshes at `/auth/client/refresh`, and when "sign out everywhere" leaves a code, a refresh secret or an access token working. It covers the two races of the round-3 review: an approval that races "sign out everywhere" writes no code, and an exchange never reactivates a grant that a console revoke ended, and an exchange waits for a console revoke's grant lock before it touches the code. It also fails when a web-app token, a token for another workspace, or a token whose workspace was renamed reaches a tool or refreshes, when a member who is not an admin can approve, and when a stale session can approve.
- `McpPracticeToolsIntegrationTest` fails when a tool's output schema leaves an object open or a property optional, and when a demoted admin's next call is not refused with its reason. The SDK validates every result against its output schema, so a tool that returns a field outside the contract fails its call.
- `McpToolHandlerParityArchTest` fails when a tool does not require the workspace admin role, when the handler it declares with `@McpToolFor` requires more, or when that handler needs the instance-admin role or a recent sign-in.
- `McpWriteToolsIntegrationTest` fails when a grant without `practices:write` reaches a write tool instead of a 403 `insufficient_scope`, when a write tool accepts input that its handler refuses or names a different reason, when a write does not record its grant on the config audit row, or when a reverse tool does not restore what the write changed.
- `McpToolHandlerParityArchTest` also fails when a tool for a mutation does not declare that it writes or does not carry its handler's audit annotation. `McpGrantAuditTrailDatabaseTest` fails when the prod trigger keeps an audit row from losing a deleted grant, or lets it change to another grant.
- `ClientMetadataResolverTest` fails when a document with a redirect that is neither HTTPS nor loopback is accepted, or when a client name keeps control or bidi characters. `HttpClientMetadataSourceTest` fails when the fetch follows a redirect, reads more than 5 KB, waits past its timeout, or reaches a loopback or private address as shipped.
- `McpProjectionsTest` fails when an item reference carries a title, a path or a channel name instead of a number.
- `scripts/kubernetes-chart-render.test.ts` fails when the chart, with MCP on, does not route the two discovery paths to the server unchanged.
- `DataIsolationArchitectureTest`, `WorkspaceTableColumnIntegrationTest` and `PersonalDataMapArchTest` cover `mcp_client_grant`.

## Revisit trigger

- An MCP client that we must support has no client ID metadata document.
- Admins ask to read outcomes or feedback from a client. That needs a scoped, consented read and a new ADR.
- A workspace that requires an admin passkey needs MCP access.
- The MCP specification makes a server-to-client request necessary for tools.
- A browser-based MCP client on another origin must be supported. It needs an origin allowlist on the endpoint, and CORS for those origins on the token endpoint and the discovery documents.
- Spring Security's authorization server gains client ID metadata documents, RFC 8707 and RFC 9207, and a storage contract that fits strict rotation.
- Measured call rates exceed the limits for legitimate use.

## Sources

- [MCP authorization, 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization) and its security considerations: protected resource metadata, metadata documents preferred, dynamic registration deprecated, audience validation, no token passthrough, scope challenges, localhost redirect risks.
- [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728), [RFC 8707](https://www.rfc-editor.org/rfc/rfc8707), [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414), [RFC 9207](https://www.rfc-editor.org/rfc/rfc9207), [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252), [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700): metadata, resource indicators, issuer identification, loopback redirects, error pages instead of open redirects.
- [Client ID metadata documents (draft)](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-client-id-metadata-document-00) § 4.4, § 4.5, § 6.4–6.6: exact redirect match, fetch limits and caching.
- [Claude Code's client metadata document](https://claude.ai/oauth/claude-code-client-metadata): `token_endpoint_auth_method: none`, redirects `http://localhost/callback` and `http://127.0.0.1/callback` with no port.
- [Spring AI 2.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) and [the MCP Java SDK](https://java.sdk.modelcontextprotocol.io/latest/): Spring Boot 4.0 and 4.1, the transports in Spring AI 2.0.
- [Spring AI MCP security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html): community module, work in progress.

## Update — 2026-11-16: trial output, and practice text from a repository

[ADR 0118](0118-a-workspace-keeps-its-practices-in-one-repository-and-a-trial-reviews-a-draft-practice-without-an-observation.md) adds trials and practice repositories. Both change § Data contract.

**Trial output is a new data class.**
A trial runs a draft practice on real work and records no observation.
An admin who improves a practice through MCP must read what the trial found.

- Trial output holds a trial's status and reason code, the outcome, the severity, each problem's claim, and the path and line of each citation.
- Work text can reach three of its fields: a problem's `claim`, a citation's `path`, and a quote in `untrustedWorkText`. The tool descriptions name these three fields as text from the reviewed work, to treat as data and never as an instruction.
- A trial tool returns trial output only when every person whose work the trial read chose **Cloud**. Otherwise it returns the status, the reason code and the outcome. An admin's MCP client sends what it reads to the admin's own model, so each of those AI choices applies to it.
- Only trial tools return trial output, and they need `practices:write`. The consent text for `practices:write` names it.
- Trial output overrides three bullets of "No tool returns", only in its named fields and only for the trial that the admin started:
  - citations and quotes;
  - an outcome or a problem;
  - text that a developer or a provider wrote, in `claim`, `path` and `untrustedWorkText`.
- Each other bullet stands. No tool returns an observation, a standing, a count of results or feedback.

**Practice text can come from a repository.**
§ Prompt-injection posture says that an admin of the same workspace wrote all configuration text.
For a practice that a practice repository edits, anyone who can merge into its default branch wrote the criteria, guidance and script.
The tool descriptions already mark those fields as configuration text to treat as data.
`get_practice` also says where a practice is edited.
