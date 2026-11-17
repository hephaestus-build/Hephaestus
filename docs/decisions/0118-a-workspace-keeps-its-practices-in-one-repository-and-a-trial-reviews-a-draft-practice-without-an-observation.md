# ADR 0118: A workspace keeps its practices in one repository, and a trial reviews a draft practice without recording an observation

**Status:** Accepted
**Date:** 2026-11-16
**Authors:** Hephaestus maintainers

**Amends:** [ADR 0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) (standing skips observations sealed in feedback mode Off), [ADR 0110](0110-a-workspace-admins-mcp-client-configures-practices-through-the-workspace-mcp-endpoint.md) (trial output, and practices that a repository writes)

**Builds on:** [ADR 0028](0028-source-synced-practice-catalog.md) (catalog layers), [ADR 0055](0055-practice-scripts-use-one-result-contract.md) (the script contract), [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) (purses, caps and the AI ceiling), [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) (review stages), [ADR 0105](0105-generated-text-carries-a-signed-provenance-record.md) (signed records)

## Context

A team must be able to write a practice, try it on real work and improve it before it judges anyone.
Today an admin edits a practice only in the web app or through MCP.
A changed definition shows its effect only in the next real review, and that review can reach a developer.

Teams also want their practices next to their code, changed through pull requests.
The bundled catalog is already kept in Git: definitions, guides, visuals and scripts, with labeled outcome cases in the test tree.
A file must not become a second practice model.

Feedback mode Off is the natural place to watch a new practice before it reaches people.
The user docs say that with Off, no feedback reaches the work, practice pages or conversations.
However, standing still counts observations of practices in feedback mode Off.

## Decision drivers

- **Feedback earns trust or it is not sent.** A trial must not create an observation, a standing or feedback.
- **One practice model** (ADR 0085). A file is a second editor of the same definition.
- **Configuration comes from the default branch,** never from the work that it judges.
- **A labeled judgment about a person's work** stays as private as an observation.
- **Every action has its reverse.**

## Considered options

1. **A `.hephaestus/` folder in each monitored repository, with practices scoped to their repository.** It needs slug namespacing across repositories and a new selection predicate. It has no home for Slack and Outline practices.
2. **One practice repository for each workspace (chosen).** Practices of every kind are workspace practices. The review scope and Applies when scope them. A `repository` clause in Applies when can come later, by addition.
3. **Configuration from the commit of the pull request.** A pull request could then turn off the practice that judges it. Rejected.
4. **A separate trial model and runner.** It needs a second capture, admission and run record, which must agree with production. Rejected.
5. **A review with origin `TRIAL` that never seals (chosen).** Capture, the runner and admission are the production code. The database refuses a result for a trial.
6. **A run of the draft practice beside the live version on real events.** Two versions would hold claims on one item and developer, which ADR 0085 decision 10 forbids. Rejected.
7. **A fourth feedback mode for watching a practice.** It doubles a concept that feedback mode Off already defines. Rejected.
8. **Case files that name real pull requests.** They publish a labeled judgment about a named person's work, and they break on another instance. Rejected.
9. **An unsigned marker with the observation on each provider comment, for coding agents.** ADR 0105 rejected unsigned flags, because anyone can forge one. Rejected.

## Decision

### Practices in a repository

1\. **A practice file is a serialization of the definition.**

- The folder `<slug>/` holds `practice.md`: YAML front matter, and the criteria as the Markdown body.
- The folder can also hold `guide.md`, `visual.svg` and `script.ts`.
- The front matter is a closed shape. It holds `format`, `kind` and `group`, and the other fields of `PracticeDefinition` under their own names.
- The folder name follows the slug rule of `@ValidPracticeSlug`.
- A parsed definition passes the same validators as a save in the web app.
- A validator that calls no model reports each error with its file, line and column. It is a script and an MCP tool.
- Every bundled practice serializes to a folder and parses back to an equal definition.

2\. **A workspace has at most one practice repository.**

- The review settings store it: any monitored repository, and a directory with the default `.hephaestus/practices/`.
- Hephaestus reads the directory from the default branch only.
- It reads on a push that changes the directory, on the scheduled sync and on request.
- The sync goes one way, from the repository to the workspace.

