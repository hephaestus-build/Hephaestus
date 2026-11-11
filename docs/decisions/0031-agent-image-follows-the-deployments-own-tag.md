# ADR 0031: The agent image reference follows the deployment's own image tag

**Status:** Superseded by [ADR 0034](0034-signed-release-image-lock.md)
**Date:** 2026-08-22
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0036](0036-agent-runtime-runs-on-node-24.md) (the runtime contract the image implements), [ADR 0007](0007-sandbox-spi-shape.md) (the sandbox the image is run through)

## Context

The application server and the `agent-pi` image are **one runtime contract shipped as two artifacts**.
The server stages TypeScript runners into a workspace and execs `node` inside the image; the image has
to carry a compatible Node, the Pi SDK, and the layout `SandboxLayout` describes. Neither artifact is
useful without a matching other half.

Only the release path bound the two together. `release.yml` retags both images from one commit,
writes a `release-vX.Y.Z.yaml` asset naming the `agent-pi` digest, cosign-signs it, and
`release-pin-fetcher` verifies it at deploy time onto a volume the server imports through
`spring.config.import`. That path is sound and this ADR does not touch it.

Every other deployment fell through to a compiled-in default:
`ghcr.io/ls1intum/hephaestus/agent-pi:latest`. `latest` is applied **only** by the release workflow's
retag step, so on a deployment tracking `main` it named an image from a different commit on a
different channel — and did so silently, because a tag that resolves is indistinguishable from a tag
that resolves *correctly*.

The result was observed on staging: an application server from `main`, which execs `bun` and stages
`.ts`, running against an agent image dated 2026-07-14 that still contained `/usr/local/bin/node`.
That is a pairing no release could ever produce, which made staging unable to tell anyone whether the
runtime worked — the one question staging exists to answer.

Two failures are tangled here and they have different fixes:

1. **Resolution.** Where does the reference come from when there is no signed pin? A compiled-in
   default cannot know which build the deployment is running, so any value it names is a guess.
2. **Verification.** Nothing checked that the resolved image could actually run the server's runners.
   An operator override, a stale daemon cache, or a hand-pinned digest all bypass resolution entirely.

## Decision drivers

- **A wrong image must not be reachable by doing nothing.** The failure was silent, not loud; the
  configuration gap turned into a version mismatch with no signal.
- **No floating tags in a deployment.** A **channel tag** is any tag a workflow moves from build to
  build, and this repository publishes three spellings of one: `latest`, the branch tag (`main`), and
  the `major.minor` **version series** the release retag step writes onto every patch release in a
  line. All three race the deploy, differ node-to-node mid-pull, and destroy reproducibility. The
  immutable identity is the digest; the accepted second best is a unique per-build tag.
- **The release path is the strongest thing here.** Whatever replaces the default must leave the
  cosign-verified digest pin authoritative wherever it exists.
- **The deployment already knows the answer.** It sets `IMAGE_TAG` and passes it as `APP_VERSION`, and
  CI publishes `agent-pi` under that same tag.

## Considered options

1. **Point the default at a branch tag (`agent-pi:main`).** Rejected. It is the same channel tag in a
   different spelling: mutable, so redeploying the same server commit next week resolves a different
   image, and a rolling deploy can pull two different images for one release. It would have removed
   the cross-channel symptom while keeping the property that caused it.
2. **Derive the reference from the deployment's own image tag.** Chosen — see below.
3. **Remove the default and require an explicit reference everywhere.** Correct but worse in practice:
   the right value is mechanically derivable from something the deployment already has, and making a
   human retype a derivable value is how it goes stale. It also fails the boot for *every* instance
   that never runs an agent (`AGENT_ENABLED` defaults false), whereas the chosen option fails only the
   ones whose reference is actually wrong — a much smaller set, but not an empty one: see the
   role-gating trade-off below.
4. **Resolve the non-release path to a digest at deploy time**, by having `release-pin-fetcher` look
   `agent-pi:$IMAGE_TAG` up in the registry and write the same pin file. Attractive — it reuses the
   delivery mechanism and precedence the release path already has, and would let a `main` deploy run
   with `require-digest=true`. Deferred, not rejected: the fetcher image carries `curl` and `cosign`
   but no Docker CLI, so this is hand-written registry-API shell on the critical path of every deploy,
   where a mistake blocks deploys entirely. It also cannot be cosign-verified — there is no signed
   asset for a `main` commit — so it would look release-grade while being weaker. See the revisit
   trigger.
