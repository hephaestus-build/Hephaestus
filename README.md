<div align="center">
  <h1>
    <img alt="Hephaestus" width="484" src="./docs/static/img/brand/hephaestus-lockup-dark.png#gh-dark-mode-only">
    <img alt="Hephaestus" width="484" src="./docs/static/img/brand/hephaestus-lockup-light.png#gh-light-mode-only">
  </h1>
  <!-- The product tagline, word for word as every other surface shows it. -->
  <!-- vale STE.Contractions = NO -->
  <p><strong>Learn from the work you’re already doing</strong></p>
  <!-- vale STE.Contractions = YES -->

  <p>
    <a href="https://hephaestus.build"><strong>App</strong></a> ·
    <a href="https://docs.hephaestus.build/"><strong>Docs</strong></a> ·
    <a href="https://docs.hephaestus.build/admin/install"><strong>Self-host</strong></a> ·
    <a href="https://github.com/hephaestus-build/Hephaestus/discussions"><strong>Discussions</strong></a>
  </p>
  <p>
    <a href="https://github.com/hephaestus-build/Hephaestus/releases/latest"><img alt="Latest Hephaestus release" height="20" src="https://img.shields.io/github/v/release/hephaestus-build/Hephaestus?display_name=tag&amp;sort=semver"></a>
    <a href="https://github.com/hephaestus-build/Hephaestus/actions/workflows/cicd.yml?query=branch%3Amain"><img alt="Hephaestus CI status on main" height="20" src="https://github.com/hephaestus-build/Hephaestus/actions/workflows/cicd.yml/badge.svg?branch=main"></a>
    <a href="https://scorecard.dev/viewer/?uri=github.com/hephaestus-build/Hephaestus"><img alt="Hephaestus OpenSSF Scorecard report" height="20" src="https://img.shields.io/ossf-scorecard/github.com/hephaestus-build/Hephaestus?label=OpenSSF%20Scorecard&amp;style=flat"></a>
    <a href="./LICENSE"><img alt="MIT license" height="20" src="https://img.shields.io/github/license/hephaestus-build/Hephaestus?style=flat"></a>
  </p>
</div>

Hephaestus is an open-source AI mentor for software teams.
It checks the issues, pull requests, and reviews that your team already writes against the engineering practices that you choose.
Each developer gets feedback with evidence and a next step.

Every piece of feedback names its practice and points back to the reviewed work.
Developers can ask why, disagree, or discuss the next step.

<div align="center">
  <img alt="An illustration of one change through a project. Issue #412 has no acceptance criteria. The pull request grows to 34 files. A reviewer raises a vague concern about large exports. The pull request merges with that thread unresolved. Hephaestus points back to the issue." src="./docs/images/readme/landing-hero-dark.png#gh-dark-mode-only" width="1280">
  <img alt="An illustration of one change through a project. Issue #412 has no acceptance criteria. The pull request grows to 34 files. A reviewer raises a vague concern about large exports. The pull request merges with that thread unresolved. Hephaestus points back to the issue." src="./docs/images/readme/landing-hero-light.png#gh-light-mode-only" width="1280">
  <p><sub>An illustration of the feedback Hephaestus writes. See the <a href="https://docs.hephaestus.build/user/ai-code-review">user guide</a> for the real interface.</sub></p>
</div>

A mentor gives feedback on how you work.
Examples include a coach on a university capstone or an experienced maintainer on an open-source project.
There is never enough of that attention for everyone.
Hephaestus can give more developers feedback on routine practices.
People keep responsibility for the harder judgment and the relationships.

> [!IMPORTANT]
> **Hephaestus is pre-1.0.** Releases are continuous.
> Only the [latest release](https://github.com/hephaestus-build/Hephaestus/releases/latest) has support.
> There are no maintenance branches or backports.
> Until 1.0, a *minor* release can change configuration or the API in ways that need you to act.
> Read the release notes before every upgrade.
>
> Version 1.0 makes upgrades, configuration, Compose, and the REST API predictable:
> [compatibility policy](https://docs.hephaestus.build/admin/compatibility-policy) ·
> [1.0 milestone](https://github.com/hephaestus-build/Hephaestus/milestone/5).

## What Hephaestus does

- **Reviews contributions against engineering practices.** A curated set of practices ships with it.
  They cover how developers scope, describe, and review work, and also testing, security, and recorded decisions.
  A workspace adopts the groups it values.
  It can rewrite any practice inside them.
- **Gets the feedback to the developer.** Feedback can appear on the work itself.
  It can also appear on the developer's own
  [Practice profile](https://docs.hephaestus.build/user/practice-profile) or in their next conversation with Heph.
  Feedback on the work waits for a workspace admin's approval by default.
  Every piece names its practice and points at its evidence.
- **Answers follow-up questions.** Developers can ask why a suggestion matters or supply context that Hephaestus did not have.
  In chat, Hephaestus goes by Heph.
  Chat is available in the web app and, if Slack is connected, in a direct message.
- **Explains itself where the work is.** A Chrome extension shows what Hephaestus recorded about the reviewed work and why.
  It shows this on the pull request, merge request, or issue that you view.
- **Uses the project context you connect.** This includes GitHub repositories and projects from one configured GitLab instance.
  It also includes selected Outline collections and Slack channel messages that members permit it to use.
- **Respects your AI choice.** Each member chooses **In-house**, **Cloud**, or **No AI** across their
  workspaces.
  That choice limits practice reviews and Heph.
  It does not stop source sync.
- **Puts admins in control.** They configure repositories, practices, members, integrations, the AI
  model through any OpenAI-compatible endpoint, and a monthly spending cap.
- **Shows what happened.** Activity shows work that needs you, such as review requests and returned pull requests.
  It also summarizes your work, or your team's work, over a time range.

## How feedback works

1. A workspace connects its GitHub or GitLab repositories and adopts the practices it cares about.
2. Hephaestus gathers a contribution together with the work around it: the issue, the change, the
   review thread, the conversation.
3. It records what it observed against those practices.
   It writes feedback from those observations.
4. Feedback on the work waits for a workspace admin's approval by default.
   Private feedback has separate delivery checks.
   It can appear on the developer's Practice profile or in conversation with Heph.
5. They act on it, push back with a reason, or let it pass. Their next contribution is read the same
   way.

The feedback is advisory: it does not approve a change for merge or grade anyone.

## Get started

- **Use it at TUM:** if your course or team has a workspace, sign in at [hephaestus.build](https://hephaestus.build).
- **Learn how it works:** read the [user guide](https://docs.hephaestus.build/user/overview).
- **Run your own deployment:** follow the [installation guide](https://docs.hephaestus.build/admin/install).
  It starts with the [supported hosts and capacity guidance](https://docs.hephaestus.build/admin/install#requirements).
  It ends when the first admin signs in.
  Before you upgrade, read the release notes and the [migration guide](./MIGRATION.md).
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

Hephaestus is an [MIT-licensed](./LICENSE) open-source project developed by
[Applied Education Technologies](https://aet.cit.tum.de/) at the
[Technical University of Munich](https://www.tum.de/en/).
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
