<div align="center">
  <h1>
    <img alt="Hephaestus" width="484" src="./docs/public/img/brand/hephaestus-lockup-dark.png#gh-dark-mode-only">
    <img alt="Hephaestus" width="484" src="./docs/public/img/brand/hephaestus-lockup-light.png#gh-light-mode-only">
  </h1>
  <!-- The tagline, the category line and the lead paragraph: docs/contributor/practice-feedback-language.mdx § Tagline and lead. -->
  <p><strong>Get better at the work you already do.</strong></p>
  <p>The open-source AI mentor for software engineering practices</p>

  <p>
    <a href="https://hephaestus.build"><strong>App</strong></a> ·
    <a href="https://docs.hephaestus.build/"><strong>Docs</strong></a> ·
    <a href="https://docs.hephaestus.build/admin/kubernetes"><strong>Self-host</strong></a> ·
    <a href="https://github.com/hephaestus-build/Hephaestus/discussions"><strong>Discussions</strong></a>
  </p>
  <p>
    <a href="https://github.com/hephaestus-build/Hephaestus/releases"><img alt="Latest Hephaestus release" height="20" src="https://img.shields.io/github/v/release/hephaestus-build/Hephaestus?display_name=tag&amp;sort=semver&amp;include_prereleases"></a>
    <a href="https://github.com/hephaestus-build/Hephaestus/actions/workflows/cicd.yml?query=branch%3Amain"><img alt="Hephaestus CI status on main" height="20" src="https://github.com/hephaestus-build/Hephaestus/actions/workflows/cicd.yml/badge.svg?branch=main"></a>
    <a href="https://scorecard.dev/viewer/?uri=github.com/hephaestus-build/Hephaestus"><img alt="Hephaestus OpenSSF Scorecard report" height="20" src="https://img.shields.io/ossf-scorecard/github.com/hephaestus-build/Hephaestus?label=OpenSSF%20Scorecard&amp;style=flat"></a>
    <a href="./LICENSE"><img alt="MIT license" height="20" src="https://img.shields.io/github/license/hephaestus-build/Hephaestus?style=flat"></a>
  </p>
</div>

Hephaestus is an AI mentor that helps software developers grow in the engineering practices their team values.

Developers grow by doing real work and getting feedback from people who know the craft: senior developers, reviewers, teachers and team leads.
Hephaestus gives you feedback alongside them, on the practices your team chose, such as writing clear issues or testing a change.
If a review finds a problem it can support, Hephaestus can show you the evidence and suggest a next step.
You can ask about the feedback in a conversation, and you decide what to do with it.

## How it works

1. **It reads your work.**
   It reads pull requests, merge requests, issues and reviews from connected repositories.
   It can also use selected Outline collections and new messages in Slack channels after it announces monitoring.
2. **It gives feedback on your team's practices.**
   If a review finds a problem it can support, Hephaestus can show you the evidence and suggest a next step.
   Feedback can appear on your work, on your Practice profile, or in a conversation.
3. **It helps you take the next step.**
   Ask Hephaestus why, ask for an example, or disagree.
   Your Practice profile shows recurring feedback and what is going well.

<div align="center">
  <img alt="An illustration of one change through a project. Issue #412 has no acceptance criteria. The pull request grows to 34 files. A reviewer raises a vague concern about large exports. The pull request merges with that thread unresolved. Hephaestus points back to the issue." src="./docs/images/readme/landing-hero-dark.png#gh-dark-mode-only" width="1280">
  <img alt="An illustration of one change through a project. Issue #412 has no acceptance criteria. The pull request grows to 34 files. A reviewer raises a vague concern about large exports. The pull request merges with that thread unresolved. Hephaestus points back to the issue." src="./docs/images/readme/landing-hero-light.png#gh-light-mode-only" width="1280">
</div>

<div align="center">
  <img alt="The practice profile in Hephaestus. A summary shows four practices in three groups: one needs attention, one is mixed and two are going well. Below it, a recurring feedback pattern for the practice State how to verify the change says that reviewers cannot see how to check the change, and why it was raised." src="./docs/public/img/user/practice-profile/overview.dark.webp#gh-dark-mode-only" width="1024">
  <img alt="The practice profile in Hephaestus. A summary shows four practices in three groups: one needs attention, one is mixed and two are going well. Below it, a recurring feedback pattern for the practice State how to verify the change says that reviewers cannot see how to check the change, and why it was raised." src="./docs/public/img/user/practice-profile/overview.light.webp#gh-light-mode-only" width="1024">
</div>

## Who it is for

- **Developers:** get feedback on your own work, see what is going well, and ask why.
  Sign in at your team's Hephaestus address, then follow [Getting started](https://docs.hephaestus.build/user/getting-started).
- **Team leads and mentors:** choose the practices your team values.
  Hephaestus adds feedback on everyday work alongside the guidance you give.
