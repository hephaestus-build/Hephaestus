# Contribution Guidelines for Hephaestus

Read the [local development guide](https://docs.hephaestus.build/contributor/local-development) on how to set up your environment.

Repository tooling runs through Vite+ (`vp`); the local development guide above has the install.

## Maintenance Status

Hephaestus is a research project at TUM, actively developed but maintained primarily by one person. Issues and pull requests are triaged on a best-effort basis. Security reports are the exception and get priority — see [SECURITY.md](SECURITY.md) for how to report vulnerabilities privately.

## Identity and Transparency

To ensure a transparent and trustworthy environment, we have established different guidelines for members of our organization and external contributors.

### For Members of Our Organization

1. **Real Names Required**: Members must use their full real name in their GitHub profile to ensure accountability.
2. **Profile Picture**: Members must upload an authentic, professional profile picture. Comic-style images or avatars are not permitted.
3. **Internal Workflow**: Members should create branches and pull requests directly within the repository.

### For External Contributors

1. **Identity Verification**: Contributions are only accepted from users with real names and authentic profile pictures.
2. **Forking**: Fork the repository and work on changes in your own branch.
3. **Pull Request**: Submit a PR from your fork. Ensure your branch is up to date with `main`.

### Signed Commits

A commit pushed to a branch in this repository is signed, and its commit email resolves to the GitHub account that opened the pull request, so set `git config user.email` to an address verified on your account before you commit; a push carrying an unsigned commit is refused. A pull request from a fork needs none of this — the commit that lands on `main` is created and signed by GitHub.

Sign with the SSH key you already push with — [GitHub accepts an authentication key a second time as a signing key](https://docs.github.com/en/authentication/managing-commit-signature-verification/about-commit-signature-verification):

```bash
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
git config --global commit.gpgsign true
```

Then register that public key on GitHub under Settings → SSH and GPG keys → New SSH key with the key type **Signing Key**, or run `gh ssh-key add ~/.ssh/id_ed25519.pub --type signing`. Until it is registered as a signing key, signatures made with it stay unverified. `vp install` warns when this is not configured.

If a push is refused, sign the commits your branch already carries and force-push:

```bash
git rebase --exec 'git commit --amend --no-edit -S' origin/main
```

### Compliance

A commit message and a pull request body carry no model, agent, harness or tool attribution — `AGENTS.md` § Pull requests has the rule and `pull-request.yml`'s `Verify commit identity` job checks for it. A human co-author stays welcome under the same `Co-authored-by:` trailer.

Contributions that do not adhere to these guidelines will be rejected. We align with [GitHub Acceptable Use Policies](https://docs.github.com/en/site-policy/acceptable-use-policies).

## Issues

An issue ships something. Its *Done when* names changes a reviewer can find in the repository — code,
a test, a gate, a document — never a number, a measurement or a verdict on its own. A measurement is a
step toward one of those bullets, or it comes out of a harness the issue ships; the run itself is an
operations action recorded where it happens, such as a release plan, not an issue that waits for it.
"Verify" and "audit" are not outcomes: do the verification while writing the issue and file what it
found as bullets, or make the check a gate that ships. Research is held to the same bar — the artifact
in the tree is the outcome. What one review unit cannot ship becomes a follow-up issue linked with
`Part of`.

The [issue forms](.github/ISSUE_TEMPLATE) carry the shape. In a pull request, `Fixes #n` means every
bullet of that issue is met; `Part of #n` means the issue stays open and says what remains.

## Contribution Process

Run `vp install` after a pull that changes the lockfile; the [local verification guide](https://docs.hephaestus.build/contributor/local-verification) has what it does to your Git hooks. Use
`vp run check:affected` for fast feedback. Before pushing, run `vp run check`; the hook runs it
automatically. Run `vp run verify` before requesting review. Scope and exclusions are documented in the
[local verification guide](https://docs.hephaestus.build/contributor/local-verification).

1. **External contributors only**: Fork the Repository and create a branch.
2. **Create a feature branch**: Work on your changes in a separate branch.
3. **Follow pull request title guidelines**: Ensure your PR title follows the [Conventional Commits](https://www.conventionalcommits.org/) specification.
4. **Submit a pull request**: Once your work is complete, submit a pull request for review.

### Stacked Pull Requests

We encourage [stacked pull requests](https://docs.github.com/en/pull-requests/get-started/about-stacked-prs)
when a change has dependent steps that are easier to review separately. Each layer must be a coherent
review unit that builds, passes its applicable checks, and is safe to release before later layers. Use
one PR when splitting would create artificial or incomplete layers, and separate PRs when the changes
are independent.

Each layer follows the normal PR rules: include applicable tests and generated artefacts, and add a
changeset when it changes shipped code. Base each PR on the layer below it, with the bottom PR based on
`main`, and merge reviewed, green layers from the bottom up.

The optional [`gh stack`](https://github.com/github/gh-stack) extension manages the branch and PR chain;
follow GitHub's [stacked PR quickstart](https://docs.github.com/en/pull-requests/get-started/stacked-prs-quickstart).
This workflow is for branches in this repository; contributors working from forks should use one PR or
coordinate with a maintainer.

### Preview Deployments

Add the `preview` label to a same-repository pull request to deploy it; every push redeploys, tests
are not awaited, and removing the label tears it down.
[Preview deployments](https://docs.hephaestus.build/contributor/ci-cd#preview-deployments).

## Pull Request Title Guidelines

PR titles follow the [Conventional Commits](https://www.conventionalcommits.org/) specification.
`commitlint.config.ts` defines the allowed types and scopes shared by CI and local commit hooks.

### Format

```text
<type>[optional scope]: <description>
```

### Releases and Changesets (Important)

**Release ≠ deploy.** Every PR that changes shipped code (`server/`, `webapp/`, `docker/`, excluding
tests and in-tree docs) carries a changeset; `Verify changesets` enforces it, and commit types never
affect versioning. `.changeset/README.md` has the format and the pre-1.0 rule; the
[release management guide](https://docs.hephaestus.build/contributor/release-management) has the flow
and the [compatibility policy](https://docs.hephaestus.build/admin/compatibility-policy) what a
version number promises.

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
- `chore`: Other changes that don't modify src or test files
- `revert`: Reverts a previous commit

### Recommended Scopes

**Service scopes** (where the code lives):

- `webapp`: React frontend
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

- `auth`: Authentication / identity (Account, IdentityLink, JWT, oauth2Login)
- `integration`: Cross-cutting integration framework (webhook, oauth, registry, SPI)
- `scm`: Source-control management (GitHub, GitLab)
- `leaderboard`: Leaderboard and rankings
- `mentor`: AI mentor (Heph)
- `notifications`: Email/notification system
- `profile`: User profiles
- `teams`: Team and membership management
- `workspace`: Workspace management

### Examples

**Valid pull request titles:**

- `fix(profile): correct avatar upload logic`
- `feat(leaderboard): add sorting functionality`
- `feat(mentor): add conversation history`
- `feat(server): add user profile endpoint`
- `docs: update installation instructions`
- `refactor(mentor): improve code analysis performance`
- `fix(deps): update vulnerable dependency`
- `fix(security): patch authentication bypass`
- `fix(db): add missing index for performance`
- `chore(deps-dev): update test dependencies`

**Draft Pull Requests:**

If your pull request is still in progress, please open it as a **Draft Pull Request**. This signals that the work is not yet ready for review without cluttering the title with `[WIP]`.

### Guidelines

- Use lowercase for the description
- Don't end the description with a period
- Use the imperative mood in the description (e.g., "add" not "adds" or "added")
  - Think of it as completing the sentence: "If applied, this commit will ..."
  - ✅ "fix authentication bug" → "If applied, this commit will fix authentication bug"
  - ❌ "fixed authentication bug" or "fixes authentication bug"
- Keep the entire title to at most 100 characters; commitlint rejects a longer one
