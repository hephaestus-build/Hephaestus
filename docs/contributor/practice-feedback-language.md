---
title: Practice feedback language
description: Product terms for practices, observations, feedback and people.
---

# Practice feedback language

This page owns product terms for practices, observations, feedback and people.
The [review glossary](./practice-review-glossary.mdx) owns review operation and runtime terms.
Use both pages with the [writing standard](./simplified-technical-english.md).
Define each term on one page only.
Keep technical names bold in the first table column.

Use feedback as an uncountable noun.
Write “three pieces of feedback.”
Use work as a plain, uncountable word, such as “your work.”
Name the concrete kind when known: pull request, merge request, issue, file, thread or document.
Use item only in contributor documentation, code and API contracts.

## Retired words

The Avoid columns apply to the named concept in UI, code, API contracts and prose.
They do not ban unrelated technical meanings, such as Slack channels, SQL triggers or operating-system signals.
Historical ADR bodies and released migration records keep their exact terms.

*Habit* is retired for a practice or a repeated pattern in someone's work.
*Leaderboard*, *league*, *league points*, *XP*, *level*, *score*, *rank* and *streak* are retired for activity.
[ADR 0045](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0045-activity-counts-work-and-never-ranks-people.md) owns that decision.
Use Position when Workspace activity sorts a count.

The [review glossary](./practice-review-glossary.mdx) defines these review terms.
This table records only their retired names.

