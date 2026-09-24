# Scripts

The Node.js version pinned in `package.json#devEngines.runtime` executes these TypeScript utilities
using native type stripping.

Most scripts require `vp install` at the repository root; Jean setup performs it.

## Available Scripts

### Repository orchestration

Infrastructure tests use `node:test`; Vitest is reserved for the Vite-managed webapp and docs trees.
Anything here that runs `git` takes its environment from `lib/git-environment.ts`, because the
pre-push hook hands whatever it starts the repository being pushed.

Substantive developer orchestration under `scripts/` uses typed Node.js entry points:

| Command | Purpose |
| --- | --- |
| `vp run check:ports` | Validate configured local ports and report listeners. |
| `vp run db:draft-changelog` | Rebuild a disposable database and generate a Liquibase diff. |
| `vp run db:generate-erd-docs` | Apply migrations and regenerate the Mermaid ERD. |
| `vp run dev:e2e:setup <options>` | Configure a local E2E workspace against the selected SCM and model provider. Secrets are environment-only. |
| `vp run dev:public-test <command>` | Manage the machine-local public test route. |

The database commands require Docker with the Compose plugin.

`dev:public-test` accepts `start`, `stop`, `status`, `smoke`, and `seed-status`. The route is
internet-facing and requires Docker, the machine's Coolify network and Traefik configuration
directory, and `server/.env`.

Jean runs `node "$JEAN_ROOT_PATH/scripts/jean-setup.ts"` from a new worktree to copy machine-local
configuration and install dependencies. It is not a general contributor command.

**ERD generation environment variables:**

| Variable            | Default      | Description         |
| ------------------- | ------------ | ------------------- |
| `POSTGRES_HOST`     | `localhost`  | PostgreSQL host     |
| `POSTGRES_PORT`     | `5432`       | PostgreSQL port     |
| `POSTGRES_DB`       | `hephaestus` | PostgreSQL database |
| `POSTGRES_USER`     | `root`       | PostgreSQL user     |
| `POSTGRES_PASSWORD` | `root`       | PostgreSQL password |

### NATS Webhook Example Extraction

Extract webhook payloads from NATS JetStream for test fixtures:

```bash
vp run schema:nats
# With options:
vp run schema:nats --event push --event pull_request:opened
```

**Environment variables:**

| Variable   | Default                 | Description     |
| ---------- | ----------------------- | --------------- |
| `NATS_URL` | `nats://localhost:4222` | NATS server URL |

**Common options:**

| Option                    | Description                         |
| ------------------------- | ----------------------------------- |
| `--nats-server <url>`     | NATS server URL                     |
| `--examples-dir <path>`   | Output directory                    |
| `--event <type[:action]>` | Filter by event type (repeatable)   |
| `--since <iso>`           | Only messages after this timestamp  |
| `--until <iso>`           | Only messages before this timestamp |
| `--dry-run`               | Validate config without extracting  |

## Retained shell boundaries

The retained shell files run where shell is already part of the runtime:

- `.vite-hooks/commit-msg` and `.vite-hooks/pre-push` are the project-owned Git hooks the Vite+ dispatcher runs.
- `docker/self-host/setup.sh` bootstraps an operator installation before the repository toolchain is available; its test
  orchestration is typed TypeScript under `scripts/`.
- `webapp/docker/entrypoint.sh` prepares assets in the final nginx image, which does not contain Node.

No substantive shell script is permitted under `scripts/`.