5. **Resolve the digest in CI and flow it to the deployment.** The strongest option in principle, and
   the shape the release path already uses. Rejected for now on interface grounds: real deploys call
   an org-owned reusable workflow whose input is `image-tag`, so a per-commit agent digest has no
   channel into the stack's `.env` without either changing that workflow or building a per-commit
   signed-asset distribution. The only beneficiary would be staging, which is the disposable
   environment; production already gets a digest.
6. **Publish `agent-pi` for every commit unconditionally.** Already true in practice and not a change:
   `cicd.yml` forces `agent_images_changed: true` whenever the event is not a pull request, so path
   filters never suppress the agent build on `main`. Making it *unconditional* would not close the
   real gap, because the gap is not the filter — see the consequences below.
7. **Verify by running the image at startup** (`bun --version` in a probe container). Rejected as the
   primary mechanism: it costs a container per boot, needs the sandbox machinery up, and still would
   not say whether the *runner ABI* matches. The label check below subsumes it more cheaply and more
   precisely.

## Decision

**The agent image reference follows the deployment's own image tag.** `application.yml` derives it as
`ghcr.io/ls1intum/hephaestus/agent-pi:${spring.application.version}`, which is the `IMAGE_TAG` the
deploy already chose for the server image; `AgentImageProperties` carries no compiled-in default at
all. A release still overrides this with the cosign-verified digest through `spring.config.import`,
and an operator still overrides both with `HEPHAESTUS_AGENT_IMAGE_REFERENCE`.

**The image declares the contract it implements, and the server checks it.** The `agent-pi` build
validates selected executable invariants: Node imports the SDK and no Bun artefact survives. It stamps
the declared contract as
`hephaestus.agent.runtime-contract`, which the server compares against
`SandboxLayout.RUNTIME_CONTRACT_VERSION` from the image's labels, before it commits work to a
container and without running one.

## Consequences

**Positive.**

- On every managed deployment the pair comes from one commit by construction, not by convention. The
  skew that motivated this ADR is not reachable by omission any more.
- Staging can answer the question it exists for: it now runs the agent image its own server was built
  against.
- The two failures are covered independently. Resolution makes the common case right; the contract
  label catches an unmatched image *however* it was chosen — including an operator's hand-pinned
  digest, which no amount of resolution logic would have caught.
- A version mismatch is reported as one ERROR naming the image, both contract versions, and the Node
  and Pi SDK versions the image's own labels carry — enough to act on without inspecting anything. The
  other two verdicts are necessarily thinner: an image with no contract label has no versions to
  quote, and an image the daemon cannot describe yields only a WARN naming the image, because nothing
  about it is proven either way.
- `docker/.env.example` no longer ships `IMAGE_TAG=latest`, which contradicted the self-host guidance
  ("never `latest`"). Under this decision that value no longer resolves a foreign agent image quietly
  — it derives `agent-pi:latest` and the guard refuses the boot. That is the intended outcome and it
  is a breaking change for anyone still carrying the old default, which is why `MIGRATION.md` names
  `IMAGE_TAG` as an affected setting in its own right.

**Negative / accepted.**

- **A per-commit tag is immutable by convention, not by enforcement.** GHCR permits re-pushing a tag,
  so re-running CI on the same commit republishes `agent-pi:<sha>`. This is a far smaller risk than
  the one it replaces — same commit means the same Dockerfile and the same contract — and the label
  check is independent of tag immutability. It is nonetheless weaker than the release path's digest,
  and the reason `require-digest` stays `true` in production.
- **The pair's existence depends on one CI expression.** `agent_images_changed` is forced true for
  non-pull-request events by a single sub-expression in `cicd.yml`; delete it and `main` commits that
  do not touch `docker/agents/**` would publish a server image with no matching agent image. The
  staging preflight now checks for both, so the consequence is a skipped deploy rather than a skewed
  one, but nothing prevents the expression from being changed.
- **A commit whose CI run was cancelled has no images at all.** Merging several pull requests in quick
  succession cancels the earlier runs, so `agent-pi:<sha>` — and `application-server:<sha>` — can be
  absent for a commit that looks green. This is why the preflight skips rather than fails, and why the
  reference is *derived* rather than assumed to exist.
