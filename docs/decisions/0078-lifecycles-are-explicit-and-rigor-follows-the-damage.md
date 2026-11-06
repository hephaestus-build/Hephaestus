# ADR 0078: Lifecycles are explicit, and rigor follows the damage

**Status:** Accepted
**Date:** 2026-11-06
**Authors:** Felix T.J. Dietrich
**Relates to:** [Lifecycles](../contributor/lifecycles.md) (the current rules), [ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md) (dependencies), [ADR 0075](0075-a-transaction-is-one-use-case-and-talks-to-nothing.md) (transactions).

## Context

A search for enums named `*Status`, `*State`, `*Phase`, `*Stage`, `*Lifecycle`, `*Outcome` or `*Disposition` finds 84 in the server.
Their writers are spread over services and repository queries.
Only three lifecycles state their legal transitions.
54 enum columns have no CHECK list.
Most of these hold no lifecycle, but `workspace.status` and `person_data_request.state` do.

An inventory of November 2026 read the code and counted rows on staging.
Leases, fences and retries held up well inside one lifecycle.
Most defects sat where one lifecycle hands over to another:

- A closed review could keep a practice as done with no result, or still queued. Three paths led there.
- A failed erasure could not retry by itself, and its log said that it would.

A row count or a read of the code found these. Neither needed a model checker.

Published experience points the same way, with limits that we note:

| Source | Finding | Limit for us |
| --- | --- | --- |
| [Yuan et al., OSDI 2014](https://www.usenix.org/conference/osdi14/technical-sessions/presentation/yuan) | In a study of 198 user-reported failures, simple tests of error handling could have caught the faults in 58% of the catastrophic failures. | Five storage and compute systems, not a product like ours. |
| [Bailis et al., SIGMOD 2015](https://amplab.cs.berkeley.edu/wp-content/uploads/2015/06/feral-sigmod2015.pdf) | Applications use validations in the application tier far more than database transactions, and concurrency can break some of these validations. | 67 Rails applications. |
| [Newcombe et al., CACM 2015](https://cacm.acm.org/magazines/2015/4/184701-how-amazon-web-services-uses-formal-methods/fulltext) | TLA+ found design bugs in concurrent protocols. One had a trace of 35 steps and had passed design review, code review and testing. | Large replicated systems. |
| [Davis et al., PVLDB 2020](https://arxiv.org/abs/2006.00915) | Checking traces of a real server against an abstract spec was impractical. Generating tests from a small spec worked. | Two case studies at one company. |

## Decision drivers

- A wrong transition can cost trust, cross a tenant boundary, or lose data. These cost more than stuck work.
- A rule that a reviewer must remember fails. A database constraint or a test does not.
- A check must fit how the code writes state. Most writes are compare-and-set SQL, not entity methods.
- Few dependencies (ADR 0072).

## Considered options

1. **A state machine framework such as Spring Statemachine.** Rejected. It keeps its own state. Our writes are compare-and-set SQL under leases. Thus, it would add a dependency and a second record of each state.
2. **A plain enum, one transition table, and the database.** Chosen.
3. **Formal models of every lifecycle.** Rejected. Most defects sit in rollups and error paths. A model of one machine does not contain them.
4. **A property-testing library such as jqwik.** Rejected for now. A walk over a transition table needs a seeded loop of a few lines. Shrinking gives little, because the walks are short.

## Decision

Each lifecycle states its legal transitions once, and the database refuses what it can express.
Rigor rises with the damage of a wrong transition.
[Lifecycles](../contributor/lifecycles.md) holds the levels.

A formal model is optional, because our evidence does not yet show that one finds a defect that the other levels miss.
We try one small model of a concurrent protocol and keep it outside CI.
A model states a design. It does not prove the code. We do not check traces of the code against it.

## Consequences

- A new lifecycle costs one table and one constraint. The contributor guide says how.
- A CHECK on old rows needs a data repair first. Pre-1.0 rows can break a rule that today's code keeps.
- Rollups stay outside any CHECK, so level 5 carries them. A metric only shows a defect after it happens.
- A transition table beside compare-and-set SQL is a second statement of each rule. Tests keep them the same.

## Revisit trigger

- A defect escapes that a framework would have prevented.
- A property test needs shrinking to be read.
- A formal model finds a defect that the other levels missed. Then we model the other concurrent protocols and consider a CI step.
- A formal model finds nothing. Then we stop adding models.
