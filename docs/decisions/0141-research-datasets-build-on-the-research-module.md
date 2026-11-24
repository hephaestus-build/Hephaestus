# ADR 0141: Research datasets build on the research module that main already has

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Builds on:**

- [ADR 0099](0099-aggregates-own-their-lifecycle-and-module-boundaries.md): module ownership.
- ADR 0125 (proposed in #4863): the disclosure rule.
- ADR 0127 (proposed in #4863): the research definer function.
- ADR 0140 (proposed in #4867): privacy facts and the journal bucket.

**Relates to:**

- [ADR 0149](0149-a-delivery-experiment-draws-only-unprompted-contact-per-person-and-per-decision.md): delivery experiments and the experiment pseudonym.
- [ADR 0142](0142-a-maintainer-signs-a-manual-release-check-and-an-offline-simulation-checks-contact.md): the simulation's reference traces.

## Context

Main has a research program in `practices/research` ([Research program](../admin/research-program.mdx)).
A person consents on the first-login screen.
An instance admin releases one dataset for each export through four public operations under `/admin/research-disclosures`.
Each export gets fresh pseudonyms from a random HMAC key that is never stored.
Removal checks and closure follow the dataset after its release.

The conversation agent adds new data that research may use: the structure of conversations and contact.
It also adds rows that concern more than one person, such as a review of one person's pull request by a teammate.
An export of such a row can disclose the teammate, who did not consent.
A delivery experiment ([ADR 0149](0149-a-delivery-experiment-draws-only-unprompted-contact-per-person-and-per-decision.md)) must also link a person's rows across exports, and it may need the guidance that Hephaestus wrote.

The real constraint is to add these sources without a second research system and without a new route to a person's data.

## Decision drivers

- Build on main's program, its operations and its admin page.
- A row leaves only with the consent of its subject. Another person's fields leave only with that person's consent.
- A dataset is large enough that a reader cannot pick out one person by elimination.
- A person's own messages never leave in a research dataset.
- No staff role gets a new read path to a person's rows.

## Considered options

1. **A second research system** beside main's program, with its own grants, exports and admin page. It duplicates main's program, and researchers would learn two of them.
2. **Differential privacy on each export.** It destroys the event-level sequences that the research questions need.
3. **Consent of the subject only,** as main checks today. A row that concerns two people discloses the one who did not consent.
4. **Drop every row that concerns a person without consent.** A review row drops out whenever its reviewer did not consent, so the subject's own data is lost.
5. **A minimum of 5 participants.** At 5, a small team can often identify one person from the rest. A staff count of 5 shows no person's rows. A dataset holds rows for each person, so it needs more.
6. **One stored key for every dataset.** A leak of that key links every dataset to people, also datasets of finished experiments.
7. **Main's program, with its rules extended and new sources (chosen).**

## Decision

Option 7.

1. **1.0 keeps main's program:**
   - The consent "Allow research use of my data" on the first-login screen, with its organization and scope.
   - Fresh pseudonyms for each export, from an HMAC key that is never stored.
   - One dataset for each export. Delete its sealed bytes after the single read or when the release is refused, canceled or expires.
   - Removal checks, recorded removals, and closure by deletion or anonymization.
   - The four public operations, with their paths and IDs.
2. **A minimum research dataset size of 20 participants.** `hephaestus.research.min-people` defaults to 20 and is never lower. Below it, a release gets `research-too-few-participants`.
   - Only one release can go below the floor. It is a study dataset of one delivery experiment, with the rows of its enrolled people.
   - Its recipient must be the research organization named in the consent of every subject in it.
   - Every other recipient needs the floor of 20.
   - The floor counts the subjects in the final dataset, after every filter.
   - The steward's counts of [ADR 0149](0149-a-delivery-experiment-draws-only-unprompted-contact-per-person-and-per-decision.md) follow the disclosure rule of ADR 0125 (proposed in #4863), not this floor.
3. **A row enters when its subject consents.** The subject is the person whose work or use the row describes.
   - The fields of any other person without covering consent are redacted, and the row stays, unless decision 11 drops it. An example is a reviewer who did not consent.
   - In a study dataset, a row's subject needs both: enrollment in that experiment, and research consent that names the recipient.
   - Teammates who did not enroll are always redacted.
4. **One experiment pseudonym for each person and each experiment.**
   - A study dataset gives each enrolled person one pseudonym for that experiment.
   - So the rows of two exports of one experiment link.
   - The experiment key stays encrypted. Only the sealed system build may use it.
   - The experiment's steward must approve the resulting hash and recipient before release.
   - It is the only stored pseudonym key. The close of the experiment destroys it ([ADR 0149](0149-a-delivery-experiment-draws-only-unprompted-contact-per-person-and-per-decision.md)).
   - Every other dataset keeps main's per-export pseudonyms, with a key that is never stored.
5. **Consent is checked again under one fence with the handoff.**
   - Consent, enrollment, the floor and the experiment key are checked in the same fence as the handoff of the bytes. Withdrawal and key destruction take that fence too.
   - A failed check stops the handoff.
   - A withdrawal after the handoff records the removal owed for the bytes already handed off.
6. **A new source, `MENTORING_STRUCTURE`.** It holds event sequences, outcomes, response types and timings. The default source stays `PRACTICE_REVIEWS`.
   - Delivery experiments add delivery decisions, pieces of work with the order of their reviewed versions, and feedback approvals.
   - The probability, draw and exposure columns enter only study datasets, and only for enrolled people who consented.
   - The delivery decisions of people who are not enrolled are never exported.
   - Traces carry Hephaestus's guidance text only for enrolled people whose consent names it.
   - The person's own messages never enter.
7. **The release receipt is a privacy fact** (ADR 0140, proposed in #4867).
   - A release needs a journal bucket (ADR 0140, proposed in #4867, decision 9).
   - Without one, the release command returns `journal-bucket-required`.
   - The first byte streams only after the receipt object is stored.
   - ADR 0140 (proposed in #4867) owns what the receipt journals, also the links that later removal checks need.
8. **An export reads through one research definer function, in two phases.** ADR 0127 (proposed in #4863) names the function as its fourth exception.
   - Build: a system job with no human reader calls the function and builds the dataset into sealed staging. It computes the content hash.
   - The function checks the consent of each row's subject, redacts every other person and applies the disclosure rule.
   - Approve: an instance admin and a research steward both bind their approval to that hash and the recipient.
   - The research steward is the instance role of ADR 0149.
   - A study dataset needs its own experiment's steward. The steward approves from the schema, the recipient and the counts, never from rows.
   - Release: only then does a grant for that recipient alone allow the single read of the sealed bytes. Main's admin download stays closed for datasets.
   - No staff role can call the function or read the bytes.
9. **No new research table for releases.** Main's `research_disclosure`, `research_disclosure_subject` and `consent_decision` stay.
10. **1.x adds** isolated evaluation, shadow evaluation and instance quality signals. They are additions to this module. The steward and period locks are 1.0, in ADR 0149.
11. **A dataset reveals only its safe fields.**
    - A release follows the disclosure rule of ADR 0125 (proposed in #4863). The definer function enforces it before the first byte.
    - The release schema is a closed set of safe fields.
    - Free text that cannot be redacted is dropped. Guidance text is dropped too when it quotes a private message or names a bystander.
    - A row that quotes a private message or names a bystander is dropped.
    - Research rows carry no provider item ID, no version or commit identifier and no exact timestamp. Times are coarsened to the release period.
    - Items and versions get pseudonyms that are random for each release. A subject keeps the pseudonym of decision 4.
    - No private-check signal enters a research row.

### What this changes in other ADRs

- ADR 0127 (proposed in #4863) gets the research definer function. Decision 8 names its checks and the recipient's grant.
- ADR 0125 (proposed in #4863) points to decision 2 for the recipient rule. Decision 11 applies its disclosure rule.
- [ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md) lists the narrower behavior of `releaseResearchDataset` as a change before 1.0 when this ADR is accepted. The admin no longer downloads a dataset.

## Consequences

- Good: research uses one program, one admin page and one set of operations.
- Good: a row about two people never discloses the one who did not consent, and the subject's data stays.
- Good: a restore cannot bring back a dataset release that the journal does not know.
- Good: research reads a person's rows through one function that no staff role can call.
- Bad: an instance with fewer than 20 consenting participants cannot release a dataset.
- Bad: an instance without a journal bucket cannot release a dataset, which main allows today.
- Bad: redacted fields make some rows harder to read.
- Bad: the closed schema and the dropped free text remove material that some studies want.
- Bad: an admin cannot download a dataset. Only the approved recipient's grant gets the bytes.
- Bad: the experiment key is a stored link to people until the experiment ends.
- Bad: guidance text needs its own consent, so research on wording covers only the people who gave it.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- A release with 19 consenting participants succeeds, counted after every filter.
- A row whose reviewer did not consent leaves with the reviewer's fields, or loses the subject's fields.
- Two releases of one experiment give a person different pseudonyms, or two other datasets share one.
- A dataset holds a person's own messages, or guidance text without consent that names it.
- A release with fewer than 20 people goes to a recipient other than the organization named in each subject's consent.
- A release succeeds without a journal bucket, or its first byte leaves before its receipt is stored.
- A routine other than the export job calls the research definer function.
- A staff account gets dataset bytes, or the bytes reach a recipient that both approvals do not name.
- A release leaves with a hash that differs from either approval.
- A withdrawal that races the handoff, in either order, neither stops the handoff nor records a removal owed.
- A dataset holds a field outside its schema, or text that quotes a private message or names a bystander.
- A dataset holds a delivery decision of a person who is not enrolled, or experiment columns outside a study dataset.

## Revisit trigger

- A research question needs the person's own messages or isolated evaluation.
- A release on a real instance fails the minimum size three times in a row.

## Sources

- GDPR, [Article 89](https://gdpr-info.eu/art-89-gdpr/). Processing for research needs safeguards, and pseudonymization is named as one. This gives decisions 1 and 5.
- GDPR, [Article 4(5)](https://gdpr-info.eu/art-4-gdpr/). Pseudonymized data needs its key kept apart. A key that is never stored is the strongest form. This keeps main's unstored key, and it limits the one stored key to an experiment's lifetime.