| Term | Owning definition | Avoid for this concept |
| --- | --- | --- |
| **Item** | [Review glossary](./practice-review-glossary.mdx#review-terms) | artifact, unit, part, target, subject |
| **Event** | [Review glossary](./practice-review-glossary.mdx#review-terms) | signal, occasion, trigger, moment |
| **Scan** | [Review glossary](./practice-review-glossary.mdx#review-terms) | population review |
| **Origin** | [Review glossary](./practice-review-glossary.mdx#review-terms) | door, trigger mode |
| **Send** | [Review glossary](./practice-review-glossary.mdx#runtime-terms) | dispatch |
| **Feedback mode** | [Review glossary](./practice-review-glossary.mdx#review-terms) | autonomy |
| **Practice script** | [Review glossary](./practice-review-glossary.mdx#review-terms) | precompute, precompute script, practice program |

| Instead of | Write |
| --- | --- |
| 3 messages | 3 pieces of feedback |
| Messages | Feedback |
| No messages yet | No feedback yet |
| This message | This feedback |
| Message details | Feedback details |
| Findings behind this message | Observations behind this feedback |

## Practices

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Practice** | A defined way of working that Hephaestus uses to review work. | rule, detector |
| **Practice group** | A named set of related practices. | category, goal, learning objective |
| **Practice version** | One fixed definition of a practice. A definition change creates a new version. | revision |
| **Catalog** | The practices that an instance offers to its workspaces. Hephaestus supplies a catalog. | curated catalog |
| **Workspace practices** | The independent practices that one workspace uses for reviews. | workspace catalog |
| **Unassigned** | A practice that belongs to no practice group. | ungrouped, unbound |
| **Customize** | Change a practice definition copied from the catalog. | override |
| **Reset** | Replace local changes with the current catalog definition. |  |
| **Include / exclude** | Make a catalog entry available or unavailable for adoption by workspaces. | offer, retire |
| **Practice visual** | The optional picture that introduces a practice, with a description of its meaning. | illustration, hero image, diagram, explainer |
| **Practice guide** | The optional longer explanation of a practice in its Guide tab. | article, explainer, lesson, documentation |
| **Why it matters** | The practice guidance that explains why the practice is useful. | lead, intro, hero text |
| **What good looks like** | The practice guidance that describes work that meets the practice. | caption, subtitle, tagline |
| **Going well** | The standing label for work that meets a practice. Its guidance describes what the developer keeps doing. | holds as, praise, positive feedback |
| **Lapse** | Work that does not follow a practice, shown in a practice visual or guide. | bad example, anti-pattern, violation |
| **Figure** | One picture in a practice guide, with a description in the guide text. | illustration, image, embedded image |

## Observations

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Observation** | A record from reviewing one practice for one item at a version, about a developer when the practice has a role. | finding, detection, verdict |
| **Outcome** | The value of an observation: Met, Not met, Does not apply, or Could not determine. | presence, assessment, Not applicable, Undetermined |
| **Problem** | One problem that a Not met observation records at a place in the work, with its own severity. | observation, finding, signal, violation, lapse |
| **Severity** | How much a problem weighs: Critical, Major, Minor, or Trivial. | Info, informational |
| **Example** | An optional place in the work that an observation cites to show the practice done well. |  |
| **Open question** | A question about the work that the evidence cannot answer. It is never feedback. |  |
| **Not reviewed** | A selected item/practice pair that a closed review did not judge or reuse. It is not an outcome. |  |
| **Withheld candidate** | A candidate observation that [admission](./practice-review-pipeline.mdx#admit-results) did not accept. It records the reason. |  |
| **Withheld feedback** | Feedback that the server did not deliver. Its state is `WITHHELD` and its stop reason records why. |  |
| **Mark** | An admin record that an observation is incorrect or accepted. Removing the mark reverses it. | invalidation |
| **Marked incorrect** | An observation that a workspace admin marked as wrong when made. It remains in history. | invalidated, deleted, retracted |
| **Accepted** | An admin record that excludes an observation from open-problem lists. It does not change its outcome or cancel an incorrect mark. |  |
| **Developer** | The person an observation concerns. Feedback based on that observation goes only to that person. | learner |

Storage, API contracts, generated types, routes, search params, and component and file names call this record `Result` or `result`.
The product calls it an observation in UI text, user-facing responses and docs.
Do not rename those identifiers without a change that starts at the API contract.

### Observation outcomes

An observation applies only within its recorded evidence boundary.
It does not establish a developer's mastery or the correctness of a whole repository.

| Outcome | Label | Evidence requirement |
| --- | --- | --- |
| MET | Met | The evidence establishes that the practice standard was met, with complete relevant coverage. |
| NOT_MET | Not met | The evidence establishes at least one problem against the standard. |
| DOES_NOT_APPLY | Does not apply | An evidenced fact excludes the practice from this work. |
| COULD_NOT_DETERMINE | Could not determine | Available evidence cannot answer at least one material question. |

One observation has one outcome.
A Not met observation has one or more problems.
Other outcomes have no problems.
A script gives each problem its severity.
The server records the worst problem's severity on the observation.
Other outcomes have no severity.

With a problem and an unresolved question, the outcome is Not met.
With no problem and a material unresolved question, the outcome is Could not determine.
Met requires complete relevant coverage and no open question.
Does not apply requires the fact that excludes the practice.

The server can withhold a candidate observation at admission.
A withheld candidate creates no observation row.
It never becomes Met because its problems were refused.
A withheld attempt is shown separately from Not reviewed in a scan report.

Missing capture, a failed read, a timeout, a spent budget and a script error are operational failures.
They create no observation.
They are not open questions about the work.

### Problems and severity

A problem has a summary, an explanation, citations, a claim basis and a severity.
It can have a stable script key and a next step.
The next step states the change that the criteria require.
An optional improvement is not a problem.

| Severity | Meaning |
| --- | --- |
| Critical | Address the problem before the work can proceed. |
| Major | Address the problem before the work is complete. |
| Minor | The problem matters but does not block the work. |
| Trivial | The problem has a small effect. Address it when the cost is low. |

Trivial is a real gap.
It is not advice with no problem behind it.

A problem can compare with the same practice's previous result on the same item.
This comparison is decided for 1.0 and is not shipped yet ([ADR 0062](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0062-the-item-is-the-review-scope.md)).

| Problem state | Meaning |
| --- | --- |
| New | The previous comparable result did not have this problem. |
| Unchanged | The previous comparable result had this problem at the same place. |
| Updated | The previous comparable result had this problem, and its place or text changed. |
| No longer present | A complete review of a changed version does not find this problem again. |

A missing problem in an incomplete or failed review is not No longer present.

### Examples and open questions

An example has one citation and a short note.
Only Met and Not met observations can have an example.
An example cannot cite a place that a problem of the same observation cites.
It teaches the practice and is never delivered alone as praise.

An open question states what the evidence cannot answer and what would settle it.
Only Not met and Could not determine observations can have open questions.
Could not determine requires at least one.
Hephaestus never sends an open question as feedback.

## Feedback

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Feedback** | Guidance that Hephaestus writes from observations for a developer. | message, feedbacks |
| **Practice feedback** | The full name for feedback when the surrounding text does not establish its context. It also names the product category: feedback on how developers apply engineering practices. | message, AI feedback, feedback item, ledger unit, AI mentoring, AI code review |
| **Destination** | Where feedback is intended to appear: on the work, on the practice page, or in conversation. | channel, location, surface |
| **Delivery** | Whether feedback is awaiting approval, ready, delivered, withheld, rejected, replaced, failed or unconfirmed. | surface |
| **Copy** | One appearance of feedback: a summary, line note, file note or Heph answer. | placement |
| **Decision** | An admin action on feedback: approve, reject, withdraw or restore. |  |
| **Response** | The developer's answer to feedback. Resolution and usefulness are separate dimensions. |  |
| **Disagreement** | A response that says the feedback is wrong and gives a reason. | dispute, disputed |
| **Withdrawn** | Feedback that an admin removed from the developer's practice page because its words were wrong. | deleted, retracted, invalidated |

### Destinations

| Value | Label | Purpose |
| --- | --- | --- |
| ON_WORK | On the work | Guidance about the current work, at the provider that holds it. |
| PRACTICE_PAGE | On the practice page | Private guidance about a pattern in the developer's work. |
| CONVERSATION | In conversation | Private feedback that Heph can show when it fits a conversation. |

A provider is a separate fact from a destination.
A Slack conversation and an app conversation have the same destination.
A destination does not establish a scientific level of feedback.
The [standard terms](./practice-review-glossary.mdx#standard-terms) explain the limits of that comparison.

Feedback in conversation counts as delivered only when Heph shows it to the developer.
A link alone does not establish delivery.
A record of an uncertain write does not establish delivery either.
Unconfirmed means a write began and its visibility is unknown.
A confirmed copy can retain its delivery time while another copy stays unknown.
Unknown visibility never permits an automatic repeat write.

A decision to withdraw feedback does not change its observations.
Restore reverses the withdrawal if the feedback remains eligible.
A disagreement does not change an observation or a standing by itself.
An admin can inspect it and mark an observation incorrect.
Removing a usefulness answer does not remove the developer's disagreement.

## People and product surfaces

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Account** | One person's record on the instance. It can exist before the person signs in. |  |
| **Member** | An account with membership in a workspace. |  |
| **Identity** | A provider login linked to an account. |  |
| **Workspace** | One team's tenant, with its connected repositories, members, practices and feedback. |  |
| **Instance** | One running deployment that hosts workspaces. |  |
| **Integration** | One connected provider: GitHub, GitLab, Slack or Outline. |  |
| **Heph** | The conversational assistant. | agent, bot, mentor |
| **Conversations** | The product area for conversations with Heph. | Mentor |
| **Mentor** | A person who guides a less experienced developer, such as a reviewer or an experienced colleague. Use it only for people. |  |
| **Hephaestus** | The application. | agent, AI mentor |
| **Practice profile** | The developer's private page for practice standing, changes and feedback. | practice dashboard, standings page |
| **Practice page** | The page for one practice within the developer's Practice profile. |  |
| **Standing** | Needs attention, Mixed feedback or Going well, read from the newest eligible decided work. A change to the work keeps its last decision until a newer review decides again. | score, grade, rating |
| **No standing yet** | What a developer surface shows for a practice or group that no review has given a standing in the window. The practice did not apply to the work, or no review decided it. It is not a fourth standing. **Not reviewed** (above, under Observations) is an item/practice pair, not this label. | Not reviewed, Nothing to report yet, not applicable |
| **Early read** | A standing based on fewer than three decided pieces of work. | preliminary score, provisional grade |
| **Practices across the workspace** | The page that counts, for each practice, the developers with current observations and the outcomes of those observations, without naming a developer. | course comparison, course figures, cohort view, benchmark, leaderboard |
| **Current observation** | An observation from the last 90 days, recorded under the practice's current version, that nobody marked incorrect. Only current observations count on Practices across the workspace. | recent result, valid result |
| **No current observations** | What Practices across the workspace shows for a practice or group with no current observation. Older or unverifiable observations can exist. | no data, no results yet, missing |
| **Typical range** | The middle half of the developers' values in a selected window. | percentile, quartile range, average, norm |
| **Activity** | The developer's page of work that needs them and work they did. | profile, stats |
| **Workspace activity** | Counts and lists of work for workspace developers or one team. | leaderboard, ranking, standings |
| **Contributions** | Requests opened, other people's requests reviewed, and issues opened in a period. Each reviewed request counts once. | score, points, GitHub-style contributions, activity score |
| **Position** | A developer's place when Workspace activity sorts a count. Equal counts share a place. | rank, placing, standing |
| **Automation** | Provider bots and machine users that a workspace admin excludes from people counts. | bots (for machine users), service account |
| **Public activity page** | Work counts and links from public repositories, available without sign-in. It has no practice observations or feedback. | public leaderboard, public profile, public workspace |
| **Workspace address** | A workspace's own host. Sign-in stays on the instance host. | subdomain, vanity URL, tenant URL |
| **Open work** | Open requests and assigned issues that need the developer or another person. | backlog, inbox, to-do |
| **Needs you** | Open work that requires an action from the developer now. | inbox, to-do, action items |
| **Covered** | A review request settled for now by another reviewer's decision, under the provider's approval state. | dismissed, done, resolved |
| **Reviewed by you** | A provider review request that the developer already approved or returned for changes. | done, completed, handled |
| **Requested from your team** | A GitHub review request to one of the developer's teams rather than to that developer. | team inbox, shared queue |
| **Chrome extension** | The browser extension that shows recorded observations for work open in a GitHub or GitLab tab. | plugin, add-on, Hephaestus overlay, extension channel |
| **Instance admin** | A person with the admin role for the instance. |  |
| **Workspace admin** | A person with the admin role in one workspace. |  |
| **Instance operator** | The person or team that runs the deployment. |  |

The workspace page never names developers, but small counts can disclose a person's standing.
It has no minimum count that prevents that disclosure.
Hidden members and repositories do not contribute to those counts.
The [Practice profile guide](/user/practice-profile) owns the user procedures and visibility details.

### Research terms

The research on Hephaestus and the product use different words for some concepts.
Use the product word in the product, its docs and its release notes.
Use the research word only when you quote or cite the research.

| Research word | Product word |
| --- | --- |
| AI-generated feedback on practice application | Practice feedback |
| Contribution | Work, or item in contributor docs. **Contributions** on Workspace activity is a separate count. |
| Assessment | Observation and its outcome |
| Delivery strategy: in-context, in-app, in-chat | Destination: On the work, On the practice page, In conversation |
| Mentor approves feedback | A workspace admin approves feedback in the Approve first feedback mode |
| Reply: agree, disagree, question, usefulness | Response (resolution and usefulness), Disagreement, or a question in the Heph conversation |

The outcomes, problem, severity and next step have the same names in both.
A destination matches a delivery strategy only by the place where feedback appears.
[Destinations](#destinations) states that a destination does not establish a level of feedback.

### Heph

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Heph conversation** | The one conversation that an account has with Heph in a workspace. The app shows all of it. A Slack direct message shows the part that the developer asked in Slack. | chat, session, chat history |
| **Heph thread** | A conversation that starts at one top-level message of the Heph conversation. It is one level deep. Each message has at most one. A Heph thread started in Slack also appears in Slack. UI and user docs say thread. | fork, branch, sub-chat |
| **Message** (Heph) | One question, answer or weekly digest in a Heph conversation. It never counts feedback. | chat message, entry (in UI), turn (in UI) |
| **Weekly digest** | A message that Hephaestus writes into a developer's Heph conversation at most once a week, when the developer turned it on. It links to feedback that reached the developer and says what changed on the Practice profile. It is not a destination and delivers no feedback. | summary, report, newsletter, recap |

The [review glossary](./practice-review-glossary.mdx#runtime-terms) defines the turn, the Heph host, the context folder, the context document, the context loader and the turn sandbox.
[ADR 0057](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0057-one-heph-conversation-per-developer.md) owns the decisions behind these terms.

### Member onboarding and AI choices

An account's AI choice applies in every workspace.
A connected identity does not grant membership or permission for AI processing.
The [AI provider guide](/admin/ai-providers#privacy) owns model tiers and their enforcement.

| Choice | Meaning |
| --- | --- |
| In-house | Allow new AI requests only to models declared In-house. |
| Cloud | Also allow new AI requests to models declared Cloud. |
| No AI | Refuse new AI requests and feedback delivery about the developer. |

No AI does not stop source sync, storage or authorized reads.
It does not retract a request already sent to a model.
A required choice that is missing is a policy refusal, not missing evidence.
The server checks the applicable choices of every person whose work it reads.
An unanswered choice is not explicit consent.
Where a choice is optional, the existing unchosen-model policy still applies.

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Member onboarding** | The first-visit setup for a person who already belongs to a workspace. |  |
| **AI choice** | The account-wide ceiling on where new AI requests may process the person's work. |  |
| **Operated by** | The admin declaration of who operates a model: Your organization or A provider. |  |
| **In-house** | A model declared as operated by the member's organization. |  |
| **Cloud** | A model declared as operated by a provider. |  |
| **Not declared** | A model with no declaration of who operates it. |  |
| **Not set up here yet** | The selected AI ceiling has no ready model in this workspace. |  |
| **Unavailable right now** | The needed account integration does not work. |  |

Member onboarding is not a request for access or research consent.
Its workspace configuration page uses the same name.
The first-visit page is owed while setup remains incomplete.
A model declaration does not verify location, retention or use for model training.
A model brand is a display choice, not evidence of its operator.

A missing brand has no logo.
Not declared models cannot serve either explicit AI ceiling.
They can serve only someone who has not chosen, where a choice is optional.
The admin row is Members who have not chosen.
A workspace can change its models without changing the member's choice.
Keep a choice visible when its model or integration is unavailable.

### Research use

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Research use** | The member's optional permission for a named research organization to use data within a stated research area. |  |
| **Research organization** | The organization named by the instance's research configuration. |  |
| **Pseudonymized** | Personal data whose identifying names are replaced by a code. It remains personal data. |  |
| **Anonymized** | Data from which nobody can identify a person, established by a re-identification test. |  |

The member label is Allow research use of my data.
Research consent is separate from the AI choice and from workspace membership.
The [privacy guide](/user/privacy) owns the developer's choices and access rights.
The [data map](/admin/dsms/personal-data-map) owns export, retention and erasure details.
A workspace admin cannot grant another person's research consent.

## Software technical names

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Runtime role** | Server, worker or webhook: the part of one application JAR that a container starts. |  |
| **Server** | The service that handles application requests. |  |
| **Worker** | The runtime role that executes reviews and Heph turns. |  |
| **Webhook** | A provider's HTTP request that reports an event. Also the runtime role that receives it. |  |
| **API** | An interface for software calls. |  |
| **UI** | The interface that a person uses. |  |
| **Pull request** | A GitHub request to merge a branch. |  |
| **Merge request** | A GitLab request to merge a branch. |  |
| **Repository** | A version-controlled project. |  |
| **Changeset** | A file that supplies a release note and version change. |  |
| **Writing standard** | The project policy for prose. |  |
| **Loading state** | A UI state while required data is not yet available. |  |
| **Shared models** | Defined in the [LLM cost vocabulary](./llm-cost-vocabulary.md). |  |
| **Own provider** | Defined in the [LLM cost vocabulary](./llm-cost-vocabulary.md). |  |

## Technical verbs

| Term | Meaning | Avoid for this concept |
| --- | --- | --- |
| **Assign** | Choose a model for a review purpose. Provider assignments to people keep the provider's meaning. |  |
| **Authenticate** | Verify an identity before access. |  |
| **Authorize** | Grant permission for an action. |  |
| **Configure** | Set a software system's parameters. |  |
| **Deploy** | Install a software version in an environment. |  |
| **Merge** | Combine a branch with another branch. |  |
| **Regenerate** | Produce a generated file again from its source. |  |
| **Sync** | Reconcile local data with its source. |  |