- **Educators:** use a workspace for a course or a project team.
  A workspace admin chooses the practices you teach and can approve comments before they post.
  [Set up your workspace](https://docs.hephaestus.build/user/workspace) shows how.
- **Researchers:** Hephaestus is the research platform for a study at TUM.
  The study asks whether and how AI-generated feedback improves how developers apply engineering practices.
  [Run a research program](https://docs.hephaestus.build/admin/research-program) explains the setup.
  Participants choose separately whether to allow research use of their data.
- **Admins and self-hosters:** install the signed Helm chart on one Linux host or an existing Kubernetes cluster.
  [First steps](https://docs.hephaestus.build/admin/first-steps) sets up sign-in, a repository, and a review model.

## Why an AI mentor

Developers learn engineering practices by doing the work and getting feedback on it, and the people who give that feedback have little time.
A [2022 study](https://doi.org/10.1145/3540250.3549167) of 37 Apache projects found that only about a quarter of pull requests got a comment that explained why.
Hephaestus adds feedback on everyday work alongside the guidance that reviewers and mentors give.

## Features

- **Your Team’s Practices**: Start from the catalog or write your own.
  Each practice describes what good work looks like.
  The catalog covers scoping, issues, reviews, testing, security, version control, and recorded decisions.
- **Feedback on Your Work**: Feedback can appear on your work, on your Practice profile, or in a conversation.
  By default, comments on the work wait for a workspace admin’s approval and post while the work is open.
  Workspace admins can let eligible comments post automatically, or turn off new feedback for a practice.
- **Practice Profile**: Your Practice profile shows recurring feedback and what is going well in your reviewed work.
  It appears when your workspace reviews practices.
- **Ask Hephaestus**: Ask why, ask for an example, or disagree, in the web app.
  When your workspace uses Slack, you can also ask in a direct message from your linked account.
  Hephaestus answers when your workspace has a model that fits your AI choice.
- **Built for Growth**: Feedback describes your work and how to improve it, without scores, grades or ranks.
  AI writes the feedback and can make mistakes, so check it against your work and decide what to do.
  Workspace admins see practice results linked to the work, and those links can identify you even when names are hidden.
  An instance admin can include your conversation in an audited export of your data.
  [Who can see what](https://docs.hephaestus.build/user/privacy#who-can-see-what) has the details.
- **Connected Services**: Connect GitHub or GitLab for repository work.
  Add Slack for conversations and team channels, or Outline for team documents.
  Each member chooses **In-house**, **Cloud**, or **No AI** for reviews about their work and answers to their questions.
  The [model provider guide](https://docs.hephaestus.build/admin/ai-providers) lists the supported services.

[MIT](./LICENSE) ·
[Helm chart](https://docs.hephaestus.build/admin/kubernetes) ·
[Signed releases with SBOM and provenance](https://docs.hephaestus.build/admin/release-image-lock)

> [!IMPORTANT]
> **Hephaestus is pre-1.0.** Releases are continuous.
> Only the latest release, a release candidate included, has support ([Support statement](https://docs.hephaestus.build/admin/compatibility-policy#support-statement)).
> Every fix ships in the next release.
> Until 1.0, a *minor* release can change configuration or the API in ways that need you to act.
> Read the release notes before every upgrade.
>
> Version 1.0 makes upgrades, configuration, the Helm chart, and the REST API predictable:
> [compatibility policy](https://docs.hephaestus.build/admin/compatibility-policy) ·
> [1.0 milestone](https://github.com/hephaestus-build/Hephaestus/milestone/5).

## Get started

- **Run your own deployment:** on one Linux host, `k3s-install.sh` installs k3s and the Helm chart.
  See [Install on one host (k3s)](https://docs.hephaestus.build/admin/k3s).
  On a Kubernetes cluster, install the [Helm chart](https://docs.hephaestus.build/admin/kubernetes#install).
  Then follow the [first steps](https://docs.hephaestus.build/admin/first-steps).
- **Sign in to an existing deployment:** if your team already has a workspace, sign in at its address.
  The [user guide](https://docs.hephaestus.build/user/overview) explains what you see.
- **Upgrade:** read the release notes and the [upgrade guide](https://docs.hephaestus.build/admin/upgrade).
  Then test the upgrade in staging.
- **Contribute:** start with the
  [local development guide](https://docs.hephaestus.build/contributor/local-development).
  It also shows how to explore the UI components in Storybook.

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
There, Hephaestus is the research platform for a study of whether and how AI-generated feedback improves developers' application of engineering practices.
[Applied Education Technologies](https://aet.cit.tum.de/) maintains Hephaestus under the [MIT license](./LICENSE).
Its name comes from the Greek god of the forge.
In Plato's myth, someone stole the god's craft from his workshop and gave it to people.
This let them make things for themselves.

<p align="center">
  <strong>Developed by</strong><br><br>
  <a href="https://aet.cit.tum.de/#gh-dark-mode-only">
    <img alt="Applied Education Technologies" align="middle" hspace="12" src="./docs/public/img/readme-brand/aet-mark-dark.svg#gh-dark-mode-only" height="72">
  </a>
  <a href="https://aet.cit.tum.de/#gh-light-mode-only">
    <img alt="Applied Education Technologies" align="middle" hspace="12" src="./webapp/public/brand/aet-mark.svg#gh-light-mode-only" height="72">
  </a>
  <a href="https://www.tum.de/en/#gh-dark-mode-only">
    <img alt="Technical University of Munich" align="middle" hspace="12" src="./docs/public/img/readme-brand/tum-logo-dark.svg#gh-dark-mode-only" height="56">
  </a>
  <a href="https://www.tum.de/en/#gh-light-mode-only">
    <img alt="Technical University of Munich" align="middle" hspace="12" src="./webapp/public/brand/tum-logo.svg#gh-light-mode-only" height="56">
  </a>
  <br>
  <sub><a href="https://aet.cit.tum.de/">Research Group for Applied Education Technologies</a> · <a href="https://www.tum.de/en/">Technical University of Munich</a></sub>
</p>