- **Previews cannot always derive.** A preview runs at `SOURCE_COMMIT`, the pull request's head
  commit, so deriving at all requires `agent-pi` to carry a head-SHA tag — which is why
  `reusable-docker-build.yml` now publishes one, as `application-server` already did. Even so, `agent-pi` is
  not built for a pull request that touches neither `docker/agents/**` nor a workflow, so a preview
  exercising the sandbox from any other pull request must name the agent image explicitly. That
  failure is louder than the silence it replaces, but it is not a refusal: the reference is a
  well-formed tag, so the boot succeeds, the startup pull logs a WARN and counts
  `agent.image.pull.failure`, the verifier records `outcome=unknown`, and the first practice review or
  mentor turn is what actually fails. `docker/preview/.env.example` documents the override, and the
  compose files forward `HEPHAESTUS_AGENT_IMAGE_REFERENCE` as a **valueless** key so it reaches the
  container only when the deployment supplies one. A Compose default (`${VAR:-}`) would instead set it
  to the empty string, which Spring binds as a value rather than treating as absent — the reference
  would resolve to `""` and every deployment that does not override would fail to boot.
- **The contract version is a number a human must remember to bump.** `AgentImageContractSyncTest`
  pins the Dockerfile literal to the Java constant so the two cannot diverge, and additionally pins
  the image's Node and Pi SDK **majors** to `SandboxLayout`, so a Renovate bump across either major
  cannot land without someone deciding whether the contract moved with it. Neither pin can tell that
  a genuinely breaking change should have bumped the contract, and the Pi SDK is pre-1.0 — where the
  breaking boundary is the minor — so its major pin catches only `0.x` → `1.x`. This is the same
  trade `MENTOR_PROTOCOL_VERSION` already makes.
- **The reference guard is global, not role-gated.** `AgentImagePullBootstrapper` and the sandbox
  beans are gated to the worker role, but a bad reference must be a deployment-wide error rather than
  a worker-only one: the whole point is that the pairing is a property of the deployment, and a
  webhook receiver that boots happily on a configuration the workers cannot run hides exactly the
  skew this ADR is about. The accepted cost is that a bad reference crash-loops every role, including
  ones that never open a sandbox — which is why the guard's message names the fix, why
  `MIGRATION.md` says `AGENT_ENABLED=false` is not an exemption, and why the override is plumbed
  through every compose file that runs the image, `webhook-server` included, as an escape hatch.
- **The verifier reports; it does not refuse** — unlike the guards, which do. The line between them is
  what they judge. A reference is configuration: local, deterministic, and fixable before the deploy
  runs, so refusing it is a fail-fast on something the operator controls. A contract label is a
  property of a *pulled image*, so refusing on it turns a registry hiccup or an unreachable daemon
  into an outage. Hence ERROR plus a metric, not a boot failure or a refused job; refusing per-run
  would additionally mean threading the verdict through the sandbox path. With resolution fixed, an
  unmatched image is off the supported paths, so detection is proportionate — but until enforcement
  exists, a determined misconfiguration still reaches a container.
- **The version-series rule can refuse a hand-built tag that nothing moves.** It reads any
  version-shaped tag stopping short of a patch component as a series, so an image an operator tagged
  `2` or `20260823` is refused although it names one build. Carving out the single-component case
  would make the rule "two components but not one" — arbitrary, and it would admit `agent-pi:0`, a
  major series in every registry convention. Nothing the derivation produces has that shape (a
  release version, a commit SHA, or `0.0.0-development`), so this reaches only an explicit override,
  where the message names both ways out: the full version, or the digest.
- **Local development must name its image.** With no `APP_VERSION`, the reference derives to
  `agent-pi:0.0.0-development`, which is not published. The guard warns with the variable to set
  rather than failing the boot.

## How this is enforced

