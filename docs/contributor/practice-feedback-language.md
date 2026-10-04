---
title: Practice feedback language
description: The normative product vocabulary for observations, feedback, practices and groups.
---

# Practice feedback language

Use these product terms in user-facing interfaces, documentation, and release notes.

This page is the project's list of technical names and technical verbs for
[Simplified Technical English](./simplified-technical-english.md).
The tables define names; the verb table defines actions.
The prose checks read the first column, so keep each term in bold.

This page owns the vocabulary for **observations and the feedback built from them**. The
[practice review glossary](./practice-review-glossary.mdx) owns the vocabulary for **the review
operation, the evidence contract, and the exact API, Java, and persistence names**, including
*practice review*, *binding*, *signal*, *evidence stance* and *practice autonomy*. A term is defined in
one of the two and cited from the other; when the two disagree, that is a bug in one of them, not a
choice for the writer.

Within a surface already titled **Practice reviews**, shorten **practice feedback** to **feedback**.
Use the full term when the surrounding context does not establish which kind of feedback is meant.
Within **Practice setup** or **Practice catalog**, shorten **practice group** to **group**.

**Practice group is the canonical noun at every layer.** Use `PracticeGroup`, `groupSlug`, and
`/practice-groups` in Java and HTTP contracts as well as **practice group** in product copy. *Practice
area*, `PracticeArea`, `areaSlug`, and `/practice-areas` are retired names, not internal synonyms.

