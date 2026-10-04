# Hephaestus — DPIA Pre-Screen (Art. 35 GDPR)

> **Status: controller/DPO determination pending.** Engineering conclusion: a full DPIA is indicated for the current scope. Several WP29 risk criteria are present. Advisory output reduces impact, as does the absence of grading, employment, or access decisions.
> These safeguards do not negate evaluation, systematic monitoring, dataset combination, or the student/employee power imbalance. This engineering screen is not the
> controller's Art. 35 determination.
>
> **Review owner:** TUM/AET data-protection coordinator. **Engineering review:** 2026-10-03. **Next review:** before
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
| Evaluation or scoring                                         | **Yes**                                   | Practice reviews create contributor-specific assessments of observed engineering work. Advisory and contestable output, no automated grading/HR/access decision, and separate delivery controls reduce consequence. They do not negate the criterion.           |
| Automated decision with legal or similarly significant effect | **No**                                    | Observations do not determine grades, employment, recognition caps, feature access, merge rights, or another legal/significant outcome. Any such consumer is a material change requiring a full reassessment before use.                                               |
| Systematic monitoring                                         | **Yes**                                   | Enabled workspaces repeatedly observe activity in administrator-connected repositories and optional monitored Slack/Outline sources. The scope is selected rather than an open crawl, but the processing is organized and recurring.                               |
| Sensitive or highly personal data                             | **Partly**                                | The system does not solicit or classify Art. 9/10 data, but free-text code, issues, messages, documents, and conversations can contain incidental sensitive or highly personal content. Private mentor and Slack conversations receive the stricter source class.  |
| Data processed on a large scale                               | **Not established by code** | Workspace scope is a tenancy boundary, not a scale measurement. Record the current deployment population, volume, duration and geographic reach. Reassess before expansion.                                                                                                   |
| Matching or combining datasets                                | **Yes**                                   | Practice review and mentoring can combine GitHub/GitLab activity with Hephaestus observations and, where enabled, selected Slack messages and Outline documents. This is source combination even though Hephaestus does not enrich people from commercial external profiles. |
| Vulnerable data subjects                                      | **Yes**                                   | Students and employees can face a power imbalance and may be unable to oppose workspace-level processing as easily as an ordinary consumer. Advisory use and rights procedures reduce risk. The product does not enforce a ban on human grading or HR use. The controller must assess that use. The controller must govern it.                                        |
| Innovative technology or organizational solution              | **Yes**                                   | An LLM interprets work artifacts and produces contributor-specific practice feedback. Enterprise no-training terms and restricted egress reduce risk but do not remove this criterion.                                                                             |
| Prevents exercise of a right or use of a service/contract     | **No**                                    | The product does not gate course, employment, repository, or Hephaestus access on observations.                                                                                                                                                                        |

WP248 rev.01 states that, in most cases, processing that meets two criteria warrants a DPIA. It states that more criteria make high risk more likely. Multiple criteria above are present. A full DPIA is indicated now.

The wider job folder can include permitted Slack threads and person-scoped observation and feedback history without record-count or history-window caps. This increases combination and longitudinal evaluation, including third-party context. Worker isolation and an advisory output reduce risks but do not remove the screening criteria.

1. Before authorizing the current scope for the deployment, open its full assessment.
2. Record necessity, proportionality, risks to people and measures under Art. 35(7).
3. Seek DPO advice under Art. 35(2).
4. Consider affected people under Art. 35(9).
 If high residual risk remains despite measures, Art. 36 requires prior supervisory consultation. A controller who concludes that no DPIA is required must record evidence addressing each present criterion. Absence from a blacklist or absence of automated grading is insufficient. This engineering conclusion does not record a completed DPIA or legal approval.

## 3. DSK list — cross-check (not directly applicable)

The Bavarian Blacklist includes extensive employee-behavior processing that can be used to evaluate work with legal or similarly significant effects (entry 7). Check actual teaching and employment use against it. An advisory product does not prevent a controller from using its outputs for significant decisions. The DSK employment criterion provides the same cross-check for non-public operators.

The DSK list of processing operations requiring a DPIA under Art. 35(4) GDPR addresses the **non-public sector**
and is not directly applicable to TUM/AET as a Bavarian public body. The controlling instrument for TUM is the
Bavarian Blacklist in §2. The absence of one exactly named DSK constellation does not override the multi-criterion
WP29 assessment above.

Enterprise no-training terms, the documented joint-controller arrangement, restricted LLM
egress, and the Art. 21 rights process reduce residual risk but do not decide whether Art. 35 requires a DPIA.
They remain mandatory while the determination is pending.

