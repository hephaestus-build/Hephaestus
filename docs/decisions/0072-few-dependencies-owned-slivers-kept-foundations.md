# ADR 0072: Few dependencies — own a sliver, keep a foundation

**Status:** Accepted
**Date:** 2026-10-31
**Authors:** Felix T.J. Dietrich
**Relates to:** [Dependencies](../contributor/dependencies.md) (the current rules) and [Supply-chain security in CI](../contributor/supply-chain-security.md) (the CI controls).

## Context

Hephaestus ships a server, a single-page app, a browser extension, two agent images and a docs site.
On 2026-10-31 the root lockfile resolved 2,544 npm packages, the Pi agent image 176, and the practice script toolkit 65.
`security/dependency-baseline.json` records the current counts for each workspace.

These supply-chain incidents of 2024 to 2026 came through a package registry, a CI action or a CDN. None came through a defect in the victim's own code:

| Date | Incident | How it worked | Source |
| --- | --- | --- | --- |
| 2024-03 | xz-utils backdoor (CVE-2024-3094) | A maintainer who had worked for years put a payload in the release tarball only | [oss-security](https://www.openwall.com/lists/oss-security/2024/03/29/4) |
| 2024-06 | polyfill.io | A new domain owner served malware from a third-party script CDN | [Censys](https://www.censys.com/blog/july-2-polyfill-io-supply-chain-attack-digging-into-the-web-of-compromised-domains) |
| 2024-12 | Ultralytics | A poisoned Actions cache in the release workflow, then a publish token | [PyPI](https://blog.pypi.org/posts/2024-12-11-ultralytics-attack-analysis/) |
| 2025-03 | tj-actions/changed-files (CVE-2025-30066) | Mutable action tags moved to a commit that printed secrets | [CISA](https://www.cisa.gov/news-events/alerts/2025/03/18/supply-chain-compromise-third-party-tj-actionschanged-files-cve-2025-30066-and-reviewdogaction) |
| 2025-07 | eslint-config-prettier (CVE-2025-54313) | A phished maintainer, then an install script | [Red Hat](https://access.redhat.com/security/cve/CVE-2025-54313) |
| 2025-08 | nx "s1ngularity" | Shell injection in a `pull_request_target` workflow, then a stolen npm token | [GHSA-cxm3-wv7p-598c](https://github.com/nrwl/nx/security/advisories/GHSA-cxm3-wv7p-598c) |
| 2025-09 | chalk, debug and 16 other packages | A phished maintainer of small utilities with about 2.6 billion weekly downloads | [Check Point](https://blog.checkpoint.com/crypto/the-great-npm-heist-september-2025/) |
| 2025-09 and 2025-11 | Shai-Hulud and Shai-Hulud 2.0 | Stolen tokens republished each package the victim could publish, with an install script | [CISA](https://www.cisa.gov/news-events/alerts/2025/09/23/widespread-supply-chain-compromise-impacting-npm-ecosystem), [Wiz](https://www.wiz.io/blog/shai-hulud-2-0-ongoing-supply-chain-attack) |
| 2025-10 | PhantomRaven | Dependencies fetched from a URL, which scanners did not see | [The Hacker News](https://thehackernews.com/2025/10/phantomraven-malware-found-in-126-npm.html) |
| 2026-03 | Trivy and trivy-action (CVE-2026-33634) | An incomplete credential rotation, then moved tags and changed images | [GHSA-69fq-xp46-6x23](https://github.com/aquasecurity/trivy/security/advisories/GHSA-69fq-xp46-6x23) |
| 2026-03 | axios | A maintainer account takeover added a new dependency that dropped a remote-access tool | [CISA](https://www.cisa.gov/news-events/alerts/2026/04/20/supply-chain-compromise-impacts-axios-node-package-manager) |
| 2026-06 | @redhat-cloud-services | A taken-over GitHub account pushed to `main`, and the trusted CI published with valid provenance | [BleepingComputer](https://bleepingcomputer.com/news/security/red-hat-npm-packages-compromised-to-steal-developer-credentials) |
| 2026-08 | ChainDrop (keyv, cacheable) | Releases with valid provenance wrote agent and editor hooks into the victim's repository | [Elastic](https://www.elastic.co/security-labs/shai-hulud-chaindrop-npm-supply-chain), [VentureBeat](https://venturebeat.com/security/the-shai-hulud-npm-worm-didnt-fake-its-security-check-it-earned-a-legitimate-one) |

The incidents teach five lessons:

1. **Each package is an account that an attacker can take over.** chalk, debug, `is` and eslint-config-prettier were small utilities. A takeover of one maintainer reached billions of installs.
2. **In these incidents, the malicious versions were live for hours.** chalk for about 2.5 hours, axios for about 3 hours. A release-age delay of a few days would have kept those versions out of a new lockfile. It is a mitigation, not a guarantee.
3. **Install scripts were a common payload path.** Shai-Hulud, rspack, eslint-config-prettier and Mastra all ran from a lifecycle script.
4. **Provenance shows where a package was built, not that its source is safe.** The Red Hat and ChainDrop releases had valid provenance. A trust-downgrade check finds a release that skips the normal pipeline, but not a taken-over pipeline.
5. **Mutable references are an attack surface.** Action tags, image tags and CDN URLs moved under their users.

## Decision drivers

- Fewer packages means fewer accounts to take over and less code to review.
- A small part of a large package brings all of its dependencies.
- Code that we own has no upstream security updates. Thus, owned code must be small and stable.
- A foundation such as React or Spring has a security team and many reviewers. Code that we write to replace it has neither.
- A rule that a reviewer must remember fails. A count that CI checks does not.

## Considered options

- **Keep packages by default, and rely on scanners and pins.** Rejected. Scanners find known advisories. In the incidents above, the malicious version was new and had no advisory yet.
- **Own every small dependency.** Rejected. Each owned module needs tests and a reviewer who watches its upstream. Too many become a second, unmaintained library.
- **Few dependencies, with a measured bar (chosen).** Own a sliver when it is small, stable and has no platform replacement. Keep the foundations, but remove their unused add-ons. Make each addition visible in review.

## Decision

1. **A platform feature comes first, a few owned lines second, a new package third.** A framework or platform feature is better than code that we write. A few owned lines are better than a new package for a sliver.
2. **A new package answers a written bar in its pull request.** [Dependencies](../contributor/dependencies.md#the-bar-for-a-new-dependency) owns the bar.
3. **Owned code keeps the license and the provenance of its upstream**, in its source and in the release SBOM. [Dependencies](../contributor/dependencies.md#how-owned-code-lives) owns the rules.
4. **The package counts are a ratchet that CI checks.** Each addition changes a committed baseline in the same pull request. [Dependencies](../contributor/dependencies.md#the-dependency-baseline) describes it.
5. **The install policy lives in `pnpm-workspace.yaml`**, with the reason for each setting in a comment.

## Consequences

- A pull request that adds a package is larger, because it changes the baseline and answers the bar.
- Owned code does not get Renovate updates or advisories. The header names the upstream so that a reviewer can compare it.
- Owned code in a shipped tree needs its SBOM component first. Thus, a self-hoster sees the same provenance as for a package.
- Removing a package lowers the baseline. The next addition cannot use that space silently.

## Revisit trigger

- The registries make a release-age delay or a trust check unnecessary, for example with mandatory staged publishing.
- The count of owned modules grows to the point where their upkeep costs more than the packages that they replaced.
