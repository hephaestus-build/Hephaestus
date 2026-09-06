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
| **Observation**                     | One recorded result of reviewing one practice against one piece of reviewed work                                                                           | finding, detection                                                   |
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
the developer is supposed to do about it. The three do line up with the task, self-regulation and process
levels of Hattie & Timperley's model (ADR 0029), but a level is a claim about content that a destination
cannot enforce, so do not use the level as the name.

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

Use **practice autonomy** for who may authorize feedback: **Off**, **Review before sending**, or
**Send automatically**. **Off** stops the review. **Review before sending** composes in-context feedback but
requires a workspace owner or administrator to approve the exact proposal. **Send automatically** lets new
feedback proceed without that decision, subject to delivery policy. Do not call this a tier, loudness, reach,
or shadow mode: those names obscure the human authorization step.

Most practices inherit from their group, and most groups inherit from the workspace. Use **effective autonomy**
for the resolved value, **override** for a value set at the level being discussed, and **inherited** when a
parent supplied it.

For whether an instance entry is copied into new workspaces, use **include / exclude** (see the table
above). Do not use *shipped*, *offered*, *retired*, *ours*, *yours*, or *here* in catalog UI copy;
those terms expose implementation or depend on who is reading.
