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
`presence` says whether the practice's fixed target behaviour meets its criterion; `assessment` says
whether that target is desirable (`GOOD`) or undesirable (`BAD`). Assessment is not the verdict on the
work. The outcome is derived, never separately annotated:

| Status | Presence | Assessment | Derived outcome | Severity |
| --- | --- | --- | --- | --- |
| ASSESSED | PRESENT | GOOD | POSITIVE | null |
| ASSESSED | ABSENT | GOOD | NEGATIVE | required |
| ASSESSED | PRESENT | BAD | NEGATIVE | required |
| ASSESSED | ABSENT | BAD | POSITIVE | null |
| NOT_APPLICABLE | null | null | null | null |
| UNDETERMINED | null | null | null | null |

Keep the target and its assessment fixed across observations of the same practice revision. For the
GOOD target “usable verification guidance,” inadequate partial guidance is ABSENT/GOOD: acknowledge
what exists in the rationale and explain which required part is missing. Do not switch to a BAD target
mid-review. For a BAD target such as swallowed exceptions, bounded, evidenced absence is
ABSENT/BAD, a positive outcome—not NOT_APPLICABLE and not proof of general correctness.

NOT_APPLICABLE needs an evidenced fact ruling out the prerequisite occasion. UNDETERMINED needs an
unresolved question after the relevant evidence was captured and read. Neither is a judgment of the
developer, and neither contributes to assessed-outcome trends. Missing, truncated or failed required
capture belongs to the review's readiness/coverage record; it creates no observation.

Severity, feedback routing and result counts use the derived outcome, not assessment alone. The
server, sandbox tool contract and database reject contradictory axes. They never manufacture a
judgment by defaulting status, presence, assessment or severity. See the
[review pipeline](./practice-review-pipeline.mdx) for capture and delivery boundaries.

## Member onboarding and AI choices

**Member onboarding** is first-visit setup after existing workspace membership. It is not an
admission request, approval, entitlement, or provisioning workflow. It is configuration and a
member's own choice, not research consent. Retired words: *processing location*, *Unclassified*,
*AI preference*, *Workspace preferences*, *Workspace default*.

**Data handling** is what an admin declares about a model: two facts, never an inference from a
hostname, connection or provider kind. **Operated by** is *Your organisation* or *A provider*;
**Kept after the reply** is *Nothing* or *For safety checks*. Declaring them is also the admin's
confirmation that the model's terms rule out training on what it receives; Hephaestus does not
accept a model that trains on developers' work. An admin-only **note** carries region, agreement
and renewal date and is never shown to developers. No retention period appears in product copy.

From the two facts Hephaestus derives one **data-handling tier**, strictest first, and shows it as a
badge with a label and an icon everywhere a model appears:

| Tier | Label | Derived when | What a developer reads |
| --- | --- | --- | --- |
| `IN_HOUSE` | **Stays in-house** | operated by your organisation | Runs only on systems your organisation operates. |
| `PROVIDER_NOT_KEPT` | **Provider, nothing kept** | a provider, nothing kept | A provider under terms your organisation accepted processes it and keeps nothing after the reply. |
| `PROVIDER_KEPT` | **Provider, kept for safety checks** | a provider, kept for safety checks | A provider under terms your organisation accepted keeps it for a limited time for safety checks, which its staff may read if flagged. |
| `UNDECLARED` | **Not declared** | either fact missing | Never shown to developers; an admin to-do. |

A developer's **AI choice** is a ceiling: the loosest tier they accept in one workspace, or **No
AI**. The three AI answers are **Only in-house**, **Allow providers without content storage**
and **Allow storage for safety checks**; anything stricter than the answer also counts, nothing ever
switches a developer to a looser tier, and *Not declared* sits outside every ceiling. A member may
choose any answer regardless of what the workspace has bound today: consent is to a boundary, not
to the current inventory. The member-facing name for the choice is **your AI choice** on every
surface: the sidebar item, the heading, the notice and the copy.

**Members who haven't chosen** names the *Not declared* row on the workspace admin's model page: it
serves only members who have not chosen yet, where the choice is optional, and never a member who
chose. (*Workspace default* is retired here; it already names practice autonomy.)

Two phrases, two meanings, never swapped: an AI answer that no ready model covers is **not set up
here yet** (a workspace owner adds a model); an account link whose integration is broken is
**unavailable right now** (it does not hold the member up).

The sign-in and first-visit pages follow ten rules:

1. Heph speaks in the first person only inside `HephSays`, and introduces himself once, on the
   sign-in setup page.
2. Outside the bubble: plain second person, no "please", contractions allowed; alert titles start
   "Couldn't …".
3. Buttons: **Continue** moves on, **Save AI choice** saves before required account links are complete,
   **Save** on a return visit, **Skip for now** defers, **Sign out**
   only on the sign-in setup page, **Back to workspace** on a return visit; never Next, Finish,
   Submit or Done.
4. Section titles are the question or the noun.
5. Hints are one sentence and the primary button's accessible description.
6. *Not set up here yet* for an AI answer; *unavailable right now* for an account link. When only
   one of practice reviews and Heph is covered, Heph says *part of your choice* is not set up yet,
   never that the whole choice is.
7. Ask "a workspace owner" on the workspace page, "whoever runs this Hephaestus instance" on the
   sign-in page.
8. `{workspaceName}` in the heading and in Heph's intro; "this workspace" in body text.
9. "AI" when reviews and Heph are both meant; "practice reviews" or "Heph" when one is; never
   *model*, *LLM* or *agent*.
10. Both pages open on "Give me a moment — I'm fetching your setup." and close on "That's
    everything. Let's get to work."
