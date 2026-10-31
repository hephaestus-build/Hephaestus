# ADR 0073: Product feedback is worked on in GitHub

**Status:** Accepted (2026-10-31)
**Date:** 2026-10-31
**Authors:** Felix T.J. Dietrich
**Relates to:** ADR 0025 (job claims and orphan recovery), ADR 0026 (the governed model catalog) and ADR 0068 (the worker runs model calls).

## Context

Product feedback is a report about Hephaestus itself: a bug, an idea, or anything else.
It is not practice feedback.
Until 1.0, a report went into the instance database (`productfeedback/ProductFeedback`).
The instance administrators got an email without the text, and they read the report in an inbox in the instance admin console.
The documentation promised that product feedback "never leaves the instance".

Nobody reads an inbox that is separate from the place where the work happens, so reports were missed.
The maintainers already work in GitHub.
Since 2025, coding agents also take issues as their input.
A short, structured issue is the fastest path from a report to a fix.

A public issue is close to irreversible.
GH Archive records the public timeline every hour, readers can see an issue's edit history, and watchers get the text by email.
GDPR Art. 25(2) requires that, by default, personal data is not made public without the person's intervention.
The EDPB names "privacy settings so that data subjects themselves can control public access" as one form of that intervention (Guidelines 4/2019, ¶ 56–58).
Silence, inactivity and pre-ticked boxes are not consent (Recital 32, CJEU C-673/17 Planet49).

## Decision drivers

- A person's effort ends at **Send**. Each extra step loses reports.
- Maintainers work on product feedback in one place: GitHub.
- No report is ever lost, and no failure is silent.
- Nothing becomes public without the person's own choice. Code, not the model, decides what is public.
- Product feedback never delays a queued practice-review model call.
- Each operator decides whether reports leave the instance. The default is that they do not.

## Considered options

1. **An admin inbox in Hephaestus.** This was the state before 1.0. Rejected: nobody checks it, and reports were missed.
2. **Draft, preview, then publish.** The person reviews the AI draft before anything is filed.
   Rejected: too much effort for a quick report. The protection it gave comes instead from the routing gate, the risk latch and the hold below.
3. **Private first.** Every report goes to a private repository, and maintainers promote it.
   Rejected: GitHub cannot move an issue from a private repository to a public one. So every promotion is a copy plus a human step, which is the unread inbox again, inside GitHub. Coding agents and the community work in the public repository.
4. **Public only.** Rejected: opted-out reports, security reports, reports about other people, abuse and redaction doubts each need a place that is not public.
5. **Public by default, private as the automatic exception (chosen).**
6. **The person's GitHub user token, so that the person is the author.**
   Rejected:
   - it needs an OAuth round trip;
   - it excludes people who sign in with GitLab;
   - it makes the person's identity public by default.
7. **A comment on a matching issue instead of a new issue.**
   Rejected: the model would choose which issue gets the comment. A reporter, or a poisoned issue title, could steer comments onto any open issue. A comment also cannot be withdrawn by moving the issue.
8. **Labels chosen by the model.** Rejected: some labels start automation, such as CI runs, and others make claims. The issue type classifies.
9. **Webhooks for follow-up and for `/publish`.** Rejected: issue events would pass through the workspace ingest path. Polling a handful of issues is simpler.
10. **Drafting as a sandboxed agent job or in the review queue.**
    Rejected:
    - drafting needs no tools, and no tools is a security property;
    - the review queue would couple product feedback to practice reviews.
11. **Billing drafts to the workspace usage ledger.** Rejected: an instance-level report has no workspace, and a nullable `workspace_id` would weaken a tenancy proof.
12. **Triage only on the receiving repository** (issue forms, GitHub Agentic Workflows). Rejected as the only path:
    - redaction must run before the text leaves the instance;
    - routing must be decided before anything is filed.

    Those tools still work on the issues, because the issues use the repository's form headings.

