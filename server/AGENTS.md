# Server

Spring Boot 4 / Java 21 / Spring Modulith 2. Liquibase owns the schema; the OpenAPI spec and the
GraphQL clients are generated. Package layout under
`application/src/main/java/de/tum/cit/aet/hephaestus/` follows the domain (`core/`, `workspace/`, `agent/`,
`practices/`, `integration/`, `mentor/`, …) — read it rather than a copy of it here.

This file is the gotchas. Spring, JPA and Lombok idioms that the surrounding code already shows you
are not here: write code that reads like the file you are editing.

## Local development loop

- **No devtools.** Hot reload is JVM HotSwap via the IDE — IntelliJ's Spring Boot run config with
  _Update Classes and Resources_ on save. Method-body edits reload; signature changes, new methods and
  `@Configuration` edits need a full restart
  ([ref](https://docs.spring.io/spring-boot/how-to/hotswapping.html)).
- **`ddl-auto: validate`** locally — Liquibase owns DDL. If the validator fails on boot your DB has
  drifted: `vp run dev:reset`.
- **`BufferingApplicationStartup`** is wired in `Application.main()`; `StartupBudgetIntegrationTest`
  reads its timeline and fails on a slow step. `/actuator/startup` is not exposed.

## Build traps

- **Use the wrapper.** `./gradlew :application:test` from `server/` builds its generated-client
  dependency automatically. Use `--tests ClassName` or `--tests 'ClassName.methodName'` to focus a test.
- **Select a tier by task**, not by a system property. `test`, `architectureTest`, `integrationTest`,
  `databaseTest` and `liveTest` each own their JUnit tag filter. Every non-live tier excludes `live`.
- **`clean` is not cache-disabled.** Use `--no-build-cache` as well for a cold measurement.
  Configuration cache reuses task configuration; build cache reuses declared task outputs.
- **One build invocation per checkout at a time.** Gradle owns the module `build/` directories.
- **Tests always execute when requested.** Test result caching and up-to-date skipping are disabled;
  PostgreSQL, containers and provider state are not content-addressed inputs. Compilation remains
  incremental and cacheable. Test JVMs set `MANAGEMENT_PORT=0 SERVER_PORT=0`; local OAuth settings
  in `server/.env` can still affect environment-sensitive tests.
- **Packaged-artifact consumption is CI-only.** `-PpackagedServer=true` uses restored compiled
  classes for database tooling and database tests. Never use it after editing source locally.

## Boundaries

**Always** — run the unit baseline and every affected tier before committing · tag every test (`@Tag("unit")`,
`@Tag("integration")`, `@Tag("live")`) · declare a new endpoint's permission explicitly.

**Ask first** — schema changes · security configuration · a new Gradle dependency · workspace
authorization logic.

**Never** — commit credentials · `System.out.println` (log through SLF4J with `{}` placeholders, and
never log a token) · `@Transactional` on a controller (service layer only) · expose an entity from a
controller.

## Null-safety

NullAway checks all handwritten production and test code in JSpecify mode; generated sources are
excluded. Every new package needs a `package-info.java` containing
`@org.jspecify.annotations.NullMarked`, and the build rejects missing null-marking scopes and
`NullAway` suppressions. Use `@Nullable` only for genuine absence and place it on the precise type:
`List<@Nullable String>` permits null elements; `String @Nullable []` permits a null array reference.
Fix violations at the contract or implementation boundary. In tests, refine a nullable result once
before using it rather than adding duplicate assertions. Run `vp run test:server:unit` after changing
a nullness contract.

## Test tiers

| Tag            | Runs                                            | Command                                                                                                                                                |
| -------------- | ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `unit`         | no Spring context                               | `vp run test:server:unit`                                                                                                                              |
| `architecture` | ArchUnit + Modulith verification                | `vp run test:server:architecture`                                                                                                                      |
| `integration`  | full context + Testcontainers                   | `vp run test:server:integration`                                                                                                                       |
| `database`     | contract tests against a running PostgreSQL     | the _App Server: Database_ CI job: from `server/`, `./gradlew :application:databaseTest` with `SPRING_DATASOURCE_URL`, `_USERNAME` and `_PASSWORD` set |
| `live`         | real provider APIs, LLMs and sandbox containers | from `server/`, `./gradlew :application:liveTest`                                                                                                      |

Live-test credential gates and setup: [Testing Guide](../docs/contributor/testing.mdx#live-external-service-tests).

**JUnit tags, not filename patterns, select tests.** Keep descriptive `*Test` and `*IntegrationTest`
names for readers. `TestTierTaggingArchTest` uses JUnit discovery to reject untagged tests;
`vp run test:server:selection` proves actual tier coverage and disjoint integration shards using
Gradle's test dry-run reports in one invocation, without executing Spring contexts. Its inventories
write only to `application/build/test-selection/`, never execution or coverage reports.

Name tests `should[ExpectedBehavior]When[Condition]`. Controller-level integration tests extend
`AbstractWorkspaceIntegrationTest` (or a domain-specific base) and exercise access control through
`WebTestClient` + `TestAuthUtils` — the identity comes from the mock JWT **token string**, not from an
annotation.

**Rows written by earlier tests are already there.** Assert on the row you created, never on a count
or on "the only" result, and never write cleanup that another test depends on having run.

## Things that bite

- **`Issue` is SINGLE_TABLE with `PullRequest` as a subclass.** A JPQL query over `Issue` therefore
  returns pull requests too. Any query that means "issues only" must say `WHERE TYPE(i) = Issue`
  explicitly — see `MentorContextQueryRepository` and `ReviewableArtifactOwnershipRepository`. A test
  with a mocked repository cannot catch a missing `TYPE(…)`.
- **Integration tests do not prove migrations.** Their schema uses `ddl-auto: create`; run
  `vp run db:check-drift` to replay and compare the changelog
  (`docs/contributor/database-migration.mdx`).
- **A native `@Query` may not contain an apostrophe inside a `--` comment.** Hibernate reads it as the
  start of a string literal and the whole `ApplicationContext` fails to build, naming something else;
  `NativeQueryCommentArchTest` turns that into a named failure.
- **`EntityManager` is injected as a `@PersistenceContext` field**, not through the constructor
  (`WorkspaceMembershipService`, `GitHubUserProcessor`). Everything else is constructor injection via
  `@RequiredArgsConstructor`.
- **A bean that exists in one runtime role only is gated on that role** (`@ConditionalOnProperty` on
  a `RuntimeRole` property, as in `LeaderboardTaskScheduler`), and a consumer that must survive its
  absence takes `ObjectProvider` (`WorkspaceSyncTargetProvider`). An ungated consumer crash-loops the
  `worker` and `webhook` runtimes, which start a different slice of the context.
- **`SlackMessageService` resolves bot tokens per workspace at send time** via `ConnectionService`.
  There is no global `App` bean and no `slack.token` property; admins connect each workspace through
  `/oauth/callback/slack`.

## API and security conventions

- Workspace-scoped controllers carry `@WorkspaceScopedController` and take a `WorkspaceContext`.
  Authorization is declared, never assumed: `@RequireAtLeastWorkspaceAdmin`, `@RequireWorkspaceOwner`,
  `@PreAuthorize("hasAuthority('app_admin')")` for instance-admin routes.
  Anything reachable while impersonating goes through `ImpersonationGuard`. Admin mutations declare
  `@Audited` or `@AuditExempt` — an ArchUnit rule enforces it.
- Express lifecycle transitions as HTTP methods (`PATCH /workspaces/{slug}/status`), not RPC verbs.
- Every error is an RFC-7807 `ProblemDetail` produced by a `@RestControllerAdvice` — the rules are in
  `docs/contributor/api-error-handling.md`.
- **A schema type without a `DTO` suffix is dropped from the generated spec** unless it is listed in
  `OpenAPIConfiguration.ALLOWED_DOMAIN_OBJECTS`. The webapp client then simply has no type for it, with
  no error anywhere. Domain types the API deliberately exposes (`ProblemDetail`, `PracticeBinding`, …)
  are there for this reason.
- DTOs are records. All bare components are non-null under `@NullMarked`; add JSpecify `@NonNull` when
  that component must also appear in the generated schema's `required` list. A component the API may
  omit is `@Nullable`, never bare.
- **Never wrap a DTO component in `Optional<>`.** springdoc unwraps it to the value type but still marks
  it required, so the generated TypeScript declares it non-optional and its response transformer
  converts it unconditionally — a value the server never sends is typed as one it always sends. Use
  `@Nullable T`.

## Schema changes

Procedure: `docs/contributor/database-migration.mdx`. What the drift gate reads from an entity:

- An un-annotated field in a `@NullMarked` package is a NOT NULL column; a column that may be NULL
  carries `@Nullable` on the field.
- A foreign key backing a plain id column with no JPA association is named `sfk_*`; the gate
  ignores that prefix (`application/build.gradle.kts`, `liquibaseDiff`). An association's foreign key
  keeps `fk_*` and is drift-checked, so a misnamed constraint fails in either direction.

## Webhook receiver

`integration/core/webhook/` is the receive-side substrate: one `POST /webhooks/{kind}` entry point
resolves the kind through `IntegrationKindRouting` and hands it to `WebhookIngestPipeline`, which
selects that adapter's `WebhookSignatureVerifier` and `SubjectKeyDeriver` and publishes the verified
envelope to JetStream, all gated on `RuntimeRole.WEBHOOK_PROPERTY`. Configuration binds to `hephaestus.webhook.*` through
`core.webhook.WebhookProperties`, shared with auto-registration in
`integration/scm/gitlab/workspace/GitLabWebhookService`.

- **Production runs it in its own `webhook-server` container** — the same image as `application-server`
  with `SPRING_PROFILES_ACTIVE=prod,webhook` — so an app-server deploy does not interrupt reception.
  That matters because push events on GitHub and GitLab are **not manually redeliverable**: a webhook
  missed during a restart is lost.
- **Subject grammar**: `github.<owner>.<repo>.<event>`, `gitlab.<namespace>.<project>.<event>`. Dots
  inside a path segment become `~`; nested GitLab groups join with `~`. The consumer-side builder
  `integration.core.consumer.ConsumerSubjectMath#buildSubjectPrefix` must agree —
  `SubjectGrammarRoundTripTest` enforces it for every committed fixture.
- **ArchUnit guards the primitives**: `HexEncodingArchTest` (only `HexFormat.of()`),
  `LocaleSafetyArchTest` (no naked `toLowerCase`/`toUpperCase`). `application/build.gradle.kts` sets
  per-package JaCoCo branch floors, checked by the unit coverage task and `verification`: `test:server:unit`,
  `test:server:verification` and the _App Server: Unit and architecture_ CI job. Raise a floor when
  its package clears the next step.

## Container image

Paketo Cloud Native Buildpacks with Application CDS; no `Dockerfile`. From `server/`:
`./gradlew :application:bootJar`, then
`pack build hephaestus/application-server --path application/build/libs/hephaestus-application-*.jar --descriptor application/project.toml --run-image <the run image pinned in .github/workflows/cicd.yml>`.
Pinning and rationale: `docs/admin/buildpacks-cds-decision.md`.
