# Hephaestus — DPIA Pre-Screen (Art. 35 GDPR)

> **Status: controller/DPO determination pending.** Engineering conclusion: a full DPIA is indicated for the current scope. Several WP29 risk criteria are present. Advisory output reduces impact, as does the absence of grading, employment, or access decisions.
> These safeguards do not negate evaluation, systematic monitoring, dataset combination, or the student/employee power imbalance. This engineering screen is not the
> controller's Art. 35 determination.
>
> **Review owner:** TUM/AET data-protection coordinator. **Engineering review:** 2026-10-09. **Next review:** before
> release of the expanded processing scope or 2026-11-04, whichever comes first. **Controller decision reference:** pending.

Records whether a full Data Protection Impact Assessment is required for the TUM-operated Hephaestus deployment. The high-risk test in Art. 35(1) applies to TUM as a Bavarian public body, together with the Art. 35(3) examples and the Bavarian Blacklist under Art. 35(4). The DSK list (which addresses the non-public sector) is referenced only as a cross-check.

## 1. Threshold check against Art. 35(3) GDPR

| Trigger                                                                                                                                                               | Present? | Reasoning                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Systematic and extensive evaluation, including profiling, that forms the basis for decisions producing legal effects or similarly significant effects (Art. 35(3)(a)) | **Not in the product. Assess deployment use** | The product has no automated grading, HR or access-decision pipeline. Private practice-page and conversation feedback is addressed to the developer. Externally posted feedback follows its destination audience. Assess whether humans use these outputs for significant decisions in the actual deployment. The advisory label cannot rule that out. |
| Large-scale processing of Art. 9(1) (special categories) or Art. 10 (criminal convictions) data                                                                       | **Requires deployment evidence**   | Hephaestus does not intentionally solicit or classify these data. Free-text repository content may contain and therefore cause incidental processing of them, but selection of connected sources does not establish its actual scale. The controller must measure the deployment and assess incidental sensitive content.                                                                                                                                                                                               |
| Systematic monitoring of a publicly accessible area on a large scale                                                                                                  | **Requires deployment evidence**   | Connected repositories may be public, but Hephaestus does not crawl or discover public sources. It processes only repositories explicitly connected by a workspace administrator, with deployment scale to be established by the controller.                                                                                                                                                                                                                                               |

The product has no automated significant-decision pipeline. The other Art. 35(3) thresholds require deployment evidence: record population, data volume, duration, geographic reach and actual source content. Repository selection alone does not prove that processing is small scale. Art. 35(3) is a list of examples, not the whole high-risk test.

## 2. Bavarian Blacklist (BayLfD)

The BayLfD's Bavarian Blacklist (dated 1 March 2019 under Art. 35(4) GDPR) enumerates public-sector processing that requires a DPIA. Check the actual use against that list. The product name or teaching purpose cannot exclude a listed operation. The risk-criteria assessment below tracks the WP29 Guidelines on DPIA (WP248rev.01) criteria the BayLfD applies in screening:

| WP29 criterion                                                | Present?                                  | Current facts and safeguards                                                                                                                                                                                                                                       |
| ------------------------------------------------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Evaluation or scoring                                         | **Yes**                                   | Practice reviews create contributor-specific assessments of observed engineering work. Advisory and contestable output, no automated grading/HR/access decision, and separate delivery controls reduce consequence. They do not negate the criterion. The research purpose adds evaluation datasets and benchmark runs built from participants' work.           |
| Automated decision with legal or similarly significant effect | **No**                                    | Observations do not determine grades, employment, recognition caps, feature access, merge rights, or another legal/significant outcome. Any such consumer is a material change requiring a full reassessment before use.                                               |
| Systematic monitoring                                         | **Yes**                                   | Enabled workspaces repeatedly observe activity in administrator-connected repositories and optional monitored Slack/Outline sources. The scope is selected rather than an open crawl, but the processing is organized and recurring.                               |
| Sensitive or highly personal data                             | **Partly**                                | The system does not solicit or classify Art. 9/10 data, but free-text code, issues, messages, documents, and conversations can contain incidental sensitive or highly personal content. Private mentor and Slack conversations receive the stricter source class. Research datasets built from free text can carry the same incidental content.  |
| Data processed on a large scale                               | **Not established by code** | Workspace scope is a tenancy boundary, not a scale measurement. Record the current deployment population, volume, duration and geographic reach. Reassess before expansion.                                                                                                   |
| Matching or combining datasets                                | **Yes**                                   | Practice review and mentoring can combine GitHub/GitLab activity with Hephaestus observations and, where enabled, selected Slack messages and Outline documents. This is source combination even though Hephaestus does not enrich people from commercial external profiles. Research datasets combine these sources again for participants. |
| Vulnerable data subjects                                      | **Yes**                                   | Students and employees can face a power imbalance and may be unable to oppose workspace-level processing as easily as an ordinary consumer. Advisory use and rights procedures reduce risk. The product does not enforce a ban on human grading or HR use. The controller must assess that use. The controller must govern it. Research consent from these groups is valid only with a real refusal path and no disadvantage. See Recital 43 and EDPB Guidelines 05/2020 paras 16-24 and 46-48. The research team must be separate from grading and line management. If doubt remains, the controller must not rely on consent (EDPB Guidelines 1/2026 para 36).                                        |
| Innovative technology or organizational solution              | **Yes**                                   | An LLM interprets work artifacts and produces contributor-specific practice feedback. Recipient-specific provider terms and restricted egress reduce risk but do not remove this criterion.                                                                             |
| Prevents exercise of a right or use of a service/contract     | **No**                                    | The product does not gate course, employment, repository, or Hephaestus access on observations.                                                                                                                                                                        |

WP248 rev.01 states that, in most cases, processing that meets two criteria warrants a DPIA. It states that more criteria make high risk more likely. Multiple criteria above are present. A full DPIA is indicated now.

The research purpose adds evaluation, dataset building and benchmark running. It does not change the conclusion. A full DPIA stays indicated. The full assessment covers the research purpose and the processing of participants' data that it needs.

The wider job folder can include permitted Slack threads and person-scoped observation and feedback history without record-count or history-window caps. This increases combination and longitudinal evaluation, including third-party context. Worker isolation and an advisory output reduce risks but do not remove the screening criteria.

1. Before authorizing the current scope for the deployment, open its full assessment.
2. Record necessity, proportionality, risks to people and measures under Art. 35(7).
3. Seek DPO advice under Art. 35(2).
4. Consider affected people under Art. 35(9).
5. Include the research purpose in the scope (see section 7).

 If high residual risk remains despite measures, Art. 36 requires prior supervisory consultation. A controller who concludes that no DPIA is required must record evidence addressing each present criterion. Absence from a blacklist or absence of automated grading is insufficient. This engineering conclusion does not record a completed DPIA or legal approval.

## 3. DSK list — cross-check (not directly applicable)

The Bavarian Blacklist includes extensive employee-behavior processing that can be used to evaluate work with legal or similarly significant effects (entry 7). Check actual teaching and employment use against it. An advisory product does not prevent a controller from using its outputs for significant decisions. The DSK employment criterion provides the same cross-check for non-public operators.

The DSK list of processing operations requiring a DPIA under Art. 35(4) GDPR addresses the **non-public sector**
and is not directly applicable to TUM/AET as a Bavarian public body. The controlling instrument for TUM is the
Bavarian Blacklist in §2. The absence of one exactly named DSK constellation does not override the multi-criterion
WP29 assessment above.

Recipient-specific provider terms recorded in the processor checklist, the AI choice, restricted LLM
egress, and the Art. 21 rights process reduce residual risk but do not decide whether Art. 35 requires a DPIA.
They remain mandatory while the determination is pending.

## 4. Risks for the full assessment