## Decision

Product feedback is filed in GitHub automatically, and it is worked on there.

1. **Destinations.** The operator names two optional repositories: a public one and a private one. The instance's GitHub App files to both as itself.
   The private repository must be private, and the server checks its visibility before each write.
   With no repository named (the default), the report goes to the admin email and the remaining list of reports that are not on GitHub.
2. **The person's choice.** On the first report, the composer asks once: share as a public issue, or keep private. Neither option is selected.
   The choice is stored as an append-only consent event with the notice version. A switch on every later report shows it, and user settings change it.
   A new notice version asks again.
3. **Drafting.** One model call without tools drafts a short issue under the repository's form headings, and suggests at most one related open issue.
   The call uses the operator's instance-level model in the lowest model-call class, `LlmCallClass.PRODUCT_FEEDBACK`. That class is granted only when no call of another class waits, and at most one is active per pool.
   Deterministic redaction runs before the call and on every rendered body.
4. **Routing.** Code decides. The model's answer is only an input.
   A report becomes a public issue only when every condition holds:
   - the person allowed public;
   - the kind is bug or idea;
   - a valid draft exists for the exact outgoing text;
   - the model's disposition is an issue, not a security report;
   - redaction found no secret, email address or known name;
   - the safety check passes;
   - the confidence is high.

   Any finding of a security disposition, a secret or a named person sets a latch that no later edit clears.
   Every other report becomes a private issue, which opens with the reason.
5. **The hold.** A public report waits 5 minutes before it is filed. During that time, the person can keep it private or edit it. An edit restarts the hold.
   The hold is not the legal basis. It catches a slip before watchers get an email.
6. **Promotion.** A maintainer comments `/publish` on a private issue. The command works only when the reporter allowed public and the commenter has write access on both repositories.
   It never overrides consent, security, the latch or abuse. The app files a new public issue from the redacted draft and closes the private one.
7. **Withdrawal.** The person can make their own public issue private. The app moves it to the private repository.
   On account erasure, a cleanup record without person data outlives the rows, so the app can still find, move and scrub the issues.
8. **Durability.** One row per report holds a state machine. Sweepers claim rows with leases (ADR 0025).
   Each GitHub write freezes its target and payload and has a deadline. A marker in the body lets a retry find an issue that an earlier attempt created. An unclear lookup counts as unknown, never as absent.
9. **Follow-up.** The server polls followed issues and emails the person when the issue changes state.
   A release email says that the change that closed the issue is in a release. It never says "fixed".
10. **The operator's view.** In the instance admin console only: a destinations card with health, a **Pause** switch, and the reports that need action, with **Retry**. There is no triage inbox.

## Consequences

- **Positive:**
  - Reports reach the maintainers where they work, as agent-ready issues, with no effort beyond Send.
  - No report is lost: the database copy comes first, and the email fallback covers every report that is not on GitHub.
- **Negative:**
  - Product feedback now leaves the instance when an operator names a repository. The operator's privacy notice must name GitHub and the model provider, and the operator decides the legal basis.
  - A public issue cannot be fully withdrawn. Copies outside GitHub remain, and the person is told so.
- **Neutral:**
  - The project's own instance files to `hephaestus-build/Hephaestus` and a private `hephaestus-build` repository. No code path is special to it.
  - Redaction lowers the risk but cannot remove it. So any risk finding routes a report to the private repository, and a routing evaluation gates the build.
- **Operations:**
  - **Pause** stops new filing and keeps follow-up, withdrawal and cleanup running.
  - Metrics count the routes by reason. A rising private share is the signal that the gate is too strict or redaction too weak.

## Revisit trigger

- The routing evaluation finds a public leak that the gate cannot prevent.
- Maintainers make more than an occasional public issue private for privacy reasons.
- GitHub gains a way to move an issue from a private repository to a public one, which would make private first cheaper.
- A destination other than GitHub is needed.