## 4. Risks for the full assessment

| Risk                                                                                                                                                                                                                       | Controls to verify                                                                                                                                                                                                                                                                                                                                                                                                       |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Free-text artifact content (PR descriptions, issue descriptions, commit messages, review comments) contains personal data of identifiable third parties and is transmitted to the LLM provider                             | The privacy statement instructs users not to enter third-party personal data. The practice-review sandbox runs on a per-job `--internal` Docker network with no general egress. It uses a token-authenticated worker gateway for LLM calls and declared workspace/result transfers. The provider is subject to enterprise no-training and transfer safeguards. Objections to processing follow the Art. 21 contact process in privacy §7.                                 |
| Practice-feedback comments or Slack reminders reach a contributor after their comments-and-reminders setting is disabled. Alternatively, the Slack mentor accepts a new interaction after the workspace's Heph models are off. | Delivery policy is checked before starting each comment or reminder and before admitting each new Slack mentor interaction. An interaction admitted before the workspace's Heph models change may finish. The personal setting covers issue, pull-request, and merge-request comments plus Slack reminders, and delivered comments link back to it.                                                                    |
| LLM provider retains the prompt beyond the enterprise default retention window                                                                                                                                             | Enterprise no-training terms apply. Abuse-monitoring retention follows the provider's published terms. Microsoft Azure OpenAI uses the enterprise abuse-monitoring window in its published data-privacy documentation. Eligible customers can apply for Microsoft's modified abuse monitoring / Limited Access program. Zero Data Retention can be negotiated where the provider supports it. Verify the actual agreement, recipient and transfer safeguards.      |
| Server access logs retain IP addresses                                                                                                                                                                                     | No layer writes an HTTP access log. The production profile explicitly disables Tomcat's access log. The Traefik reverse proxy starts without `--accesslog` (Traefik's default is off). Both nginx containers disable the access log at the server level. The shipped stack creates no general HTTP access-log copy. Authentication events record IP addresses, under that log's 12-month partitioned window. Other authentication and operational events can contain personal details. Verify deployment logging and support exports. |
| gitlab.lrz.de content leaks through the LRZ integration                                                                                                                                                                        | LRZ is a separate controller. Inter-public-body transmission falls under Art. 5(1) Nr. 1 BayDSG. LRZ applies its own TOMs on its own infrastructure.                                                                                                                                                                                                                                                                   |
| Workspace administrator enables Slack without informing contributors about monitored channels                                                                                                                              | Privacy §10 documents the joint-controller / shared-responsibility model. Monitored channels are forward-only, require explicit activation, post a visible channel announcement, and provide App Home/settings opt-out plus erasure.                                                                                                                                                                             |
| Compromise of the application DB exposes federated identity links + cookie-session revocation list                                                                                                                         | AET infrastructure hosts the application. Upstream tokens have encryption at rest. Cookie-JWTs use short-lived ES256 tokens with server-side revocation. Ingress is TLS-only. The TUM DPO oversees incident response.                                                                                                                                                                                                                    |
| Source expansion combines more contributor context than an enabled practice needs                                                                                                                                          | Resolve the permitted practice set and purpose before collection. Justify the uncapped context in the DPIA. Default-deny new source uses. Require the [artifact-source governance gate](./artifact-source-governance.md).                                                                                                                                                                                                        |
| A missing source systematically withholds feedback from particular platforms, workflows, or privacy choices                                                                                                                | The internal readiness report records refusal separately from what a review observed. Do not compare or rank results across unequal evidence coverage. Coverage analytics and administrator remediation must not be claimed until their operator surface is implemented.                                                                                                                                                                                                                     |
| Repository history exposes deleted secrets or personal data beyond the reviewed change                                                                                                                                     | Contract 1.3.0 includes reachable Git history in read-only review snapshots. Upstream configuration, credentials, hooks, and unreachable objects are not exported. Workspace authorization, governed source use, and evidence retention still apply. Secrets removed from the current tree can remain in reachable history. Deployment privacy owners must reassess this expanded scope.                                                                                                                                                                                                                                                          |
| Restricted or reviewer-only context is quoted into developer-facing feedback                                                                                                                                                 | Source-use decisions govern automated review and feedback delivery separately. Capture requires the automated-review purpose. Delivery rechecks the feedback-delivery purpose, source authorization, and citation ownership.                                                                                                                                                                                                                                                        |

The table records risks and available controls, not measured residual-risk ratings. The full DPIA must assess likelihood and severity with deployment evidence, including affected people and misuse outside the product.

