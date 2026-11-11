# ADR 0097: Product feedback becomes a GitHub issue, and only the reporter's tick and a code gate make it public

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0073](0073-product-feedback-is-worked-on-in-github.md)

**Amends:** [ADR 0079](0079-one-agent-system.md): the `action` sandbox kind and the band of `report_draft`.

**Builds on:** [ADR 0044](0044-email-is-a-notification-transport.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md), [ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md)

## Context

Product feedback is a report about Hephaestus itself, never practice feedback.
ADR 0073 made GitHub the place where maintainers work on reports. It carried three updates and 19 options.
Its option 17 rejected a sandboxed draft because "no tools is a security property". Its 2026-11-09 update then put the draft in an `action` sandbox.
This ADR states product feedback at 1.0 as rules, and it resolves that conflict.

A public issue is close to irreversible. GH Archive records it, and watchers get its text by email.
The [contributor page](../contributor/product-feedback.mdx) holds the design. The [admin guide](../admin/product-feedback.mdx) and the [user guide](../user/product-feedback.mdx) hold the steps.

## Decision drivers

- The reporter's effort ends at **Send**.
- Maintainers work on reports in one place: GitHub.
- No report is lost, and no failure is silent.
- Nothing becomes public without the reporter's own choice. Code, not the model, decides what is public.
- A draft never passes a waiting call of other agent work, and it never charges a workspace.

## Considered options

1. **The draft runs in an `action` sandbox** (ADR 0073 update 2026-11-09). This costs a Pod, a template and a warm pool for one call. The sandbox contains code that a model runs, and a draft runs none.
2. **The server runs the draft as a `report_draft` run, with no tools, on the shared model path (chosen).**
3. **Keep the direct adapter of main.** It keeps a second lease model and a provider client without the egress guard.
4. **Drop follow-up and `/publish` for 1.0.** This removes two polls. Reporters lose update emails, and **Allow public** on a filed private report does nothing.

## Decision

