# ADR 0073: Product feedback is worked on in GitHub

**Status:** Accepted (2026-10-31)
**Date:** 2026-10-31
**Authors:** Felix T.J. Dietrich
**Relates to:** ADR 0025 (job claims and orphan recovery), ADR 0026 (the governed model catalog) and ADR 0068 (the worker runs model calls).

## Context

Product feedback is a report about Hephaestus itself: a bug, an idea, or anything else.
It is not practice feedback.
Until 1.0, a report went into the instance database (`productfeedback/ProductFeedback`).
The instance administrators got an email without the text.
They read the report in an inbox in the instance admin console.
The documentation promised that product feedback "never leaves the instance".

Nobody reads an inbox that is separate from the place where the work happens, so reports were missed.
The maintainers already work in GitHub.
Since 2025, coding agents also take issues as their input.
A short, structured issue is the fastest path from a report to a fix.

A public issue is close to irreversible.
GH Archive records the public timeline every hour.
Readers can see an issue's edit history, and watchers get the text by email.
GDPR Art. 25(2) requires that, by default, personal data is not made public without the person's intervention.
The EDPB names privacy settings that people control as one form of that intervention (Guidelines 4/2019, ¶ 56–58).
Silence, inactivity and pre-ticked boxes are not consent (Recital 32, CJEU C-673/17 Planet49).

## Decision drivers

- A person's effort ends at **Send**. Each extra step loses reports.
- Maintainers work on product feedback in one place: GitHub.
- No report is ever lost, and no failure is silent.
- Nothing becomes public without the person's own choice. Code, not the model, decides what is public.
- Product feedback never passes a waiting practice-review model call.
- Each operator decides whether reports leave the instance. The default is that they do not.

## Considered options

1. **An admin inbox in Hephaestus.** This was the state before 1.0.
   Rejected: nobody checks it, and reports were missed.
2. **Draft, preview, then publish.** The person reviews the AI draft before anything is filed.
   Rejected: it is too much effort for a quick report.
   The routing gate, the risk latch and the hold give its protection instead.
3. **Private first.** Every report goes to a private repository, and maintainers promote it.
   Rejected: GitHub cannot move an issue from a private repository to a public one.
   So every promotion is a copy plus a human step: the unread inbox again, inside GitHub.
   Coding agents and the community work in the public repository.
4. **Public only.** Rejected: some reports need a place that is not public.
   These are opted-out reports, security reports, reports about other people, abuse and redaction doubts.
5. **Public by default, private as the automatic exception (chosen).**
6. **Public by default with only an undo or a hold.**
   Rejected: that is consent through inactivity (Recital 32).
7. **Ask on every report.** Rejected: a switch shows the remembered choice at no cost.
   EDPB ¶ 58 does not require a question each time.
8. **A 10-minute hold with an email.**
   Rejected: the email usually arrives after the decision point.
   A longer hold only delays the issue.
9. **The person's GitHub user token, so that the person is the author.**
   Rejected:
   - it needs an OAuth round trip;
   - it excludes people who sign in with GitLab;
   - it makes the person's identity public by default.
10. **A comment on a matching issue instead of a new issue.**
    Rejected: the model would choose which issue gets the comment.
    A reporter or a poisoned issue title could steer comments onto any open issue.
    A comment also cannot be withdrawn by moving the issue.
11. **Duplicates closed automatically.** Rejected: wrong automatic closes cost trust.
    The best published retrieval still misses about a third of duplicates.
12. **Labels chosen by the model.** Rejected: some labels start automation, such as CI runs.
    Other labels make claims. The issue type classifies.
13. **Ideas filed as GitHub Discussions.** Rejected: one write path is simpler.
    Maintainers can convert an issue to a discussion.
14. **GitHub search plus a model rerank to find matches.**
    Rejected: a snapshot of open issue titles needs one model call.
    It has no search rate limit and shows every recent title.
15. **A prefilled "new issue" link for other instances to report upstream.**
    Rejected: it needs a person's step on GitHub, so the effort does not end at Send.
16. **Webhooks for follow-up and for `/publish`.**
    Rejected: issue events would pass through the workspace ingest path.
    Polling a handful of issues is simpler.
17. **Drafting as a sandboxed agent job or in the review queue.**
    Rejected:
    - drafting needs no tools, and no tools is a security property;
    - the review queue would couple product feedback to practice reviews.
18. **Billing drafts to the workspace usage ledger.**
    Rejected: an instance-level report has no workspace.
    A nullable `workspace_id` would weaken a tenancy proof.
19. **Triage only on the receiving repository** (issue forms, GitHub Agentic Workflows).
    Rejected as the only path:
    - redaction must run before the text leaves the instance;
    - routing must be decided before anything is filed.

    Those tools still work on the issues, because the issues use the repository's form headings.

## Decision

Product feedback is filed in GitHub automatically, and it is worked on there.

1. **Destinations.** The operator names two optional repositories: a public one and a private one.
   The instance's GitHub App files to both as itself.
   The private repository must be private. The server checks its visibility before each write and stops if it is public.
   Withdrawal moves an issue between the two, so both must have the same owner.
