# ADR 0149: A delivery experiment draws only unprompted contact, per person and per decision

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Builds on:**

- ADR 0139 (proposed in #4865): delivery decisions.
- ADR 0140 (proposed in #4867): privacy facts and the journal bucket.
- [ADR 0141](0141-research-datasets-build-on-the-research-module.md): research datasets.
- [ADR 0142](0142-a-maintainer-signs-a-manual-release-check-and-an-offline-simulation-checks-contact.md): the agreement floor.
- ADR 0145 (proposed in #4867): the runtime fingerprint.

**Relates to:**

- ADR 0120 (proposed in #4862): the start policy.
- ADR 0121 (proposed in #4862): the composer, the required problems and graded work.
- ADR 0122 (proposed in #4862): the withheld copy.
- ADR 0125 (proposed in #4863): research is the one carve-out, and the disclosure rule.
- ADR 0133 (proposed in #4865): check-ins and scheduled turns.

## Context

Hephaestus decides in code whether to contact a person when nobody asked.
It posts a copy on the work after a seal, and it starts a message when a private result arrives.
ADR 0139 (proposed in #4865) records each of these decisions, with the policy's result and the delivery.

An operator who wants to compare two delivery rules needs random assignment that people agreed to.
Assignment for each team cannot tell the rule apart from the team.
The consent "Allow research use of my data" does not tell a person that a draw may hold a result back.

A copy on the work has more readers than its author. Teammates and reviewers read it, and they did not enroll.
The person who runs an experiment may also teach the people in it.
That person must not decide from the outcomes whether the experiment goes on.

The real constraint is a fair comparison of delivery rules that never holds back what a person asked for, or a Critical problem.

## Decision drivers

- A draw never takes away what a person asked for, an agreed check-in, or a Critical problem.
- A person enrolls in each experiment on purpose, and a withdrawal acts at once.
- Every draw is recorded with its probability.
- The composer is never forced to post, and never forced to stay silent, by a rule it cannot see.
- People who did not enroll are not studied.
- Someone who sees no outcomes can stop an experiment at once.
- A result is comparable only when the configuration behind it is known.

## Considered options

1. **No experiments. Analyze the logs.** The decisions that stayed silent leave no trace, and every comparison is confounded by who got feedback.
2. **Assignment for each team.** It cannot tell the rule apart from the team, and a course has few teams.
3. **One fixed arm for each person for the whole experiment.** A person in the silent arm loses all unprompted feedback for months. It also cannot measure effects within one person.
4. **Draw every kind of contact.** A draw would then hold back requested results and agreed check-ins.
5. **A silent arm that the composer must respect.** The composer would get a turn and be told to stay silent. That breaks its required-problem rule, and a model would decide dispatch.
6. **Run experiments under research use alone.** The person never learns that a draw may hold a result back.
7. **Experiments as an optional module after 1.0.** The draw on the copy on the work must happen in the seal's transaction, before any composer turn. A module added later needs that hook in the core anyway.
8. **A draw for each person and each decision of unprompted contact (chosen).** It runs under enrollment, with a steward and period locks.

## Decision

Option 8.

1. **A delivery experiment** belongs to one workspace. It names its practices, the probability of speaking, its start and its end.
   - It holds only practices that read provider records. Slack and Outline stay conversation channels in an experiment.
   - Its practices do not run in Approve first. An expert's approval or edit would change the treatment.
   - An experiment starts only on an instance with a journal bucket (ADR 0140, proposed in #4867, decision 9).
   - Otherwise the start command returns `journal-bucket-required`. Startup stops while an experiment runs with no bucket.
2. **Only unprompted delivery decisions are drawn, one draw for each decision.**
   - Two kinds are drawn: the copy on the work and an unprompted message.
   - An agreed check-in, a requested result and a stepping-back message are recorded and never drawn. A check-in fires as agreed.
   - An answer to the person's message is not a decision to contact. It has no record and no draw.
   - The server draws a uniform number in the decision's transaction. It speaks when the number is below the probability.
   - No assignment for each team exists.
3. **A decision is drawn only when every condition holds.** Otherwise the policy alone decides, and the row records why it was not drawn.
   - An experiment of the workspace is running, and every practice of the decision is in it and not stopped.
   - The person is enrolled and not paused, and the practice is not On request for them.
   - No problem of the decision is Critical. A Critical problem is never drawn.
     This exception does not bypass the policy or the agreement floor ([ADR 0142](0142-a-maintainer-signs-a-manual-release-check-and-an-offline-simulation-checks-contact.md) decision 13).
   - The policy would speak. A policy silence is never drawn.
   - For a copy on the work, the workspace allows draws on it (decision 5).
4. **Enrollment is apart from research use.**
   - **Enroll** and **Withdraw** have the same size and style.
   - **Withdraw** ends the person's draws and holds at once. It is a privacy fact (ADR 0140, proposed in #4867).
   - A restore reapplies the withdrawal, so a restore never enrolls the person again.
   - Hephaestus never asks a person to enroll.
5. **The copy on the work and the people who did not enroll.**
   - A silent draw on a copy on the work also withholds it from teammates and reviewers.
   - So draws on the copy on the work need a workspace admin to allow them for that workspace. The admin can turn them off again, which ends those draws at once.
   - Teammates who did not enroll are not subjects. A study dataset holds a row only when its subject enrolled and gave research consent ([ADR 0141](0141-research-datasets-build-on-the-research-module.md) decision 3).
   - A withheld copy leaves the work as it would be without Hephaestus. Teammates get the no-Hephaestus baseline, never a changed copy.
   - Graded work uses the fixed renderer for every copy (ADR 0121, proposed in #4862, decision 10). A graded workspace cannot allow draws on the copy on the work.
   - Marking a workspace's work as graded ends its draws on the copy on the work at once. Draws on unprompted messages go on.
6. **The experiment's columns.** For an enrolled person, the `delivery_decision` row of ADR 0139 also holds the probability, the draw and the exposure.
   - A delivery decision has an immutable key and the set of result IDs that it covers.
   - The draw and any hold are written in the decision's transaction, before the starter of the copy or the message.
   - The assignment, the realized action, the provider delivery and the exposure are separate columns. Each ends in a named terminal disposition.
   - The exposure is the first card open, notification open and seen message. Each fills its column once, through `reportClientState`.
   - A held result stays visible on its card. The exposure shows whether the person saw it anyway, which the analysis needs.
   - These columns enter only consented study datasets of enrolled people ([ADR 0141](0141-research-datasets-build-on-the-research-module.md)). No product or staff surface shows them.
7. **`STUDY_SILENCE` is a refusal reason.**
   - A silent draw on a copy on the work settles the copy `WITHHELD` with the reason `STUDY_SILENCE`, in the seal's transaction. The work place's inbox item settles with the same reason.
   - No composer turn starts. So the composer is never forced to post the required problems in a silent draw, and the required-problem rule holds.
   - No `DELIVERY_WITHHELD` item is written, so the person's thread hears nothing of it.
   - The item card shows the copy as not posted. No reason names the draw, and the card offers **Request a new copy**.
   - A silent draw on an unprompted message drops its inbox item with the same reason.
8. **A silent draw holds the results of its decision until the hold ends.**
   - A **practice opportunity** is the first admitted review of a new item version, for one person and one practice. This ADR owns the term.
   - No unprompted starter cites a held result. A turn that a schedule starts skips it: check-ins, the weekly reflection, the Monday focus and goal follow-ups.
   - A later unprompted message for a held result is not drawn again. Its row records the hold as the reason.
   - After a copy that spoke, a later unprompted message for the result is a new decision with its own draw.
   - A turn that another result starts does not read the held result.
   - The item card and the practice page show a held result as any result, with no label. The draw decides only whether Hephaestus speaks first.
9. **The hold ends at the first of these events:**
   - The next practice opportunity of the same person and practice.
   - The person asks. A turn that the person started reads the result, or the person uses a card control on it. No citation is needed.
   - The person withdraws.
   - The steward stops the practice.
   - The experiment ends (decision 12).
10. **The steward and the stop switch.**
    - The instance role `RESEARCH_STEWARD` holds the stop switch of each experiment practice. The account that created an experiment cannot be its steward.
    - When the researcher also teaches the people in the experiment, a colleague holds the steward role or operates the instance. Operator access is logged.
    - The steward sees disagreements and correction requests for each experiment practice. Each count follows the disclosure rule of ADR 0125 (proposed in #4863). The steward never sees a person or a later outcome.
    - Stop ends new draws on that practice at once and ends its holds. Until Resume, enrolled people get that practice's feedback only when they ask.
    - Stop and Resume record the steward and the time.
11. **The run fingerprint, the period lock and help modes.**
    - The server writes `agent_run.config_digest` when it serves the run descriptor. It covers the practice versions, feedback modes, destinations, help defaults, the model assignment and the prompt digest.
    - With the runtime fingerprint of ADR 0145 (proposed in #4867), it is the run fingerprint. An audit counts runs without one.
    - A period lock freezes the same settings for one workspace from a start time. A change gets `period-locked`, and a new practice version waits.
    - Unlock ends the period at once. Lock and Unlock record the admin and the time.
    - For an enrolled person, each help mode change is recorded with its time, in an append-only `help_mode_transition` row.
12. **An experiment ends at its configured end, and a separate close destroys its key.**
    - The end stops all draws and ends all holds. Nobody can enroll after it.
    - Between the end and the close, the steward can still approve releases with the experiment pseudonyms. A withdrawal still acts and records the removal it owes.
    - The close destroys the experiment's pseudonym key ([ADR 0141](0141-research-datasets-build-on-the-research-module.md)), and this cannot be undone. No later release with those pseudonyms is possible.
    - The close needs an explicit confirmation. The admin confirms with the experiment's name, after the console states that the key is destroyed for good.
    - The destruction is a privacy fact. A restore reapplies it, so a restore never brings the key back.
13. **One follow-up notice, through the steward.**
    - Its purpose is a researcher's follow-up with the people who were in one experiment, such as an interview invitation.
    - The steward owns one notice for the whole cohort of the experiment and approves its text and purpose.
    - The researcher cannot select individual targets. The notice goes to every enrolled person with a current follow-up grant who matches criteria registered before the study.
    - The steward approves the cohort criteria before the study. The server rejects criteria that name or single out a person, including predicates equivalent to a target list.
    - A person allows a follow-up when they enroll, and can revoke it. Each send checks that the person holds a current follow-up grant and has not withdrawn.
    - The steward sends it through Hephaestus before the close. The researcher gets no receipt for any person.
    - A sent count shows only under the disclosure rule of ADR 0125: at least 5 people, for each period.
    - This is the one permitted follow-up. A second or overlapping notice for the experiment is refused. It reaches only people who enrolled in that experiment.
    - It is not recruiting. Hephaestus never invites a person to enroll or to allow research use.

### What this changes in other ADRs

- ADR 0122 (proposed in #4862) decision 8 points to decision 7 here. It is the one withheld copy without a `DELIVERY_WITHHELD` item.
- ADR 0121 (proposed in #4862) keeps its required-problem rule. A silent draw starts no composer turn, so the rule never applies to it. Decision 5 adds the graded-work guard.
- ADR 0133 (proposed in #4865) keeps check-ins out of draws. Decisions 8 and 9 own the held result and the end of its hold.
- ADR 0139 (proposed in #4865) owns delivery decisions. This ADR adds their experiment columns. Their action column becomes the assignment, apart from the realized action and the delivery (decision 6).
- ADR 0140 (proposed in #4867) gets the journal kinds `EXPERIMENT_WITHDRAWN` and `EXPERIMENT_KEY_DESTROYED`. An experiment needs its journal bucket.
- [ADR 0141](0141-research-datasets-build-on-the-research-module.md) gets the experiment pseudonym as the one stored pseudonym key.

## Consequences

- Good: a delivery rule is compared under random assignment that each person agreed to.
- Good: every draw is visible later, with its probability.
- Good: no draw holds back a requested result, an agreed check-in or a Critical problem.
- Good: the person who runs an experiment cannot keep it going against contested feedback that the steward sees.
- Good: one draw holds a result for every unprompted starter, so no other path announces it.
- Bad: `STUDY_SILENCE` is the one place where the core withholds a copy without telling the person. It comes from the research, not from the product.
- Bad: it still sits in the 1.0 core. The copy is drawn in the seal's transaction, before any composer turn. An optional module has no such hook.
- Bad: a silent draw also withholds the copy from teammates and reviewers who did not enroll. They get the no-Hephaestus baseline on that work.
- Bad: an enrolled person gets fewer unprompted messages and copies on the work.
- Bad: an instance without a journal bucket cannot run a delivery experiment.
- Bad: a period lock blocks configuration changes that an admin may need.
- Bad: a researcher who also teaches the people in the experiment needs a colleague as steward or operator. Without a second person, the experiment cannot run.
- Bad: the experiment key stays stored until someone closes the experiment. An analysis that is not finished at the close cannot link its pseudonyms again.
- Bad: draws on one person can affect later decisions, so an analysis must model carry-over.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- ExposureOnceTest fails when an exposure column is filled twice, or filled for a person who is not enrolled.
- DeliveryDecisionOutcomeTest fails when a draw to speak that is muted, cancelled or not delivered reads as spoken.
- StudySilenceSettlesCopyTest fails when a silent draw starts a composer turn. CriticalNeverDrawnTest fails when a Critical problem is drawn.
- CopyDrawNeedsWorkspaceTest fails when a copy is drawn without the workspace admin's choice, or in a graded workspace.
- HeldResultSkippedTest fails when a scheduled turn, a check-in included, cites a held result. It also fails for a later unprompted message or another result's turn.
- HeldResultReleaseTest fails when a hold does not end at the next practice opportunity or a person's ask.
  It also fails when a hold outlasts Withdraw, Stop or the end.
- PerDecisionAssignmentTest, ExperimentEnrollmentTest and WithdrawEndsDrawsTest fail on a draw for each team, a shared enrollment or a late withdrawal.
- StewardNotAuthorTest and StewardViewNoOutcomeTest fail when the author acts as steward, or the steward's view shows a person or an outcome.
- RunFingerprintTest and PeriodLockTest fail on a run without a digest, or a change under a lock.
- ExperimentNeedsJournalTest fails when an experiment starts, or keeps running after startup, on an instance without a journal bucket.
- KeyDestroyedTest fails when a release with a closed experiment's pseudonyms, or its notice, succeeds after the close.
  It also fails when one succeeds after a restore.
- FollowUpNoticeTest fails when the notice reaches a withdrawn person or one without a follow-up grant. It also fails on a target that a researcher selects, or a second notice.
  It also fails on a sent count that ADR 0125 suppresses, including any period with fewer than five people.

## Revisit trigger

- A researcher needs a draw on a kind of contact that this ADR never draws.
- A steward stops the same practice in two experiments because of contested feedback.
- A workspace admin turns off draws on the copy on the work because teammates miss the copy.

## Sources

- Klasnja et al., [Microrandomized trials](https://doi.org/10.1037/hea0000305). Each decision is randomized for each person, and availability is recorded. This gives decisions 2 and 3, and the delivery decision of ADR 0139.
- Boruvka et al., [Assessing time-varying causal effect moderation in mobile health](https://doi.org/10.1080/01621459.2017.1305274). The analysis needs the randomization probability of each decision. This gives the probability and draw columns of decision 6.
