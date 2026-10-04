# Contribution Guidelines for Hephaestus

Read the [local development guide](https://docs.hephaestus.build/contributor/local-development) on how to set up your environment.

Repository tooling runs through Vite+ (`vp`).
The local development guide above gives the installation procedure.

## Maintenance Status

Hephaestus is a research project at TUM.
Development is active, but one person does most maintenance.
Maintainers triage issues and pull requests as time permits.
Security reports are the exception and get priority.
See [SECURITY.md](SECURITY.md) for how to report vulnerabilities privately.

## Identity and Transparency

We have different guidelines for members of our organization and external contributors.
These guidelines support transparency and trust.

### For Members of Our Organization

1. **Real Names Required**: Members must use their full real name in their GitHub profile for accountability.
2. **Profile Picture**: Members must upload an authentic, professional profile picture. Comic-style images or avatars are not permitted.
3. **Internal Workflow**: Members should create branches and pull requests directly within the repository.

### For External Contributors

1. **Identity Verification**: Contributions are only accepted from users with real names and authentic profile pictures.
2. **Forking**: Fork the repository and work on changes in your own branch.
3. **Pull Request**: Submit a PR from your fork. Make sure your branch is up to date with `main`.

### Signed Commits

A commit pushed to a branch in this repository must be signed.
Its commit email must resolve to the GitHub account that opened the pull request.
Before you commit, set `git config user.email` to an address verified on your account.
The repository refuses a push that carries an unsigned commit.

A pull request from a fork needs none of this.
GitHub creates and signs the commit that enters `main`.

Sign with the SSH key you already push with — [GitHub accepts an authentication key a second time as a signing key](https://docs.github.com/en/authentication/managing-commit-signature-verification/about-commit-signature-verification):

```bash
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
git config --global commit.gpgsign true
```

Then register that public key on GitHub as a signing key.
Use Settings → SSH and GPG keys → New SSH key, with the key type **Signing Key**.
Alternatively, run `gh ssh-key add ~/.ssh/id_ed25519.pub --type signing`.
Until you register the key as a signing key, signatures made with it stay unverified.
`vp install` warns if this is not configured.

If a push is refused, sign the commits your branch already carries and force-push:

```bash
git rebase --exec 'git commit --amend --no-edit -S' origin/main
```

### Compliance

A pull request title or body carries no model, agent, harness, or tool attribution.
`AGENTS.md` § Pull requests has the rule.
The `Verify commit identity` job in `pull-request.yml` checks the body for it.

A commit trailer is different.
`Co-authored-by:` is Git's own way to record who or what worked on a commit.
You can credit a tool there.
A human co-author is welcome under the same trailer.

Contributions that do not adhere to these guidelines will be rejected. We align with [GitHub Acceptable Use Policies](https://docs.github.com/en/site-policy/acceptable-use-policies).

## Issues

An issue ships something.
Its *Done when* names changes a reviewer can find in the repository: code, a test, a gate, or a document.
It never names only a number, measurement, or verdict.

A measurement is a step toward one of those bullets, or comes from a harness that the issue ships.
The run itself is an operations action, not an issue that waits for it.
Record that action where it occurs, such as a release plan.

"Verify" and "audit" are not outcomes.
Do the verification while you write the issue.
File its results as bullets, or make the check a gate that ships.
Research has the same requirement: the artifact in the tree is the outcome.
If one review unit cannot ship the work, create a follow-up issue linked with `Part of`.

The [issue forms](.github/ISSUE_TEMPLATE) carry the shape. In a pull request, `Fixes #n` means every
bullet of that issue is met.
`Part of #n` means the issue stays open and says what remains.

## Contribution Process

After a pull that changes the lockfile, run `vp install`.
The [local verification guide](https://docs.hephaestus.build/contributor/local-verification) explains its changes to your Git hooks.
Use `vp run check:affected` for fast feedback.
Before you push, run `vp run check`.
The hook runs it automatically.
Before you request review, run `vp run verify`.

The [local verification guide](https://docs.hephaestus.build/contributor/local-verification) defines the scope and exclusions.

1. **External contributors only**: Fork the Repository and create a branch.
2. **Create a feature branch**: Work on your changes in a separate branch.
3. **Follow pull request title guidelines**: Make sure your PR title follows the [Conventional Commits](https://www.conventionalcommits.org/) specification.
4. **Submit a pull request**: Once your work is complete, submit a pull request for review.

### Stacked Pull Requests

We encourage [stacked pull requests](https://docs.github.com/en/pull-requests/get-started/about-stacked-prs)
when a change has dependent steps that are easier to review separately. Each layer must be a coherent
review unit that builds, passes its applicable checks, and is safe to release before later layers. Use
one PR if separate layers would be artificial or incomplete.
Use separate PRs if the changes are independent.

Each layer follows the normal PR rules.
Include applicable tests and generated artifacts.
If the layer changes shipped code, add a changeset.
Base each PR on the layer below it, with the bottom PR based on `main`.
Merge reviewed, green layers from the bottom up.

The optional [`gh stack`](https://github.com/github/gh-stack) extension manages the branch and PR chain.
Follow GitHub's [stacked PR quickstart](https://docs.github.com/en/pull-requests/get-started/stacked-prs-quickstart).
This workflow is for branches in this repository.
If you work from a fork, use one PR or coordinate with a maintainer.

### Preview Deployments

To deploy a same-repository pull request, add the `preview` label.
Every push redeploys it without waiting for tests.
To remove the deployment, remove the label.
[Preview deployments](https://docs.hephaestus.build/contributor/ci-cd#preview-deployments).

## Pull Request Title Guidelines

PR titles follow the [Conventional Commits](https://www.conventionalcommits.org/) specification.
`commitlint.config.ts` defines the allowed types and scopes shared by CI and local commit hooks.

### Format

```text
<type>[optional scope]: <description>
```

### Releases and Changesets (Important)

**Release ≠ deploy.**
Every PR that changes shipped code carries a changeset.
This includes `server/`, `webapp/`, `docker/`, and `extension/`, except tests and in-tree docs.
`Verify changesets` enforces this.
Commit types never affect version changes.

`.changeset/README.md` has the format and the pre-1.0 rule.
The [release management guide](https://docs.hephaestus.build/contributor/release-management) has the procedure.
The [compatibility policy](https://docs.hephaestus.build/admin/compatibility-policy) defines what a version number promises.

### Allowed Types

- `fix`: A bug fix
- `feat`: A new feature
- `docs`: Documentation only changes
- `style`: Changes that do not affect the meaning of the code
- `refactor`: A code change that neither fixes a bug nor adds a feature
- `perf`: A code change that improves performance
- `test`: Adding missing tests or correcting existing tests
- `build`: Changes that affect the build system or external dependencies
- `ci`: Changes to our CI configuration files and scripts
- `chore`: Other changes that do not modify src or test files
- `revert`: Reverts a previous commit

### Recommended Scopes

**Service scopes** (where the code lives):

- `webapp`: React frontend
- `extension`: Chrome extension
- `server`: Java application server (includes the in-process Pi mentor agent and the webhook receiver)
- `docs`: Documentation

**Infrastructure scopes** (affect runtime):

- `deps`: Production dependencies (security patches, bug fixes)
- `security`: Security fixes are critical
- `db`: Database migrations affect runtime
- `docker`: Dockerfiles, production compose files

**Infrastructure scopes** (tooling and process):

- `ci`: CI/CD workflows
- `config`: Tooling configuration (renovate, oxfmt, oxlint, tsconfig, etc.)
- `deps-dev`: Dev dependencies only
- `scripts`: Helper scripts
- `release`: Release engineering (also used by the automated Version PR)

> ⚠️ **`config` scope warning:** Only use for tooling config files like `renovate.json`, `.oxfmtrc.json`, `webapp/.oxlintrc.json`. Do NOT use for:
> - Runtime config (`application.yml`) → use `server`
> - Dockerfiles → use service scope (`webapp`, `server`, etc.)
> - Production compose files → use `docker`

**Feature scopes** (domain-specific):

- `activity`: Activity and Workspace activity
- `auth`: Authentication / identity (Account, IdentityLink, JWT, oauth2Login)
- `integration`: Cross-cutting integration framework (webhook, oauth, registry, SPI)
- `scm`: Source-control management (GitHub, GitLab)
- `mentor`: AI mentor (Heph)
- `notifications`: Email/notification system
- `teams`: Team and membership management
- `workspace`: Workspace management

### Examples

**Valid pull request titles:**

- `fix(activity): keep the time range when switching workspaces`
- `feat(teams): show sub-teams under their parent`
- `feat(mentor): add conversation history`
- `feat(server): add an open-work endpoint`
- `docs: update installation instructions`
- `refactor(mentor): improve code analysis performance`
- `fix(deps): update vulnerable dependency`
- `fix(security): patch authentication bypass`
- `fix(db): add missing index for performance`
- `chore(deps-dev): update test dependencies`

**Draft Pull Requests:**

If your pull request is still in progress, open it as a **Draft Pull Request**.
This shows that the work is not yet ready for review.
Do not add `[WIP]` to the title.

### Guidelines

- Use lowercase for the description
- Do not end the description with a period
- Use the imperative mood in the description (e.g., "add" not "adds" or "added")
  - Think of it as completing the sentence: "If applied, this commit will ..."
  - ✅ "fix authentication bug" → "If applied, this commit will fix authentication bug"
  - ❌ "fixed authentication bug" or "fixes authentication bug"
- Keep the entire title to at most 100 characters.
  Commitlint rejects a longer one
