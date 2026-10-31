---
title: Supply-chain security in CI
description: The security and supply-chain controls that CI runs, when each one runs, what it blocks, and where it reports.
page_type: reference
---

CI runs these controls on pull requests, merge groups, `main` pushes and releases.
[CI/CD architecture](./ci-cd.mdx) describes the pipeline that runs them.

## Controls

| Control                               | Timing                                                                       | Enforcement                                                                                                             | Destination                                 |
| ------------------------------------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| Dependency vulnerability gate         | Security workflow                                                            | owns the dependency verdict. Blocks fixable high or critical findings in every dependency the checkout resolves         | required Security workflow                  |
| Dependency baseline                   | `vp run check` and Quality → Tooling and Docs                                | fails when an npm package count of a lockfile rises above `security/dependency-baseline.json`. A lower count passes with a notice | gate output                                 |
| GitHub dependency review              | pull requests that change a dependency input                                 | reports the advisories a pull request introduces, with their patched versions. Holds no verdict                         | Independent dependency-review summary       |
| TruffleHog and GitHub push protection | pull requests and pushes                                                     | block verified secrets                                                                                                  | Security workflow and repository protection |
| Trivy filesystem scan                 | Security workflow                                                            | a scanner error blocks. Findings of every severity are report-only                                                      | code scanning                               |
| Semgrep TLS policy                    | pull requests and `main` pushes                                              | fails on a rule violation or a scanner error                                                                            | code scanning and a run artifact            |
| Image vulnerability policy            | image build and release                                                      | blocks fixable high or critical findings                                                                                | build summary and release evidence          |
| Weekly image rescans                  | `rescan-images.yml`                                                          | the `main` rescan upserts one tracking issue. The release rescan fails and comments on the vulnerability response issue | issues and run artifacts                    |
| OpenSSF Scorecard                     | `main` pushes, branch-protection changes, and weekly. Never on pull requests | reports                                                                                                                 | code scanning and a run artifact            |
| Scorecard ratchet                     | the same events, plus on demand                                              | fails when a check scores below `security/scorecard-baseline.json`. On a push, only for the checks that commit decides  | job summary and a run artifact              |
| Renovate                              | configured schedule and vulnerability alerts                                 | pull requests require normal review and CI                                                                              | dependency dashboard                        |

Image scanning also reads embedded SBOMs shipped by image builders. Trivy's third-party-SBOM
advisory describes that additional inventory's provenance. It does not mean the image was scanned
only through the separately published release SBOM. Keep those embedded inventories and the
scanner's vendor-severity provenance visible rather than changing severity sources or disabling
analyzers to remove a warning. The vulnerability policy result and retained scanner JSON carry the
actual verdict.

One check owns the dependency verdict: the dependency vulnerability gate in `ci-security-scan.yml`,
a second Trivy pass in `table` format over the same checkout. It runs on pull requests and on `main`
pushes and includes development and test dependencies from the lockfiles.

