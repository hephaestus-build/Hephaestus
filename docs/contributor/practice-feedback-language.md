---
title: Practice feedback language
description: The normative product vocabulary for observations, feedback, practices and groups.
---

# Practice feedback language

Use these product terms in user-facing interfaces, documentation, and release notes.

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
| **Practice feedback**               | Guidance written from observations and addressed to a developer — both the whole and the countable unit                                                    | message, AI feedback, feedback item, ledger unit                     |
| **Delivery**                        | Whether one piece of feedback was prepared, delivered, withheld, failed, or replaced                                                                       | placement, surface                                                   |
| **Channel**                         | Where one piece of feedback is intended to appear — a fact about that piece, not a workspace setting; the destinations are the `FeedbackChannel` constants | destination, surface, reach                                          |
| **Practice feedback about a habit** | Practice feedback prepared for one developer about what recurs across their work, readable by nobody else — the `IN_APP` channel                           | my feedback, private view, profile, reflection, reflection dashboard |
| **Reviewed work**                   | A pull request, merge request, issue, or conversation being reviewed                                                                                       | artifact, target                                                     |
| **Developer**                       | The person an observation is about                                                                                                                         | learner                                                              |
| **Contributor**                     | A repository role relevant to review eligibility                                                                                                           | user, when the role matters                                          |
| **Heph**                            | The conversational assistant                                                                                                                               | agent, bot                                                           |
| **Mentor**                          | The product area for conversations with Heph                                                                                                               |                                                                      |
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
to work it; nothing does — `FeedbackDeliveryState.PREPARED` advances to `DELIVERED` on the next chat turn
that links the feedback. The label is **Prepared for conversation**.

**Observation, not finding**, for the measurement — in copy, URLs, API schema, field names, and Java.
Delivery uses `FeedbackAnchor` and `InlineFeedbackChannel`; the mentor uses `link_observation` and
`data-observation`. The schema and wire protocol have no aliases for the retired vocabulary. The only
compatibility surface is an HTTP redirect from the former reviews URL so existing bookmarks do not break;
it carries no data contract and new links never use it.

Everything else is an observation, including the read APIs and the reviews UI — the surfaces an operator
actually reads are exactly where the banned word does the most damage. Those names are
`ReviewObservation`, `ReviewObservationDetail`, `ReviewBoundObservation`, `observationId`, and the
workspace-admin route `/workspaces/{workspaceSlug}/practices/reviews/observations`. Developer-scoped
reads live under `/workspaces/{workspaceSlug}/practices/observations`. Apart from the web-route redirect, a
*finding* in this subsystem is a bug.

