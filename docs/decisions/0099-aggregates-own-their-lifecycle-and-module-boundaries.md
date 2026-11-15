# ADR 0099: Each module owns its aggregates, declares its dependencies, and changes a lifecycle only through the aggregate

**Status:** Accepted

**Date:** 2026-11-12

**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0003](0003-spring-modulith-adoption.md)

**Builds on:** [ADR 0075](0075-a-transaction-is-one-use-case-and-talks-to-nothing.md), [ADR 0076](0076-reads-are-shaped-like-the-screen.md), [ADR 0078](0078-lifecycles-are-explicit-and-rigor-follows-the-damage.md), [proposed ADR 0084](https://github.com/hephaestus-build/Hephaestus/blob/d1983d96ee7f7dcbbb6e6370b7777fe123d1b3f5/docs/decisions/0084-every-statement-on-workspace-data-binds-its-workspace.md), [proposed ADR 0095](https://github.com/hephaestus-build/Hephaestus/blob/d1983d96ee7f7dcbbb6e6370b7777fe123d1b3f5/docs/decisions/0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md)

## Context

Spring Modulith verifies 21 modules, and ArchUnit holds the provider boundary.
Inside a module, most entities are data holders: 81 of 131 have class-level setters.
Services and other modules set lifecycle fields directly.
This shape caused defects:

- A suspend could undo a purge (#3757). The rule "PURGED is final" is written separately in seven places, and a provider adapter writes the workspace status.
- Three review end paths left practices done or queued (#3878). On 2026-11-11, a fourth path, the replaced review, still skipped the end meter.
- Two code paths create a workspace.
- 84 files outside the workspace module import its repository. At least 10 of them save the tenant root.
- 13 modules do not declare their dependencies. `config` is open, but only 5 files import it.
- A passkey change writes its audit record after commit, and a process stop loses it.
- ADR 0003 promised generated module diagrams. None exist, and its hand-written module list has drifted.

## Decision drivers

- A rule that one class owns cannot be forgotten by a caller.
- Trust, tenancy and data loss cost more than stuck work (ADR 0078).
- Most lifecycle writes are compare-and-set SQL (ADR 0078). A rule must work for them.
- Few dependencies (ADR 0072).

## Considered options

1. **Keep services as the owners of rules, and review each change.** It costs nothing now. It has already let the defects above through.
2. **Adopt jMolecules and Spring Data aggregate events.** jMolecules adds a dependency and `Association` types that we do not use. Spring Data publishes aggregate events only on `save` and `delete`, so our compare-and-set writes would not publish.
3. **Aggregates in plain Java, owned by their module, checked by Spring Modulith and ArchUnit.** It costs about 8 to 10 days of refactoring, in standalone pull requests.

## Decision

Option 3.

1. **A lifecycle enum implements `Lifecycle` and states its legal transitions once.**
   An entity changes a lifecycle field only through its own named methods, which refuse an illegal move. It has no setter for that field or for a column that the state implies.
2. **Rigor follows ADR 0078.**
   A lifecycle that can cost trust, tenancy or data has a database trigger for its transition table.
   This includes `review` and `workspace`.
   A terminal review's status and a purged workspace's status cannot change.
   Use the `guard_agent_run` form.
3. **The end of an aggregate runs in one place.**
   If an end closes children or emits a metric or event, one method does that work.
   The method is package-private and belongs to the owning module.
   Every end path calls it.
4. **An aggregate is created in one place.** A static factory or one service method of the owning module creates it.
5. **Only the owning module writes an aggregate.**
   Its entities and write repositories are not in the module's base package.
   Other modules use the module's named interfaces: queries that return records (ADR 0076) and commands that call the aggregate.
   An entity refers to an aggregate of another module by its id, not by a JPA association.
   Exception: the SCM kernel and its adapters, which write the shared mirror (ADR 0095).
6. **Every module declares `allowedDependencies`** by module and named interface. A module is `Type.OPEN` only with a reason in an ADR. Today that is the five integration modules of ADR 0095. `config` is closed. `integration` and `productfeedback` carry `@ApplicationModule`.
7. **An after-commit listener names its repair.** A side effect that no poll repairs is an `@ApplicationModuleListener` (ADR 0075).
8. **The context map is generated.** The architecture tier writes the Spring Modulith documentation, and CI keeps it as an artifact. `system-design.mdx` names each module's relationship (Shared Kernel, Conformist, Anticorruption Layer, Customer/Supplier) once.
9. **We do not use** jMolecules, Spring Data `@DomainEvents` or `AbstractAggregateRoot`, a typed workspace id, Context Mapper, or `@Externalized`.

## Consequences

- Good: the defects listed in Context cannot recur through a path that forgot a rule. A new coupling between modules shows in a `package-info.java` diff.
- Good: the lifecycle levels of ADR 0078 get one shared contract and one walk test.
- Bad: about 8 to 10 days of refactoring before 1.0, mostly mechanical.
- Bad: a cross-module read needs a named-interface query instead of a direct repository call.
- Bad: the after-commit allowlist exists until it is empty. It must be empty before 1.0.

## Confirmation

| Rule | Check |
|---|---|
| 1 | `LifecycleContractTest`: every `Lifecycle` enum has no exit from a terminal state and reaches each state. ArchUnit `LifecycleSetterArchTest`: no `@Entity` method named `set*` takes a `Lifecycle` type. |
| 2 | Database tests: the `review` and `workspace` triggers refuse the illegal update. |
| 3 | `ReviewClosureIntegrationTest`: each end path (complete, cancel, refused selection, replaced) asserts its rows and the `practice_review.ended` increment. |
| 4 | ArchUnit: `new Workspace()` appears only in `Workspace`. |
| 5 | ArchUnit `RepositoryOwnershipArchTest#shouldReachNoRepositoryOfAnotherModule` over `ApplicationModules`: a class that uses a Spring Data repository is in that repository's module, except for the SCM-kernel pairs. `ModulithVerificationTest` refuses access to a non-exposed package. |
| 6 | `ModuleDeclarationTest#shouldDeclareEveryModuleAndItsAllowedDependencies`: every module declares `allowedDependencies` other than `ApplicationModule.OPEN_TOKEN`, and only listed modules are `Type.OPEN`. `ModulithVerificationTest` refuses an unlisted dependency. |
| 7 | `AfterCommitRepairArchTest`: every after-commit `@TransactionalEventListener` is listed with its repair. |
| 8 | `ContextMapCoverageTest#shouldDescribeEveryModuleInTheContextMapTable`: every module of `ApplicationModules` is in the table of `system-design.mdx`. `ContextMapCoverageTest#shouldGenerateTheContextMapFromVerifiedModules` verifies the module graph and writes `build/spring-modulith-docs`. |
| Ratchet | The list of rule 7 names each after-commit listener that still lacks its repair. The test fails when the target version has no pre-release suffix and the list is not empty. The target is `hephaestus.releaseVersion`, or the root `package.json` version by default. |
| 9 | Not enforced. Review refuses a new dependency (ADR 0072). |

## Revisit trigger

- A defect reaches a query with a wrong id of the right type. Then reconsider a typed workspace id.
- Spring Modulith adds support that makes the open integration modules unnecessary.
- A cross-module flow breaks that a one-module test would have caught. Then adopt `@ApplicationModuleTest`.

## Sources

- [Vaughn Vernon, Effective Aggregate Design](https://www.dddcommunity.org/library/vernon_2011/): the four aggregate rules. One aggregate per transaction.
- [Martin Fowler, AnemicDomainModel](https://martinfowler.com/bliki/AnemicDomainModel.html): why services that set fields are not a domain model.
- Eric Evans, Domain-Driven Design (2003), ch. 6 and 14: repositories serve aggregate roots. Context-map relationships.
- [Spring Modulith, Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html): allowed dependencies, the base package as API, open modules.
- [Spring Modulith, Documenting Application Modules](https://docs.spring.io/spring-modulith/reference/documentation.html): the `Documenter`.
- [Spring Modulith, Application Events](https://docs.spring.io/spring-modulith/reference/events.html): the publication registry and `@Externalized`.
- [Spring Data, Publishing Events from Aggregate Roots](https://docs.spring.io/spring-data/commons/reference/repositories/core-domain-events.html): events publish only on save and delete.
- [jMolecules](https://github.com/xmolecules/jmolecules): what we decline.

## Already priced and declined

- ADR 0003 option 2, all shared-looking packages open: rejected again. Rule 6 allows open modules only with an ADR reason.
- ADR 0003 option 3, named interfaces on the genuine kernels: rejected for the integration kernels by ADR 0095. Rule 6 keeps that.
- ADR 0003 option 4, ArchUnit only: rejected. Spring Modulith verification stays.