| Risk                                                                                                                                                                                                                       | Controls to verify                                                                                                                                                                                                                                                                                                                                                                                                       |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Free-text artifact content (PR descriptions, issue descriptions, commit messages, review comments) contains personal data of identifiable third parties and is transmitted to the LLM provider                             | The privacy statement instructs users not to enter third-party personal data. The practice-review sandbox runs on a per-job `--internal` Docker network with no general egress. It uses a token-authenticated worker gateway for LLM calls and declared workspace/result transfers. An external provider's training terms and transfer safeguards are verified per recipient in the processor checklist. Objections to processing follow the Art. 21 contact process in privacy §7.                                 |
| Practice-feedback comments or Slack reminders reach a contributor after their comments-and-reminders setting is disabled. Alternatively, the Slack mentor accepts a new interaction after the workspace's Heph models are off. | Delivery policy is checked before starting each comment or reminder and before admitting each new Slack mentor interaction. An interaction admitted before the workspace's Heph models change may finish. The personal setting covers issue, pull-request, and merge-request comments plus Slack reminders, and delivered comments link back to it.                                                                    |
| A model provider retains or uses the prompt beyond what this deployment needs                                                                                                                                              | Before engagement, record each external provider's prompt and response retention, training terms and human access for the exact deployment in the [processor checklist](./processor-checklist.md). Published provider terms do not prove the deployment's terms. Zero data retention can be negotiated where a provider supports it.      |
| Server access logs retain IP addresses                                                                                                                                                                                     | No layer writes an HTTP access log. The production profile explicitly disables Tomcat's access log. The Traefik reverse proxy starts without `--accesslog` (Traefik's default is off). Both nginx containers disable the access log at the server level. The shipped stack creates no general HTTP access-log copy. Authentication events record IP addresses, under that log's 12-month partitioned window. Other authentication and operational events can contain personal details. Verify deployment logging and support exports. |
| gitlab.lrz.de content leaks through the LRZ integration                                                                                                                                                                        | LRZ is a separate controller. Inter-public-body transmission falls under Art. 5(1) Nr. 1 BayDSG. LRZ applies its own TOMs on its own infrastructure.                                                                                                                                                                                                                                                                   |
| Workspace administrator enables Slack without informing contributors about monitored channels                                                                                                                              | Privacy §10 documents the workspace configuration and responsibilities. Monitored channels are forward-only, require explicit activation, post a visible channel announcement, and provide App Home/settings opt-out plus erasure.                                                                                                                                                                             |
| Compromise of the application DB exposes federated identity links + cookie-session revocation list                                                                                                                         | AET infrastructure hosts the application. Upstream tokens have encryption at rest. Cookie-JWTs use short-lived ES256 tokens with server-side revocation. Ingress is TLS-only. The TUM DPO oversees incident response.                                                                                                                                                                                                                    |
| Source expansion combines more contributor context than an enabled practice needs                                                                                                                                          | Resolve the permitted practice set and purpose before collection. Justify the uncapped context in the DPIA. Default-deny new source uses. Require the [artifact-source governance gate](./artifact-source-governance.md).                                                                                                                                                                                                        |
| A missing source systematically withholds feedback from particular platforms, workflows, or privacy choices                                                                                                                | The internal readiness report records refusal separately from what a review observed. Do not compare or rank results across unequal evidence coverage. Coverage analytics and administrator remediation must not be claimed until their operator surface is implemented.                                                                                                                                                                                                                     |
| Repository history exposes deleted secrets or personal data beyond the reviewed change                                                                                                                                     | Contract 1.3.0 includes reachable Git history in read-only review snapshots. Upstream configuration, credentials, hooks, and unreachable objects are not exported. Workspace authorization, governed source use, and evidence retention still apply. Secrets removed from the current tree can remain in reachable history. Deployment privacy owners must reassess this expanded scope.                                                                                                                                                                                                                                                          |
| Restricted or reviewer-only context is quoted into developer-facing feedback                                                                                                                                                 | Source-use decisions govern automated review and feedback delivery separately. Capture requires the automated-review purpose. Delivery rechecks the feedback-delivery purpose, source authorization, and citation ownership.                                                                                                                                                                                                                                                        |
| Re-identification of released benchmarks built from free text and code. Git history, authorship and writing or coding style can identify an author. | Run a motivated-intruder style test before release. Do not call a dataset anonymous without it. If the test fails, the dataset stays inside the research team. State the residual risk honestly (EDPB Guidelines 1/2026 para 164). |
| Pseudonymized data goes to AI model providers during benchmark runs | Pseudonymized data is still personal data (EDPB Guidelines 01/2025). Send only pseudonymized data, and only to a recipient whose agreement, location and training terms are recorded in the [processor checklist](./processor-checklist.md). Apply the participant's AI choice (No AI, In-house or Cloud) to the runs. |
| A participant withdraws, but the data is already in a dataset | The research team removes the account's data from datasets that are not yet anonymized without undue delay, as a manual step. Anonymized data in a published result or dataset cannot be removed, so release only anonymized data. Test the withdrawal path before the first release. |
| Third-party code in a dataset carries a licence that does not allow the release | This is not a GDPR matter, but it is a release gate. Check the licence of each source before release. |
| Special-category content in free text reaches a research dataset | The research team does not look for special-category data (Art. 9) and removes any that it finds. Researchers screen for names and remove what they find. Assess this incidental content in the full DPIA. |
| The broad research purpose is wider than a participant can foresee | Keep participants regularly informed about the research through a research information page or a named research contact (section 7). Assess whether an ethics committee vote is needed and get it where the institution or study requires it ([research prerequisites](../legal-pages.mdx#what-operators-must-do)). Restrict access to the research team. See section 7. |
| Consent is not freely given because of a power imbalance | Keep a real refusal path with no disadvantage and equal-weight answers (EDPB Guidelines 3/2022). Keep the research team separate from grading and line management. If doubt remains, do not rely on consent. |

The table records risks and available controls, not measured residual-risk ratings. The full DPIA must assess likelihood and severity with deployment evidence, including affected people and misuse outside the product.

## 5. Safeguards that must remain in place

- **Recorded provider terms** for every external model provider. Verify retention, training terms and human access per recipient in the processor checklist. Regressing to a consumer tier is a material change.
- **Per-job LLM proxy** enforced by the practice-review sandbox. The sandbox has no DNS.
  The sandbox limits outbound traffic to the token-authenticated worker gateway for governed LLM calls and declared workspace/result transfers. Any widening of this network posture is a material change.
- **Heph network scope:** Heph has a separate allow-internet binding. The internal-network restriction for practice reviews does not prove that Heph has no internet access. Before enabling broader connectivity, record the actual setting.
  Before enabling broader connectivity, assess the setting.
- **Delivery controls:** a signed-in contributor's **Comments and Slack reminders** setting gates its delivery paths.
  The workspace's Heph model rows (under **AI models**) gate their corresponding delivery paths. Removing or bypassing either is a material change.
- **Data-subject rights process** in privacy §7. The delivery setting is not presented as an Art. 21 objection or as a control over review processing.
- **Workspace configuration and responsibilities notice** in the privacy statement (§10). Structural changes to the shared-responsibility split require an amended record.
- **No HTTP access log.** The production profile disables Tomcat's access log.
  The Traefik ingress runs without `--accesslog`.
  Both nginx containers disable the access log at the server level.
  The shipped production stack has no general access-log copy. Authentication and diagnostic events are separate. Enabling it at any layer is a material change.
- **Error telemetry needs a deployment decision.** Browser Sentry is optional and consent-controlled. Server Sentry is configured separately by DSN and does not read browser consent. Verify the actual endpoint, enabled state, recipient, retention and scrubbing before activation. Code alone does not prove that production has disabled it.
- **Research consent gate.** Research use of a participant's data requires the latest `RESEARCH_PARTICIPATION` decision to be a grant.
  The grant must be for the current notice version and research organization.
  An earlier yes to narrower wording does not carry over. Removing this gate is a material change.
- **Anonymization before release.** A dataset or benchmark leaves the research team only if it is anonymized.
  A release of data that is not anonymized needs a separate choice and a new notice version.
- **AI model providers in benchmark runs.** They receive only pseudonymized data, under an Art. 28 agreement.
  The participant's AI choice applies to these runs.
- **First-party product feedback.** Feedback and survey responses stay in the instance database.
  Only instance administrators can see them.
  They are not reused for research. A research-purpose survey reaches only accounts with a current research grant for the current notice version and the named organization.
  The survey states this on screen. Its answers are research data under that consent.

## 6. Required determination and change freeze

None of the specific Art. 35(3) examples is documented as present, but the broader WP29 screen identifies several
concurrent risk criteria. A full DPIA is indicated for the combined scope already shipped. The controller must record the assessment and deployment decision. Do not defer the assessment until another source is added.

Until the controller records that decision, the TUM-operated deployment must not enable any of these changes:

- A new artifact-source family.
- A new cross-source purpose.
- Broader processor egress.
- Extended evidence retention.
- A materially broader monitored population.
 This is not an instruction to weaken existing safeguards or erase operational audit evidence.

`ENGINEERING_BASELINE` / `ENGINEERING_APPROVED` in the machine source-use registry is not a legal decision
and does not permit expansion.

As a deployment governance requirement, reassess the DPIA and amend it where the change affects its conclusions before any of the following takes effect. These are review triggers, not a claim that every change independently meets a statutory DPIA threshold:

- An external model provider is added, changed to a consumer tier, or its recorded training terms change.
- Practice-review sandbox gains outbound connectivity beyond the governed worker gateway.
- Observations begin to drive any automated decision within Hephaestus (grading, recognition caps, feature access).
- The bundled Sentry integration is activated against a SaaS tenant.
- The processing population starts to include data subjects in a category covered by the BayLfD vulnerable-data-subjects criterion.
- Repository ingestion expands beyond administrator-selected repositories into systematic or large-scale monitoring of public sources.
- Research or evaluation reuse starts or widens.
  The research consent covers it only for participants with a current grant. The controller must record the decision for that reuse.
- A dataset release contains data that is not anonymized.
- A dataset release serves a purpose outside the stated research area.
- A dataset release goes to a third-country recipient without adequate safeguards.
- A proposed change has no coverage in the recorded decision.
  This includes a new artifact source, source combination, private-conversation use or repository-history use.
  It also includes research/evaluation reuse, retention extension or a developer/admin audience.
- The public activity page shows more than counts of work in public repositories, or it goes on by default.

### Reassessment: Practices across the workspace without a smallest count

**Date:** 2026-10-07. **Trigger:** a new developer audience. **Change:** ADR 0051, amendment of 2026-10-07.
**Decided by:** the maintainer, as product owner. **Residual risk:** low.

Other members of a workspace see counts of practice standings with no smallest count.
A small count can tell them the standing of one developer.
This reassessment also records the derivation decision of rule 6 in [`artifact-source-governance.md`](./artifact-source-governance.md) for these counts.

The reasons:

- The page shows only a coarse standing per practice group and practice, as counts.
  It shows no feedback text, no observations, and no work content.
- The audience is the closed workspace.
  Its members already see the pull requests that the standing comes from.
- A standing is not a grade, and a person still assesses the work.
  Thus, the page makes no automated decision under Art. 22 GDPR.
- Tutors, as workspace admins, already see individual observations.
  Thus, the extra exposure is only towards peers.
  It applies mainly to a bar part of one or two developers.
  Classmates can guess who they are from visible pull requests.
- The realistic worst case is mild social discomfort.
- The page, the user docs, and the privacy notice tell developers about this risk.
  They say that a small count can let others tell where a developer stands.
- The page says that a standing comes from AI review and can be wrong.
  The admin docs say that standings must not be used to grade or assess a person.

The TUM/AET data-protection coordinator confirms this reassessment in the full DPIA, which the current scope needs anyway.

### Reassessment: the public activity page

**Date:** 2026-10-09. **Trigger:** a new audience, the public. **Change:** [ADR 0052](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0052-activity-sorts-by-contributions-and-can-be-public.md).
**Decided by:** the maintainer, as product owner. **Residual risk:** low for open-source project workspaces.
**Open:** the TUM/AET data-protection coordinator confirms this reassessment before the first TUM workspace goes public.

A workspace admin can publish the activity of the workspace's public repositories.
For each person on the page, the public sees this data:

- Display name, login and avatar.
- For the selected range: pull requests opened and merged, pull requests reviewed and their authors, issues opened, and active weeks.
- The weekly trend, the **Contributions** total, the position, and links to the work.

The page shows no practices, feedback, observations, Slack, Outline or AI review content.

**Lawful basis for TUM.**
Hephaestus processes the activity under Art. 6(1)(e) and (3) GDPR i.V.m. Art. 2 BayHIG and Art. 4(1) BayDSG, as for Activity.

The publication is a transfer to the public.
The BayLfD bases a publication on Art. 5(1) sentence 1 no. 1 BayDSG.
The publication must be necessary for a task of TUM, and "mere usefulness" is not sufficient.
The task is an open-source project in teaching and research that credits its contributors in public.
The coordinator confirms this necessity.

**Objection (Art. 21 GDPR).**
The opt-out is the objection to this processing.
**Show me on public activity pages** stops the publication at once, and nobody asks for reasons.

A person without an account objects through the privacy contact.
The operator then applies the same hide to the verified provider identity.
The onboarding step, the page notice and the privacy notice state this right separately from other information (Art. 21(4)).

**Information (Arts. 13 and 14 GDPR).**
The data comes from the provider, so Art. 14 applies.

- A signed-in member gets the onboarding step at the first visit to a public workspace. It offers **Show me** and **Hide me** with equal weight.
- The public page says what it shows and how to hide.
- The privacy notice describes the page, the basis and the objection.
- Outside contributors have no contact data in Hephaestus.
  For them, the controller makes the information public under Art. 14(5)(b).

There is no email, no banner and no notice period.
Thus, a member who does not visit the workspace learns of the page only from the page or the privacy notice.
The maintainer accepts this gap.

**Course workspaces stay private.**
Students in a course are graded by the people who run it.
A public order of students adds pressure that the course does not need.
The workspace admin briefing (privacy §10) says to keep course workspaces private.
Hephaestus does not enforce it.

**Screening criteria.**
The page orders people by counts of their work.
It does not assess the work, but a reader can use it to compare people.
Thus, it adds weight to *evaluation or scoring* and *vulnerable data subjects* in § 2.
The Bavarian Blacklist has no entry for a publication of this kind.
The full DPIA stays indicated, as before.

The reasons for a low residual risk:

- The page shows only work that the provider already publishes.
  GitHub shows a contributors graph of each public repository.
  Hephaestus checks the repository visibility at query time.
- It shows counts of work, never a judgment of the work.
- A person can hide at once.
  A hidden person leaves every total, and no position has a gap for them.
- Three switches must be on.
  The instance switch is off by default.
  Search engines get `noindex` unless an admin allows them, and the response cache lasts 60 seconds.
- An unknown workspace and a private workspace give the same response.

Risks for the full DPIA:

- A copy made before a person hides stays with whoever made it.
- Outside contributors who never heard of Hephaestus appear on the page.
- An admin can compare the member view with the public page and find who is hidden.
  The opt-out hides a person from the public, not from the workspace.
- An employer or a teacher reads the page as a measure of performance.
  ADR 0052 and the admin docs say that the counts are not one.

**Private self-hosters.**
The DSK list addresses the non-public sector (§ 3).
A private operator that publishes this page checks DSK list entries 7 and 8.
Entry 7 covers a large-scale publication of data that third parties can use to evaluate people.
Entry 8 covers employee behavior that can evaluate their work.

In Germany, a works council co-determines a technical system that can monitor employee behavior or performance (§ 87(1) no. 6 BetrVG).
The [operator obligations](../legal-pages.mdx#the-public-activity-page) list the steps.

### Reassessment: workspace addresses

**Date:** 2026-10-09. **Trigger:** a new processor. **Change:** [ADR 0053](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0053-workspace-subdomains-are-a-presentation-origin.md).
**Residual risk:** unchanged, after the processor record is complete.

Workspace addresses change no data category, purpose or audience.
Sign-in and the session stay on `hephaestus.build`.
A workspace host keeps its own cookie choice and theme.
hephaestus.build serves its hosts through a Cloudflare proxy.
Cloudflare then processes the IP address, the request metadata and the content of all traffic that it proxies.
The [processor checklist](./processor-checklist.md) must record Cloudflare before the proxy carries traffic.

The source-specific decision and test checklist lives in
[`artifact-source-governance.md`](./artifact-source-governance.md). The controller's decision identifier and date
must replace the pending status at the top of this file when the determination is recorded.

## 7. Research use under broad consent

Participants can allow research use of their data under Art. 6(1)(a) GDPR. The consent is a broad consent to an area of research (Recital 33 GDPR).
The area is how developers work and learn, and how AI systems can review and support that work.
It includes building and running benchmarks and evaluation datasets for such AI systems.

The controller of the research is the research organization of the instance (`HEPHAESTUS_RESEARCH_ORGANIZATION`). For the TUM deployment, this is TUM (AET).

A change to the research wording is a new notice version, and every account answers once more.
An earlier yes to narrower wording does not authorize the broader scope. The server accepts only a decision on the current notice version and organization.

### Decision: no second consent for sharing or publishing

The screen asks one research question. It has no separate switch for sharing or publishing a dataset. These are the reasons:

1. Only anonymized data leaves the research team. Anonymous data is outside the GDPR (Recital 26).
   A release therefore discloses no further personal data to consent to.
2. EDPB Guidelines 1/2026 para 47 say that broad consent can cover "collection, curation, storage and provision of data".
   The guidelines are a draft for consultation, adopted 15 April 2026.
   The data must stay within the research area and the reasonable expectations of the participants.
3. The Medical Informatics Initiative broad consent and the Our Future Health consent put third-party research use inside one consent.
   The DSK recognizes the Medical Informatics Initiative consent.
4. EDPB Guidelines 05/2020 paras 42-45 on granularity concern separate purposes.
   A dataset release for the same research purpose is not a separate purpose.

A separate choice and a new notice version are required if a release has one of these properties:

- It contains data that is not anonymized.
- It serves a purpose outside the stated research area.
- It goes to a recipient in a third country without adequate safeguards.

### Compensating transparency for the broad purpose

A broad purpose needs more transparency and safeguards (EDPB Guidelines 05/2020 paras 161-162, EDPB Guidelines 1/2026 paras 48-49).
The controller must evidence these controls:

1. Regular information about the research: its current purpose, working methods, projects and released datasets. Give a way to receive updates as the research progresses. A research information page or a named research contact who sends these updates provides it ([research prerequisites](../legal-pages.mdx#what-operators-must-do)). An email address that only waits for questions is not enough.
2. An ethics committee vote where the institution or the study requires one ([research prerequisites](../legal-pages.mdx#what-operators-must-do)). TUM has not sought or obtained an ethics vote for this research.
3. Access restricted to the research team.

### Safeguards and withdrawal

The research applies these safeguards under Art. 89(1) GDPR:

- Before analysis, name, username and contact details are replaced by a code.
- The key is stored apart from the data. Only the research team can reach it.
- Researchers screen free text for names and remove what they find.
- The team does not look for special-category data (Art. 9) and removes any that it finds.

Withdrawal (Art. 7(3) GDPR) is one switch in User settings. It stops use for new research and research survey invitations.
The research team removes the account's data from datasets that are not yet anonymized without undue delay.
This is a manual step of the research team, not an automatic product function.

Anonymized data in a published result or dataset cannot be removed. Research done before withdrawal stays lawful.

The [personal-data map](./personal-data-map.md) records how withdrawal reaches research copies.

## Evidence and sources

The code establishes source scope and safeguards. It does not establish deployment size, a lawful
basis, contracts, operational effectiveness or residual-risk acceptance. Keep those records with the
controller's assessment. In particular, the maintainer approval in
[#2335](https://github.com/hephaestus-build/Hephaestus/pull/2335) is engineering approval only.

- [GDPR Arts. 35 and 36](https://eur-lex.europa.eu/eli/reg/2016/679/oj): high-risk assessment and prior consultation.
- [WP29 DPIA guidelines, WP248 rev.01, endorsed by the EDPB](https://ec.europa.eu/newsroom/article29/items/611236/en): the nine criteria and the usual two-criterion threshold.
- [BayLfD DPIA guidance](https://www.datenschutz-bayern.de/dsfa/) and [Bavarian Blacklist](https://www.datenschutz-bayern.de/datenschutzreform2018/DSFA_Blacklist.pdf): apply the public-sector list and guidance to the actual deployment.
- [EDPB Guidelines 05/2020 on consent](https://www.edpb.europa.eu/system/files/documents/files/file1/edpb_guidelines_202005_consent_en.pdf): paras 153-163 (scientific research), 42-45 (granularity), 46-48 (detriment), 16-24 (imbalance of power).
- [EDPB Guidelines 1/2026 on scientific research](https://www.edpb.europa.eu/system/files/2026-05/edpb_guidelines_202601_scientificresearch_en.pdf): draft for consultation. Paras 44-49 and 163-164.
- [DSK resolution of 3 April 2019 on Recital 33](https://www.datenschutzkonferenz-online.de/media/dskb/20190405_auslegung_bestimmte_bereiche_wiss_forschung.pdf): broad consent for scientific research.
- EDPB Guidelines 3/2022 on deceptive design patterns: equal-weight answers and no emotional steering.
- EDPB Guidelines 01/2025 on pseudonymisation: pseudonymized data is still personal data.
- GDPR Recitals 26, 33 and 43, and Arts. 7, 89: anonymous data, broad consent, imbalance, consent conditions and research safeguards.
- [GDPR Arts. 14 and 21](https://gdpr-info.eu/art-21-gdpr/): information for data from another source, and the objection to Art. 6(1)(e) processing.
- [BayDSG Art. 4](https://www.gesetze-bayern.de/Content/Document/BayDSG-4) and [Art. 5](https://www.gesetze-bayern.de/Content/Document/BayDSG-5), [BayHIG Art. 2](https://www.gesetze-bayern.de/Content/Document/BayHIG-2): the TUM task and the transfer to the public.
- [BayLfD 34th activity report, no. 5.4](https://www.datenschutz-bayern.de/tbs/tb34/k5.html): a publication on the internet rests on Art. 5(1) sentence 1 no. 1 BayDSG and must be necessary.
- [WP29 transparency guidelines, WP260 rev.01](https://ec.europa.eu/newsroom/article29/items/622227): layered notices, and public information under Art. 14(5)(b).
- [GitHub Docs, contributors graph](https://docs.github.com/en/repositories/viewing-activity-and-data-for-your-repository/viewing-a-projects-contributors): the provider's own public view of contributors.
- [§ 87 BetrVG](https://www.gesetze-im-internet.de/betrvg/__87.html): works council co-determination for technical monitoring.
- [DSK mandatory DPIA list, version 1.1](https://www.datenschutzkonferenz-online.de/media/ah/20181017_ah_DSK_DSFA_Muss-Liste_Version_1.1_Deutsch.pdf): cross-check evaluation and monitoring in employment. Non-public operators must check its direct applicability.

Adversarial cases for the full assessment include:

- A course leader reads disputes.
- A reviewer who never signed in appears in a colleague's evidence.
- Shared repository evidence contains work by someone who chose No AI.
- A long-lived Slack thread contains sensitive content.
- A revoked repository permission leaves a retained mirror.
- A backup restore reintroduces erased data.
- A released benchmark lets a reader identify the author of a code sample.
- A researcher who also grades or manages a participant sees that participant's data.
 For each case, record the audience, lawful basis, prevention, rights path and remaining
risk. Do not infer low impact from the absence of automated decisions.
