<div align="center">
  <h1>
    <img alt="Hephaestus" width="484" src="./docs/static/img/brand/hephaestus-lockup-dark.png#gh-dark-mode-only">
    <img alt="Hephaestus" width="484" src="./docs/static/img/brand/hephaestus-lockup-light.png#gh-light-mode-only">
  </h1>
  <!-- The product tagline, quoted verbatim. -->
  <!-- vale STE.Contractions = NO -->
  <p><strong>Learn from the work you’re already doing</strong></p>
  <!-- vale STE.Contractions = YES -->
  <p>Open-source practice feedback for software teams</p>

  <p>
    <a href="https://hephaestus.build"><strong>App</strong></a> ·
    <a href="https://docs.hephaestus.build/"><strong>Docs</strong></a> ·
    <a href="https://docs.hephaestus.build/admin/kubernetes"><strong>Self-host</strong></a> ·
    <a href="https://github.com/hephaestus-build/Hephaestus/discussions"><strong>Discussions</strong></a>
  </p>
  <p>
    <a href="https://github.com/hephaestus-build/Hephaestus/releases/latest"><img alt="Latest Hephaestus release" height="20" src="https://img.shields.io/github/v/release/hephaestus-build/Hephaestus?display_name=tag&amp;sort=semver"></a>
    <a href="https://github.com/hephaestus-build/Hephaestus/actions/workflows/cicd.yml?query=branch%3Amain"><img alt="Hephaestus CI status on main" height="20" src="https://github.com/hephaestus-build/Hephaestus/actions/workflows/cicd.yml/badge.svg?branch=main"></a>
    <a href="https://scorecard.dev/viewer/?uri=github.com/hephaestus-build/Hephaestus"><img alt="Hephaestus OpenSSF Scorecard report" height="20" src="https://img.shields.io/ossf-scorecard/github.com/hephaestus-build/Hephaestus?label=OpenSSF%20Scorecard&amp;style=flat"></a>
    <a href="./LICENSE"><img alt="MIT license" height="20" src="https://img.shields.io/github/license/hephaestus-build/Hephaestus?style=flat"></a>
  </p>
</div>

Hephaestus reviews the pull requests, issues, and code reviews your team already writes against the engineering practices you choose.
It gives each developer feedback that cites their work and names a next step.

Feedback appears on the work itself, on each developer's private practice page, and in a conversation.
There, developers can ask why, ask for an example, or disagree.
Workspace admins can approve feedback before it appears on the work.

<div align="center">
  <img alt="An illustration of one change through a project. Issue #412 has no acceptance criteria. The pull request grows to 34 files. A reviewer raises a vague concern about large exports. The pull request merges with that thread unresolved. Hephaestus points back to the issue." src="./docs/images/readme/landing-hero-dark.png#gh-dark-mode-only" width="1280">
  <img alt="An illustration of one change through a project. Issue #412 has no acceptance criteria. The pull request grows to 34 files. A reviewer raises a vague concern about large exports. The pull request merges with that thread unresolved. Hephaestus points back to the issue." src="./docs/images/readme/landing-hero-light.png#gh-light-mode-only" width="1280">
  <p><sub>An illustration of the feedback Hephaestus writes. See the <a href="https://docs.hephaestus.build/user/ai-code-review">user guide</a> for the real interface.</sub></p>
</div>

## Why Hephaestus