3\. **Each active practice is edited in one place:** Hephaestus or the repository.

- The web app and the MCP write tools refuse to change a practice that the repository edits.
- A folder never takes over an active practice that is edited in Hephaestus.
- **Move to repository** hands an active practice to its folder on the default branch.
- **Edit in Hephaestus** hands it back with its current version. The sync then ignores its folder.
- A version records the commit that it came from. A new version comes only from a changed definition.

4\. **A broken or removed file deletes nothing.**

- With an invalid folder, a practice keeps its current version, and the sync reports the error.
- A removed folder adds a version with Reviewed by Nobody. A restored folder adds a version from the file.
- When the practice repository is unset, its practices become edited in Hephaestus. Move to repository hands each one back.

5\. **The repository owns the definition, the kind and the group.**

- The workspace owns the feedback mode, the position and the state.
- A new repository practice starts in feedback mode Off.
- A changed practice keeps its mode, so a live practice changes on merge.
- The sync report says when a merged definition has a review digest that no trial used.

### Feedback mode Off counts for nobody

6\. **Each review practice records its effective feedback mode when the review seals.**

- An observation sealed in Off neither counts nor hides a live observation in standing ([ADR 0085, update of 2026-11-16](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md)).
- A later change of the mode does not change the classification of an earlier observation.
- Admin views show every observation.

### Trials

7\. **A practice can be a draft practice.**

- No event, schedule, scan or request selects a draft practice, and members do not see it.
- The entity carries a framework filter on the state. An architecture test fails on a native query that reads practices without the state.
- A tried definition of an existing practice is a version that is not current. History views list only versions that were current.
- The sync activates a draft practice that has the same slug. An admin can delete a draft practice at any time, because it has no observations.

8\. **A trial is a review with origin `TRIAL`.**

- A trial has one practice version and one item.
- It passes the checks of Request review, except delivery. These include the AI choice of every person whose work it reads.
- Capture, the runner and admission are unchanged. A trial never seals and never composes.
- The database refuses a result for a trial review. The origin, report and requester constraints admit `TRIAL` by name.
- The review report keeps the outcome, or the reason why the trial stopped, for `hephaestus.practices.trials.retention` (default 30 days).
- The erasure of the developer whose work a trial read removes the trial.
- In 1.0 a trial reads GitHub and GitLab work only.

9\. **A trial spends from the same purse, under the same caps, and is reported apart.**

- A trial that an admin starts runs in the batch band, as Request review does.
- The trials of a workspace share a number of run subjects that an instance property sets, so only that many run at once. The update of 2026-11-17 changes this.
- Production metrics, standing and coverage exclude trials.

10\. **Only workspace admins can try a practice.**

- The REST handlers are the contract. The web app uses them.
- The MCP tools map to them (ADR 0110 decision 15) and need `practices:write`.
- [ADR 0110, update of 2026-11-16](0110-a-workspace-admins-mcp-client-configures-practices-through-the-workspace-mcp-endpoint.md) states what a trial tool returns.

### Cases and evals

11\. **Ichnaea decides on changes to the bundled catalog.**

- In 1.0 the decision is the practice-review suite of the label `eval:practice-review`. It is label-only and does not block.
- It compares main's catalog with the catalog of the pull request, on the outcome cases of each touched practice.
- Hephaestus pins the signed build branch of Ichnaea by commit.

12\. **When cases for workspace practices ship, a labeled case about real work stays in the workspace.**

- Admins see a case, as they see an observation.
- An export for Ichnaea carries opaque case ids. It carries no name, handle, work address, quote or note.
- Only synthetic snapshot cases can live in files.
- The server-side eval runs its trials in the background band.

### Agents

13\. **Coding agents read the practice files.**

- Agents also use the existing read tools and the review threads that they already act on.
- Hephaestus adds no unsigned structured marker to provider comments.
- A signed, structured agent surface needs its own decision.

## Consequences

- An admin can improve a practice on real work, and no developer's history changes.
- A repository is a second editor with no second model. Observations keep the version that judged them, and each version records its commit.
- The schema gains these facts:
  - the practice repository and its directory, in the review settings;
  - the state of a practice, and where it is edited;
  - the commit of each version;
  - the feedback mode at seal of each review practice;
  - the origin `TRIAL`.
