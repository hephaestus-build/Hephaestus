# Scripts

The Node.js version pinned in `package.json#devEngines.runtime` executes these TypeScript utilities
using native type stripping.

Most scripts require `vp install` at the repository root.

## Available Scripts

### Repository orchestration

Infrastructure tests use `node:test`; Vitest is reserved for the Vite-managed webapp, docs and extension trees.
Anything here that runs `git` takes its environment from `lib/git-environment.ts`, because the
pre-push hook hands whatever it starts the repository being pushed.

Substantive developer orchestration under `scripts/` uses typed Node.js entry points:

| Command | Purpose |
| --- | --- |
| `vp run check:ports` | Validate configured local ports and report listeners. |
| `vp run db:draft-changelog` | Rebuild a disposable database and generate a Liquibase diff. |
| `vp run db:generate-erd-docs` | Apply migrations and regenerate the Mermaid ERD. |
| `vp run dev:e2e:setup <options>` | Configure a local E2E workspace against the selected SCM and model provider. Secrets are environment-only. |

The database commands require Docker with the Compose plugin.

**ERD generation environment variables:**

| Variable            | Default      | Description         |
| ------------------- | ------------ | ------------------- |
| `POSTGRES_HOST`     | `localhost`  | PostgreSQL host     |
| `POSTGRES_PORT`     | `5432`       | PostgreSQL port     |
| `POSTGRES_DB`       | `hephaestus` | PostgreSQL database |
| `POSTGRES_USER`     | `root`       | PostgreSQL user     |
| `POSTGRES_PASSWORD` | none         | PostgreSQL password |

## Retained shell boundaries

The retained shell files run where shell is already part of the runtime:

- `.vite-hooks/commit-msg` and `.vite-hooks/pre-push` are the Git hooks, and `.vite-hooks/run` starts them with the worktree's own `vp`.
- `deploy/platform/k3s-install.sh` installs an operator deployment before the repository toolchain is available; its test
  orchestration is typed TypeScript under `scripts/`.
- `webapp/docker/entrypoint.sh` prepares assets in the final nginx image, which does not contain Node.

No substantive shell script is permitted under `scripts/`.
