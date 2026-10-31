---
title: Dependencies
description: The bar for a new dependency, when we own a few lines instead, how owned code records its source, and how to remove a package.
page_type: explanation
---

Each dependency is code that people outside this project can change, and each one brings its own dependencies.
We add a package only when it gives more than it costs in that trust.
[ADR 0072](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0072-few-dependencies-owned-slivers-kept-foundations.md) records the decision, the incidents behind it, and the rejected alternatives.
This page gives the current rules.
[Supply-chain security in CI](./supply-chain-security.md) lists the controls that CI runs.

## The constraint

Each package in a lockfile is one more maintainer account or publish token that an attacker can take over.
Thus, the number of packages is a cost, in addition to the size of the code.
[ADR 0072](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0072-few-dependencies-owned-slivers-kept-foundations.md#context) gives the incidents that show it.

## The bar for a new dependency

A new package needs a reason in its pull request.
Answer each item in the pull request body:

1. **What we use.** Name the functions or components that we import. If it is one or two small functions, read [When to own code instead](#when-to-own-code-instead).
2. **Transitive cost.** Give the change that `gate:dependency-baseline` reports. The section [The dependency baseline](#the-dependency-baseline) explains it.
3. **Install scripts.** The package and its dependencies run no install script. If one must run, add an `allowBuilds` entry in `pnpm-workspace.yaml` and give the reason.
4. **Provenance.** The version has npm provenance from a trusted publisher, or the maintainers publish it the same way for each release.
5. **Maintainers.** An organization or more than one active maintainer releases the package. A release in the last 12 months shows that it is maintained.
6. **Bundle bytes.** For a webapp or extension runtime package, give the gzip bytes that it adds to the production build.
7. **License.** The license permits use in an MIT project. Copyleft and unknown licenses need a maintainer decision.

A Gradle dependency answers items 1, 5 and 7.
Its transitive cost is the change to the Gradle lockfiles.
For its provenance, Gradle dependency verification checks the checksum of each artifact against `server/gradle/verification-metadata.xml`.
[Updating Java dependencies](./local-development.mdx#updating-java-dependencies) describes that review.
The dependency baseline, install scripts and bundle bytes apply only to npm packages.

A platform feature comes before a package.
Examples are a Node built-in such as `util.parseArgs`, `util.styleText`, `fs.glob` or `fetch`, a browser API, or a feature of a framework that we already use.

In `webapp/` and `server/`, ask a maintainer before you add a dependency.
Their package guides say so.

## When to own code instead

Own the code when all of these are true:

- We use a small part of the package, such as one or two functions.
- That part is small enough to review in one sitting, typically less than 200 lines with its tests.
- That part is stable. It implements a fixed format or a simple algorithm, not a moving specification.
- No platform feature does the same work.

Keep the package when it is a foundation.
Foundations include React, TanStack, Base UI, Spring and parsers for complex formats.
Code that a security property depends on is also a foundation, such as an HTML sanitizer or a cryptographic library.
Keep the large, well-run foundations, but remove the optional add-ons that we do not use.

Owned code has a cost that a package does not have.
Renovate does not update it, and an advisory against the upstream package does not reach us automatically.
Thus, own only a part that has no security history and that changes rarely.

## How owned code lives

- **One place.** Owned code is one module in the tree that uses it, such as `webapp/src/lib/` or `scripts/lib/`. Do not copy it into a second tree. Import it from the first.
- **A header.** Each file of owned code starts with a comment that gives its source. A tool reads it, so the format is exact. [The owned-code header](#the-owned-code-header) gives it.
- **The license notice.** MIT, ISC and BSD licenses require that the copyright line and the notice stay with the code. Put them in the header.
- **Tests.** Port the upstream tests for the part that we keep. Add tests for our callers. A module without tests does not replace a package.
- **The SBOM entry.** Each owned module in shipped code needs a component in the release SBOM. Its upstream package is the pedigree ancestor of that component. Thus, the license and the provenance stay true. Do not merge owned code into a shipped tree before the release SBOM lists it. [Release management](./release-management.mdx#supply-chain-evidence) describes the SBOM.

## The owned-code header

The header is the first comment in the file: `/** … */` in TypeScript and JavaScript, `/* … */` in CSS.
It has four fields, each on its own line, in this order and once each.
A line can start with `*` and spaces.

| Field | Value |
| --- | --- |
| `Owned from:` | An `https` URL of the upstream source at a tag or a commit, not at a branch |
| `Package:` | The canonical [package URL](https://github.com/package-url/purl-spec) of the upstream release, such as `pkg:npm/%40gitlab/svgs@3.164.0`. A scope's `@` is `%40` |
| `License:` | The upstream license as an expression of [SPDX license identifiers](https://spdx.org/licenses/), such as `MIT` |
| `Changes:` | One line: what we kept, removed or changed |

After the fields, an empty line and the upstream copyright line and license notice follow.

```ts
/**
 * Owned from: https://github.com/<owner>/<repository>/blob/<tag>/<path>
 * Package: pkg:npm/<name>@<version>
 * License: MIT
 * Changes: <what we kept, removed or changed>
 *
 * <the upstream copyright line and license notice>
 */
```

Shipped code is each file under `webapp/src/`, `extension/src/`, `docker/` and `server/application/src/main/`.
Test files, story files, and files in `test/` and `__tests__/` directories are not shipped code.
In shipped code, a file with an `Owned from:` line in its first 40 lines is owned code.
Such a file needs a complete header and a component in the release SBOM.

## The dependency baseline

`security/dependency-baseline.json` records, for each lockfile and workspace, the number of direct and transitive packages.
It also records the packages of pnpm's env document: the package manager and the config dependencies, which run during an install.
`gate:dependency-baseline` compares it with the lockfiles and fails when a count rises above the baseline.
Thus, an addition that takes a count above the baseline raises the baseline in its pull request.
The review sees the cost beside the change.

A count below the baseline passes, and the gate names the lower value.
Any later pull request can lower the baseline in passing.
Thus, removals, and updates that stay within the baseline, merge in parallel.

To raise the baseline with an addition, or to lower it after a removal, run:

```sh
node scripts/check-dependency-baseline.ts --write
```

## How to remove a dependency

1. Find each import site with `git grep -n "<package>"`. Include configuration files, `package.json` scripts and CSS `@import` and `@plugin` lines.
2. Replace each use with a platform feature or owned code, or delete the use.
3. Remove the package from its `package.json`.
4. Run `pnpm install`.
5. Find each `pnpm-workspace.yaml` entry that names the package: `allowBuilds`, `overrides`, `minimumReleaseAgeExclude`, `packageExtensions` and `patchedDependencies`.
6. For each entry, run `pnpm why -r <package>` in each lockfile directory, so that every workspace importer is searched. Remove the entry only if no package in any lockfile still needs it. A security override for a package that stays as a transitive dependency must stay.
7. If you removed an entry, run `pnpm install` again. The lockfile records the workspace settings.
8. Update the dependency baseline.
9. Run the tests of each tree that used the package.
10. If the package was in shipped code, add a changeset.

## Install hardening

`pnpm-workspace.yaml` owns the install policy, such as the release age and the packages that can run install scripts.
Its comments give the reason for each setting.
pnpm reads the nearest `pnpm-workspace.yaml`.
Thus, the two agent images resolve with their own files in `docker/agents/pi/` and `docker/agents/practice-scripts/`.
[Supply-chain security in CI](./supply-chain-security.md) describes how CI and Renovate apply the policy.

## Next steps

- [Supply-chain security in CI](./supply-chain-security.md)
- [Vulnerability remediation](./vulnerability-remediation.mdx)
- [Release management](./release-management.mdx#supply-chain-evidence)
