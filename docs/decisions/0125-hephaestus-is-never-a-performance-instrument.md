# ADR 0125: Hephaestus is never a performance instrument

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0112](0112-practices-across-the-workspace-hides-counts-of-fewer-than-3.md)

**Amends:** [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) rules 19 and 21. [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) rule 16. [ADR 0047](0047-the-practice-profile-has-one-reader.md), its View as user exception. [ADR 0051](0051-practices-across-the-workspace-counts-developers.md), its smallest count.

**Builds on:** ADR 0124 (proposed in #4866)

**Relates to:** ADR 0141 and ADR 0149 (proposed in #4868), research datasets and delivery experiments. ADR 0139 (proposed in #4865), the decision record

## Context

A person speaks honestly about their work only when what they say cannot reach their employer.
Hephaestus now keeps goals, lessons and a long conversation for each person.
An employer path to any of it would turn the product into a monitoring tool.

On main, three such paths exist.
An instance admin reads a member's Practice profile and conversation through **View as user** ([ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) rule 16).
A workspace owner can turn on **Per-person results for admins**.
Workspace admins read every observation with its work item ([ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) rule 19).
[ADR 0112](0112-practices-across-the-workspace-hides-counts-of-fewer-than-3.md) left one question open: whether admins should still see work items.

Under the EU AI Act, a system that evaluates the performance of workers is high-risk.
The person who approves Hephaestus for a team still needs to see that it works.
The real constraint is that no staff route can tell anything about one person.

Research on Hephaestus needs sequences of events for each person.
A researcher can also be course staff, and so the employer or teacher of the people whose data they study.

## Decision drivers

- No staff member can learn what one person said, received or did in Hephaestus.
- The approver sees that practices improve, without a view of people.
- Support can still help a person who asks for it.
- Text promises only what Hephaestus enforces. The operator can read its own servers.
- Research gets data for each person only by that person's consent, and never through a staff role.

## Considered options

1. **Keep the per-person views, with names hidden.** Names are inferable from the work item, the time and the team. A hidden name is a per-person view.
2. **No aggregate at all.** The approver sees no value and pushes for a person view.
3. **Usage dashboards.** They measure use of Hephaestus, which says nothing about practice and reads as monitoring.
4. **No per-person path, staff approvals and corrections on items only, and one monthly practice outcome for each practice (chosen).**

## Decision

Option 4.

1. **Staff get no per-person path.** Staff are instance admins, workspace admins and workspace owners. They get no conversation or feedback detail, no usage counts, no impersonation, and no person-level export or preview.
2. **View as user and Per-person results for admins are deleted.** Their routes, settings and audit rows go at the cutover.
3. **Staff see items only inside an approval or a correction.** Each such view shows exact fields and expires.
   - An approval under Approve first shows the copy, the item link and the practice for 14 days.
   - A correction shows the observation and the person's explanation for 30 days.
   - No other staff view lists observations.
4. **No staff route returns an account ID beside an outcome.** A generated test sweeps every staff route.
5. **One monthly practice outcome for each practice.** Workspace admins see one value for each practice and month. An example is "verification steps present in 62 % of reviewed pull requests".
   - It counts only observations on the work.
   - It needs at least 5 people, the floor of every staff-visible aggregate. `SmallCounts` holds that floor.
   - Suppression hides a value that a total would give back.
   - It never shows use of Hephaestus or any person.
6. **Practices across the workspace hides counts of fewer than 5 people.** This replaces the 3 of ADR 0112. The other rules of ADR 0112 stay, with 5 in place of 3:
   - A split shows all its cells or only its total. The reader is not told why.
   - A typical range is read from at least 5 other developers.
   - `SmallCounts` on the server is the one home of the threshold.
7. **Exports.** The scan export covers only repositories that are public on the provider. The admin export of a person's data becomes a sealed delivery to that person.
8. **Personal data reaches the person by self-service.** The person uses the data access export and the portable download ([ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md)).
9. **Support sees content only through a support snapshot.** The person shares one snapshot with a named recipient and can revoke it. The snapshot shows its last read and its read count.
10. **The operator statement.** Settings show **Who runs this server**, with the operator's name and a link to its statement.
    - The first screen promises only what Hephaestus enforces: "Acme's admins and managers cannot see this in Hephaestus."
    - "Your employer sees nothing about you" appears only where the operator declares that it is not the employer.
11. **Nothing ranks people by practice feedback or by any assessment of a person.**
    - Activity stays as [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) decides it. Its sorted people table counts provider work that members already see.
    - Activity never shows data from conversations, personal records or practice feedback.
12. **The copy on the work names its developer,** as the work itself does. The admin guide tells the operator that this copy is public to the employer.
13. **Research is the one carve-out.** Sequences for each person exist only in research datasets of people who allowed research use.
    - Who may receive a dataset, and when it needs the floor of 20: ADR 0141 (proposed in #4868) decision 2.
    - Who runs a delivery experiment and who holds its stop switch: ADR 0149 (proposed in #4868).
    - No staff role and no product surface sees the sequences or the decision record.

### What this changes in other ADRs

- [ADR 0112](0112-practices-across-the-workspace-hides-counts-of-fewer-than-3.md) is superseded. Decision 6 keeps its mechanism and raises the threshold. Its open question gets the answer of decision 3: admins see work items only inside an approval or a correction.
- [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) rule 19 changes. Admins no longer read the observations of the review and delivery pages, except inside an approval or a correction. Rule 21 goes, because no read-only user view exists. Rule 4 stays: Activity sorts people by counts of provider work, which assess no practice.
- [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) rule 16 keeps "Nobody acts as another account". The read-only user views go.
- ADR 0131 (proposed in #4862) supersedes ADR 0082. Its decision 5 keeps the conversation for its owner only, without View as user.
- [ADR 0047](0047-the-practice-profile-has-one-reader.md) loses its View as user exception. The Practice profile has one reader with no exception.

## Consequences

- Good: no staff route can tell anything about one person. A test proves it for every route.
- Good: the approver sees a practice improve over months.
- Good: support still works, by the person's own act.
- Bad: an instance admin cannot debug a person's problem from that person's pages. The person must share a snapshot.
- Bad: workspace admins lose the observation lists that they use to find wrong results. They see a result only when a person asks for a correction.
- Bad: with 5 in place of 3, Practices across the workspace shows more total-only splits. A workspace of 30 to 40 developers sees this often. ADR 0112 priced this cost and declined it. We now accept it, because the page is part of the promise that no one learns about one person.
- Bad: teams of fewer than 5 people see no monthly practice outcome. The page says why.

## Confirmation

Not enforced yet. The implementation adds these tests:

- A generated staff route test fails when a staff route returns an account ID beside an outcome.
- `SmallCountsTest` fails when a count of 1 to 4 people shows, or a split with such a cell shows a cell.
- A practice outcome test fails when a figure from fewer than 5 people shows a value.
- A route test fails when any route reads another account's conversation, records or Practice profile.
- `ArchitectureTest` fails when a class named for View as user or Per-person results for admins exists.

## Revisit trigger

- A works council or a data protection authority asks for a staff path that this ADR forbids. It gives a legal reason.
- Workspaces of 30 to 40 developers report that Practices across the workspace is empty most of the time.
- Approvers drop Hephaestus because the monthly practice outcome does not show its value.

## Sources

- Regulation (EU) 2024/1689, the [AI Act](https://eur-lex.europa.eu/eli/reg/2024/1689/oj), Annex III point 4(b). AI systems "to monitor and evaluate the performance and behaviour of persons" in work relationships are high-risk. Hephaestus has no per-person staff path, so it stays outside that use.
- Microsoft, [Viva Insights privacy settings](https://learn.microsoft.com/en-us/viva/insights/advanced/setup-maint/privacy-settings). The minimum group size is "the minimum aggregation threshold for insights", and it must be "at least five". This sets the threshold of decision 6.

## Already priced and declined

- [ADR 0112](0112-practices-across-the-workspace-hides-counts-of-fewer-than-3.md#considered-options) option 1: no threshold. Still declined.
- [ADR 0112](0112-practices-across-the-workspace-hides-counts-of-fewer-than-3.md#considered-options) option 2: a threshold of 5. ADR 0112 declined it because it hides most splits. This ADR now chooses it, for the reason in Consequences.
- [ADR 0112](0112-practices-across-the-workspace-hides-counts-of-fewer-than-3.md#considered-options) option 3: hiding cells until the hidden cells hold enough people. Still declined. It closes the subtraction but not the inference. Decision 6 keeps "all cells or only the total".
- [ADR 0112](0112-practices-across-the-workspace-hides-counts-of-fewer-than-3.md#considered-options) option 5: noise or rounding. Still declined.
- [ADR 0051](0051-practices-across-the-workspace-counts-developers.md#considered-options): a smallest count of 10. Still declined. Every staff-visible aggregate uses 5, the monthly practice outcome included. One floor gives one answer for a workspace of six.
