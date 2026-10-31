# Hephaestus — Art. 28 Processor Checklist

Use this checklist to record each recipient of personal data in your deployment and its role under the GDPR.
Hephaestus does not establish any of these relationships for you.
Fill in the recipient record below for each recipient before you enable it.

## Recipients to assess

| Component | What it receives | Role to assess |
|---|---|---|
| Your container host, PostgreSQL, NATS and the application roles | All application data | Own infrastructure needs no agreement. An externally operated host or database needs an Art. 28 assessment. |
| Worker, review sandboxes and the conversation host | AI work, temporary context and mounted evidence | Own infrastructure needs no agreement. Record each external worker operator, location, access and deletion acknowledgement. |
| Reverse proxy or Gateway, ACME endpoint, DNS provider | TLS traffic and domain-validation data | A DNS-only record carries no request data. A proxied record or CDN makes its provider a recipient of request data. |
| GitHub, a GitLab instance | Sign-in identities and the source system's data | Assess whether the provider processes on your instructions or is a separate controller of its own service. |
| Model providers configured for a workspace | Prompts and responses for practice review and Hephaestus, where the AI choice allows them | An external provider processes on your instructions and needs an agreement. An in-house model on your infrastructure does not. |
| Slack | App delivery, identity linking, Hephaestus messages and monitored-channel events | Assess when a workspace enables it. |
| An Outline instance | Selected documents and optional sign-in linking | Classify the exact origin: your infrastructure, a processor, or a separate controller. |
| SMTP relay | Recipient addresses, notification type and links | Record the relay operator, hosting, retention terms and role before you enable email. |
| Log store, trace collector | Container logs and traces | See [Optional log collection and monitoring](#optional-log-collection-and-monitoring). |

First-party product feedback submissions remain inside the instance PostgreSQL database.
The webapp and the server include no error-reporting service.

## Evidence required before engagement

The repository cannot prove that a DPA is executed, that a certification is current or that production
uses the stated endpoint. Before activation or renewal, the controller must fill this recipient record:

| Field | Required deployment evidence |
|---|---|
| Recipient and role | `[legal entity, service, endpoint, controller/processor/joint-controller analysis]`. Distinguish source-platform purposes from processing on instructions. |
| Contract | `[signed DPA reference, instructions, subprocessors, renewal/review owner]`. An API key is not an agreement. |
| Scope | `[data categories, subjects, purposes, review/Hephaestus inputs and any human provider access]`. Include broader Slack-thread and person-history context. |
| Retention and training | `[prompt/response and abuse-monitoring retention, deletion path, training terms, any exception]`. No-training is not zero retention. |
| Location and transfer | `[storage, inference, support access, subprocessors, transfer mechanism and evidence date]`. Verify deployment type, not just a resource's region. |
| Rights and exit | `[export/erasure assistance, incident notice, credential revocation, deletion confirmation]`. State what a completed request cannot retract. |

Art. 28 requires a processor agreement only where the recipient actually processes on the controller's
instructions. Software selection or an admin role cannot establish that relationship. External workspace institutions may be joint controllers under an actual arrangement. Individual authorized admins within one controller are not separate controllers merely because they choose settings.
Assess your own roles rather than copy another operator's arrangement.

For transfers, verify the appropriate basis for the exact recipient and covered service:

1. Verify an active adequacy decision and certification.
2. Alternatively, verify the appropriate SCC module and transfer assessment with supplementary measures where required. Module 2 is controller-to-processor. It is not automatically the right module for separate controllers or a processor chain. EU hosting and DPF participation do not by themselves
prove every support or subprocessor flow stays in the EEA. Keep evidence and a review date.

### Optional log collection and monitoring

Record `[log store operator, endpoint, hosting, retention, access, contract and transfer safeguard]`
before you collect container logs. Controller-owned self-hosting and an external service have different
roles. Collected lines carry stacks, workspace and job identifiers and trace IDs. Library exception
text can still carry personal data.

1. Inspect synthetic lines from each runtime role before activation.
2. Restrict read access to the operators who need it.
3. Set the store's retention to the recorded period.

A release before 1.0 could send error reports to Sentry. That recipient keeps what it received under
its own retention. Removing the integration does not delete those reports.

Optional trace export also needs an
approved collector and retention. It can contain workspace and run identifiers. Private metrics
must not be exposed through the public application route.

### Research benchmark runs

Building and running benchmarks for research can mean running AI models on research data.
The research organization is the controller of this research.
A provider that runs these models for it is a processor.

Apply these rules to every benchmark run:

1. Complete the recipient record above for each provider before its first run. Do not assume that the agreement, region or training terms of practice review apply.
2. Send only pseudonymized data. Pseudonymized data is still personal data (EDPB Guidelines 01/2025).
3. Send data only for participants with a current research grant.
4. Honor the participant's AI choice (**No AI**, **In-house** or **Cloud**) in the run.

Recipients of anonymized datasets are not processors. Anonymous data is outside the GDPR (Recital 26).
A release of a dataset that is not anonymized needs a separate choice and a new notice version.

## Follow-up if the processing surface changes

Amend this record, your Art. 30 record, and the privacy statement before deploying any of the following:

- A new model provider.
- A new identity provider.
- Enabling Outline for a new origin or changing its operator, hosting region, or contractual role.
- SMTP email delivery becomes active. The chosen SMTP host becomes a recipient of personal data. An external relay needs an Art. 28 DPA.
- Collecting container logs into a log store, or adding a recipient to one. Record the exact operator, role, region, retention and transfer path. Amend the notice and reassess the DPIA. An external processor needs an Art. 28 agreement.
- The application adds external storage (S3, CDN) or serves a third-party font, script, image, or embed. This requires a recipient-role assessment, any applicable agreement and a privacy-statement entry.
- Hephaestus gains internet access or the practice-review sandbox network posture expands beyond the governed worker gateway. This triggers a new DPIA assessment.
- An existing processor receives a new source privacy class. Its region or provider retention changes. Product evidence gains an operator/research evaluation use. An existing DPA does not by itself authorize a new purpose or data category. Complete the [source governance gate](./item-source-governance.md).
- Research benchmark runs start without the agreement that "Research benchmark runs" requires.
- Research benchmark runs use data that is not pseudonymized.

### Direct browser image requests

Avatar components pass provider image URLs to browser image elements. Hephaestus does not proxy these requests. The image host receives visitor network metadata and the requested URL, including from
people browsing public pages without signing in. Record `[hosts, roles, basis, transfer safeguard and
retention]`.

Third-party hosting does not automatically imply Art. 28 status. Assess its actual role.

## Primary references

- [EDPB Guidelines 07/2020, controller and processor](https://www.edpb.europa.eu/documents/guideline/guidelines-072020-on-the-concepts-of-controller-and-processor-in-the-gdpr_en): roles depend on actual purposes and means.
- [European Commission transfer safeguards and SCCs](https://commission.europa.eu/law/law-topic/data-protection/international-dimension-data-protection/standard-contractual-clauses-scc_en): choose the module for the actual roles.
- [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data): distinguish training, abuse monitoring and endpoint-specific application state. Do not infer these terms for a compatible third-party API.
- [Microsoft Azure OpenAI data privacy](https://learn.microsoft.com/en-us/legal/cognitive-services/openai/data-privacy): verify deployment geography and any modified abuse-monitoring approval.
- [Loki label best practices](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/) and [structured metadata](https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/): keep per-request and per-person values out of index labels.
- [GitLab account deletion](https://docs.gitlab.com/user/profile/account/delete_account/): upstream deletion is separate from Hephaestus copies and attribution.

Recheck these terms against the contract and exact service version when a provider changes. Published
vendor documentation does not prove that the deployment has purchased or enabled a particular control.