| Term                                | Meaning                                                                                                                                                    | Avoid for this concept                                               |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| **Practice**                        | A defined way of working used to review work                                                                                                               | rule, detector                                                       |
| **Practice group**                   | A named collection of related practices                                                                                                                               | category, goal, learning objective                                   |
| **Unassigned**                      | Practices that are not in a practice group                                                                                                                  | ungrouped, unbound                                                   |
| **Observation**                     | One recorded result of reviewing one practice against one piece of reviewed work                                                                           | finding, detection, verdict                                          |
| **Marked incorrect**                | An observation a workspace admin [invalidated](./practice-review-glossary.mdx#invalidated-observations) because it was wrong when made; it stays in history  | deleted, retracted, superseded                                       |
| **Withdrawn**                       | A Practice profile card a workspace admin [withdrew](./practice-review-glossary.mdx#withdrawn-feedback) because its words were wrong; its observations are unchanged | deleted, retracted, invalidated                                      |
| **Disputed**                        | Feedback the developer answered as wrong, with an explanation workspace admins read on its observations; a later review of the same work holds the same claim back while it stands ([ADR 0022](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0022-observation-presence-assessment-and-schema-cleanup.md)) | rejected, contested, flagged                                         |
| **Practice feedback**               | Guidance written from observations and addressed to a developer — both the whole and the countable unit                                                    | message, AI feedback, feedback item, ledger unit                     |
| **Delivery**                        | Whether one piece of feedback was prepared, delivered, withheld, failed, or replaced                                                                       | placement, surface                                                   |
| **Channel**                         | Where one piece of feedback is intended to appear — a fact about that piece, not a workspace setting; the destinations are the `FeedbackChannel` constants | destination, surface, reach                                          |
| **Practice feedback about a way of working** | Practice feedback prepared for one developer about what recurs across their work, readable by nobody else — the `IN_APP` channel                           | my feedback, private view, profile, reflection, reflection dashboard |
| **Reviewed work**                   | A pull request, merge request, issue, conversation, or document being reviewed                                                                             | artifact, target                                                     |
| **Developer**                       | The person an observation is about                                                                                                                         | learner                                                              |
| **Contributor**                     | A repository role relevant to review eligibility                                                                                                           | user, when the role matters                                          |
| **Heph**                            | The conversational assistant                                                                                                                               | agent, bot                                                           |
| **Mentor**                          | The product area for conversations with Heph                                                                                                               |                                                                      |
| **Practice profile**                | The developer's own page: how their reviewed work stands across practice groups, how it developed, and one next step from Heph. A surface, not a channel   | practice dashboard, standings page                                   |
| **Chrome extension**                | The browser extension that shows what Hephaestus recorded about the work open in a GitHub or GitLab tab. A place to read context, not a channel: it delivers no feedback and never shows private feedback | plugin, add-on, Hephaestus overlay, extension channel |
| **Activity**                        | The developer's own page: what needs them, what they did over a time range, and the pull or merge requests and issues they did it on. Counts work, never scores it | profile, stats, contributions                                        |
| **Workspace activity**              | The same counts and lists for everyone in a workspace or one team, with members listed by name                                                             | leaderboard, ranking, standings                                      |
| **Open work**                       | Pull or merge requests awaiting someone's review, their own open pull or merge requests, and open issues assigned to them                                  | backlog, inbox, to-do                                                |
| **Needs you**                       | The open work that asks something of the developer now: review requests nobody has settled, and their own pull or merge requests returned to them or approved | inbox, to-do, action items                                           |
| **Covered**                         | A review request another reviewer's verdict settles for now: they requested changes, so the author acts first, or they approved and the provider counts the pull request as approved. The developer's own review never covers their own request. It waits with the rest of what waits on someone else; [Activity](/user/activity#needs-you) states the rule's limits | dismissed, done, resolved                                            |
| **Reviewed by you**                 | A review request the developer already approved or requested changes on, which the provider still lists — GitLab keeps a reviewer listed after their verdict, and the author asking again resets the reviewer's state. It waits with the rest of what waits on someone else | done, completed, handled                                             |
| **Requested from your team**        | A GitHub review request to a team the developer is in, and not to them; it names the team and waits with the rest of what waits on someone else | team inbox, shared queue                                             |
| **Holds as**                        | The one present-tense sentence a bundled practice reads as when it holds — what the developer keeps doing — shown on the Practice profile beside a practice that is going well, and written by [the catalogue rules](./practice-catalogue.md#changing-bundled-defaults) | strength summary, praise, positive feedback |
| **Hephaestus**                      | The application, named only where the application itself is the subject — installing it, an account linked to it, a release of it                          | agent                                                                |
| **Hephaestus default**              | A practice or group bundled with the running Hephaestus release                                                                                             | shipped entry                                                        |
| **Instance catalog**                | The set of practices a workspace may adopt from                                                                                                                              | curated catalog                                                      |
| **Workspace practices**             | Independent definitions used for reviews in one workspace                                                                                                  | workspace catalog                                                    |
| **Review rules**                    | Inputs and criteria that determine review behavior                                                                                                         | detector configuration                                               |
| **Developer guidance**              | Explanatory text that does not change review behavior                                                                                                      | learner guidance                                                     |
| **Customize**                       | Change a default or catalog-based definition                                                                                                               | override                                                             |
| **Include / exclude**               | Whether an instance entry is available for workspaces to adopt                                                                                                    | offer, retire                                                        |
| **No Hephaestus default**           | An instance-maintained entry with no bundled definition                                                                                                    | ours, yours                                                          |

Use provider-specific names such as **pull request** or **merge request** when the provider is known;
otherwise write **pull or merge request**.

*Habit* is retired for what a practice describes or what recurs across someone's work: say *practice*,
*way of working* or *repeated pattern*. A card that still shows retired words is wrong wording, and is
[withdrawn](./practice-review-glossary.mdx#withdrawn-feedback).

*Leaderboard*, *league*, *league points*, *XP*, *level*, *score*, *rank* and *streak* are retired for
activity and do not describe anything in the product
([ADR 0045](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0045-activity-counts-work-and-never-ranks-people.md)).

**Feedback is the countable unit, and there is no other word for it.** Do not reach for *message*, *item*,
*note*, or *entry* to get a noun that pluralises; *feedback* is uncountable, so the fix is to phrase the
count so the noun is not needed rather than to invent a second word for the same thing.

| Instead of                   | Write                                                      |
| ---------------------------- | ---------------------------------------------------------- |
| 3 messages                   | 3 pieces of feedback                                       |
| Messages                     | Feedback — a heading naming the collection needs no plural |
| No messages yet              | No feedback yet                                            |
| This message                 | This feedback                                              |
| Message details              | Feedback details                                           |
| Findings behind this message | Observations behind this feedback                          |
| Queued for conversation      | Prepared for conversation                                  |

How a piece of feedback on the Practice profile stops being open — **resolved by the work** or
**marked as addressed** — is the glossary's
([How feedback resolves](./practice-review-glossary.mdx#how-feedback-resolves)); the card, the
summary and the release notes use those two phrases and no other.

A column that counts per row is headed **Feedback** and the cell holds the number alone. Where a sentence
needs a singular subject, name what the feedback is *about* — "the feedback for this observation", not
"the message for this observation".

**Say what happens, not who does it.** On practice-review surfaces, use *practice review*, *review*,
*feedback*, or *observation* as the subject. Use **Hephaestus** when the application itself is the subject,
such as installation, account linking, or releases. Do not call the application or a review an *agent*.

**All three channel names say where the feedback lands, and nothing else.** `IN_CONTEXT` lands on the work
itself — a pull request summary or inline note, an issue comment. `IN_CHAT` lands in a turn of a
conversation, wherever that conversation runs: the in-app mentor, or Slack. `IN_APP` lands on the
developer's own practice pages, where nobody replies to it.

The mentor also renders inside the app, so the line between the last two is *dialogic or not*: ask **is it
a turn?** before **which screen?** A channel names a destination, never a cognitive level and never what
the developer is supposed to do about it. The three do line up with the levels of Hattie & Timperley's
model ([which is which](./practice-review-glossary.mdx#the-three-channels-are-three-levels)), but a
level is a claim about content that a destination cannot enforce, so do not use the level as the name.

`IN_APP` is the code noun — the enum constant and the `chk_feedback_channel` value. Do not call it a
*profile* or *reflection* channel: those words name a different surface or an outcome the system cannot
observe.
[ADR 0029](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0029-measurement-intervention-seam-and-channel-levels.md)
records the naming
decision. On an operator surface, the place reads **On their practice pages**.

**Feedback waiting for a mentor conversation is *prepared*, never *queued*.** A queue implies somebody has
to work it; nothing does — `FeedbackDeliveryState.PREPARED` advances to `DELIVERED` when a completed chat turn
shows the developer feedback about it: the mentor's `link_observation` call carries the words, and the reply
renders them in the app and in Slack. The label is **Prepared for conversation**.

**Unconfirmed** (`UNCONFIRMED`) is conversation feedback a completed turn linked with no record that the feedback
was shown. Its visibility is unknown, so it counts as neither delivered nor withheld, and it is never prepared again.

A **delivery family** is how a count of feedback groups the ten stored delivery states, so a summary
answers what an admin asks at a glance. There are six, and every state is in exactly one:

| Delivery family       | Delivery states it holds                                                      |
| --------------------- | ----------------------------------------------------------------------------- |
| **Awaiting approval** | Awaiting approval (`AWAITING_APPROVAL`)                                       |
| **Prepared**          | Prepared (`PREPARED`)                                                         |
| **Delivered**         | Delivered (`DELIVERED`), Partially delivered (`PARTIALLY_DELIVERED`)          |
| **Unconfirmed**       | Unconfirmed (`UNCONFIRMED`)                                                   |
| **Withheld**          | Withheld (`SUPPRESSED`), Rejected (`DISCARDED`), Replaced by newer (`SUPERSEDED`) |
| **Failed to deliver** | Failed to deliver (`FAILED`), Partially delivered · retries exhausted (`PARTIALLY_FAILED`) |

A family is a count's word and nothing else. Rejected and replaced feedback count as **Withheld**,
but a row, its badge and a list filter keep each delivery state's own word, and a count opens its list
filtered to exactly the states its family holds. Unconfirmed is a family of its own because counting
it as prepared would promise a delivery that will not happen.

**Observation, not finding**, for the measurement — in copy, URLs, API schema, field names, and Java.
Delivery uses `FeedbackAnchor` and `InlineFeedbackChannel`; the mentor uses `link_observation` and
`data-observation`. The schema, wire protocol and web routes have no aliases for the retired
vocabulary, and no address redirects from a retired word.

Everything else is an observation, including the read APIs and the reviews UI — the surfaces an operator
actually reads are exactly where the banned word does the most damage. Those names are
`ReviewObservation`, `ReviewObservationDetail`, `ReviewBoundObservation`, `observationId`, and the
workspace-admin route `/workspaces/{workspaceSlug}/practices/reviews/observations`. Developer-scoped
reads live under `/workspaces/{workspaceSlug}/practices/observations`. A *finding* in this subsystem is
a bug.

**Practice autonomy** and **effective autonomy** are the glossary's
([Practice autonomy](./practice-review-glossary.mdx#practice-autonomy),
[Autonomy inheritance](./practice-review-glossary.mdx#autonomy-inheritance)). The product labels for the
three values are **Off**, **Review before sending** and **Send automatically**; a value set at the level
being discussed is an **override**, and one a parent supplied is **inherited**.

For whether an instance entry is copied into new workspaces, use **include / exclude** (see the table
above). Do not use *shipped*, *offered*, *retired*, *ours*, *yours*, or *here* in catalog UI copy;
those terms expose implementation or depend on who is reading.

## Observation outcomes

An observation records whether the reviewed work meets one practice's criteria within the recorded
evidence boundary. It does not establish a developer's mastery or the correctness of the entire project.

| Outcome | Meaning | Severity |
| --- | --- | --- |
| `MET` | The captured evidence establishes conformance to the practice standard. | None |
| `NOT_MET` | The captured evidence establishes a material shortfall against that standard. | Required |
| `NOT_APPLICABLE` | An evidenced fact rules out the occasion to apply the practice. | None |
| `UNDETERMINED` | Adequate captured evidence was read, but a material question remains unresolved. | None |

`outcome` is the only result field. There is no separate presence, desirability, or derived result axis.
Criteria states the positive standard. An undesirable action and a required action that is missing can
both establish `NOT_MET`; the summary explains the actual shortfall without a second taxonomy.
`MET` requires conformance to the standard, not merely the presence of one desirable behavior.

A review records at most one observation per practice. Its outcome applies to the complete standard
within the evidence boundary; the rationale can describe several independent shortfalls. Do not
record both `MET` and `NOT_MET` for one practice. Provisional runtime submissions may be corrected
before server admission; admitted observations are immutable. A met observation does not require praise
or delivery.

A claim based on absence requires a complete bounded search, recorded in `evidence.search`.
Conformance based on avoiding harm requires exhaustive coverage of the relevant corpus. Missing an
optional improvement is not a shortfall. Citations and the rationale name the reviewed boundary.

`NOT_APPLICABLE` requires an inapplicability warrant identifying the fact that excludes the occasion.
`UNDETERMINED` requires an undecidability warrant naming the open question and what would settle it.
Neither contributes to the decided-outcome denominator. Report both separately when measuring
coverage: combining them hides the difference between no occasion and unresolved assessment.
Missing, truncated, or failed required capture is a review readiness failure and creates no observation.

For a change to saved settings, complete non-destructive restart instructions can establish `MET`.
Missing a required restart check, or instructions that erase the state under test, establishes
`NOT_MET`. An empty change may establish `NOT_APPLICABLE`. Conflicting captured requirements that
prevent a judgment may establish `UNDETERMINED`. A missing captured description establishes none of
these outcomes: capture must succeed first.

The sandbox, server, and database reject invalid outcomes and severity combinations. Delivery remains
an independent decision; see the [review pipeline](./practice-review-pipeline.mdx).

## Member onboarding and AI choices

**Member onboarding** is first-visit setup for a person who already belongs to a workspace. It is not a request for access, an approval flow, or research consent. A workspace *needs setup* while its page is still owed. The member-facing name for the account-wide answer is **your AI choice**; **Member onboarding** names the workspace owner's configuration page. Do not call either one a *workspace preference* or a *workspace default*.

An admin declares a model's **Operated by** fact as **Your organization** or **A provider**. Hephaestus derives **In-house** (`IN_HOUSE`) or **Cloud** (`CLOUD`) from that declaration; without it, the model is **Not declared** (`UNDECLARED`). The declaration does not verify a provider's location, retention, or training terms. An optional model brand is a separate display label selected by the admin, not evidence of who operates the model. Unknown brands have no logo. The [admin AI provider guide](/admin/ai-providers#data-handling-and-members-ai-choices) owns the configuration and routing details.

A developer's **AI choice** is a ceiling held by the account across its workspaces:

| Answer | Meaning |
| --- | --- |
| **In-house** (`IN_HOUSE_ONLY`) | Allow only declared In-house AI processing for practice reviews and Heph. |
| **Cloud** (`CLOUD`) | Also allow declared Cloud processing; In-house still qualifies. |
| **No AI** (`NO_AI`) | Stop new AI requests for this person's work in practice reviews and Heph. It does not stop source synchronisation, storage, authorised reads, or requests already sent. |

A choice is a boundary, not a selection of today's models. A workspace can add or remove models without asking the member again. **Not declared** sits outside both explicit AI ceilings and can serve only someone who has not chosen where a choice is optional. **Members who have not chosen** is the admin label for that row. An AI answer with no ready model here is **not set up here yet**; a broken account integration is **unavailable right now**. Keep both visible without silently changing the person's answer. The [user privacy guide](/user/privacy#your-ai-choice) owns the member-facing data boundary.

## Software technical names

Use these names for software concepts, not as substitutes for ordinary words.
The review glossary owns review-specific names; its link above remains their source.

| Term | Meaning |
| --- | --- |
| **Workspace** | One team's tenant and its connected work. |
| **Instance** | One deployment that hosts workspaces. |
| **Integration** | A connection to GitHub, GitLab, Slack, or Outline. |
| **Runtime role** | The server, worker, or webhook part of one application. |
| **Server** | The service that handles application requests. |
| **Worker** | The runtime role that executes background work. |
| **Webhook** | A provider's HTTP event request. |
| **API** | An interface for software calls. |
| **UI** | The interface that a person uses. |
| **Pull request** | A GitHub request to merge a branch. |
| **Merge request** | A GitLab request to merge a branch. |
| **Repository** | A version-controlled project. |
| **Changeset** | A file that supplies a release note and version change. |
| **Writing standard** | The policy for prose in this project. |
| **Loading state** | The UI state while data is not yet available. |

## Technical verbs

Use each verb only for the action in its definition.
Use its normal grammatical forms when necessary.

| Term | Meaning |
| --- | --- |
| **Authenticate** | Verify an identity before access. |
| **Authorize** | Give an identity permission for an action. |
| **Configure** | Set the parameters of a software system. |
| **Deploy** | Install a software version in an environment. |
| **Merge** | Combine a branch with another branch. |
| **Regenerate** | Produce an artifact again from its source. |
| **Sync** | Reconcile local data with its source. |