1. **GitHub is the queue.** The operator names two optional repositories, one public and one private, with the same owner. The instance's GitHub App files as itself. No other provider is a destination.
   Enforced by: `ProductFeedbackTrustGatesIntegrationTest#shouldRefuseAPrivateRepositoryWhenItIsNamedAsThePublicDestination` and `OutboundEgressArchitectureTest` (the app's `EgressExempt` declaration).
2. **Saved first, then routed.** The server saves a report before any model call or GitHub write. It routes the report public if the gate passes, else private, else to the instance admins. No destination and **Pause** keep the report for the admins.
   Enforced by: `ProductFeedbackTrustGatesIntegrationTest#shouldKeepAReportForTheAdminsEvenWhenTheHourlyGitHubLimitIsFull`, `#shouldRefuseToFileWhileFilingIsPaused` and `#shouldMoveNothingWhilePaused`.
3. **Consent is a recorded tick.** The box is clear on a first report, and a clear box means private. A tick, **Allow public** and a withdrawal are each a consent event with the notice version. A new notice version clears the remembered tick. A withdrawal moves held public reports to the private route at once.
   Enforced by: `ProductFeedbackTrustGatesIntegrationTest#shouldKeepAFirstReportPrivateAndRecordNoChoiceWhenNothingWasTicked`, `#shouldNotCarryARememberedChoiceOverToANewerNotice` and `#shouldNotReverseAWithdrawalWithoutANewTick`. No database rule makes `product_feedback_consent` append-only.
4. **Code decides public.** `RoutingGate` needs consent and an actionable `ISSUE` draft of high confidence. It also needs no blocking redaction finding, no abuse and no named person. A security disposition, a blocking finding or a named person sets a risk latch that no edit or retry clears. The gate runs on each revision. Filing checks consent again at dispatch, and `/publish` runs the gate again.
   Enforced by: `RoutingGateTest`, `ProductFeedbackRoutingEvalTest` (unit tier, with a model answer that allows everything) and `FilingLifecycleModelTest`.
5. **Redaction runs twice, and the model only drafts.** Deterministic redaction runs before the model call and on every rendered body. The renderer escapes model text. The model chooses no label, and it never comments on, edits or closes another issue.
   Enforced by: `IssueRendererTest#shouldRenderPersonTextAsInertTextWhenItCarriesMarkup` and `ProductFeedbackRoutingEvalTest`.
6. **A draft is one server-run model call with no tools.** It is a `report_draft` run in the lowest band, and no sandbox runs it. The call uses the proxy's guarded client, permit and usage record. It never passes a waiting call, and at most one runs in each pool. Its spend counts against the instance's draft limits, never a workspace.
   1.0 target: no pull request delivers it yet. On main, `ProductFeedbackModelAdapter` runs in the worker role with its own lease and a plain `WebClient`. [#4079](https://github.com/hephaestus-build/Hephaestus/pull/4079) moves it to the server, and [#4095](https://github.com/hephaestus-build/Hephaestus/pull/4095) puts it in the `background` band.
   Enforced by: `LlmProductFeedbackAdmissionIntegrationTest`. No test checks the empty tool list.
7. **A public report waits five minutes.** In that time the reporter can select **Keep private** or **Edit**. An edit starts a new hold and new checks. The hold catches a slip. It is not the legal basis.
   Enforced by: `ProductFeedbackTrustGatesIntegrationTest#shouldFileAPrivateIssueWhenTheReporterKeepsAHeldReportPrivate` and `FilingLifecycleTest`.
8. **Each GitHub write is an outbox action.** Filing is a `file_issue` action in `agent_action`. A marker lets a retry find an earlier issue, and an unclear lookup counts as unknown. The server checks that the private repository is private before each private write.
   1.0 target: [#4029](https://github.com/hephaestus-build/Hephaestus/pull/4029) moves filing. No pull request moves promotion, transfer and cleanup writes yet.
   Enforced by: `FilingLifecycleModelTest` (no second issue, no private report made public) and `GitHubProductFeedbackIssuesTest#shouldReturnUnknownWhenSecondMarkerPageFailsEvenAfterMatch`.
9. **One state table owns the filing.** `FilingLifecycle` names every state and every allowed move. Each status that a person sees derives from the state.
   Enforced by: `FilingLifecycleTest`, `FilingLifecycleModelTest` and the `ck_product_feedback_filing_state` CHECK.
10. **A maintainer publishes a private report with `/publish`.** The commenter needs `write` or `admin` on both repositories at dispatch. An unknown answer denies. The command overrides only confidence, never consent, security, the latch or abuse.
    Enforced by: `RoutingGateTest#shouldNeverOverridePermissionSecurityOrTheRiskLatchDuringPromotion`. No test covers the permission read in `FollowService`.
11. **Withdrawal and erasure.** **Make private** moves a public issue to the private repository. Erasure deletes the rows at once. A cleanup record without person data then moves or scrubs the issues. Deletion of an issue is a maintainer step.
    Enforced by: `ProductFeedbackCleanupIntegrationTest`.
12. **Updates say only what the poll saw.** The server polls filed issues. It emails the reporter on filing, publication, a public comment, a close, a reopen and a withdrawal. Hephaestus sends no release email and never claims that a change shipped.
    1.0 target: no pull request yet deletes the unused `INCLUDED_IN_RELEASE` kind or corrects the duplicate email's false claim.
    Enforced by: `ProductFeedbackUpdateEmailIntegrationTest#shouldNotifyReporterWithoutCopyingReportTextWhenSubscribed`.
13. **The operator works in the instance admin console only.** It shows destination health, **Pause**, **Needs action** with **Retry**, and the reports not on GitHub. An admin can send a saved report as a private issue, never a public one. **Pause** stops filing and `/publish`, but follow-up, withdrawal and cleanup go on.
    Enforced by: `RecentSignInByDefaultArchTest`, `AuditByDefaultArchTest`, `ProductFeedbackTrustGatesIntegrationTest#shouldNeverPublishByAnAdminsAction` and `#shouldNeverHandAReportToAModelWhenItsAuthorWasNotTold`.

## What changed, and why

- **The draft stays out of a sandbox.** Option 17 holds, and the 2026-11-09 update is reversed. A sandbox contains code that a model runs. A draft offers the model no tool, so it has nothing to contain. OWASP names the minimal tool set as the defence ([LLM06](https://genai.owasp.org/llmrisk/llm062025-excessive-agency/)).
- **The draft joins the one run table.** Option 17 also rejected the review queue. `agent_run` is not that queue: the band keeps a draft behind every other call ([ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 3).
- **The `action` sandbox kind goes** (amends ADR 0079). Its only user was the draft. `AgentSandboxKind.ACTION`, its chart template and warm pool, and its isolation-gate case go. Decided, not shipped: no pull request deletes them yet.
- **`report_draft` is `background`, not `interactive`.** ADR 0079 said that a person waits. A reporter's effort ends at **Send**, so nobody waits for the draft.
- **No release email.** ADR 0073 decision 11 promised one. Main has no code that sends it, and the user guide does not list it. Under the 1.0 rule against unused paths, it goes.
- **The filing lease goes,** as the 2026-11-09 update decided. The marker, the frozen target and payload, and the cleanup records stay.
- **The two 2026-11-06 updates are folded in.** A first report never waits for a choice. Admins can send saved reports to GitHub.

## Consequences

- Good: reports reach maintainers as short, structured issues, with no step after **Send**.
- Good: no report is lost. The saved row comes first, and the admin console holds every report that is not on GitHub.
- Good: a draft needs no sandbox platform and no Pod start. One lease model and one guarded model path cover it.
- Bad: reports leave the instance when an operator names a repository. The privacy notice must name GitHub and the model provider.
- Bad: a public issue cannot be fully withdrawn. Copies outside GitHub remain, and the reporter is told so.
- Bad: redaction can miss personal data. The gate therefore routes every risk finding private, and the private share can grow.
- Bad: three polls stay: the title snapshot, `/publish` commands and follow-up.

## Confirmation

Each rule above names its test or says what is not enforced.
Ledger row PF-1 is the production proof: one report filed on production from the build that ships. No receipt exists yet.

## Revisit trigger

- The routing evaluation finds a public leak that the gate cannot prevent.
- Maintainers make more than an occasional public issue private for privacy reasons.
- GitHub can transfer an issue from a private repository to a public one. Private first then costs less.
- A draft needs tools or source evidence ([contributor page § Source investigation boundary](../contributor/product-feedback.mdx#source-investigation-boundary)). Then a sandbox is the place for it.
- A destination other than GitHub is needed.

## Sources

- [GDPR Art. 25](https://gdpr-info.eu/art-25-gdpr/): by default, personal data must not reach "an indefinite number of natural persons" without the person's own act. This makes public a recorded tick (rule 3).
- GitHub Docs, [Transferring an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/administering-issues/transferring-an-issue-to-another-repository): "A private repository issue cannot be transferred to a public repository." Transfers stay within one owner. This sets the same owner and the copy on `/publish` (rules 1 and 10).
- OWASP, [LLM06:2025 Excessive Agency](https://genai.owasp.org/llmrisk/llm062025-excessive-agency/): "Limit the extensions that LLM agents are allowed to call to only the minimum necessary." This keeps the draft without tools and out of a sandbox (rule 6).

## Already priced and declined

Each line links the options section of ADR 0073.

- [Option 1](0073-product-feedback-is-worked-on-in-github.md#considered-options): an admin inbox in Hephaestus.
- [Option 2](0073-product-feedback-is-worked-on-in-github.md#considered-options): draft, preview, then publish.
- [Option 3](0073-product-feedback-is-worked-on-in-github.md#considered-options): private first, with promotion by maintainers.
- [Option 4](0073-product-feedback-is-worked-on-in-github.md#considered-options): public only.
- [Option 6](0073-product-feedback-is-worked-on-in-github.md#considered-options): public by default with only an undo or a hold.
- [Option 7](0073-product-feedback-is-worked-on-in-github.md#considered-options): ask on every report.
- [Option 8](0073-product-feedback-is-worked-on-in-github.md#considered-options): a 10-minute hold with an email.
- [Option 9](0073-product-feedback-is-worked-on-in-github.md#considered-options): the reporter's GitHub user token as the author.
- [Option 10](0073-product-feedback-is-worked-on-in-github.md#considered-options): a comment on a matching issue.
- [Option 11](0073-product-feedback-is-worked-on-in-github.md#considered-options): duplicates closed automatically.
- [Option 12](0073-product-feedback-is-worked-on-in-github.md#considered-options): labels chosen by the model.
- [Option 13](0073-product-feedback-is-worked-on-in-github.md#considered-options): ideas filed as GitHub Discussions.
- [Option 14](0073-product-feedback-is-worked-on-in-github.md#considered-options): GitHub search plus a model rerank.
- [Option 15](0073-product-feedback-is-worked-on-in-github.md#considered-options): a prefilled "new issue" link.
- [Option 16](0073-product-feedback-is-worked-on-in-github.md#considered-options): webhooks for follow-up and `/publish`.
- [Option 17](0073-product-feedback-is-worked-on-in-github.md#considered-options): a sandboxed draft, or the draft in the review queue.
- [Option 18](0073-product-feedback-is-worked-on-in-github.md#considered-options): drafts billed to the workspace usage ledger.
- [Option 19](0073-product-feedback-is-worked-on-in-github.md#considered-options): triage only on the receiving repository.
- [ADR 0073 update 2026-11-09](0073-product-feedback-is-worked-on-in-github.md#update--2026-11-09-superseded-in-part-by-adr-0079): the draft as a run in an `action` sandbox.