The native `server/buildscript-gradle.lockfile` also covers the resolved Gradle plugin classpath.
Trivy reads this file through its [Gradle lockfile analyzer](https://github.com/aquasecurity/trivy/blob/main/pkg/fanal/analyzer/language/java/gradle/lockfile.go).
Application lockfiles and container scans do not cover build plugins.

An advisory published
against a pin no pull request touches fails the next run rather than waiting for someone to open
the code-scanning dashboard. It applies the [vulnerability remediation](./vulnerability-remediation.mdx) severity policy: fixable high or critical.
`security/trivy-dependency-ignore.yaml` records its exceptions, the sole exception path for dependency findings.

Dependency review compares the base and head dependency graphs, so it cannot hold that verdict. It
reports what the pull request introduces in the independent `dependency-review.yml` workflow.
Asynchronous snapshots can keep this report pending, but it does not delay `CI Status Gate`,
CI/CD completion, or release qualification.

The comparison runs only when a pull request changes a file that the dependency graph reads.
These files are package manifests and lockfiles, workflows and actions, and the Gradle build
scripts, lockfiles and version catalog. Any other change has an empty comparison.
`dependency-graph.yml` uses the same pull-request filter. Thus, the head snapshot that the review
waits for exists whenever the review runs. `scripts/ci-contract.test.ts` holds both filters to one list.

Those filters use GitHub's bounded changed-file
diff. A very large mixed change can omit this informational report. The required full-checkout
security and release policies do not depend on that filter.

Gradle graphs are generated on pull requests and main pushes through the official Gradle action,
with dependency verification still enabled. The workflow runs `dependencies buildEnvironment` to include the plugin classpath.
Generation has read-only permissions.

A separate `workflow_run` publisher submits the
snapshot without checking out or executing the originating code. The publisher must exist on the
default branch before GitHub runs it.

The first Trivy pass supplies the code-scanning feed.
SARIF reports every severity regardless of `severity`.
Thus, this pass sets neither `severity` nor `exit-code`.
It keeps Trivy's secret scanner, which covers the whole tree.
TruffleHog covers only a pull request's own commits.

TruffleHog has two independent pins in `ci-security-scan.yml`: the action commit selects its
wrapper, while the supported `image` and `version` inputs select the scanner container by tag and
digest. The wrapper otherwise defaults to a mutable scanner tag even when its action is pinned.
Renovate tracks the wrapper through its GitHub Actions manager and the scanner through a Docker
custom manager. Their versions need not move together.

When you review a wrapper update, verify its [`image` and `version` input contract](https://github.com/trufflesecurity/trufflehog/blob/363923b901c911a9164f50b6c423f47c15372b1c/action.yml).
The resulting Docker reference must retain the digest.
A scanner failure must still fail the step.
The event's immutable base/head commit range, verified-secret policy, and scan-error failure policy
apply to either update.

Renovate groups routine Gradle updates as server dependencies and webapp development updates
separately. Webapp production dependencies remain separate unless Renovate identifies a package
family that must move together.

Major updates and minor updates to pre-1.0 dependencies remain ungrouped, wait seven days, and require
Dependency Dashboard approval. Native
[branch and PR limits](https://docs.renovatebot.com/configuration-options/#branchconcurrentlimit)
bound routine update concurrency. The hourly limit only restricts PR creation. Vulnerability alerts
remain ungrouped and bypass those gates and limits.

pnpm's `minimumReleaseAge` in `pnpm-workspace.yaml` still applies to a version that a lockfile update newly resolves.
A fix younger than that age opens as a pull request with a failed lockfile update.
To take it earlier, a maintainer adds the version to `minimumReleaseAgeExclude`.

Lockfile maintenance runs on Mondays. `.github/renovate.json` owns the managed ecosystems and extraction
rules. `scripts/renovate-config.test.ts` exercises each custom manager against its source.

Renovate runs as the Mend-hosted app on the [Community plan](https://docs.renovatebot.com/mend-hosted/overview/#resources-and-scheduling).
A job has 3 GB of memory and 30 minutes.
A job that exceeds a limit stops before it writes pull requests or the Dependency Dashboard.
Mend then [runs the repository only weekly](https://docs.renovatebot.com/mend-hosted/job-scheduling/).

If the Dependency Dashboard stops changing, read the latest job on the [Mend Developer Portal](https://developer.mend.io/github/hephaestus-build/Hephaestus).
The job page shows its status, such as `kernel-out-of-memory`, and its log.
After you fix the cause, select **Retry** on that job.
A successful job returns the repository to the normal schedule.
`trustLockfile` in `pnpm-workspace.yaml` keeps the pnpm lockfile updates in a job inside the memory limit.

The Node,
pnpm and Pi SDK pins are grouped, so a version stated in several managed files moves in one pull
request. The toolchain checks reject a version that reads differently in any of them. Renovate
provisions the Node it runs lockfile updates with from `engines`, so `engines.node` restates
`devEngines.runtime` exactly rather than the supported line.

For Gradle, `constraints.java` restates `.java-version`, enforced by `gate:toolchain`: Renovate's
parser cannot infer the toolchain through the file read in the Kotlin build. The Gradle and wrapper
managers own version, lockfile and verification-metadata updates.

A self-hosted Renovate administrator
must permit `gradleWrapper` in `allowedUnsafeExecutions` for artifact updates. A repository config
cannot grant that permission.

Missing or failed artifact updates are not a reason to weaken Gradle
verification. Follow [Updating Java dependencies](./local-development.mdx#updating-java-dependencies)
to repair and verify them before merging.

Renovate-generated checksums still require independent review.

The [Vulnerability remediation](./vulnerability-remediation.mdx) guide owns the severity policy and exception shape, ceiling, and approval for images and dependencies.

The [Release management](./release-management.mdx#supply-chain-evidence) guide owns artifact signing, provenance, SBOM, and release-verification guarantees.

## Semgrep policy

`security/semgrep/` holds this repository's own rule pack: syntax checks for disabled Node
certificate verification and unconditional Java hostname verifiers, not comprehensive TLS analysis.
The image-reference and Liquibase guards in `ci-security-scan.yml` stay independent. Inline `nosemgrep` suppression is disabled, so an
exception is a reviewed rule change, and every rule carries a positive and a negative fixture beside
it.

Semgrep reports. It is not a required check. The digest-pinned scanner runs without network access or
credentials over a read-only checkout, and `.github/workflows/semgrep.yml` owns the commands that
reproduce a run locally. A pull request from a fork gets the scan verdict but no code-scanning row,
because GitHub caps a fork token's `security-events` access at read.

## Static analysis coverage

CodeQL analysis is disabled: CI has no extraction, query-analysis or scheduled CodeQL job, and
GitHub's CodeQL default setup must remain disabled. This removes CodeQL's interprocedural security
analysis. The remaining checks are not an equivalent replacement. GitHub Code Quality is a separate
maintainability service and remains enabled.

The pinned `github/codeql-action/upload-sarif` steps publish results from other scanners. They do
not run CodeQL analysis. Keep those uploads so Semgrep, Trivy and Scorecard retain their reporting.
Normal Java builds retain NullAway and fatal compiler warnings without an extraction-only bypass.

## Scorecard baseline

`security/scorecard-baseline.json` owns the minimum every enforced Scorecard check must hold, the
exclusions with their reasons, and what each enforced check is scored over. Checks are compared
one at a time, so an improvement cannot offset a regression, and a check the baseline does not name
is not enforced. Lowering a minimum also edits the map in `scripts/check-scorecard.test.ts`, so the
change is visible in the pull request that makes it.

`scoredOver` is `configuration` when [Scorecard](https://github.com/ossf/scorecard/blob/main/docs/checks.md) computes the score from current repository files, workflows, and branch rules.
It is `history` when Scorecard uses a window:

- SAST and Code-Review: about 30 most recent merged pull requests and commits.
- CI-Tests: about 30 most recent commits.
- Signed-Releases: 30 most recent releases.
- Maintained: the previous 90 days.

Each enforced check must carry one classification and a one-line reason.
Without these, the script fails.
Thus, a new baseline check gets a deliberate classification, not a default.

The classification determines responsibility for a regression.
On `push`, only a `configuration` check fails the ratchet, because that commit determines repository configuration.
A `history` check appears in the job summary, but the run remains green.
No single commit can control the aggregate of the last thirty.
Failure on `main` would fail every merge until the window advances.

On `schedule`, `workflow_dispatch`, and `branch_protection_rule`, every check can fail.
These runs check again after time for window recovery.
A score still below baseline represents a trend, not a merge.
Outside Actions, without `GITHUB_EVENT_NAME`, the script enforces everything.

`scripts/check-scorecard.ts` reads OpenSSF's latest published assessment for this repository, not necessarily the current commit.
It fails on any of these conditions:

- A regression attributable to the event.
- A missing enforced check.
- Evidence older than the baseline or more than eight days old.
- An API or parse failure.

`scorecard-ratchet.yml` runs on `main` pushes, branch-protection changes, weekly, and on demand.
It retains the fetched assessment as an artifact.
It needs its own workflow because [OpenSSF restricts the publishing job](https://github.com/ossf/scorecard-action#workflow-restrictions) to allowlisted actions.
Reproduce it with `node scripts/check-scorecard.ts`.
Alternatively, run `node --test scripts/check-scorecard.test.ts` without network access.

### Gradle dependency inventory and the Vulnerabilities exclusion

Scorecard's `Vulnerabilities` check does not read the dependency graph submitted to GitHub or
Dependabot's alerts. The [pinned action's dependencies](https://github.com/ossf/scorecard-action/blob/2d1146689b8cda280b9bc96326124645441f03bc/go.mod) select two tools. These are Scorecard 5.5.0 and OSV Scanner 2.3.2.
[Scorecard's OSV client](https://github.com/ossf/scorecard/blob/v5.5.0/clients/osv.go) scans the checkout recursively and queries its Git commit. The
[Gradle verification-metadata extractor](https://github.com/google/osv-scalibr/blob/cf20290d7624/extractor/filesystem/language/java/gradleverificationmetadataxml/gradleverificationmetadataxml.go)
turns every component's group, name and version in `server/gradle/verification-metadata.xml` into a
package, ignoring its artifact entries and whether Gradle selected that version.

That file is a checksum allowlist, not a resolved dependency graph.
Gradle can read an older version's POM or module descriptor while it selects a patched JAR.
Metadata generation also only adds entries. It keeps the entry of a version that no build downloads any more.
OSV reports both kinds as dependencies.

Quality → App Server checks this file on a pull request that changes Gradle inputs.
It fails on an entry that a clean dependency resolution does not download.
The merge group skips this check, because the pull request already decides it.
[Updating Java dependencies](local-development.mdx#updating-java-dependencies) gives the command that removes it.
Do not remove an entry that a fresh build reads: dependency verification then fails.

Reproduce the distinction for the Gradle sources from the repository root with the
[OSV Scanner release used by the action](https://github.com/google/osv-scanner/releases/tag/v2.3.2):

```sh
osv-scanner scan source --format json --all-packages \
  --lockfile server/application/gradle.lockfile \
  --lockfile server/generated-clients/gradle.lockfile \
  --lockfile server/buildscript-gradle.lockfile \
  --lockfile server/gradle/verification-metadata.xml
```

Each result names its `source.path`, package coordinates and version, and any advisory's affected
ranges. `--all-packages` also includes the patched lockfile entries, so compare the same package
across sources rather than treating every reported version as selected. Exit 1 means vulnerabilities
were reported. An extraction or network error is not a clean assessment.

The advisories investigated
in [#2078](https://github.com/hephaestus-build/Hephaestus/issues/2078) all came from verification
metadata, not either application lockfile. The plugin classpath now has its own native lockfile,
`server/buildscript-gradle.lockfile`. `server/build.gradle.kts` aligns both Jackson API families with
patched BOMs and constrains other generator dependencies.
[Gradle requires separate activation for buildscript locking](https://docs.gradle.org/current/userguide/dependency_locking.html#sec:activate-locking-build-script).
Check `./server/gradlew -p server buildEnvironment` when diagnosing these packages.

Re-run the checks when the action or dependencies change. The recorded observation is not an
exemption for a new advisory against a resolved package.

This inventory limitation justifies excluding the Scorecard `Vulnerabilities` check, not relaxing
other gates. The baseline records that reason. Its other exclusions have separate policy reasons.
The [dependency vulnerability gate](#controls) and release image policy remain
enforced.

Restore the check when the OSV Scanner run above reports no advisory.
Do not alter dependency submission or disable checksum verification to change its score.