**Practice autonomy** and **effective autonomy** are the glossary's
([Practice autonomy](./practice-review-glossary.mdx#practice-autonomy),
[Autonomy inheritance](./practice-review-glossary.mdx#autonomy-inheritance)). The product labels for the
three values are **Off**, **Review before sending** and **Send automatically**; a value set at the level
being discussed is an **override**, and one a parent supplied is **inherited**.

For whether an instance entry is copied into new workspaces, use **include / exclude** (see the table
above). Do not use *shipped*, *offered*, *retired*, *ours*, *yours*, or *here* in catalog UI copy;
those terms expose implementation or depend on who is reading.

## Observation assessment axes

An observation first records whether the practice could be assessed. For assessed observations,
`presence` records whether the specific behavior named by the observation occurred; `assessment`
records whether that behavior is desirable (`GOOD`) or undesirable (`BAD`) in the evidenced context.
The practice supplies the stable expectation. Each observation identifies a precise behavior and keeps
that referent unchanged through its presence claim, assessment, rationale and evidence. Different
behaviors under one practice may have different assessments.

A practice review may record zero, one or several observations for the same practice and reviewed
work. Each assessed observation names one behavior; independent material concerns may need separate
observations. One positive observation does not declare the whole piece of work satisfactory.
Outcome is derived:

| Status | Presence | Assessment | Derived outcome | Severity |
| --- | --- | --- | --- | --- |
| ASSESSED | PRESENT | GOOD | POSITIVE | null |
| ASSESSED | ABSENT | GOOD | NEGATIVE | required |
| ASSESSED | PRESENT | BAD | NEGATIVE | required |
| ASSESSED | ABSENT | BAD | POSITIVE | null |
| NOT_APPLICABLE | null | null | null | null |
| UNDETERMINED | null | null | null | null |

For verification guidance, relevant instructions may be PRESENT/GOOD, misleading instructions
PRESENT/BAD, and a needed restart check ABSENT/GOOD. State which behavior the evidence establishes:
partial instructions are present even when a necessary part is absent. Select the observation that
best explains the correction; do not count both formulations of the same problem as separate lapses.

ABSENT/GOOD requires an evidenced need for the named behavior in this context; an omitted optional
improvement is not a lapse. ABSENT/BAD requires a relevant opportunity for the undesirable behavior and a complete bounded
search establishing its absence. Optional guidance is not undesirable merely because it is unnecessary.
An empty change supplies no meaningful verification occasion and can be NOT_APPLICABLE. A positive
outcome establishes neither general correctness nor a requirement to send praise.

NOT_APPLICABLE needs an evidenced fact ruling out the prerequisite occasion. UNDETERMINED needs an
unresolved question after the relevant evidence was captured and read. Neither is a judgment of the
developer, and neither contributes to assessed-outcome trends. Missing, truncated or failed required
capture belongs to the review's readiness/coverage record; it creates no observation.

Severity, feedback routing and result counts use the derived outcome, not assessment alone. The
server, sandbox tool contract and database reject contradictory axes. They never manufacture a
judgment by defaulting status, presence, assessment or severity. See the
[review pipeline](./practice-review-pipeline.mdx) for capture and delivery boundaries.

### Reading an observation without reversing its meaning

Read an assessed observation as: **“This behavior is present or absent. Doing that behavior would
be good or bad in this context. Together, those facts yield the outcome.”** For absence,
GOOD/BAD still evaluates the named behavior, not the act of omitting it.

For example, consider a change to saved settings and its verification instructions:

| Named behavior | Evidence and context | Presence / assessment | Outcome |
| --- | --- | --- | --- |
| Describe a restart check for saved settings | The instructions say to save a setting, restart, and confirm the saved value. The change makes persistence material. | PRESENT / GOOD | POSITIVE |
| Describe a restart check for saved settings | The same persistence requirement applies, but the complete recorded guidance supplies no restart check. | ABSENT / GOOD | NEGATIVE |
| Tell the reviewer to erase their personal settings before checking persistence | The instructions require erasing the very state the check needs to preserve, without an isolated disposable fixture. | PRESENT / BAD | NEGATIVE |
| Tell the reviewer to erase their personal settings before checking persistence | The revision removes that destructive step and supplies a non-destructive check; inspection of the complete guidance confirms the step is absent. | ABSENT / BAD | POSITIVE |

These are illustrative observations, not four labels to assign to the same deficiency. In each pair,
the named behavior and its assessment stay fixed; the evidence for presence changes. The last row
has a concrete avoidance occasion. Merely failing to mention deletion on unrelated work does not
earn that observation. If required guidance was not captured, none of these absence claims is
available. If there is no material change to check, record the evidenced lack of occasion instead.

## Member onboarding and AI choices

**Member onboarding** is first-visit setup for a person who already belongs to a workspace. It is not a request for access, an approval flow, or research consent. A workspace *needs setup* while its page is still owed. The member-facing name for the account-wide answer is **your AI choice**; **Member onboarding** names the workspace owner's configuration page. Do not call either one a *workspace preference* or a *workspace default*.

An admin declares a model's **Operated by** fact as **Your organisation** or **A provider**. Hephaestus derives **In-house** (`IN_HOUSE`) or **Cloud** (`CLOUD`) from that declaration; without it, the model is **Not declared** (`UNDECLARED`). The declaration does not verify a provider's location, retention, or training terms. An optional model brand is a separate display label selected by the admin, not evidence of who operates the model. Unknown brands have no logo. The [admin AI provider guide](/admin/ai-providers#data-handling-and-members-ai-choices) owns the configuration and routing details.

A developer's **AI choice** is a ceiling held by the account across its workspaces:

| Answer | Meaning |
| --- | --- |
| **In-house** (`IN_HOUSE_ONLY`) | Allow only declared In-house AI processing for practice reviews and Heph. |
| **Cloud** (`CLOUD`) | Also allow declared Cloud processing; In-house still qualifies. |
| **No AI** (`NO_AI`) | Stop new AI requests for this person's work in practice reviews and Heph. It does not stop source synchronisation, storage, authorised reads, or requests already sent. |

A choice is a boundary, not a selection of today's models. A workspace can add or remove models without asking the member again. **Not declared** sits outside both explicit AI ceilings and can serve only someone who has not chosen where a choice is optional. **Members who haven't chosen** is the admin label for that row. An AI answer with no ready model here is **not set up here yet**; a broken account integration is **unavailable right now**. Keep both visible without silently changing the person's answer. The [user privacy guide](/user/privacy#your-ai-choice) owns the member-facing data boundary.