## 5. Safeguards that must remain in place

- **No-training enterprise API terms** for every LLM provider configured by the AET-pool. Regressing to a consumer tier is a material change.
- **Per-job LLM proxy** enforced by the practice-review sandbox. The sandbox has no DNS.
  The sandbox limits outbound traffic to the token-authenticated worker gateway for governed LLM calls and declared workspace/result transfers. Any widening of this network posture is a material change.
- **Heph network scope:** Heph has a separate allow-internet binding. The internal-network restriction for practice reviews does not prove that Heph has no internet access. Before enabling broader connectivity, record the actual setting.
  Before enabling broader connectivity, assess the setting.
- **Delivery controls:** a signed-in contributor's **Comments and Slack reminders** setting gates its delivery paths.
  The workspace's Heph model rows (under **AI models**) gate their corresponding delivery paths. Removing or bypassing either is a material change.
- **Data-subject rights process** in privacy §7. The delivery setting is not presented as an Art. 21 objection or as a control over review processing.
- **Workspace-administrator joint-controller notice** in the privacy statement (§10). Structural changes to the shared-responsibility split require an amended record.
- **No HTTP access log.** The production profile disables Tomcat's access log.
  The Traefik ingress runs without `--accesslog`.
  Both nginx containers disable the access log at the server level.
  The shipped production stack has no general access-log copy. Authentication and diagnostic events are separate. Enabling it at any layer is a material change.
- **Error telemetry needs a deployment decision.** Browser Sentry is optional and consent-controlled. Server Sentry is configured separately by DSN and does not read browser consent. Verify the actual endpoint, enabled state, recipient, retention and scrubbing before activation. Code alone does not prove that production has disabled it.
- **First-party product feedback.** Feedback and survey responses stay in the instance database.
  Only instance administrators can see them.
  They are not reused for research. A research-purpose survey reaches only accounts with a current research grant for the named organization.
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

- LLM provider is added, changed to a consumer tier, or loses its no-training commitment.
- Practice-review sandbox gains outbound connectivity beyond the governed worker gateway.
- Observations begin to drive any automated decision within Hephaestus (grading, recognition caps, feature access).
- The bundled Sentry integration is activated against a SaaS tenant.
- The processing population starts to include data subjects in a category covered by the BayLfD vulnerable-data-subjects criterion.
- Repository ingestion expands beyond administrator-selected repositories into systematic or large-scale monitoring of public sources.
- A proposed change has no coverage in the recorded decision.
  This includes a new artifact source, source combination, private-conversation use or repository-history use.
  It also includes research/evaluation reuse, retention extension or a developer/admin audience.

The source-specific decision and test checklist lives in
[`artifact-source-governance.md`](./artifact-source-governance.md). The controller's decision identifier and date
must replace the pending status at the top of this file when the determination is recorded.

## Evidence and sources

The code establishes source scope and safeguards. It does not establish deployment size, a lawful
basis, contracts, operational effectiveness or residual-risk acceptance. Keep those records with the
controller's assessment. In particular, the maintainer approval in
[#2335](https://github.com/hephaestus-build/Hephaestus/pull/2335) is engineering approval only.

- [GDPR Arts. 35 and 36](https://eur-lex.europa.eu/eli/reg/2016/679/oj): high-risk assessment and prior consultation.
- [WP29 DPIA guidelines, WP248 rev.01, endorsed by the EDPB](https://ec.europa.eu/newsroom/article29/items/611236/en): the nine criteria and the usual two-criterion threshold.
- [BayLfD DPIA guidance](https://www.datenschutz-bayern.de/dsfa/) and [Bavarian Blacklist](https://www.datenschutz-bayern.de/datenschutzreform2018/DSFA_Blacklist.pdf): apply the public-sector list and guidance to the actual deployment.
- [DSK mandatory DPIA list, version 1.1](https://www.datenschutzkonferenz-online.de/media/ah/20181017_ah_DSK_DSFA_Muss-Liste_Version_1.1_Deutsch.pdf): cross-check evaluation and monitoring in employment. Non-public operators must check its direct applicability.

Adversarial cases for the full assessment include:

- A course leader reads disputes.
- A reviewer who never signed in appears in a colleague's evidence.
- Shared repository evidence contains work by someone who chose No AI.
- A long-lived Slack thread contains sensitive content.
- A revoked repository permission leaves a retained mirror.
- A backup restore reintroduces erased data.
 For each case, record the audience, lawful basis, prevention, rights path and remaining
risk. Do not infer low impact from the absence of automated decisions.
