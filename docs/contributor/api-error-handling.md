---
title: API error handling
description: How the server reports errors and how the generated client surfaces them.
---

# API Error Handling Guidelines

Every REST error this server returns is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json` body produced by a
`@RestControllerAdvice`. A controller throws a meaningful exception. An advice class translates it
into HTTP semantics and decides exactly what detail leaves the server. No controller formats an error
itself, and no stack trace reaches a client.

## One problem model

A problem has the RFC 9457 members `type`, `title`, `status` and `detail`.

- `type` is the only machine-readable code.
  A client branches on `type`, never on another member or on the `detail` text.
- `ProblemType` (`core/exception`) is the registry of every `type` that a client can branch on.
  Each constant has a slug.
  The `type` is `https://docs.hephaestus.build/contributor/api-error-handling#<slug>`, which points to the slug in [Problem types](#problem-types).
- A problem without a distinct kind keeps `type: about:blank`. Its status is then its kind.
- Other members carry named data, such as `maxAgeSeconds`, `errors` or `withholdingReason`.
  Do not add a second code member such as `code`, `reason`, `kind`, `reasonCode` or `error`.
- Build a typed problem with `ProblemType.X.problem(status, title, detail)`.
  For a `ResponseStatusException`, set `getBody().setType(ProblemType.X.uri())`.
- A slug is part of the API. Renaming or removing one is a breaking change.
  Add the slug to [Problem types](#problem-types) in the same change.
- The webapp reads the `type` through `isProblemType` and `stepUpChallengeOf` in `webapp/src/lib/problem-detail.ts`.
  It shows `detail`, or `title` when `detail` is absent.

## How `WorkspaceControllerAdvice` works

1. `@RestControllerAdvice(basePackages = "de.tum.cit.aet.hephaestus.workspace")` scopes the advice to workspace endpoints only, but covers the entire workspace package (controllers for repositories, labels, lifecycle, etc.). One mapper handles every route in that package, which guarantees consistency.
2. Each `@ExceptionHandler` method matches a domain exception (for example `EntityNotFoundException`) and returns a configured `ProblemDetail` object. Spring automatically serializes it to JSON with the proper status code.
3. The private helpers sanitize exception messages through `LoggingUtils` before they return them. This prevents leaks of stack traces or SQL fragments. The server logs any unexpected `IllegalStateException` as a server error. Domain-specific violations, such as slug conflicts and lifecycle issues, have their own exception classes. Thus, the advice can return the correct HTTP semantics.
4. Controllers simply throw exceptions (or let services throw) and never build ad-hoc `Map.of("error", ...)` responses again.

## Rolling the pattern out elsewhere

When you touch another REST controller:

1. **Identify the domain-specific exceptions** the controller or underlying services already throw.
2. **Create a dedicated advice class** in the same package. If multiple controllers share the same error model, use a shared module instead. Annotate it with `@RestControllerAdvice(basePackages = "your.package")`. Thus, every controller in that bounded context reuses the mapper.
3. **Map exceptions to HTTP semantics**:
   - Validation or invariant violations → `400 Bad Request`
   - Missing resources → `404 Not Found`
   - Conflicts (duplicates, state transitions) → `409 Conflict`
   - Authorization issues should still bubble up to Spring Security so we get `401/403` uniformly.
4. **Return `ProblemDetail` everywhere**. Include a user-actionable `title` plus a `detail` sourced from the exception where it is safe to expose. Give a problem a `ProblemType` only when a client must tell it apart from other problems with the same status.
5. **Document the mapping** (update this file or the feature README) so clients know what to expect.

### Shared advice for multiple controllers

If controllers reuse the same exception hierarchy, point `@RestControllerAdvice` at the shared base package. Git provider controllers are an example. Every endpoint in that bounded context then inherits the mapper. Keep advice classes focused. Too many unrelated handlers in one class make maintenance difficult.

## Validation errors deserve structure, too

Spring surfaces method-argument validation failures as `MethodArgumentNotValidException` (body binding) or `ConstraintViolationException` (query/path parameters). Both become structured JSON, so a client can highlight the field that failed rather than print a sentence. See [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) for the media type. Our convention:

- Use the same advice class to `@ExceptionHandler` both exception types.
- Return `ProblemDetail` with `title = "Validation failed"`, `status = 400`, and a short descriptive `detail`.
- Attach a machine-friendly `errors` map through `problem.setProperty("errors", …)`. Each key is the field or parameter that failed. We remove method prefixes so only the field name remains. Each value is a list of human-readable messages.

### Path-variable validation

Slug parameters across `WorkspaceController` use the shared `@WorkspaceSlug` constraint, which `WorkspaceSlugValidator` implements.
The controller carries `@Validated`.
Invalid slugs raise a `ConstraintViolationException`.
The advice handles this exception and returns the same `errors` map shape as body validation failures.

## Testing expectations

- Add WebTestClient or MockMvc tests that trigger the exception path and assert the `ProblemDetail` body.
- Cover the advice indirectly through integration tests so we catch serialization regressions when upgrading Spring Boot.

## Problem types

Each heading is the slug at the end of a problem `type`.

### agent-binding-slot-mismatch

The selected model does not fill this agent binding row. `declaredTier` names the model's data-handling tier.

### agent-job-state-conflict

The agent job is not in a state that allows the request.

### concurrent-modification

Another request changed the record first. Repeat the request.

### csrf-token-rejected

The CSRF filter refused the request. Fetch a new token once, then retry the request once.

### feedback-withheld

The publication context does not permit approval. `withholdingReason` names the reason and `state` the feedback state.

### llm-call-pool-in-use

A model still uses the call pool.

### llm-call-pool-slug-conflict

Another call pool has this slug.

### llm-connection-in-use

A model or binding still uses the LLM connection.

### llm-connection-slug-conflict

Another LLM connection has this slug.

### llm-model-in-use

A binding still uses the LLM model.

### llm-model-slug-conflict

Another LLM model has this slug.

### llm-model-upstream-id-conflict

Another LLM model on this connection has this upstream id.

### mentor-lease-lost

A Hephaestus host wrote for a turn attempt that lost its lease.

### mentor-refused

Hephaestus does not reply to this member. The `detail` names the setting that changes it.

### mentor-rejected

The conversation refused a write that conflicts with its state.

### oauth-callback-failed

An OAuth callback failed. `callbackError` names the failure with the same value as the browser redirect's `reason`.

### outline-rate-limited

Outline limits the requests. `Retry-After` tells when to try again.

### outline-unreachable

The server could not reach Outline.

### passkey-required

The action needs a passkey verification.

### review-batch-conflict

The request conflicts with a review batch that is already open.

### review-schedule-conflict

The request conflicts with the current review schedule.

### step-up-required

The action needs a recent sign-in. `maxAgeSeconds` gives the allowed age of the sign-in.

### sync-not-supported

This connection does not support a manual sync.

### unknown-outline-collection

Outline does not know the collection.

### workspace-budget-exceeded

The sandbox workspace is larger than its byte budget. `workspaceBytes` and `workspaceByteBudget` give the sizes.