2. **The route.** A report goes to the public repository when the gate in decision 5 passes.
   Otherwise it goes to the private repository, if one is configured.
   Otherwise it goes to the admin email and the list of reports that are not on GitHub. This is the default.
3. **The person's choice.** On the first report, the composer asks once: share as a public issue, or keep private.
   Neither option is selected.
   - Each choice and each withdrawal is an append-only consent event with the notice version.
   - The events determine the current choice. No mutable flag does.
   - A switch on each later report shows the remembered choice. Changing it applies to that report only.
   - Each report records the event that it was routed under.
   - A withdrawal at once moves the person's held public reports to the private route.
   - A new notice version asks every person again.
4. **Drafting.** One model call without tools drafts a short issue under the repository's form headings.
   It can name one related open issue.
   - The call uses the operator's instance-level model in `LlmCallClass.PRODUCT_FEEDBACK`.
   - That class gets a grant only when no call of another class waits. At most one is active per pool.
   - A later call of another class can wait for that one call, whose transport has a deadline.
   - The class does not separate provider quota or cooldown.
   - Deterministic redaction runs before the call and on every rendered body.
5. **The public gate.** Code decides. The model's answer is only an input.
   A report becomes a public issue only when every condition holds:
   - the person allowed public for this report;
   - the kind is bug or idea;
   - a valid draft exists for the exact outgoing text;
   - the model's disposition is an issue, not a security report;
   - redaction found no secret, no email address and no known name;
   - the safety check passes;
   - the confidence is high;
   - the report has no risk latch.

   A security disposition, any blocking redaction finding, or a report about a named person sets the risk latch.
   No edit and no retry clears it.
   Every public write checks this gate at dispatch: the first filing, a filing after an edit, a retry and a promotion.
   A private issue opens with the reason it is private.
6. **The hold.** A public report waits 5 minutes before it is filed.
   During that time, the person can keep it private or edit it. An edit restarts the hold.
   The hold is not the legal basis. It catches a slip before watchers get an email.
7. **Promotion.** A maintainer comments `/publish` on a private issue.
   - At dispatch, the server reads the commenter's permission on both repositories. It requires `write` or `admin`. An unknown answer denies.
   - The reporter must have allowed public, and the report must have a valid draft. The fallback text that has no draft is never promoted.
   - The command overrides only the quality conditions: the kind and the confidence. It never overrides consent, security, the latch or abuse.
   - The app files a new public issue from the redacted draft and closes the private one.
8. **Withdrawal and erasure.** The person can make their own public issue private. The app moves it to the private repository.
   On account erasure, the rows are deleted at once.
   - First, a cleanup record copies what reconciliation needs: the repository, the installation, the marker, the payload hash and the deadline. It holds no person data.
   - A sweeper first reconciles an unclear create. Then it moves or scrubs the issues.
   - The app cannot delete an issue or its edit history. That step goes to a maintainer as an action item.
   - A cleanup record stays until its steps are done or an admin confirms the manual step.
9. **Durability.** One row per report holds a state machine. Sweepers claim rows with leases (ADR 0025).
   - Each GitHub write freezes its target and payload and has a deadline.
   - A marker in the body lets a retry find an issue that an earlier attempt created.
   - An unclear lookup counts as unknown, never as absent.
10. **Pause.** A switch stops new filing.
    New and unfiled reports go to the admin email. `/publish` commands wait until resume. Each dispatch checks Pause.
    Follow-up, withdrawal and cleanup continue.
11. **Follow-up.** The server polls followed issues and emails the person when an issue changes state.
    A release email says that the change that closed the issue is in a release. It never says "fixed".
12. **The operator's view.** The instance admin console only. It has a destinations card with health, the **Pause** switch, and the reports that need action, with **Retry**.
    There is no triage inbox.

The operator steps get their home in the Admin Guide when they ship: `docs/admin/product-feedback.mdx` (setup, Pause, manual cleanup) and `docs/admin/legal-pages.mdx` (the privacy notice).

## Consequences

- **Positive:**
  - Reports reach the maintainers where they work, as agent-ready issues, with no effort beyond Send.
  - No report is lost. The database copy comes first, and the email fallback covers every report that is not on GitHub.
- **Negative:**
  - Product feedback now leaves the instance when an operator names a repository.
    The operator's privacy notice must name GitHub and the model provider. The operator decides the legal basis.
  - A public issue cannot be fully withdrawn. Copies outside GitHub remain, and the person is told so.
- **Neutral:**
  - The project's own instance files to `hephaestus-build/Hephaestus` and a private `hephaestus-build` repository. No code path is special to it.
  - Redaction lowers the risk but cannot remove it. So any risk finding routes a report to the private repository.
  - A routing evaluation with about 30 cases gates the build. Any public leak fails it.
- **Operations:**
  - **Pause** stops new filing and keeps follow-up, withdrawal and cleanup running.
  - Metrics count the routes by reason.
  - A rising private share means that the gate is too strict or redaction is too weak.

## Revisit trigger

- The routing evaluation finds a public leak that the gate cannot prevent.
- Maintainers make more than an occasional public issue private for privacy reasons.
- GitHub gains a way to move an issue from a private repository to a public one. Private first would then cost less.
- A destination other than GitHub is needed.