- Bad: a workspace that used Off to keep counting quietly loses that use.
- Bad: anyone who can merge into the default branch of the practice repository changes what judges people. That person also ships practice scripts that run in the review sandbox and spend the AI budget.
  - An MCP client reads that criteria text as configuration.
  - The admin docs say so. Branch protection is the control.
- Bad: an MCP client with `practices:write` reads trial output, which an admin's model then processes. ADR 0110's update limits it to work whose people all chose **Cloud**.
- After 1.0, each with its own trigger:
  - snapshot cases in files;
  - try on a local diff;
  - the validation comment on pull requests;
  - the command-line client;
  - `practices_for_change`;
  - member-scoped MCP;
  - promotion into a catalog;
  - the server-side eval.

## Confirmation

`McpToolHandlerParityArchTest#everyMcpToolCarriesTheAuthorizationOfTheHandlerItStandsFor` fails when a trial tool requires less than its REST handler (decision 10).
`McpPracticeToolsIntegrationTest#shouldDeclareClosedOutputSchemas` fails when a tool's output schema admits a field that it does not declare (decision 10).

Not enforced. Each other rule gets its check in the pull request that ships it, and this section then names the test:

- decision 1: the round trip over the bundled catalog;
- decisions 2–5: a sync test that keeps the version on an invalid folder and adds Reviewed by Nobody on a removed one;
- decision 6: a standing test that skips an observation sealed in Off;
- decision 7: `DraftPracticeFilterArchTest` fails on a native read of practices that names no state, and `PracticeDraftControllerIntegrationTest` fails when selection or the practice catalog sees a draft, or when a tried version becomes current or enters the history.

Decisions 8 and 9 are enforced:

- `ResultAdmissionIntegrationTest#shouldRefuseInTheDatabaseAResultForATrial` fails when the database admits a result for a trial.
- `ResultAdmissionIntegrationTest#shouldKeepATrialCandidateInItsReportAndWriteNoResult` fails when a trial writes a result, feedback, a feedback copy or an outbox action, or publishes `ReviewCompleted`.
- `ReviewRequestPinIntegrationTest#shouldRecordATrialOfOnePracticeForItsRequesterAndRefuseASecondWhileItRuns` fails when a second trial runs at once, or when a trial blocks a requested review of the same work.
- `PracticeTrialControllerIntegrationTest` fails when a member who is no admin starts a trial, when the developer's practice page shows a running trial, or when retention keeps a report past its period.

Until then, a reviewer rejects a diff that writes a result for a trial, selects a draft practice, or counts an observation sealed in Off.

## Update — 2026-11-17: how many trials run, and what retention removes

The pull request that ships trials changes two bullets.

- **Decision 9: at most `hephaestus.practices.trials.max-active` trials of a workspace are queued or running at once** (default 1). Another trial is refused until one ends.
  A shared run subject cannot bound them: a subject also keys the sandbox, so trials of different work would share one.
- **Decision 8: retention and erasure clear a trial's report.** They do not delete the trial.
  The report is what cites someone's work.
  The trial's row stays, as every review's row does, and job retention removes its jobs and runs.
- A practice script sees a trial as origin `REQUEST`, so a script never behaves differently on trial.

## Revisit trigger

Revisit when:

- a workspace needs practices from more than one repository;
- Ichnaea gives a validated PASS on practice changes;
- members need to try practices on their own work.

## Sources

- [GitHub Actions, `pull_request_target`](https://docs.github.com/en/actions/writing-workflows/choosing-when-your-workflow-runs/events-that-trigger-workflows#pull_request_target): it "runs in the context of the default branch of the base repository, rather than in the context of the merge commit", so the head of a pull request cannot change the configuration that judges it. Hephaestus reads practices the same way.
- [OWASP LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/): "Separate and clearly denote untrusted content". Trial output marks each field that work text can reach.
- [Macroscope check run agents](https://docs.macroscope.com/check-run-agents/configuration): a check is "optional YAML front matter followed by Markdown instructions" in the repository. A practice file takes the same shape, with the closed field set of the definition.