Developers learn engineering practices by doing the work and getting feedback on it.
That feedback comes from reviewers and experienced colleagues, and their time is scarce.
In 37 Apache projects, a [2022 study](https://doi.org/10.1145/3540250.3549167) found that only about a quarter of pull requests received an implicit-mentoring comment.
Such a comment explains a suggestion, an instruction, or a way to fix an error.
Hephaestus gives feedback on every piece of work it can assess, so experienced people can spend their time where judgment and relationships matter.

## Features

- **Practices**: Start from a curated catalog or write your own.
  Each states what good work looks like.
  The catalog covers scoping, issues, reviews, testing, security, version control, and recorded decisions.
- **Feedback on the Work**: A comment on the pull request, merge request, or issue.
- **Practice Page**: Each developer's private view of feedback and progress per practice.
  Workspace admins see the observations and delivery status behind it.
- **Ask Hephaestus**: A weekly digest you can turn on arrives in the conversation.
  Developers ask why, ask for an example, or disagree.
  Conversations run in the web app and, if Slack is connected, in a direct message.
- **Feedback Mode**: Off, Approve first, or Automatic, per workspace, practice group, or practice.
  Approve first holds feedback on the work until a workspace admin approves it.
- **Your Stack**: Works with GitHub, GitLab, Slack, and Outline.
  Your own model provider: OpenAI, Azure OpenAI, or any OpenAI-compatible endpoint.
  Helm on your Kubernetes.

Developers decide what to do with feedback: fix the work, ask why, or disagree.
Merge decisions stay with your reviewers.
Each member chooses which models may handle their work: **In-house**, **Cloud**, or **No AI**.

[MIT](./LICENSE) ·
[Helm chart](https://docs.hephaestus.build/admin/kubernetes) ·
[Signed releases with SBOM and provenance](https://docs.hephaestus.build/admin/release-image-lock)

> [!IMPORTANT]
> **Hephaestus is pre-1.0.** Releases are continuous.
> Only the [latest release](https://github.com/hephaestus-build/Hephaestus/releases/latest) has support.
> Every fix ships in the next release.
> Until 1.0, a *minor* release can change configuration or the API in ways that need you to act.
> Read the release notes before every upgrade.
>
> Version 1.0 makes upgrades, configuration, the Helm chart, and the REST API predictable:
> [compatibility policy](https://docs.hephaestus.build/admin/compatibility-policy) ·
> [1.0 milestone](https://github.com/hephaestus-build/Hephaestus/milestone/5).

## Get started

- **Run your own deployment:** on one Linux host, `k3s-install.sh` installs k3s and the Helm chart.
  See [Install on one host (k3s)](https://docs.hephaestus.build/admin/kubernetes#install-on-one-host-k3s).
  On a Kubernetes cluster, install the [Helm chart](https://docs.hephaestus.build/admin/kubernetes#install).
  Then follow the [first steps](https://docs.hephaestus.build/admin/first-steps).
- **Sign in to an existing deployment:** if your team already has a workspace, sign in at its address.
  The [user guide](https://docs.hephaestus.build/user/overview) explains what you see.
- **Upgrade:** read the release notes and the [upgrade guide](https://docs.hephaestus.build/admin/upgrade).
  Then test the upgrade in staging.
- **Contribute:** start with the
  [local development guide](https://docs.hephaestus.build/contributor/local-development).
  It also shows how to explore the UI components in Storybook.

## Where Hephaestus is going

Practices apply to everyone who contributes to a project, people and coding agents alike.
The same evidence-backed feedback can steer a coding agent, and it shows a team which practices it actually applies, so the team can refine them.

## Get help

- Ask usage and installation questions in [Support](https://github.com/hephaestus-build/Hephaestus/discussions/categories/support).
- Propose a change in [Ideas](https://github.com/hephaestus-build/Hephaestus/discussions/categories/ideas).
  A discussion can explore a proposal before it becomes selected work.
- Report reproducible bugs in [GitHub Issues](https://github.com/hephaestus-build/Hephaestus/issues).
- Report security vulnerabilities privately as described in [SECURITY.md](./SECURITY.md).

## Contributing

Contributions are welcome.
[CONTRIBUTING.md](./CONTRIBUTING.md) explains project setup, change proposals, quality checks, pull requests, and what to expect from triage.
The [Code of Conduct](./CODE_OF_CONDUCT.md) governs participation in the project.

## Project origins

Developed at the Technical University of Munich.
There, Hephaestus is the research platform for a study of how AI-generated feedback can improve developers' application of engineering practices.
[Applied Education Technologies](https://aet.cit.tum.de/) maintains Hephaestus under the [MIT license](./LICENSE).
Its name comes from the Greek god of the forge.
In Plato's myth, someone stole the god's craft from his workshop and gave it to people.
This let them make things for themselves.

<p align="center">
  <strong>Developed by</strong><br><br>
  <a href="https://aet.cit.tum.de/#gh-dark-mode-only">
    <img alt="Applied Education Technologies" align="middle" hspace="12" src="./docs/static/img/readme-brand/aet-mark-dark.svg#gh-dark-mode-only" height="72">
  </a>
  <a href="https://aet.cit.tum.de/#gh-light-mode-only">
    <img alt="Applied Education Technologies" align="middle" hspace="12" src="./webapp/public/brand/aet-mark.svg#gh-light-mode-only" height="72">
  </a>
  <a href="https://www.tum.de/en/#gh-dark-mode-only">
    <img alt="Technical University of Munich" align="middle" hspace="12" src="./docs/static/img/readme-brand/tum-logo-dark.svg#gh-dark-mode-only" height="56">
  </a>
  <a href="https://www.tum.de/en/#gh-light-mode-only">
    <img alt="Technical University of Munich" align="middle" hspace="12" src="./webapp/public/brand/tum-logo.svg#gh-light-mode-only" height="56">
  </a>
  <br>
  <sub><a href="https://aet.cit.tum.de/">Research Group for Applied Education Technologies</a> · <a href="https://www.tum.de/en/">Technical University of Munich</a></sub>
</p>
