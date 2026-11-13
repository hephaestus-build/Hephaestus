# ADR 0103: The public API grows only by addition, and every client tolerates what it does not know

**Status:** Proposed
**Date:** 2026-11-13
**Authors:** Hephaestus maintainers

**Builds on:** [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md) (installed clients), [ADR 0070](0070-1-0-is-a-cutover-release.md) and [ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md) (breaks end at 1.0, and ADR 0096 lists them)

## Context

The [public contract](../admin/compatibility-policy.mdx#the-public-contract) promises the operations in `server/public-api-operations.json` from 1.0 until 2.0.
Long-lived clients of any kind call them: the browser extension, third-party integrations, and installed clients that update on their own schedule.
Such a client can run an old version against a new server for years.
Its code usually comes from `server/openapi.yaml` through a generator, so it tolerates only what the generated code tolerates.

The spec does not yet say what the policy promises.
Response enums are promised open, but the spec writes them as JSON Schema `enum`, which is closed.
A missing value is absent in most records and `null` in others.
Strict generators, such as swift-openapi-generator and OpenAPI Generator `kotlin`, stand in for clients in other languages, and they refuse the spec or drop properties from it.
The oasdiff gate checks the previous release, but it cannot tell an open enum from a closed one.

## Decision drivers

- A client built against 1.0 keeps working against every 1.y without a change.
- The spec is the one home of the contract. A generator reads it as it is, with configuration but with no transform script.
- A rule that a gate can check has a gate. Each other rule names the review that catches it.
- No second version axis: one server runs one release.

## Considered options

1. **Version the API** (a path major such as `/v1`, or dated versions as at Stripe and Azure). It lets a break ship without a major release. The cost is a translation layer for each version in every server, and one self-hosted server runs one release.
2. **Closed enums, open by policy only (today).** Kotlin can fall back with `enumUnknownDefaultCase` (moshi, or kotlinx.serialization for string enums). Swift's generated enums are `@frozen` and fail on a new value.
3. **`x-extensible-enum`.** Zalando retired it in October 2025 for OpenAPI 3.1 specs. Generators ignore it, so it gives a string plus a list that only people read.
4. **Open enums as `anyOf: [enum, string]`** (Apple's pattern). Correct in Swift, but the Kotlin `jvm-okhttp4` generator emits an empty class that keeps no value, and TypeScript widens the type to `string`.
5. **Nullable values present as `null`.** Swift drops every nullable reference (`anyOf: [$ref, {type: null}]`) with only a warning (apple/swift-openapi-generator#906, open). Nullable scalars work, but two conventions for a missing value would remain. Jackson already leaves out nulls (`non_null`).
6. **Addition-only rules, open enums as strings with examples, absent values, and gates (chosen).**

## Decision

The rules apply to the public contract: the public operations, the documents and streams that [the policy](../admin/compatibility-policy.mdx) lists, and every schema that they reach.

1. **After 1.0, a public operation changes only by addition until 2.0.**
   Compatible: a new operation; a new optional request parameter or property; a new response property; a new value of an open enum; a new problem type; a new error status; a new stream event name.
   Breaking: removing or renaming an operation, parameter or property; a response property that was always present becomes absent; a new required request input; a narrower request constraint (`maxLength`, `pattern`, `minimum`); a changed type, format or default; a new value of a closed enum; `additionalProperties: false` on a response; a changed status code or problem type for the same condition.
   The contract is the wire format. Generated source code can need edits after a compatible change ([Zalando #106][zalando]).
2. **A missing value is absent.** A response leaves out a property that has no value. No schema that a public operation reaches has a `null` type. A request may leave out an optional property, and `null` means the same as absent, except in a JSON Merge Patch body ([Zalando #123][zalando]).
3. **An enum that a public operation returns is open everywhere it appears.** The spec writes it as `type: string` with the known values in `examples`, and its description starts with "Extensible enum:" and says that a client must accept other values ([Zalando #112][zalando]). The server checks a request value against the known values and answers 400 for an unknown one. Removing a known value is a break. The closed enums that the policy names (observation outcomes, practice problem severities) keep `enum`, and a new value in them is a break.
4. **Every Java enum is one named schema.** A type that crosses operations has one name in every generated client.
5. **A client tolerates what it does not know.** It ignores an unknown response property, accepts an unknown value of an open enum, falls back on the status for an unknown problem `type`, and skips an unknown stream event name or property ([RFC 9457 §3.1.1, §3.2](https://www.rfc-editor.org/rfc/rfc9457#section-3.2)). Each stream event payload is a schema of the spec.
6. **The release version is the contract version.** An operation, parameter or property added in 1.y exists on every server at 1.y or later. A server ignores a request property that it does not know, so a client checks the version before it uses an addition. It reads the version from `/.well-known/hephaestus.json`. A feature that an instance can turn off is reported by the read that serves it, never inferred from the version.
7. **Deprecation comes before removal, and removal waits for a major release.** A deprecated operation, parameter or property is `deprecated: true` in the spec. From that release on, the responses of a deprecated operation carry `Deprecation: @<epoch seconds>` ([RFC 9745][rfc9745]). When the major release that removes it has a date, they also carry `Sunset: <HTTP-date>`, never earlier than the deprecation ([RFC 8594](https://www.rfc-editor.org/rfc/rfc8594)). Behavior does not change until the removal. The release notes announce both steps.
8. **New work follows the long-lived shapes.** A new ID is a string. A list that can grow without bound is paginated in its first release. A response is a JSON object, not a bare array.

Existing int64 IDs and the bounded arrays that public operations return stay as they are: they are exact in every client, and a type change would break clients for no failure.
Before 1.0, each break that these rules cause is listed in ADR 0096 with its migration note.

## Consequences

- Good: an old client reads every new response. A new enum value, property, event or problem type does not break it.
- Good: a client in any language generates from the spec with no transform. CI proves it with two strict generators.
- Good: the gates stop null types, closed enums, inline enums and invalid OpenAPI from coming back.
- Bad: open enums are `string` in generated code. Each client maps the known values itself and needs a default branch, which the policy already requires.
- Bad: before 1.0, removing `null` and opening the enums change generated types. TypeScript does not flag `x === null` on a `T | undefined`, so each changed property gets a reviewed search.
- Neutral: operations outside the public contract keep closed enums. The web app ships with the server.

## Confirmation

The pull requests that implement this ADR add these checks. The status stays Proposed until they run in CI.

- Rules 1, 2 and 3: `vp run gate:api-compatibility` fails, against the previous release, on a removal, on a `null` type in a schema that a public operation reaches, on an `enum` in a public response outside the closed set, and on a new value of a closed enum.
- Rules 3, 4 and 7: an OpenAPI linter checks the spec on every pull request: OpenAPI 3.1 structure (unique tags, a schema on every header), no inline enum, the "Extensible enum" description, and a `Deprecation` header on every deprecated operation.
- Rules 2, 3 and 5: a round-trip job generates and compiles a Swift client (swift-openapi-generator) and a Kotlin client (OpenAPI Generator `kotlin`) from the unmodified spec. It fails on a dropped property, and it decodes a fixture with an unknown property and an unknown enum value.
- Rules 6 and 8: not enforced. Review of each pull request that adds a public operation catches them.

## Revisit trigger

- swift-openapi-generator reads `anyOf: [$ref, {type: null}]`, or OpenAPI Generator `kotlin` reads `anyOf: [enum, string]`. Then rules 2 and 3 can choose another form.
- A client needs two releases of the contract from one server. Then evaluate option 1 again.

## Sources

- [Zalando RESTful API Guidelines][zalando], rules #106–#112 and #123: the compatible and breaking change lists, the tolerant reader, open enums as strings with examples, and absent equals null. They set rules 1, 2, 3 and 5.
- [Apple swift-openapi-generator, Useful OpenAPI patterns](https://github.com/apple/swift-openapi-generator/blob/main/Sources/swift-openapi-generator/Documentation.docc/Articles/Useful-OpenAPI-patterns.md): `enum` and `oneOf` are closed and fail on an unknown value. With a generator trial on 2026-11-13, this rules out options 2, 4 and 5.
- [RFC 9745][rfc9745]: the `Deprecation` format and its order with `Sunset` in rule 7. Zalando #189 also allows `Deprecation: true`. We follow the RFC, which allows only a date.

[zalando]: https://opensource.zalando.com/restful-api-guidelines/
[rfc9745]: https://www.rfc-editor.org/rfc/rfc9745
