# ADR 0035: Pull request previews are label-gated and driven from the default branch

**Status:** Accepted
**Date:** 2026-08-28
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md) (the runtime the controller is written for)

## Context

Coolify's built-in preview deployments were enabled on the preview application, so every
same-repository pull request deployed automatically, onto the VM that also runs staging,
from a Compose stack that mounted the Docker socket. Nothing gated it on CI, the deployment tracked a
mutable `HEAD` rather than a commit, and nothing bounded how many previews ran at once.

Cleanup was assumed rather than checked. Coolify's webhook handler dispatches teardown to a queue and
answers `queued` in the same breath, so a 2xx from it is a receipt for the request, not evidence that
the containers, volumes and networks are gone. Nothing downstream ever looked.

Two questions were tangled together and they have different answers:

1. **Authority.** Who may cause pull-request code to run on a public URL on a host that also runs
   staging?
2. **Currency.** Once a preview exists, what keeps it showing the pull request's current commit?

Answering both with "a maintainer approves it" makes the second answer expensive: because an approving review is recorded against one commit, every push needs a new
review before the preview catches up. That inverts the purpose of the preview, which is the thing a
reviewer wants *in order to* review.

## Decision drivers

- **A preview a contributor cannot start themselves is a preview they will not use.** The reviewer
  and the author both need it before the code is approved, not after.
- **Pull-request code must never execute on a runner that holds deployment credentials.** This is the
  one property no amount of convenience may trade away.
- **Do not invent an authority GitHub already models.** Push access is the repository's existing
  statement about who may put code in front of CI.
- **The host is shared with staging.** Admission has to be bounded, deployments serialized, and
  cleanup proven rather than trusted.
- **A gate that only exists in prose is not a gate.** Every claimed constraint needs something red
  when it is violated.

## Considered options

1. **Keep Coolify's automatic previews.** Rejected. A mutable `HEAD` rather than a commit, no
   admission bound, a Docker socket in the stack, and leaked resources on every close.
2. **Maintainer approval, re-checked against each head commit.** Rejected.
   Approving code for merge and authorizing a deployment are different decisions, and coupling them
   means the preview is always one review behind the branch. It also made approving a pull request
   silently deploy it, which surprises the approver.
3. **A GitHub Environment with required reviewers.** Rejected *for previews*. It is the correct
   primitive and it is what production uses, but it gates every individual deployment behind a click
   — the same per-commit friction as option 2, with a nicer button.
   Deleting such an environment also deletes its secrets and protection rules
   and fails any job waiting on them, and `GITHUB_TOKEN` cannot do it at all — which is why cleanup
   here marks deployments inactive and never deletes environments.
4. **The `preview` label as opt-in state, push access as the authority.** Chosen — see below.
5. **Restricting `pull_request_target` to the default branch, and refusing stacked layers.**
   Rejected. Since 2025-12-08 GitHub takes the workflow file and the checked-out commit for
   `pull_request_target` from the default branch whatever the pull request targets, so a base-branch
   filter withholds nothing. Even before that it was not the boundary it looked like: a
   same-repository branch belongs to someone who can already read every repository secret, because
   this repository's `pull_request` workflows receive them (`cd-docs.yml` deploys with `SURGE_TOKEN`).
   The filter would cost stacked previews, which `CONTRIBUTING.md` encourages, and protect nothing.
   Fork exclusion is the boundary that holds, and it is enforced on its own terms.
