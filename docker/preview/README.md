# Preview deployment setup

The preview application runs one isolated stack per opted-in pull request. Anyone who can push to
this repository adds the `preview` label to their own pull request; the current head
deploys, and every push after that redeploys. A preview waits only for CI to publish that commit's
images, never for the test suite, so it exists even when the tests are red. Removing the label, closing the pull request, or converting it back to draft removes
the stack and frees its slot.

This directory is infrastructure for this repository's own pull requests. It is not part of a
self-hosted Hephaestus install — `docker/self-host/` is that. Contributors use previews through
[CI/CD → Preview Deployments](https://docs.hephaestus.build/contributor/ci-cd).

## Security boundary

Pull-request code is untrusted even when it comes from a branch in this repository. These constraints
hold, each enforced before Coolify is asked to deploy:

- Coolify receives a signed synthetic GitHub webhook, HMAC-verified against this application's
  manual webhook secret. Its stock PR handler queues the supplied full commit SHA; the public
  `/api/v1/deploy` endpoint is not used because it queues mutable `HEAD`. Coolify selects the
  application by the webhook's base ref, and the controller always sends the branch that application
  is configured for, so a pull request stacked on another branch still routes to it.
- Every service runs the image CI published for that exact commit — the same artifacts staging and
  production run, buildpack-built application server included. Each digest must carry a GitHub build
  attestation naming this repository's `reusable-docker-build.yml` as signer, checked before Coolify
  is asked to pull it. Nothing is built on the deployment host, so a preview cannot pass on a
  lookalike of the shipped image.
- The database is this preview's own, seeded from staging so it starts with real data. The clone is
  taken with `pg_dump` — staging's data volume is never mounted — and the preview policy runs before
  the application server is allowed to boot: every review trigger, agent binding and sweep schedule
  is disabled, queued jobs are cancelled, pending deliveries are failed, and the instance identity
  (`login_provider`, `jwt_signing_key`, `issued_jwt`) is dropped so the preview signs its own tokens.
  The seed loader verifies that against the database and refuses to mark the preview seeded
  otherwise, so a policy that silently did not apply leaves the preview un-booted rather than live.
- Staging's provider credentials do not survive the clone either: the instance and workspace AI
  catalog keys are cleared and those connections disabled, every integration connection loses its
  credential bundle and the webhook signing secret its config carries, and the job tokens staging
  minted for its own review jobs are replaced. The same verification covers all of them, so a
  preview that would hold something able to act as staging never boots.
- The application server reads staging's JetStream, so a preview sees the events a shared GitHub App
  delivers there. Its durable is named per deploy, so previews never compete for one consumer, and
  it expires 72h after the preview stops reading — a preview is deleted, not shut down, so it never
  removes its own.
- Agent execution, repository checkout, inbound webhooks and recurring sync stay disabled, which is
  what keeps a stack holding real data from acting on it.

No container here holds the Docker socket. A `:ro` socket mount restricts the file and not the API —
a container holding one can still create containers, and from there mount the host — so there is no
scoped way to hold it and nothing does. The seed loader reads staging over the network instead, as a
role that can only read.

Only the seed loader and the application server join staging's network, the first to reach its
database and the second its broker. The preview's own database and the SPA stay on Coolify's
per-preview network — the one it creates and attaches its proxy to. This file defines no network of its own:
Coolify runs every preview of this application under one Compose project named after the application
UUID, so a network defined here would be `<uuid>_backend` for all of them at once, private-looking
and shared. `staging-shared` is joined by name, which makes it a decision rather than a side effect.

PostgreSQL keeps a PR-specific volume; nothing else in the stack keeps state.

Deployment authority is deliberately the same as push authority;
[ADR 0035](../../docs/decisions/0035-pull-request-previews-are-label-gated.md) records why, and what
was declined. Operationally that means the gates decide what deploys, not a person: fork exclusion, a
trusted author association, and refusal for any head that introduces changes to `.github/workflows/**`, `.github/actions/**`, or this
directory. Any base branch in this repository is eligible, so a stacked layer previews the whole
stack up to that layer; only forks are excluded.

Coolify re-reads this directory's Compose file from the commit it is deploying, not from `main`, so a
pull request's own copy of it defines that pull request's stack. Two rules hold that line and neither
works alone: the controller refuses to deploy a head that introduces changes anywhere under
`docker/preview/` — compared against the default branch, so a stacked layer cannot inherit an edit
from the layer below — and `ci-compose-validate.yml` renders the file on every pull request and fails if
the stack gains a way out of its sandbox — a socket, a build stage, a published port, an unbounded
memory limit, a network every preview would share, or a flipped integration switch.
`scripts/check-preview-stack.ts` is the authoritative list; this paragraph is not.

## Host capacity

Memory and CPU ceilings are set per service in `compose.app.yaml`; budget roughly 3 GiB per slot.
The CPU ceilings deliberately oversubscribe a small host: a preview is idle almost all the time, and
a ceiling stops one stack taking the box rather than reserving capacity for it.

`PREVIEW_MAX_ACTIVE` caps concurrent previews and defaults to 3. It has to leave roughly 3 GiB of
memory per slot in whatever staging is not already using — check `free -g` on the host before raising
it, and move previews to their own destination rather than raising it much further. At the cap the
controller refuses the next preview and names the pull requests holding the slots. Deployments are
serialized through one Actions concurrency group, so previews never start at once.

## Coolify configuration

Create one Docker Compose application for `hephaestus-build/Hephaestus` on branch `main` and point it at
`/docker/preview/compose.app.yaml`.

1. Keep normal automatic deployment off. Disable the repository webhook that targets Coolify's
   manual endpoint. Enable preview deployments so the Actions controller's signed webhook may create
   and update PR records. This application's public/manual source is not selected by the normal
   GitHub App webhook.
2. Do not select **Deploy preview** in Coolify. That UI action bypasses attestation checking and the
   admission limit.
3. Give the webapp the wildcard domain `https://pr<id>.<preview zone>`, and give the appserver
   **the same host with the `/api` path**, `https://pr<id>.<preview zone>/api`. Coolify routes that
   path to the application server and, with **Strip Prefix** left enabled (its default), removes the
   prefix before the container sees it — the shape production serves. Do not give the appserver a
   hostname of its own: the session and CSRF cookies are `__Host-` prefixed, so they bind to one
   host, and a page on a sibling hostname cannot read the CSRF cookie it must send back, which
   refuses sign-out and every other write. The web hostname must match the
   `COOLIFY_PREVIEW_URL_TEMPLATE` repository variable below — that variable is the single place the
   zone is written down.

   Two things do not follow the compose file and must be changed with it:

   - **Domains already stored for an existing preview.** Coolify keeps each preview's domains in its
     own record, so a preview created under the old sibling-hostname layout keeps sending `/api`
     requests to the webapp until its record is updated or the preview is torn down and re-created
     through the label.
   - **Any configured GitHub OAuth app.** Its callback becomes
     `https://pr<id>.<preview zone>/api/login/oauth2/code/<registrationId>`. Moving the proxy does
     not update that registration, and sign-in fails until it does.
4. Set every value in `.env.example`. Compose rejects a blank required value before any container
   starts. Generate new database, state-cookie, encryption, and webhook secrets for this preview;
   the broker and seed-role credentials must match staging. A GitHub OAuth app is optional, but if
   configured it must be preview-only.
5. Leave `SOURCE_COMMIT`, `COOLIFY_BRANCH`, and the `SERVICE_*` variables to Coolify. Define nothing
   on this application beyond `.env.example`; anything else does not belong here.
6. Disable **Connect to predefined network**. Coolify attaches its proxy to each generated preview
   network; the services do not need the shared `coolify` network. The one network this stack joins,
   staging's `shared-network`, it names itself.
7. Deploy onto the host running staging: the stack reaches staging's database and broker over its
   `shared-network`, which is local to that host. A seed source that is unreachable fails the
   preview rather than quietly starting it on an empty database.
8. Create the seeding role once, on staging's PostgreSQL, and put its password in
   `PREVIEW_SEED_SOURCE_PASSWORD`. `pg_read_all_data` is the whole grant — the role can read every
   table `pg_dump` needs and write nothing:

   ```sql
   CREATE ROLE preview_seed LOGIN PASSWORD '<generated>';
   ALTER ROLE preview_seed WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 4;
   GRANT pg_read_all_data TO preview_seed;
   GRANT CONNECT ON DATABASE hephaestus TO preview_seed;
   ```

   Rotating it is one `ALTER ROLE … PASSWORD` and one Coolify variable. Between the two, seeding
   fails and previews stay down rather than coming up on an empty database.

## GitHub configuration

Create a repository label named exactly `preview`. It is the opt-in switch, so give it a description
that says so.

Set these repository variables:

| Variable | Purpose |
|---|---|
| `COOLIFY_URL` | Coolify HTTPS origin |
| `COOLIFY_APP_UUID` | Preview application UUID |
| `COOLIFY_PREVIEW_URL_TEMPLATE` | Public web URL containing `{pr}` |
| `PREVIEW_MAX_ACTIVE` | Concurrent preview limit (optional; defaults to 3) |

Set two repository secrets:

| Secret | Scope |
|---|---|
| `COOLIFY_PREVIEW_WEBHOOK_SECRET` | Must equal this application's Coolify **Manual Webhook Secret (GitHub)** |
| `COOLIFY_PREVIEW_READ_TOKEN` | Coolify API token with the `read` ability |

The controller needs no Coolify mutation token: the HMAC-authenticated webhook queues deployments and
requests preview cleanup, and the read token only follows deployment status.

### Rotating them

Each rotation fails closed, so a half-finished one blocks previews rather than weakening them.

- **`COOLIFY_PREVIEW_WEBHOOK_SECRET`** — set the new value in Coolify's **Manual Webhook Secret
  (GitHub)** and in the repository secret. Between the two, deploys and teardowns are refused by
  Coolify's signature check; the nightly reconcile retries teardown once both agree.
- **`COOLIFY_PREVIEW_READ_TOKEN`** — issue the new `read` token first, update the secret, then revoke
  the old one. A deploy in flight reports "cannot read deployment status" and the next push retries.

Leaving these unset disables previews. Every preview workflow is guarded on them, so nothing runs and
nothing fails.

## Teardown

Closing a pull request, merging it, dropping the `preview` label or converting the pull request to a
draft sends Coolify a signed close event, and the GitHub deployment is then marked inactive so the
slot is free. Coolify queues that teardown and answers immediately, so the workflow reports that the
request was accepted, not that the containers are gone.

Nothing here checks the host. That is a deliberate scope choice: verifying teardown would mean a
standing credential on the deployment host. If leaks turn up, the nightly `Preview reconcile`
workflow is where that check belongs — it already walks every retained preview and re-sends the close
event.

Preview images are pulled per commit and are never removed by this system.