- `AgentImageReferenceGuard` **parses** the reference in every environment, unconditionally: it reads
  the tag out (a registry port is not a tag) and refuses a blank reference, a malformed digest, a tag
  that is empty or outside Docker's tag grammar, a reference that names no tag, and a channel tag in
  either spelling — the named channels `latest`, `stable`, `edge`, `main`, and a version series, a
  version-shaped tag that stops short of a patch component (`0.73`, `v1.2`, `0`). The series rule is
  what covers the `major.minor` tag the release workflow itself publishes; a denylist of names alone
  would refuse `stable` and `edge`, which this repository never writes, while admitting the one it
  writes on every release. It is deliberately narrow, so a pre-release (`0.74.0-rc.1`), a commit SHA
  and a hand-built `dev` are all left alone — the rule refuses tags that cannot name one build, not
  tags that are not releases. Parsing rather than suffix-matching `:latest` is the point throughout:
  an untagged reference is a channel reference — the daemon supplies `latest` for it — and an empty
  tag is what an empty `APP_VERSION` derives, which any deploy substrate that interpolates a missing
  image tag to the empty string will hand it. Both would pass a suffix match and fail at the daemon
  instead, where the message names neither the property nor the fix. `AgentImagePinGuard` still
  requires a digest wherever `require-digest` is true, and reports an unresolved reference rather than
  throwing `NullPointerException` on it.
- `AgentImageDefaultResolutionTest` binds the real `application.yml` and asserts the reference follows
  `APP_VERSION`, so the derivation cannot silently stop resolving. It also pins what an *empty*
  `APP_VERSION` produces, because a present-but-empty placeholder value defeats its own default —
  the derivation yields a tagless reference rather than the development fallback, and the guard rather
  than the placeholder is what stops it.
- `AgentImageReferenceGuardTest` asserts the guard is a component-scan candidate. Nothing else
  references the class, so without that assertion deleting its stereotype disables every other
  assertion in the file without failing any of them.
- `AgentImageContractSyncTest` pins the Dockerfile's `LABEL` to `SandboxLayout`, and its
  `ARG NODE_VERSION` / `ARG PI_VERSION` majors to `SandboxLayout.NODE_MAJOR` / `PI_SDK_MAJOR`. The label
  assertion alone holds for every value of those ARGs — both are Renovate-managed, so a major bump
  would otherwise land unattended and leave the image declaring a contract it no longer implements.
- `ci-quality-gates.yml` greps for the literal string `agent-pi:latest` outside the files that must
  name it to describe the upgrade. That is a backstop against reintroducing the specific default this
  ADR removed — not a general channel check. It cannot see `IMAGE_TAG=latest`, an untagged reference,
  or `:stable` / `:edge` / `:main` / `:0.73`; the guard is what covers those, at boot rather than at
  review.
- `cd-staging.yml`'s preflight probes **both** images for the commit and skips the deploy unless the
  pair exists. The probe is a bare `imagetools inspect` tested on its own exit status: reading the
  digest inside the `if` instead tests `jq`'s status, and `jq` exits 0 on empty input, so an absent
  image reads as present and the deploy proceeds against an unmatched pair. The digest is captured
  after the probe and shape-checked, as in `release.yml`.
- `release.yml` compares the image's contract label against the released tree's constant before it
  writes the pin asset, and asserts that Node runs and Bun does not.

## Revisit trigger

- **A tag re-push is observed to have changed what a deployed `agent-pi:<sha>` resolves to.** That
  falsifies "immutable by convention" and makes option 4 or 5 worth their cost.
- **A non-release deployment needs to survive GHCR tag retention or an audit that requires digests
  everywhere.** Option 4 is the cheaper of the two and is already scoped.
- **The contract label proves insufficient** — an image passes the check and still cannot run the
  runners — which means the contract is not the right granularity and the check should move to
  something executed rather than declared.
- **The verifier's `mismatch` or `unlabelled` counter is non-zero on a managed deployment.** Resolution
  is supposed to make that unreachable; if it fires, detection has to become enforcement.

## Contract locations

- `AgentImageProperties`, `AgentImageReferenceGuard`, `AgentImagePinGuard`, `AgentImageContractVerifier`
- `SandboxLayout.RUNTIME_CONTRACT_VERSION`, `SandboxLayout.NODE_MAJOR`, `SandboxLayout.PI_SDK_MAJOR`,
  `docker/agents/pi/Dockerfile`
- `reusable-docker-build.yml` (`agent-pi-build`'s head-SHA tag, which is what a preview derives against)
- `docker/compose.app.yaml` (`release-pin-fetcher`, and the `HEPHAESTUS_AGENT_IMAGE_REFERENCE`
  passthrough it shares with `docker/compose.core.yaml` and `docker/preview/compose.app.yaml`),
  [Release image lock](../admin/release-image-lock.md)