6. **Reusing the reference stack through Compose `include`, so a preview is one file of overrides.**
   Rejected after building it. It renders — profiling out `volume-init`, `release-pin-fetcher`,
   `application-worker` and `webhook-server`, and overriding `volumes`, `labels` and `depends_on` with
   `!override` — but it inverts the sandbox. Compose merges per key, so the preview inherits every one of the
   reference application server's variables rather than the subset it chooses, and the rendered result
   arrives with `LEADERBOARD_NOTIFICATION_ENABLED`, `MONITORING_BACKFILL_ENABLED` and
   `MONITORING_SYNC_CRON` switched on, `HEPHAESTUS_AGENT_IMAGE_REQUIRE_DIGEST` set (which refuses the
   boot), and staging's hostname in `APPLICATION_HOST_URL`. It also inherits `/var/run/docker.sock` on
   the application server unless a `!override` removes it. A preview's posture is "off unless listed";
   inheriting makes it "on unless removed", and a *new* integration would arrive switched on with
   nothing to catch it. The duplication is the safer direction — `scripts/check-preview-stack.ts`
   makes it a loud one by failing when the reference gains a variable the preview neither sets nor
   records as deliberately omitted.
7. **A commit command (`/deploy-preview`).** Rejected as the primary interface. The command is a verb
   with no state: nothing on the pull request says whether it is opted in, and the pull request list
   cannot be filtered by it. A label is the state, is visible, and is removed the same way it is
   added.

## Decision

**The `preview` label is the opt-in, and adding it is the whole authorization.** Any pull request in
this repository is written by someone with push access, who can already run CI with repository
secrets; requiring a second person to start a preview would not change what code can run. Once the
label is on, every push deploys with no further human action, and removing the label,
closing the pull request, or converting it to draft tears the stack down.

**The label is a switch, not a security boundary, and nothing here relies on it as one.** GitHub's
Security Lab is explicit that a `safe to test`-style label is "prone to a race condition" and "prone
to human error" and should not be what stands between untrusted code and secrets
([Preventing pwn requests](https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/)). The boundary is the
privilege split below: the label decides *whether we bother*, and the split decides *what may run*.
The label is checked again immediately before the deployment is queued, so removing it mid-flight
stops the deploy rather than racing it.

The gates that remain are the ones that describe *what may run*, not *who asked*:

- Fork pull requests never deploy. Any pull request within this repository may, whatever it targets,
  so a stacked layer gets its own preview of the stack up to that layer.
- The head must not introduce edits to `.github/workflows/**`, `.github/actions/**`, or
  `docker/preview/**`, measured by comparing the head against the *default branch* rather than against
  the pull request's own base — a stacked layer's diff hides what the layers beneath it changed, and
  those commits are in the head Coolify deploys. A comparison too large for GitHub to report in full
  is refused rather than trusted. Coolify re-reads the Compose file from the commit it deploys, so
  this rule is load-bearing.
  `ci-compose-validate.yml` holds the other half: on every pull request it renders that file and fails
  if the stack gains a way out of its sandbox — a socket, a build stage, a published port, an
  unbounded memory limit, a network every preview would share, or a renamed or flipped integration
  switch. `scripts/check-preview-stack.ts` is the authoritative list, and its own tests prove each
  assertion fails when that escape is present.
- Every service runs the image CI published for the exact head commit, and each digest must carry a
  build attestation signed by this repository's `reusable-docker-build.yml`, verified before Coolify
  is asked to pull. One gap is left open and named rather than implied closed: Coolify resolves the
  commit tag itself, so the verified digest is not what it is handed. Closing it would need Coolify to
  accept `image@sha256:…`, which it does not. The window is narrow — CI writes a commit tag once and
  never moves it — but it is a verify-then-pull gap, not a proof. A preview is therefore
  the shipped artifact, not a second recipe that could start cleanly where the real image would not.
  It waits for those images to be published, which happens in parallel with the tests — so a preview
  exists for a pull request whose tests are red, but not for one whose images failed to build.
- The pull request's author association must be `OWNER`, `MEMBER` or `COLLABORATOR`. Same-repository
  branches make this close to tautological, but Coolify is handed the association and refuses an
  untrusted one, so the controller asserts it early enough to explain itself.

**No fork code ever reaches a privileged runner.** `pull_request_target` takes the workflow file and
`github.sha` from the default branch, so the controller that runs beside the secrets is always
reviewed, merged code, and a fork's head is never checked out or executed. GitHub guarantees that;
this repository does not have to arrange it, and no base-branch filter adds to it.

Those jobs sparse-checkout `.github/actions/setup-toolchain`, the package manifests and `scripts/` —
no application source. They run no install step, and the controller imports only the runtime's built-in
modules, so nothing from the lockfile is resolved on a runner holding the preview secrets. The
third-party actions that do run there are pinned by commit SHA; that pin, not their absence, is the
control.

**Coolify is driven, not trusted.** The controller sends an HMAC-signed synthetic GitHub
`pull_request` webhook naming the full commit SHA, because the public `/api/v1/deploy` endpoint
queues a mutable `HEAD`. The API token it holds is read-only; every mutation goes through the signed
webhook. Teardown is a signed close event; Coolify queues it and answers immediately, so what the
workflow records is that the request was accepted.

**Admission is bounded and the lifecycle is serialized.** `PREVIEW_MAX_ACTIVE` caps concurrent previews. The
bound exists because the host is shared with staging and a preview stack's memory ceiling is a
meaningful fraction of what staging leaves free; the runbook carries the default and how to size it. Deploy, cleanup and the nightly reconcile share one Actions concurrency group with
`queue: max`, so previews never start on the host at once and admission counting cannot race.

Refusing the newcomer is a deliberate divergence from hosted providers, which queue instead and evict
only by superseding an earlier commit on the same branch. Queuing is the better behaviour when
capacity frees up on its own; here it does not, because a slot is held until a human closes a pull
request or drops its label. A queued preview would therefore sit indefinitely with nothing to tell
the author why, so the refusal is immediate and names the pull requests holding the slots.

## Consequences

- A contributor previews their own pull request with one click, and every commit after that follows on
  its own, red tests included. This is the outcome the whole design exists to produce.
- Anyone with push access can put pull-request code on a public URL, against a copy of staging's
  data and reading staging's event stream. This is accepted deliberately: previews exist to be
  reviewed against real data, the copy is silenced and re-homed before the application server boots,
  and the same person can already run that code in CI.
- **A stack can hold several slots at once**, one per labelled layer, because each layer is a
  separate pull request. The admission message names the occupants, so the author can see it and drop
  a label.
- Teardown is a request, not a proof: Coolify queues it and answers immediately, so a preview that
  Coolify fails to remove leaks silently until someone looks at the host. Failures to *send* it are
  loud and retried nightly. Every teardown step is idempotent, so a retry over already-removed
  resources succeeds rather than compounding the failure. Reclamation is decided per pull request
  from that pull request's own state, never from a resource's absence from a listing, so a failed
  inventory query cannot make live previews look orphaned.
- One inactive deployment record is retained per environment after cleanup, so the nightly reconcile
  can repeat Coolify cleanup before deleting it. Admission ignores exactly those tombstones.

## Revisit trigger

- **Previews move off the staging VM.** Then `PREVIEW_MAX_ACTIVE` can rise and the single global
  concurrency group can be split per pull request.
- **A preview stack is observed outliving its pull request.** Teardown is currently a request to
  Coolify, not a proof; the nightly reconcile re-sends it but checks nothing on the host. Verifying it
  would mean a standing credential on the deployment host, which is not worth paying for a failure
  that has not been seen.

## Update — 2026-09-03 (issue #1719)

[ADR 0041](0041-compose-1x-kubernetes-2.md) supersedes the Consequences' “copy of staging's data”
consequence for agent execution, and the Revisit trigger's assumption that previews stay on the
staging VM. Agent-enabled previews run only against fixture workspaces, under the sandbox bounds ADR
0041 fixes, on a separate cluster or tainted node pool: giving pull-request code the shared Docker
socket is unsafe. A preview may still use a neutralized copy of staging for the application read
model, but job-folder rendering, repository mirrors, provider credentials, and agent bindings are
restricted to fixture workspaces. There is no separate less-restricted “non-agent preview” mode.
Label admission is unchanged, and the Coolify production path is deleted in 2.0.
