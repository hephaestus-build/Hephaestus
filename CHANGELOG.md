# Changelog

## 0.77.0

### Minor Changes

- Staging now follows the default branch instead of waiting for a release, so a merge reaches it in
  minutes rather than sitting undeployed until someone cuts a version. Its channel names the commit
  and the images to run, each pinned by digest and each required to carry this repository's build
  provenance, so nothing runs there that a release would not have been allowed to run.

  Releases are unchanged and remain how production is promoted. A release no longer promotes staging:
  it re-tags the images of a commit staging has already been running, so there is nothing left to
  rehearse. To hold an environment on what it has, freeze its channel.

- Both audit viewers now show when an instance admin acted in a workspace they are not a member of. Instance admins have always been able to open any active workspace with admin rights without joining it; that access was simply invisible on the audit trails. Each such access window is now recorded as a "Workspace reached as instance admin" event in the sign-in audit log, and every settings change made that way is marked "Elevated" in the settings audit log — in the instance-wide console and in each workspace's own, so a workspace admin can see it too. The CSV export of the sign-in audit log gains a final `elevated_via_instance_admin` column; it is appended after the existing columns, so a spreadsheet or script that reads the export by column position keeps working. Events recorded before this release are unmarked, which means "no elevation recorded" rather than "the person was a member".
- Deleting your account now also deletes the product feedback you sent and the survey answers or dismissals you gave. They used to survive account deletion: the deleted account is kept as an empty placeholder, so the database cleanup that should have removed them never ran.

  **Operators:** the upgrade permanently deletes the product-feedback submissions and survey answers of accounts that were already deleted. It runs once, as part of the database migration, and cannot be undone — export them or take a database backup first if you need to keep them.

- Issue practice reviews now react to a title, description, label, assignment, milestone or reopen change on GitHub and GitLab, and to a native issue type change on GitHub — where before only a new label did. Closing an issue again after reopening it is now reviewed again too, instead of only the first close being. Practices already bound to the labelled occasion move to the wider one automatically on upgrade, keeping their customisations. Expect issue reviews to run more often: each round of triage that changes what a practice can read occasions its own review, there is no delay that batches a burst of edits into one, and the first backfill campaign after the upgrade reviews each already-closed issue once more. Locking an issue, pinning it, or editing a due date, weight or time estimate occasions nothing, and a replayed event or a return to an issue state already reviewed no longer starts another review.
- Docker sandbox settings now live under `hephaestus.sandbox.docker.*`. Interactive sessions use the configured Docker connection rather than an inherited Docker context.

  **Operators:** Rename custom Spring property overrides and replace `SANDBOX_TLS_VERIFY` and `SANDBOX_CONTAINER_RUNTIME` with `SANDBOX_DOCKER_TLS_VERIFY` and `SANDBOX_DOCKER_CONTAINER_RUNTIME`. TLS verification requires an explicit certificate directory. Removed names have no aliases: a worker-role process refuses to start while any of them is still set. Follow the migration guide before upgrading. The Docker host environment variable and gateway port are unchanged.

### Patch Changes

- A practice review that succeeds no longer logs a validation failure. Every review that recorded
  observations wrote one, so the first line an operator read when a review looked wrong pointed away
  from the real problem.
- A worker no longer claims practice reviews its sandbox cannot start. When a host allows fewer
  containers than the worker's review capacity, the worker could pick up a review, fail to start it and
  put it back, logging a warning each time; a queued review waited behind that churn instead of running.
  A claim interrupted by a database error no longer retires one of that worker's review slots until
  the next restart.
- The container that runs a practice review no longer carries a package manager, so nothing in it can
  install software while a review is running.
- An observation on your practice page now names what the review saw. A summary of a single word, such
  as "Test", sat above the practice's own name and told you nothing about the work; a short phrase is
  recorded there instead.
- The workspace wizard no longer offers GitHub App setup when its installation URL is blank.
- Serving an instance on more than one hostname is now entirely the reverse proxy's job: the extra
  names, their shared certificate, and the redirect that sends a browser back to `APP_HOSTNAME` are all
  configured in one place. An instance that names a single hostname routes and serves exactly as
  before, and one fronted by a proxy that does not read this stack's routing configuration answers on
  whatever names that proxy sends it — configure the extra names and their redirect there instead.
- Signing in for the first time works again. A new account was shown "Something went wrong" instead of
  the transparency notice: the server withholds everything until the notice is answered, and the page
  you land on after signing in asked for your workspaces before asking for the notice. That page now
  sends you to the notice like every other page already did, and the address bar stays on the page you
  were opening, so the notice reads as that page pausing rather than a detour.

  The notice itself is easier to answer: a short summary you can scroll, the required acceptance and
  the optional research choice clearly separated, and a way to sign out if you would rather not accept.
  The sign-in page is shorter, while still saying — before you sign in — that doing so shares your
  provider identity.

- A host that follows the default branch no longer restarts its database on every apply. The
  PostgreSQL image is rebuilt for every commit, so each apply used to bring the database container
  back up under a new image, dropping every connection for minutes, failing the practice reviews in
  flight and answering 503 meanwhile. The host now keeps the PostgreSQL image it runs until a commit
  changes what that image is built from; a release still applies exactly the images it was signed
  with.
- Practice-review diff summaries no longer misdescribe a changed file whose path contains unusual characters or a quoted rename.
- Issue metadata edits now wait briefly for triage to settle before starting a practice review, instead of spending a review on every intermediate snapshot. Pending updates survive server restarts, duplicate deliveries do not extend the delay, and queued reviews refuse metadata that changed after admission. Review activity explains coalesced updates, and operators can count deferred and duplicate occasions.

  Previously queued issue-update reviews without a recorded admission revision stop safely rather than guessing which metadata they were meant to review. A manual review can be requested if needed.

  A review that was rolled back is no longer written to the logs as if it had started.

- Restores the timeouts the bundled edge applies to the application server, so a backend that accepts a connection but never answers releases the browser with an error page instead of leaving requests and mentor chat streams hanging indefinitely. A host that keeps itself on its channel now starts the stacks in the order the migration requires, converges again when you promote the release it already runs, and no longer refuses to retry after a partly-applied deploy. Rolling back to a release published before GitHub offered immutable tags works again.
- An integration whose stored token cannot be read now tells a workspace administrator to store a replacement personal access token, instead of naming an API surface the console has no form for.

  The credential key rotation runbook's statement for clearing a quarantined GitHub App credential now matches the key version and quarantine time you listed, so it cannot null out a credential that was reconnected between the listing and the statement.

- The TUM-operated instance is now addressed as `hephaestus.build`. The imprint, the privacy
  statement, the in-app footer links, `security.txt` and the documentation all name it, and the older
  `hephaestus.aet.cit.tum.de` address continues to reach the same instance. Source links follow the
  repository to the `hephaestus-build` organisation; images and signatures for releases cut before the
  move keep their original namespace and signing identity, so verifying an older release is unchanged.
- Live GitHub and GitLab events no longer start practice reviews while the work is marked as deleted upstream. The occasion stays visible as pending and can be retried if an ordinary sync restores the work, within the existing retry deadline.
- Once a host that pulls its own releases applies a release that carries this change, it runs that
  release's deployment tooling and keeps its two systemd units matching it, so applying a release
  also brings the tooling forward — a host whose tooling had fallen behind previously failed every run
  until an operator logged in and updated it by hand. The tooling an operator installs or upgrades by
  hand, and releases older than this change, are the exceptions: the host keeps the tooling it has
  while running one. A host installed before this release needs the one-time upgrade steps in the
  pull-based deployment guide.
- An instance now sends `Strict-Transport-Security` whatever proxy sits in front of it. The header was
  attached to the reverse proxy shipped with Hephaestus, so a deployment fronted by a different proxy —
  a PaaS, or an existing ingress — served without it, and the omission was easy to miss because every
  other security header comes from the responses themselves. Nothing changes for a deployment that uses
  the bundled proxy.
- Removing a label from an issue or pull request no longer fails the sync that noticed it. Workspace
  isolation had rejected the statement that unlinks a label, so a repository whose labels changed
  upstream stopped following them.
- A developer's projected league change loads even when an account outside your workspace uses the
  same login on another provider. The league looks at the developer who is a member of your
  workspace, not at whoever else shares their login.
- A login-provider change that the database refuses no longer appears in the audit viewer as if it had
  succeeded. Moving a provider onto a base URL another provider already uses is rejected, and the
  rejection now happens before the change is recorded, so the trail matches what the instance actually
  has configured.
- The transparency notice now interrupts the page you were opening instead of taking you somewhere
  else: the address bar keeps the page you asked for while you answer, and you land back on it. And if
  the notice itself cannot be loaded, you keep working on the page rather than meeting an error screen
  — the server still withholds anything that needs your answer.
- Practice reviews can search the reviewed work again. The search their sandbox offered relied on a
  program the sandbox does not allow to run, so every search failed and observers could only read
  files one at a time.
- Practice reviews and Heph conversations complete again on the current agent image. The previous fix
  let a review start, but every model turn still failed inside its sandbox before any practice was
  evaluated.
- Practice reviews and Heph conversations complete instead of stopping at their first tool call.
- A repository checkout whose remote configuration became unusable is rebuilt on the next sync; a repository that no longer exists upstream is reported, not rebuilt.
- A practice review whose results cannot be read back is now reported as failed instead of as a review
  that found nothing. Results are capped at 50 MiB in total, 10 MiB per file and 10,000 files, and a
  result file's name is limited to 100 characters including its folder; a review that passes a cap, or
  whose results come back damaged, fails rather than reporting a partial answer. A review interrupted
  by the container host while its results were being read is retried, since that one can succeed on a
  second attempt.

  A symbolic link inside a repository under review is no longer followed when the work is handed to the
  reviewing container, so a link is left out rather than pulling in whatever it points at.

- Pending practice reviews no longer run after reconciliation marks their issue, pull request, or merge request as deleted upstream. They resume automatically if a later sync finds the work again.
- A hardcoded credential committed in a package named `example` or `sample` — the placeholder package
  most JVM projects are generated with — is no longer read as sample code. Those words counted as a
  directory of samples wherever they sat in a path, so a credential in ordinary application code was
  reduced to a minor suggestion, and a review with other suggestions to make could leave it out of the
  comment entirely. A credential in an `examples/` or `samples/` directory is still a minor suggestion.
- The self-host setup script no longer generates an encryption key on a host that already has a
  Hephaestus database, and stops rather than guess when Docker cannot say whether one exists. A
  generated key made every stored integration credential unreadable without warning; setup now names
  the key to carry over, and the pull-based deployment guide says which settings a host keeps when it
  moves.
- Writing a personal access token to a workspace whose GitHub connection is an App installation, or reading a credential the server's current keys cannot decrypt, now answers with the conflict that names the connection's state instead of an internal server error.
- Practice reviews no longer treat missing or upstream-deleted issues, pull requests, merge requests and their discussions as empty work: the review stops with the work reported unavailable instead of producing feedback about a change nobody can open. New feedback checks that its reviewed work is still available before it is delivered, on the work itself, on the practice page and in conversation, including feedback prepared for a conversation in advance. Previously delivered feedback remains in your history, subject to access and privacy controls.
- A stored integration credential that none of the server's configured encryption keys can read is
  now shown for what it is. When the key version itself is missing from the server's configuration,
  the request fails as a configuration fault instead, as the operator guide explains. The workspace's integration pages say the stored token cannot be read and what to
  do, and a request that needs the credential answers with that same explanation instead of a generic
  server error. Replacing the credential clears it.
- A workspace page no longer fails when one of its stored integration credentials cannot be read with
  the running encryption key: the page reports whether a token is stored without decrypting it. A
  developer's profile and their projected league change load again; both had stopped with an error
  after workspace isolation started rejecting their queries.

## 0.76.0

### Minor Changes

- Operators can now alert on privacy-job outcomes: account erasure, export generation, export expiry and LLM usage retention each publish success, failure and affected-row counters. A retention pass that runs out of its time budget with rows still expired reports `incomplete` rather than success, so a sweep that never catches up is visible.

  LLM usage accounting is no longer kept indefinitely — rows become eligible for deletion 400 days after they were recorded by default, and a daily sweep removes them in batches. The public privacy statement and the record of processing document the window.

  **Operators:** the first sweep after upgrade begins deleting usage rows older than the window, and further sweeps continue until the backlog is gone. If your accounting obligations require longer, set `HEPHAESTUS_LLM_USAGE_RETENTION` before upgrading.

- Hosts can now keep themselves on a release instead of being deployed to. A host polls a channel naming the release it should run, verifies the release signature and the channel's own signature itself, and applies the digest-pinned stacks — so no deployment credential, and no way in to the machine, has to exist anywhere else. A failed upgrade stops and reports failure for host monitoring rather than rolling back, because schema changes only ever move forward.

  Nothing changes until you install the units in `docker/self-host/systemd/`. If you do, set up the staleness alert the guide describes before relying on it: a host that stops updating is quiet, and that alert is what makes it loud.

- An instance can now answer on more than one hostname. Set `APP_HOST_MATCH` to a matcher naming
  **every** hostname it should answer on, `APP_HOSTNAME` included — the matcher replaces the default
  rather than adding to it, so a hostname left out of it stops being served and drops out of the
  certificate. All the names in it are served and covered by the same certificate, which is what makes
  a move to a new domain possible without the old one going dark:

  ```
  APP_HOST_MATCH=Host(`new.example.com`) || Host(`old.example.com`)
  ```

  `APP_HOSTNAME` stays the instance's single origin: the SPA, the API and the auth issuer are all
  configured for it, so a browser arriving on any other name is redirected there and the app is only
  ever loaded from one host. The OAuth callback URLs stay on `APP_HOSTNAME` alone. An instance with a
  single hostname needs no matcher and behaves exactly as before.

- **Operators:** Worker sandboxes now reach a dedicated sandbox gateway on `SANDBOX_API_PORT` (default `8081`), which serves only the sandbox capabilities and answers every other path with an empty `404`. Worker containers bind their HTTP and management endpoints to loopback, so sandboxes reach them only through the gateway. `SANDBOX_API_REQUESTS_PER_MINUTE` caps each authenticated sandbox and `SANDBOX_API_MAX_REQUEST_BYTES` caps the request bodies it accepts.

### Patch Changes

- Credential key rotation now quarantines and reports undecryptable integration credentials while continuing to rotate healthy connections. The rotation guide explains how operators identify, recover, or replace affected credentials.
- Heph no longer uses issues or pull requests that were deleted upstream, and asking for a practice review of deleted work now says so instead of starting one. Leaderboard scores, profile counts and the practice feedback you already received are unchanged: they are a record of what happened, not of the work's current state.
- Pull-request previews start again. The application refuses to talk to a database it cannot recognise
  as local over an unencrypted connection, and a preview's database answers to a per-pull-request name
  rather than the one on that list, so every preview's application server failed to start and retried
  until the deployment was reported as failed. Previews now declare that their database is a sibling
  container on the deployment's own private network, which is the trust every other stack already has.
- Switching workspaces no longer carries a page that belongs to the workspace you are leaving — a conversation, a person or a practice opens the new workspace's home page instead, with a note explaining why. A page option that names something in the workspace you are leaving, such as a team filter, is cleared. A link to a workspace you can no longer access opens a workspace you can. Hephaestus opens your first workspace when you sign in rather than the one you last used.
- The application and webhook stacks now carry their own request-body limits, so they work with an existing Traefik edge without depending on the bundled proxy stack. Required deployment secrets are now rejected during stack rendering instead of after containers start.

## 0.75.2

### Patch Changes

- The single-host Compose topology now persists each webhook and integration-sync event before the
  broker acknowledges it, closing the periodic-sync loss window during an unclean shutdown. This
  trades some ingest throughput and latency for stronger durability; no operator action is required.
- Practice reviews now recognize native issue types as triage metadata instead of asking for a
  duplicate type label.
- Workspaces can again adopt, create, and edit practices. Existing practices keep working, and failed writes did not lose or partially save data.

## 0.75.1

### Patch Changes

- Dependencies in the agent image that runs practice reviews could be less than three days old,
  escaping the release-age floor every other Hephaestus dependency is held to. The image now applies
  that floor and verifies it while it builds.
- The reference multi-host stack now deploys instead of stopping with `service "webhook-server"
depends on undefined service "postgres": invalid compose project`. That deployment brings the proxy,
  core and app stacks up as three separate Compose projects, and the core stack had been asking Compose
  to start the webhook receiver after a database that belongs to the app stack — an order Compose
  cannot honour across projects, and one that made the core stack refuse to render at all. The receiver
  reaches the database over the shared network as it always did, and retries until it answers. The
  single-host install is unaffected: it runs everything as one project and still starts the receiver
  after the database. Nothing to set on either — deploy or upgrade as usual.
- A fresh self-host install now starts instead of crash-looping with
  `hephaestus.security.prior-credential-encryption-key and prior-credential-encryption-key-version must
be configured together`. The stack passes both credential-rotation variables through empty when you
  have not set them, and Hephaestus was reading one empty value as a half-finished key rotation; an
  empty value now means what you meant by it — not configured. Finishing a rotation by clearing the
  prior key and its version works the same way. Setting only one of the two is still refused at
  startup, so a rotation cannot half-apply and leave stored credentials unreadable.
- A fresh self-host install now starts instead of stopping with `required variable NATS_USERNAME is
missing a value`: `setup.sh` generates the message-broker credentials alongside the database password
  and the other internal secrets. Upgrading an existing installation picks them up by rerunning
  `docker/self-host/setup.sh`, which leaves every value you already set untouched.
- The webhook receiver in a self-host install now starts instead of crash-looping with
  `hephaestus.agent.image.reference must be digest-pinned`, so incoming pull request, issue and chat
  events are received again. The stack was handing the verified release lock's agent image digest to
  the API server and the worker but not to the receiver, which fell back to naming that image by tag —
  and Hephaestus refuses a tag there, because the tag can move to an image built from a different
  commit than the one you installed. Every container now reads the same digest from the lock. Nothing
  to set: reinstall or upgrade as usual.
- Hephaestus now serves HTTP on Apache Tomcat 11.0.25. The previous release line could apply a
  security constraint written for a longer path ahead of a stricter one covering a shorter path
  beneath it, letting a request reach a page the constraint was meant to close; it could also let a
  redirect after a sign-in form skip a method restriction, and let one DIGEST-authenticated request be
  replayed. No action on upgrade — the server picks the new version up with the image.

## 0.75.0

### Minor Changes

- Your practice feedback now lists every practice your workspace reviews, not only the ones that raised something. A practice with nothing to report says which kind of nothing it is: either no review has reached it yet, or the reviews ran and your work offered no relevant occasion. Those are different answers to "how am I doing here", and until now both looked like an absent row. Practices with actual feedback still come first, worst first; the quiet ones sort to the end.

  A group's standing is now read straight off its practices, including the quiet ones, so the summary at the top of a group and the practices beneath it can no longer tell different stories.

- Deciding whether to adopt a practice now starts with why the habit matters, not with the rule the reviewer follows. The rule is still there, under **How it decides**, next to what the practice reads and what it measures first — but it is written for the model that applies it, runs to several thousand characters, and is not what you need in order to choose.

  The library list carries each practice's reason too, so you can work through the catalog without opening every entry to find out what it is for.

- Instance administrators can publish workspace-targeted surveys and review survey responses and product feedback
  without sending data to an external analytics service. Contributors can send feedback, respond to surveys, or
  permanently dismiss them; submissions remain in the instance database.

  **Operators:** the PostHog integration is removed entirely. `POSTHOG_ENABLED`, `POSTHOG_API_HOST`,
  `POSTHOG_PROJECT_ID`, `POSTHOG_PROJECT_API_KEY`, and `POSTHOG_PERSONAL_API_KEY` are no longer read
  and can be deleted from your `.env`; no replacement variable is needed and no other action is
  required.

- Operators can monitor agent-job phase latency and terminal outcomes through a documented JSON log and Prometheus contract.
- Workspace administrators now choose which practices their workspace reviews, instead of receiving a copy of the instance library at creation. Practice setup shows the library beside the practices you already have, so you can read a practice's full definition, the evidence it needs, and what it will look like in your workspace before you add it. Adding one gives you an independent copy you can edit; later library changes never rewrite it. A whole group can be added at once, and a group you removed earlier can be restored from the same place.

  Adding never starts sending feedback on its own. A practice Hephaestus can review starts at **Review before sending**, and a practice it cannot review stays **Off** until you connect what it reads.

  You can also add a whole group at once. Hephaestus shows every practice it would add, reuse, or skip first, and applies the result in one step or not at all. Adding is refused if the library or your workspace changed while you were reading the preview, so you always act on what you saw.

  Workspaces you already have keep everything in them, and a workspace that has never recorded a catalog installation still receives one at the next start, so nothing goes missing.

- Practice reviews can now be rolled out to part of a workspace instead of all of it. Choose which
  monitored repositories are reviewed and, for each of them, which base branches; choose whether
  everybody's work is reviewed or only selected people's. Before a change widens either list, a preview
  says how many repositories and people it would cover, so a pilot can be checked before it starts.
  Sending feedback is now its own switch: pause it and reviews keep running, developers can still read
  their own feedback in Hephaestus, and nothing reaches a pull request or the mentor. Feedback refused
  while paused is never released by resuming. Proposals that nobody has decided remain available for an
  administrator to approve or reject after sending resumes.

  When a review needs approval, the approval page now shows the exact summary and every inline comment
  as one package. One decision releases or rejects the whole review; automatically authorized observations
  in the same review wait for that decision instead of appearing early. After approval, the delivery
  page shows how many comments have reached the provider while safe retries finish the remainder.

  Every delivery decision now keeps its reasoning. On a piece of work under Review activity, a workspace
  administrator can see, for each attempt, which checks ran and in what order, which one stopped it, and
  the repository, branch, author and settings it was judged against — so "why did this go quiet?" is
  answerable from the screen instead of from the logs.

  **Operators:** reviews now run only on work whose author is a member of the workspace, so a pull request
  from an outside contributor who is not on the **Members** screen is no longer reviewed and no feedback is
  prepared about them. Signing in to Hephaestus does not make somebody a member. After upgrading, read the
  **People** count under Practices → Review → When and where; `MIGRATION.md` says how to cover anybody who
  is missing.

- Practice reviews deliver feedback again. A review measured a pull request, recorded what it found, and then stopped: the step that turns those observations into something a developer reads was never switched on, so every review ended with its results stored and nothing said. Reviews now compose feedback for each lane the occasion can reach — the note on the work, the developer's own practice pages, and an ongoing conversation — and issue reviews compose for the two longitudinal lanes, since an issue is not the work a note belongs on.

  **Operators:** feedback now appears where it previously did not, so a workspace with review switched on begins posting again. Nothing new is required of you, and the instance-wide Silent Mode brake still holds everything back while it is engaged — but if you upgraded during the window where reviews were silent, this is the change that ends it.

- **Operators:** Install, upgrade, and roll back with the signed release image lock; production and staging now refuse mutable tags or any image digest that did not pass the release evidence gate.
- Each piece of practice feedback now says what kind it is: a behaviour you demonstrated, a trap you avoided, something harmful that was done, or something needed that was left out. Reading a strength no longer means guessing whether you did the good thing or steered clear of the bad one — and the two kinds call for different responses.
- Hardens the reference deployment with HTTPS security headers, a TLS floor, a request-size ceiling, shared rate limits for costly operations, authenticated internal messaging, and container resource limits.

  **Operators:** Set the new required `NATS_USERNAME` and `NATS_PASSWORD` variables. Optional `*_CPUS` and `*_PIDS_LIMIT` variables tune container ceilings. Remote databases require TLS unless `HEPHAESTUS_DATABASE_ALLOW_INSECURE_REMOTE=true` explicitly accepts plaintext transport. Each server role's database pool now defaults to 20 connections instead of 30; the optional `HIKARI_MAXIMUM_POOL_SIZE` variable tunes it.

- Run sandboxed practice reviews and mentor sessions on Node.js 24 with a 256 MB JavaScript heap ceiling and scoped runner filesystem permissions.

  **Operators:** Upgrade the agent image and server together. Runtime contract v2 reports a mismatched image as unsupported; follow the coordinated upgrade steps in `MIGRATION.md`.

- Moves the container images to `ghcr.io/hephaestus-build/<image>` after the repository's transfer to the hephaestus-build organization, dropping the redundant `hephaestus/` path segment. Releases published before the move keep their images and signatures at `ghcr.io/ls1intum/hephaestus/<image>`; upgrades, deploys, and signature verification select the right namespace and signing identity per release automatically.

  **Operators:** From this release on, images pull from `ghcr.io/hephaestus-build/<image>`. Update any registry mirrors, egress allowlists, or hand-written image references (such as a pinned `HEPHAESTUS_AGENT_IMAGE_REFERENCE`); the standard install and upgrade flow needs no changes. Older releases remain valid at their original `ghcr.io/ls1intum/hephaestus/<image>` paths.

- A developer can open a practice group from their profile and see what its standing is actually built from: which practices contribute to the group, where each of them stands, and the feedback behind them. A standing is no longer a label you have to take on trust.
- A practice group with no verdict now says which kind of silence it is instead of one catch-all "No feedback yet": whether nothing in it has been reviewed for you yet, or whether it was reviewed and nothing could be judged — because the practices did not apply, or because the evidence did not settle the question. A developer can tell a review that ran and found nothing from one that never ran.

  An observation carries the same severity wording wherever it appears, and every severity is told apart by its own icon rather than by colour alone.

  The evidence behind an observation reads as a quoted passage with its source named. A quote from code shows the file and the line range, with numbered lines below, and is marked when it comes from the old side of the change — the line quoted is then not what the file says now. A quote from a conversation or a document shows the source and the passage without line numbers — those numbers are positions inside a stored copy, not places you could open, and printing them made a chat message look like a file. When a quote is withheld, the block says why: the secret scanner never stores text that looks like a credential, and the location is still named so you can read it at the source.

- A practice group now shows its review runs as complete moments: every observation from one review stays together, instead of being split across pages so that a review is only ever half-visible.

  Developers can answer the feedback they receive, not just rate it. Alongside marking a piece of feedback helpful or unhelpful, they can record what they did about it — addressed, disputed, or not applicable — and explain it in their own words. Disputing asks for that explanation, so a disagreement always arrives with a reason attached. Any part of an answer can be changed or withdrawn later.

- On their own profile, a developer now sees a compact summary per practice group: where they currently stand, the guidance behind that standing, and how the group has developed across recently reviewed work. These summaries appear on your own profile only — the standing is derived for the signed-in developer, so another person's profile does not show them.
- The application API can now answer where a developer stands in each practice group: the current standing and its guidance, how the group has developed across recently reviewed work, which kinds of work contributed feedback, a filterable observation history, and the complete review runs behind it. An undecided observation remains visible in that history without being presented as a verdict. Developers can also replace or delete their response to delivered feedback, recording whether it was helpful, how they handled it, and an optional explanation. Every endpoint answers only for the signed-in developer.

  **Operators:** direct API callers must replace `/practice-areas` with `/practice-groups`, use the corresponding group schema and field names, replace `/practices/learner` with `/practices/reviewed`, and move from the older reaction endpoint to the combined response endpoint. Existing response history is preserved; `MIGRATION.md` lists the contract changes. The generated Hephaestus web client is updated in the same release.

- Where you stand on a practice now follows your most recently reviewed work instead of the whole 90-day record. Two problem-free pieces of reviewed work in a row are enough for a practice to read as going well again, so fixing a habit becomes visible within two reviews — and a problem on your newest piece of reviewed work registers just as quickly. How much of the work went well is counted rather than merely whether anything went wrong, so a single problem among otherwise clean reviews no longer weighs the same as a run of them. The list of feedback below the standing is unchanged: it stays the complete record of what the window raised, while the standing above it describes where things stand now.
- The practice screens now read the way the decision is actually made, and they stop implying things that are not true.

  A practice leads with why the habit matters; what adding it will do sits at the bottom, next to the button that does it. **Not independently validated** is gone — it appeared on every practice, so it told you nothing while looking like a warning. In its place is a sentence saying nobody has measured how often the practice is right, which is why a new one starts by asking you to approve each piece of feedback.

  Provenance says what actually happens. A copy never tracks the catalog, whether you edited it or not, so the badges now say **Same as the catalog**, **Catalog changed, yours did not**, and **No longer in the catalog** — and the matching case is finally labelled instead of silent.

  Opening or dismissing a panel no longer moves the page behind it, panels slide rather than jump when you step back, and the whole thing respects a reduced-motion setting.

  Loading no longer means a spinner in an empty box: each screen draws the shape of what is coming, so nothing jumps when it arrives, and quick responses no longer flash a loading state at all.

  One word throughout: the set of practices this instance offers is the **catalog**.

- A practice group now reports how it has developed by comparing its four most recent relevant pieces of reviewed work with the four before them. It shows improvement or decline only when that evidence supports a direction; otherwise it reports that the direction is unclear or that more reviewed work is needed.
- Pull request previews are now self-service. Add the `preview` label to a pull request in this repository and it deploys; every commit after that redeploys on its own. A preview waits only for its images to be published, never for the test suite, so it exists even when the tests are red — and it runs the same artifacts staging and production run, so what you see is what ships. A comment on the pull request carries the preview link, and GitHub's native deployment link opens it too. Removing the label, closing the pull request, or converting it back to draft removes the stack. Up to three previews run at once by default, and when the host is full the pull request comment names the ones holding the slots.

  Each preview starts from a copy of staging's database, so workspaces and synced work are already there — and that copy is silenced before the app starts: review triggers, agent bindings and sweep schedules off, queued jobs cancelled, and the sign-in identity dropped so the preview issues its own tokens. It reads staging's event stream on a consumer of its own. Agent runs and inbound webhooks stay off. Previews never run for forks, nor for changes to the deployment workflows themselves. Stacked pull requests each get their own preview.

  **Operators:** follow the preview runbook before enabling the Coolify application. It requires a `preview` repository label, a preview-only Coolify application, two scoped Coolify secrets, and the repository variables listed there — including the optional `PREVIEW_MAX_ACTIVE` limit. Previews must be deployed onto the staging host: they seed from its database and read its event stream. That needs a read-only PostgreSQL role on staging, whose password goes in `PREVIEW_SEED_SOURCE_PASSWORD` — the runbook has the grant. Keep Coolify's automatic repository webhook disabled.

- **Operators:** PostgreSQL 18 is now the bundled and qualified database target. Before upgrading a stack whose volume was initialized by PostgreSQL 17, follow the documented dump-and-restore procedure; do not attach the old data directory to the new container.
- New and existing users now review a first-login transparency notice and make a separate, optional research choice before entering Hephaestus. Research participation is off by default, every grant and withdrawal is recorded with the exact notice version, and consent can be withdrawn in one action from settings.
- Production startup now reports all catalogued production configuration problems together without
  exposing configured values, and instance administrators can inspect redacted deployment and runtime
  readiness facts through the API. New self-hosted installations generate and preserve internal secrets
  with `setup.sh`. **Operators:** validate production settings against the configuration readiness guide
  before upgrading; the process now refuses to start when a catalogued required setting is missing or
  invalid.
- Removing a practice group now asks what should happen to the practices in it: keep them and move them to Unassigned, or delete them together with the group. Deleting them also deletes their observations, and the dialog says so before you choose. Previously the practices were always kept, with no way to remove a group and its practices in one step.
- Operators can rotate stored integration credential encryption keys without disconnecting configured providers. **Operators:** set `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY` before upgrading; the supported self-host installer derives the initial value automatically. The optional `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY_VERSION`, `HEPHAESTUS_SECURITY_PRIOR_CREDENTIAL_ENCRYPTION_KEY`, `HEPHAESTUS_SECURITY_PRIOR_CREDENTIAL_ENCRYPTION_KEY_VERSION`, and `HEPHAESTUS_SECURITY_CREDENTIAL_ROTATION_ENABLED` variables drive the documented online rotation procedure.
- **Operators:** the PostgreSQL Compose volume keeps its stable `postgresql-data` name across the 17 → 18 upgrade instead of moving to a version-suffixed `postgresql-data-v18`. This corrects the PostgreSQL 18 qualification shipping in this same release, so no deployed instance ever sees the `-v18` name. The upgrade is dump, remove the volume, restore into the freshly initialized PostgreSQL 18 cluster; a PostgreSQL 18 container started against un-migrated PostgreSQL 17 data refuses to start instead of coming up empty.
- Operators can now ingest application logs as structured JSON, correlate agent-job logs by job or workspace, and scrape application metrics from the Prometheus actuator endpoint. Bearer, GitLab, GitHub, and token-query-string credentials are masked before console output.

  **Operators:** the server now writes one JSON object per log line instead of plain text, by default and in every profile. Anything that parses the previous plain-text format — log shippers, alerts, grep-based tooling — must be updated to read JSON; see `MIGRATION.md`.

- HTTP responses now expose a request ID that follows queued reviews through sandbox and model calls, so operators can correlate request, job, proxy, sandbox, and Sentry records without a tracing collector.

### Patch Changes

- A review comment now stays as it was written. Where a later look at the same change used to rewrite the original comment in place — and quietly demote anything already answered on the diff — it now leaves a new comment beside the old one, the way a person would. The comment also stops repeating the notes that sit on the diff: those live on the lines they are about, and anything that could not be placed on a line falls back into the comment rather than disappearing between the two.
- Keyboard users can skip repeated navigation and move directly to the page's main content. The documentation now explains the current accessibility assessment and how to report barriers.
- LLM usage reports and budget enforcement now record and price prompt-cache writes separately from ordinary input tokens.
- Practice setup no longer sends you to another page to look at something on it. Selecting a practice — one of yours, or one the library is offering — opens it beside the tree you were reading, and selecting something inside that opens on top of it, so you can go two levels deep and step back one at a time with Escape, a press on the page, the browser's Back button, or a swipe. The page behind stays readable rather than blurred, and each panel leaves a column of the one beneath it showing.

  Your own practices now have a read-only view for the first time. Opening one used to mean opening the edit form, so "what does this practice say?" and "change this practice" were the same act; now reading is reading, and editing is one clearly marked step away. A practice looks the same whether you met it in the library or in your own tree, because both show the same definition.

  Writing a practice no longer takes over the page either — on Practice setup and in the instance catalog, **Create** and **Edit** open beside the tree the practice belongs to, so you can see where it is going while you write it. An editor closes the same four ways everything else does — Escape, a press on the page beside it, a swipe, or its own controls — and asks before discarding unsaved changes on any of them, so nothing is lost and nothing silently refuses to close. The old addresses still work and open the same editor.

  Adding a practice returns you to the library you were working through — the practice moves into your own tree and stops being offered — instead of dropping you into an edit form. Adding five practices is now five selections rather than five round trips. Everything is addressable: the URL you are looking at is the URL you can share, and it reopens exactly the panels you had open.

- A comment written alongside "not helpful" is no longer lost. Explaining why a piece of feedback missed the mark, without also saying whether you addressed or disputed it, stored the text and then never showed it again — the next answer overwrote the reading with its own empty comment. The words now stay with the answer they came with.
- Creating or editing a practice group now lets you pick its icon and colour, the same way the instance library does. Left alone, both still follow the group's name.

  A practice's "What to look for" is written in markdown — the editor says so — and the read view now renders it. Headings, lists, emphasis and inline code used to reach you as literal `##` and `-` characters in one long paragraph of bold text.

- Releases now reject high and critical image vulnerabilities unless a disposition is bound to the exact image digest and platform, owned, evidence-backed, and expires within 90 days. Clean verification and recurring rescans authenticate and re-evaluate the same signed release lock.
- Pull request responses no longer require a URL when the upstream provider does not supply one.
- Makes the agent and PostgreSQL images reproducible by installing the agent SDK from a committed lockfile and pinning every supported PostgreSQL base image by digest.
- The landing and about pages now say plainly what Hephaestus is, show the kind of gap it points out, name the practice groups a workspace can turn on, and credit Applied Education Technologies at TUM.
- Hardens automated reviews and mentor conversations against instructions embedded in imported work, messages, and documents.
- No operator action is required for the repository's package-manager migration.
- Practice-review comments now identify themselves as AI-generated and link to an explanation and delivery controls.
- Review runs now identify every eligible practice they did not evaluate, so partial reviews no longer appear complete.
- Updates the frameworks the web application is built from. Error reports still infer nothing about
  who reported them, and now say category by category what they may carry. No action is needed to
  upgrade.
- The documentation now lives at <https://docs.hephaestus.build>. Links in the web app, the sample configuration files, and server messages point at the new address, and the old github.io pages redirect there — no action needed.
- Fixes three faults in the sliding panels used across practice setup and the instance catalog. A panel now slides in from the edge instead of appearing fully formed. Opening a panel on top of another no longer makes the one behind jump back and re-animate when you step back out of it. And a covered panel keeps a readable column of its own content on screen rather than a bare strip of its margin, which is the whole reason these stack instead of replacing each other.
- A review whose evidence check fails on one observation now delivers the others. The check that refuses to show a developer a claim it cannot trace back to the code applied to the whole review at once: one practice that mis-quoted its source — by a stray character, in a file the reader never sees — withheld every other observation in that review, including correct, fully evidenced ones. The developer saw nothing at all. Only a quote that does not match its source is treated this way; a citation to evidence the review never gathered still stops the whole delivery, as before. What was withheld is logged with the reason, and a review in which no claim can be verified still fails rather than arriving empty.
- Practice reviews now reuse shared evidence while assessing every applicable practice, recover missing coverage, and produce one coherent set of feedback.
- In-context practice feedback now uses the default delivery preference for developers who have not saved
  account settings. Explicit opt-outs remain honoured everywhere.
- Feedback about a practice no longer disappears when a later review of the same work never got to that practice. A review that was skipped for partial evidence, refused, timed out or ran out of budget covers fewer practices than the one before it, and the untouched practices' earlier observations were being dropped as though the newer review had reconsidered them — so an interrupted review looked exactly like a fixed habit. A later review now replaces only what it actually re-examined. The same holds per person: a review that had something to say about one contributor no longer clears what an earlier one found about another. Re-reviewing the same work with the same practice still replaces the earlier verdict, as before.
- On preview and staging deployments the footer's branch link now opens the branch it names. Builds of
  a pull request recorded the merge ref (`1538/merge`) rather than the branch, so the link led nowhere.
- The webapp image is now built from a digest-pinned nginx base without an ad-hoc package-upgrade layer, so the image you deploy matches its published SBOM and signed provenance; base updates arrive as reviewed dependency bumps instead of changing silently at build time.
- Hephaestus container images now install the operating-system security updates published since their
  base image was built, instead of shipping whatever the base image happened to contain. The web
  application image carries no known high or critical operating-system vulnerabilities as of this
  release. No action is needed to upgrade.
- New agent bindings default to a three-hour run timeout, allowing comprehensive reviews to finish when model requests are queued.
- Notes prepared for the mentor now say where a point has already been put to the developer and whether anything has moved without help, so a conversation does not repeat feedback they have already had twice. Notes written before this carry no such record, which reads as nothing having been said rather than as nothing to say.
- When the library changes while you are reading a group's plan, the panel now stays open and shows you the refreshed plan, the same way a single practice already did. It used to close and leave you a message asking you to go and look at the group again.
- Screen readers now announce what every dropdown list is for. The status, timeframe, work-type,
  rows-per-page and model pickers each opened a list of options with no name attached, so the list
  itself was announced as unlabelled.

  Also fixed, all surfaced by a stricter type and lint gate across the whole codebase:

  - A review schedule saved with a time that had no minutes (`9` rather than `09:00`) stored no minute
    at all instead of falling back to the hour's start.
  - Audit-log entries and the instance catalog version panel printed `[object Object]` for any field
    whose value was not plain text.
  - A cookie-consent choice was read back from browser storage without checking it, so a corrupted
    entry could be treated as a decision.
  - A theme, a workspace role or a feature flag that the browser or server reported as something this
    build does not recognise is now ignored rather than trusted: the theme falls back to the default,
    the role is refused, and the flag reads as off.
  - GitLab sub-issue sync could delete parent links it had never looked at. When a page walk stopped
    early — an error, or a repository past the pagination ceiling — the cleanup step still ran against
    the partial result and cleared the parent of every issue whose link lived on a page it never
    fetched, then reported the sync as completed.
  - Server errors now keep their original stack trace. Fifty-two places caught an exception and threw a
    new one without attaching the cause, so the log recorded where the failure was reported rather than
    where it happened. Sign-in, token validation and Slack preference failures were all affected.
  - Scrollbars inside scrollable panels rendered 2px wide with no border instead of the intended 10px.
  - A checkbox or radio that is switched off now looks switched off: its label kept full contrast, and
    the control itself showed neither the dimming nor the blocked cursor.
  - The primary button gave no hover feedback. The style was written so that it only applied when the
    button was rendered as a link, so the most-used button in the app looked inert under the cursor.
  - A mentor attachment that failed to upload disappeared with no message, leaving the sender believing
    it was attached. The failure is now reported.
  - A disabled accordion section still opened when clicked.
  - Copying a mentor reply to the clipboard failed silently when the browser refused.
  - Countdowns now advance while the page is open: an Outline token's expiry, and the wait shown while a
    sync is rate-limited, previously only moved when something else on the page happened to redraw.
  - Screen readers no longer hear an orientation announced on grouped toggle buttons, which is not
    something a group can meaningfully have.
  - The achievements API described an unlock time as always present, even for an achievement nobody
    has earned. It is now reported as absent, which is what the server was already sending.

- A panel covered by another one now leaves noticeably more of itself showing, so the thing you opened from stays readable rather than reduced to a stripe of its margin.
- The server image no longer ships a cryptography library with a known critical vulnerability, so it passes a high- and critical-severity image scan with nothing left to disposition. Deployments that talk to a TLS-protected Docker daemon keep working unchanged.
- Clears 22 high and critical vulnerabilities from the PostgreSQL image. The image no longer carries the bundled Go helper that dropped privileges at start-up — the same step now uses a tool the operating-system updates keep patched, so the vulnerabilities cannot come back with the next rebuild. The database initialises, restarts and runs exactly as before, and no configuration changes.
- Tidies the practice and group editors. Their Save and Cancel buttons now sit on the panel's own bottom edge, spanning it, instead of floating in a bar that stopped short of both sides and left a strip of empty space beneath it. The fields fill the panel rather than stopping partway across, so the buttons line up with them. Panel headings no longer squeeze the practice name into a narrow column beside a badge — the badge moves under the name, which leaves the name room to read on a phone. The scrollable part of a panel can also be scrolled from the keyboard, including while a form is saving and every field in it is disabled.
- Editing a practice no longer costs you your place: opening the editor from a practice panel and saving or cancelling returns you to that panel, with the catalog still open where you left it.

  Rows that pair a label with a dropdown now stack on a narrow screen instead of squeezing the label into a sliver beside it.

- Disputing feedback now waits for the required explanation before saving the response, and selected practices and expanded observations remain available when a practice-group page is refreshed or shared.
- The practice screens say one thing one way. The set of practices an instance offers is the **catalog** everywhere, entries are **included** or **excluded** rather than offered, a practice with no group is **Unassigned**, and the grouping is called a **group** — or not named at all where its own name is already on screen.

  Filtering the practice list to nothing now says so and gives you a button to clear the filter, instead of leaving you with empty groups and a note about a filter you had no way to remove.

- Browser errors now carry release and source-map context and show recovery controls. Server and browser reports omit Sentry request, user, and breadcrumb context.
- Updates Spring Boot to 4.0.7 for CVE-2026-40992 and the PostgreSQL driver to 42.7.12 for CVE-2026-54291.
- Prevents observations from crossing workspace boundaries and preserves the practice revision that produced each observation.
- Ensures practice reviews and mentor conversations use the configured model, and prevents mentor responses from completing while automatic retries are still running.
- Review comments no longer repeat their own issue count in the opening line, no longer run a strength and its next step together into one unpunctuated sentence, and no longer report how long the run took.
- Practice reviews now assess reviewer-facing practices against the person who submitted each review, including when several reviews target the same pull request revision.
- A problem the review found by opening a file now arrives as a comment on the changed line, rather than as a paragraph at the bottom of the merge request.
- A review comment now leads with its most serious point and the one edit that fixes it, instead of leading with whichever one happened to have no line number attached. Each point says what to do before it says why it matters, and the reasoning is one sentence rather than the same paragraph on every review that touches the practice.
- A review comment now opens with a sentence written about your change, instead of one of a handful of fixed lines that read the same on every review — including on reviews that opened with praise ahead of a serious problem. When the review has nothing worth opening on, it opens on its first observation.
- Review comments no longer append the workspace's own wording about a practice to each note. That paragraph was identical on every review that touched the practice, and it was about the practice rather than about the change in front of you. The practice still decides what gets raised; it just doesn't get quoted back.
- Workspace administrators now confirm before setting practices, groups, or the workspace default to Send automatically. Feedback already awaiting approval remains unchanged.
- Prevents workspace profiles and activity views from revealing users who only belong to another workspace.
- OAuth return targets now reject excessive percent-encoding, and OAuth intent cookies enforce expiration and future timestamps at millisecond precision.
- Releases now publish verifiable SBOM, license, provenance, signature, and vulnerability evidence for every supported production image and platform. Production NATS, Traefik, nginx, and Alpine images are pinned to reviewed digests and covered by the same release evidence and recurring scans as Hephaestus images.
- Workspace isolation checks now fail closed by default instead of allowing a detected unscoped query to continue.
- Hephaestus now uses the signal-blue Heph mark across the web app, documentation, README, browser and installable-app icons, and link previews.
- The sortable columns in the workspace members and achievements admin tables now say which column is
  sorted and in which direction — the arrow points, and a screen reader announces it. Both showed the
  same static icon whatever the sort was. Their "Columns" menus no longer offer the column of row
  actions, which was never meaningful to hide, and while a search is active both tables now count
  "Showing 5 of 12" against the rows that matched rather than against every row.
- Rejects mentor chat requests missing a message and integration connection requests missing a provider kind.
- A start that cannot read one of its settings — a variable referenced but never provided, or one that
  refers to itself — now prints the configuration report naming that setting, instead of ending at a
  stack trace about an unresolved placeholder. A setting that cannot be read is never answered with its
  default either, so a security switch whose value is unreadable is reported as needing attention
  rather than as satisfied, and the reason is logged.
- Agent sandboxes now use short-lived, proxy-scoped JWTs instead of reusable database-backed job secrets.
- Fixes the "Uses Hephaestus default" notice in the instance practice catalog running edge to edge while every field below it was indented. It now sits inside the panel like the rest of the content, and scrolls with it instead of holding a fixed strip at the top.
- Practice reviews now also start for practices that have no precompute script. Preparing the sandbox failed for those, so the review ended before it began.

## 0.74.0

### Minor Changes

- A run that is waiting on a spend cap now says so. Jobs held because their monthly LLM cap is
  exhausted used to be indistinguishable from jobs waiting for a free worker, both in the API and on
  the queue metrics — a capped workspace read as a queue of depth zero, exactly like an idle one. Each
  job now reports when it becomes eligible to run and, when an admin can release it, why it is waiting;
  and a new `agent.queue.held` metric counts the jobs parked on a cap, so a paused instance can be
  alerted on instead of looking healthy. Raising the cap still releases them by itself.

  Practices → **Runs** shows it too. A held run reads "Held · Over the AI budget · due in …" beneath
  its status, and a run backing off after a crash says when it will be tried again; every other run
  looks exactly as it did, because it is claimable right now and has nothing to wait for. Opening a
  held run adds an **On hold** note: the run is parked rather than failed, it resumes on its own once
  the cap is raised or the month rolls over, and AI usage names which purse is capped.

- Eight defect-focused code practices can now record a clean, fully searched change as a strength instead of treating it like irrelevant work. This covers error handling, input validation, unsafe crashes, untrusted input, insecure defaults, duplication, oversized functions, and leftover debug code.

  A clean result is allowed only when the practice declares an exhaustive source and the observation records the bounded corpus it searched. For code review, that claim covers the added and changed lines—not unchanged code, callers, runtime behavior, or overall correctness. If the available evidence cannot support that bounded claim, the review reports insufficient evidence rather than an unearned all-clear.

- The practice editor now asks when a practice is reviewed, and what each of those reviews reads —
  separately, for each occasion. A practice used to have one flat list of moments that started a review
  and one shared set of evidence behind all of them, so the only requirements it could state were the
  ones true of every moment at once. It can now say, for example: review this when the work arrives, and
  review it again at the merge — where that second review additionally reads the review threads in full.

  That second answer is what makes an honest statement about something _missing_ possible. A source can
  now be marked "this review says what is missing from it", which refuses the review outright on a
  partial capture rather than letting an incomplete list read as "there was nothing there". The editor
  offers it only on sources that can actually be captured whole, so the claim can never rest on a source
  that could never support it.

  The kind of work a practice reviews is no longer a separate field that could disagree with its
  moments; it is read off them. Existing practices are unchanged: each keeps its moments as a single
  occasion with the evidence it already had.

- A review comment now tells a developer what the review saw, and stops there. Every practice used to
  end on a next step written at the moment of measuring — and where the review had already decided
  what to do about a practice, that step is still there, written by the stage that can see the person's
  whole history. Where it had not, the comment now ends on the observation and the practice's "why this
  matters" instead of on advice invented to fill the slot.

  The change is there to make the measurements themselves more honest. Asking one step to record what
  it saw _and_ prescribe a remedy pulls it toward finding a fault worth prescribing against, and the
  three answers that assert nothing is wrong — a strength, a practice with nothing here to judge, and a
  question the evidence could not settle — are the ones that were being lost to it.

  Two comments that could previously land badly no longer can: a note whose text was entirely internal
  grading vocabulary is not placed on the diff at all (it appears in the summary instead, so nothing is
  lost), and a long comment is now cut on a sentence rather than mid-word, closing any code block the
  cut would have left open.

- A review that reads the repository around a change now says how much of it it actually read. Very large repositories are read up to a ceiling — 20,000 files and 32 MiB by default, skipping any single file over 10 MiB — and when a ceiling is reached the repository evidence is marked incomplete and names what was left out. Practices that judge work by something being absent from the repository are skipped on an incomplete read instead of answering from the part that was read, so "this does not exist anywhere in the repository" is only ever said about a repository that was read in full. Reviews of ordinary repositories are unaffected; very large ones cost less and no longer risk an unbounded bill.

  The ceilings are optional and default to the values above; set `GIT_TREE_MAX_FILES`, `GIT_TREE_MAX_TOTAL_SIZE` or `GIT_TREE_MAX_FILE_SIZE` to raise or lower them.

- Reviews no longer report something as missing unless they can say where they looked for it. An observation that claims a practice is absent now has to record the sources it searched, what it searched for, and what the search did not cover — and it is rejected if it skipped a source the practice is supposed to search before concluding an absence. Expect slightly fewer "this is missing" observations, and more of them saying "could not be determined" instead: those are the ones that were previously being asserted from a partial view of the evidence.
- The two admin consoles now call the same thing by the same name. Both the instance console and a
  workspace's own console have an **AI models**, an **AI usage** and an **Audit log** page, and the
  sidebar tells you which console you are in. In a workspace, "Manage members" / "Manage teams" /
  "Manage achievements" / "Manage workspace" are now just **Members**, **Teams**, **Achievements** and
  **Settings**, and "Usage" is **AI usage**. Every admin page also sets a browser tab title, so an
  instance tab and a workspace tab are finally distinguishable when both are open.

  The instance console's new model catalogue sits at `/admin/models`, the same address the workspace
  page has always used.

- Setting up AI for a workspace is now one page. Under Administration → "AI models", a workspace
  administrator picks the model that runs practice reviews and the model that runs the mentor
  directly — one card each, with an active toggle, a readiness indicator, and optional advanced
  limits (timeout, concurrent runs, internet access). This replaces the previous flow of creating named configurations and wiring them up on separate pages.

  Practice review now runs exactly the model you assign to it. Previously, a workspace with no
  explicit assignment fanned out to every enabled configuration, submitting one review per
  configuration for the same event — multiplying both cost and duplicate feedback. A workspace with no
  practice-reviews model assigned now runs no reviews until one is assigned.

  Budget enforcement is more accurate and more honest:
  - When a workspace crosses its monthly cap, review work that was already queued is now **held and
    resumes automatically** once the cap is raised or the month rolls over, instead of being dropped
    the moment the cap is crossed. A held job is kept for up to seven days from when it was queued; a
    job still over cap after that is cancelled rather than held indefinitely. Raising the cap is the only
    way to release a held job inside that window — the month rolls over too late for anything queued
    before the 24th.
  - Once a workspace is over its cap, the in-app AI proxy refuses new calls, so a run already in
    progress can no longer keep spending unbounded.
  - A run that crashes or times out mid-way now records the calls it actually made, instead of
    reporting zero — so the cap can see spend that used to leak.

  **Operators:** two changes may need action. (1) The instance-wide "usage without a known price"
  Warn/Block setting has been removed; a workspace that has a cap set and unverifiable spend is now
  always paused (a cap you cannot verify is not a cap), while an uncapped workspace is never paused —
  no configuration is needed. (2) Practice review no longer runs on every configuration by default,
  so a workspace that relied on that implicit behaviour needs a practice-reviews model assigned
  before its reviews resume. This is part of the one post-upgrade pass over each workspace's AI models
  page that `MIGRATION.md` describes — do it once, there, rather than as a separate step.

- Practice reviews and mentor conversations now run in the agent sandbox image built from the same
  commit as the application server. A deployment that tracked `main` previously fell back to the newest
  released sandbox image, which pairs a server with a sandbox nobody built it against — reviews and
  mentor turns then failed inside the container with nothing explaining why. The server now reports at
  startup when the sandbox image cannot run it, naming both versions.

  **Operators:** the sandbox image now follows `IMAGE_TAG`, so a tag that moves between builds refuses
  to start — `IMAGE_TAG=latest`, which earlier example configuration shipped, and equally a partial
  version such as `0.73`, which every patch release moves. The same goes for setting
  `HEPHAESTUS_AGENT_IMAGE_REFERENCE` to one. Set `IMAGE_TAG` to a full release version or a commit SHA,
  and remove or digest-pin the reference override, before upgrading. This applies even with the agent
  disabled. Release deployments that changed neither, and take the signed digest pin, are unaffected.
  See MIGRATION.md.

- Practice-review job execution no longer needs NATS — the agent job queue now runs on
  PostgreSQL. Smallest self-host deployments that only want practice review can drop a moving part.
  The queue now also prunes its own history automatically, so a busy instance no longer accumulates
  finished jobs without bound, and it reports its depth and the age of its oldest waiting job as
  metrics rather than leaving you to infer them from logs.

  **Operators:** replace `AGENT_NATS_ENABLED` with `AGENT_ENABLED` (and drop
  `HEPHAESTUS_AGENT_NATS_SERVER`, `AGENT_NATS_MAX_ACK_PENDING`, `AGENT_NATS_FETCH_BATCH_SIZE`) on
  every role that submits, executes, or recovers jobs; optional new tuning is `AGENT_POLL_INTERVAL`,
  `AGENT_CLAIM_BATCH_SIZE`, `AGENT_MAX_RETRIES`, `AGENT_PAYLOAD_RETENTION` (default `P14D`), and
  `AGENT_ROW_RETENTION` (default `P90D`). NATS is still required for webhook and sync ingest.

- A workspace's per-run timeout under Administration → AI models is now capped at one hour (it already
  had a 30-second floor). A single agent run has an upper bound again, and the sweep that closes and
  bills runs abandoned by a crashed worker is sized from it — previously an unusually long timeout could
  let that sweep close a mentor conversation that was still answering. The form catches a longer value
  as you type it, with the reason beside the field, instead of sending it and reporting a rejection
  that named neither the number nor the limit.

  **Operators:** check any workspace whose timeout was set above one hour. Existing settings are kept as
  they are, so such a workspace goes on running to its stored value and cannot save any other change on
  that page until the timeout is lowered. See `MIGRATION.md`.

- Practice reviews can now actually start. The application server and worker run unprivileged, so every attempt to launch an agent sandbox was refused by the Docker socket with a permission error and no review ever ran. They now join the host's Docker group.

  **Operators:** set `DOCKER_GROUP_ID` to the group id that owns `/var/run/docker.sock` on your host — `getent group docker | cut -d: -f3` prints it. There is no portable default, so a deployment that leaves it unset keeps failing the same way it does today.

- An instance no longer publishes its API documentation to the internet. Previously both the full
  OpenAPI description and the interactive Swagger UI answered any unauthenticated request, so anyone
  who knew the address could read the complete list of routes — including the instance-admin and
  workspace-admin ones — and use the built-in "try it out" form against them. The routes themselves
  always required a login, but the map of them no longer needs to be public.

  This now holds for every way the server is run, not only production: a staging or evaluation instance
  reachable from the internet published the same list.

  Nothing is required of you at upgrade. If you deliberately published the API description — for a
  client generator or an internal integration — set `SPRINGDOC_API_DOCS_ENABLED=true`, and
  `SPRINGDOC_SWAGGER_UI_ENABLED=true` for the browser UI, to keep it reachable.

- Adds a "Review this now" button to a piece of work's review activity page, so you can ask for a review instead of waiting for one. Only the work's author or assignees, or a workspace admin, can ask — a review's feedback goes to the author, not to whoever asked for it. When no review starts, the page says why in the same words the rest of the product uses, rather than reporting an error.

  Asking is rate limited twice: a second ask about the same piece of work inside the workspace's review cooldown is turned down, and one person can ask for at most 5 reviews an hour in a workspace. Both limits are configurable and the defaults need no change.

- Adding, editing or removing a login provider is now recorded on the instance audit trail. Until now
  these three actions — the ones that decide how everybody signs in to the instance — left no entry at
  all, so an unexpected change to a sign-in method could not be traced back to who made it. Each entry
  names the provider, whether it ended up enabled, and which fields a change touched; a rotated client
  secret is listed as having changed, but its value is never stored. Instance administrators find the
  entries alongside role changes and impersonation under Administration → Audit log.
- You can now self-host Hephaestus on a single Linux server. One supported Docker Compose stack —
  application server, webhook receiver, PostgreSQL and NATS behind a TLS reverse proxy — reuses the
  maintainers' own service definitions, so there is no second copy to fall out of date. Follow the new
  [install guide](https://ls1intum.github.io/Hephaestus/admin/install); GitHub App setup, manual
  webhook creation, and backup/restore each have a companion page.

  Existing deployments are unaffected: the reference Compose files are unchanged apart from making the
  NATS JetStream limits overridable, with the defaults unchanged.

- Workspace teams can review proposed practice feedback before sending it and promote reliable practices to automatic delivery.
- Administrators can now see a history of changes to a workspace's AI-settings controls — who changed a
  setting, when, and from what to what. It covers the practice-review policy, which model is bound to
  practice reviews and to the mentor, and the run limits on those bindings. Each entry shows
  the field-level before/after, keeps the author — including changes made while impersonating another
  user — and never stores credentials such as API keys. A workspace administrator finds it under
  Administration → "Audit log" for their own workspace; an instance administrator gets a
  cross-workspace view under the instance-admin console. The history is append-only and retained for
  twelve months.
- The core NATS port can now be exposed beyond localhost. It stays bound to `127.0.0.1` by default;
  set `NATS_BIND_HOST=0.0.0.0` (or a specific interface address) to let other hosts reach the bus — for
  example when a separate environment consumes events from this one's JetStream.

  Expose it only on a trusted or firewalled network: the bus is unauthenticated, so a public bind puts
  its contents within reach of anyone who can route to the host.

- Instance administrators can manage the starting practice catalog for new workspaces under **Admin →
  Practice catalog**. Areas and practices can be created, customized, included, excluded, and arranged
  by dragging, by keyboard, or through the row menu; no numeric positions need to be managed. Concurrent
  edits are rejected rather than overwritten. A custom arrangement can be reset to the order included
  with Hephaestus at any time.

  New workspaces receive the entries included by the instance, including entries created there. Existing
  workspaces never change automatically. Hephaestus defaults update automatically until an instance
  administrator customizes them. A customized entry keeps its saved definition when a new default arrives;
  the catalog shows whether applying the update would change review behavior, wording or guidance, or area
  appearance, and lets the administrator inspect the incoming definition before applying it or keeping
  the saved version. Excluding an area also excludes its included practices from new workspaces, and the
  confirmation names them before the change.

- Practice review screens now say where a piece of feedback went and what became of it as two separate answers, instead of showing whichever one happened to be set. A feedback detail shows the delivery as a trace: when it was composed, what held it back if anything did, and where it ended up.

  Feedback the mentor has since raised in a chat now reads "Delivered in conversation", so feedback that has landed is no longer listed the same way as feedback still waiting for that chat.

  The fourteen reasons feedback can be withheld are grouped into four you can filter by — the work moved on, policy kept it quiet, the developer's choice, and housekeeping — while each row still shows its own precise reason. Severity, practice status, place and reason are all on the filter bar; nothing hides behind "More filters" any more. Every filter option carries the same colour and icon as the tag it filters for.

- Workspace admins can now inspect each review from **Practices → Practice reviews**, including its
  observations and feedback—even when delivery was withheld or failed.
- Written documents are now a kind of work a practice can be about, and practices about them run.
  Publishing, editing or archiving a page in a connected Outline wiki is recorded against that page and
  starts a review of it, the practice editor lists documents as a kind of work to review alongside pull
  requests, issues and conversations, and a bundled practice asks whether a published decision record says what else the
  team considered and why those options lost — so decision records get read for the first time.

  A document review reads only the document: its title, collection, authorship and body. The results
  land on the author's own profile, and nothing is written back into the wiki.

  Practices bound to a document are recorded and shown as waiting until an Outline connection exists,
  the same way a practice bound to a merge request waits for an SCM connection. Where a review does not
  start, the reason is recorded rather than lost — the document's author has not linked their account,
  every bound practice is turned off, the workspace's budget is spent — and the ones an administrator
  can lift are retried automatically once they do.

  A document is reviewed when the wiki tells Hephaestus it changed. The periodic sync that catches up
  on changes missed while that connection was down refreshes the mirrored copy without starting reviews
  for them.

- A practice now decides for itself whether a draft is worth reviewing. Previously a single workspace
  switch, "Skip drafts", silenced every practice on every draft — including the one whose whole subject
  is how work is handed over, so its advice about draft hand-offs could never actually reach a draft.
  The switch and its instance-wide default are gone; each practice's occasion says whether it includes
  drafts. In the shipped catalogue, only "Ready and traceable handoff" opts in.

  **Operators:** remove `PRACTICE_REVIEW_SKIP_DRAFTS` from your environment — it is no longer read. If
  you had drafts switched off, expect that one practice to start commenting on draft pull requests; no
  other practice reviews a draft.

- The practice editor now shows how a practice's evidence requirements have actually turned out: how many of the recent reviews they let through, and which source skipped the rest. Requirements that quietly skip most reviews used to look identical to ones that never skip.

  Two sources stop overstating what they hold. Linked work items and Outline documents are both found by heuristics that cannot establish they found everything, so neither is reported as fully captured any more, and a practice can no longer require that of them.

  The manifest recorded with each review states only what the capture itself establishes. Where a source's completeness and fidelity are fixed by its contract, the manifest pins that contract by digest rather than restating it, so the two can no longer disagree.

- Refuses to start when the retention window for cached review evidence is set below one day. Zero was
  accepted and read as the opposite of what it looks like: rather than switching the cleanup off, it
  made every cached job directory eligible for deletion on the next sweep.

  **Operators:** the shipped default of 30 days needs no change. If you set
  `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS`, it must now be `1` or more; `0` or a negative value stops the
  server starting, with the limit in the message. There is no value that switches the cleanup off — set
  a long window instead.

- The directory Hephaestus keeps its working copies in is now named by `HEPHAESTUS_FABRIC_ROOT`. It has
  held more than repository clones for several releases — cached review evidence and per-job manifests
  sit beside them — and it is now named for what it is rather than for the one thing it started as.

  **Operators:** if you set `GIT_STORAGE_PATH`, set `HEPHAESTUS_FABRIC_ROOT` to the same value before
  starting the new version, then remove the old one. `GIT_STORAGE_PATH` is no longer read and there is
  no alias, so an instance that keeps it does not fail — it silently falls back to `/data/git-repos`
  and writes everything to a directory you did not choose. Deployments that use the shipped Compose
  files unchanged are unaffected; those files already pinned this path and now pass the new name for
  the same directory. See `MIGRATION.md`.

- Hephaestus now produces a third kind of feedback: written for one developer, private to them, and
  about what keeps happening across several pieces of their work rather than what is wrong in one.
  Where a note on a pull request says what to change before merging, this says the habit behind the
  notes, the pieces of work it showed up on, and one thing to try on the next change. It is not the
  pull-request comments reorganised — it is composed separately, by its own step, after a review has
  finished measuring, and it exists precisely to say the thing a comment on one change can never say.

  **This release has no page of its own for it.** The feedback is composed, stored and available
  through the API, and it is what a developer's practice pages will read; it is not reachable as a
  standalone surface yet.

  A message is only composed once the same problem has shown up on at least two separate pieces of
  work, at most two habits are offered at a time, and the same habit stays quiet for two weeks after it
  was raised. Feedback that judges the person rather than the work is refused here as it is in the
  mentor chat.

  **It is private.** Workspace admins and instructors can still see on the review surfaces that a
  message was prepared, whether it was delivered, and why one was withheld — they cannot read what it
  said. That is deliberate and it is the direction that can be revisited later; the reverse cannot.

  Feedback from a retrospective backfill campaign is not composed here. A backfill is a snapshot of
  finished work, and this feedback makes claims about what keeps happening, so a sweep over a year of
  history will not arrive as a wall of advice on the day you run it.

  Reviews of documentation pages continue to record what they find without delivering anything; turning
  that on is a separate, deliberate step.

  Practices that judge how somebody _reviews_ a teammate's change — leaving specific comments, asking
  rather than demanding, reading before approving — now say so, and a review that cannot name the
  reviewer does not run rather than recording the observation against the author of the change, which
  is what happened before. **Operators:** an occasion records who it judges, and a workspace set up
  before this release still holds the old wording for those three practices until they are updated from
  the catalogue on the practice-catalogue screen; until then they keep behaving as they did. Every other
  practice is unaffected.

- Feedback for all three surfaces is now written in one deliberate step after the review has finished
  measuring, instead of each surface deciding on its own. That step sees what the review just found,
  what earlier reviews recorded about the same person, what has already been said to them, what is
  still waiting to be read, and how each recurring problem moved — so it can say "this is the third
  time", stay quiet about something that has not changed, or point out that last review's gap is
  closed.

  Each surface now gets what it is for. A note on the merge request is about this change and the one
  edit to make before merging. The feedback written for you alone is about what keeps happening across
  several pieces of your work and one habit to try next time, and it never re-quotes a line of code. A mentor
  conversation opens with a question so you reach the diagnosis yourself, and holds the evidence back
  until you have answered — the mentor still writes the words of the turn, so nothing goes stale
  waiting to be raised.

  A note on the merge request can only be placed on a line the change actually touches: the composition
  step names an observation and one of its own recorded citations, and the server resolves the file and
  line from that, so a note can no longer land somewhere the diff does not contain.

  Fixes advice on an observation's detail page being read from another workspace's feedback when the
  same observation was referenced from both.

- Fixes self-hosted GitLab instances being pointed at someone else's GitLab. The setting for which
  GitLab workspace creation and repository sync talk to is documented to follow your GitLab login URL
  when you do not set it, but the shipped compose file pinned it to a specific university's server, so
  that fallback never happened: an operator who configured only their GitLab login silently got a
  GitLab they had never named. It now follows the login URL as documented.

  **Operators:** if you run against a GitLab other than `gitlab.com` and have been relying on the
  shipped default rather than setting `GITLAB_DEFAULT_SERVER_URL` yourself, set it (or
  `GITLAB_OAUTH_BASE_URL`) before upgrading — otherwise sync will move to `gitlab.com`.

- The instance-admin console now opens on an **overview dashboard** instead of a blank page: whether
  delivery is running, how many workspaces and memberships the instance has, and the latest
  authentication activity — each tile linking to the page that manages it. The sidebar is grouped
  (Access, Practices, AI, Operations) rather than one flat list.

  A new **Instance settings** page carries the emergency **silent mode** switch. While it is engaged,
  Hephaestus posts nothing outward anywhere on the instance — no practice feedback on pull requests,
  merge requests or issues, no Slack messages, not even the 👀 acknowledgement on a `/hephaestus review`
  comment — and a banner across the console says so, naming who engaged it and why. Engaging takes one
  click and an optional reason; releasing asks you to type "release", because resuming delivery for
  every workspace at once deserves more thought than pausing it. Both directions are recorded on the
  audit log.

  Silent mode holds feedback back rather than throwing it away: reviews keep running and their observations
  are still saved and marked as withheld, so nothing is lost — but they are not posted retroactively
  when you release it. Workspace settings are untouched throughout and apply again immediately.

- The application-server, application-worker and webhook-server containers now have explicit memory
  limits, so each JVM sizes its heap for its own container instead of the whole host. Co-located
  services no longer oversubscribe host memory and push the box into swap.

  **Operators:** defaults are application-server 5 GB, application-worker 3 GB and webhook-server 2 GB,
  overridable via `APPLICATION_SERVER_MEM_LIMIT`, `APPLICATION_WORKER_MEM_LIMIT` and
  `WEBHOOK_SERVER_MEM_LIMIT`. Keep the sum under the host's RAM; raise them on larger hosts. A host
  sized for the old advice will not fit these limits — the single-server install guide states the
  floor.

- Instance administrators can now register OpenAI and other OpenAI-compatible endpoints — including
  self-hosted gateways such as vLLM — under Instance admin → AI models, set a
  price per model, and share individual models with workspaces. Workspaces can instead connect their
  own provider ("bring your own AI provider") to run practice review and the mentor on their own
  account. API keys are never exposed to a workspace or a sandboxed agent — they stay server-side
  behind the LLM proxy, which is now the only path a sandbox has to a provider.
  Interactive mentor conversations reuse a healthy sandbox for faster follow-up turns and replace it
  when its binding changes or its lease expires.

  Monthly budget totals now count only verifiable priced usage. A started attempt with no trustworthy
  usage counters is never folded in as if it cost nothing: it is counted separately and the total says
  how many runs it is missing ("2 runs aren't counted in these totals"), so an understated figure
  declares itself instead of reading as complete.

  **Operators:** remove `HEPHAESTUS_WORKER_LLM_BASE_URL`, `HEPHAESTUS_WORKER_LLM_API_KEY`,
  `HEPHAESTUS_SANDBOX_LLM_PROXY_ENABLED`, and every `AGENT_DEFAULT_CONFIG_*` variable from your
  deployment (they are now ignored), then register your OpenAI-compatible endpoint(s) under Instance
  admin → AI models.

- AI proxy latency and errors are now broken down by the API contract a call was made under —
  `openai-completions` or `openai-responses` — instead of by a fixed provider name. Naming a provider
  stopped being meaningful once any OpenAI-compatible endpoint can be registered, since two endpoints
  from the same vendor can speak different contracts and one gateway can front several vendors. Four
  new counters also make refusals visible: calls blocked by a spending cap, calls refused because they
  could not be billed, and responses whose usage counters could not be read or were not provided at all.

  **Operators:** the `llm.proxy.duration` and `llm.proxy.errors` metrics keep their names but are now
  labelled `apiProtocol` rather than `provider`. A dashboard or alert that groups or filters on
  `provider` matches nothing after upgrading — it goes blank rather than erroring, and an alert that
  stops firing looks like an alert that is satisfied. Update those queries before you upgrade. Log
  searches on the `proxy.provider` field need the same change, to `proxy.apiProtocol`.

- The instance-wide AI settings are now configurable from the environment and validated at startup, and the AI admin endpoints are served only by the application server.

  Three new optional variables, all with working defaults, are documented in the shipped configuration: `HEPHAESTUS_LLM_DISPLAY_CURRENCY` (unset), `HEPHAESTUS_LLM_EGRESS_ALLOW_LOOPBACK` (`false`; never turn this on in production) and `HEPHAESTUS_LLM_FX_DAILY_URL` (the European Central Bank's daily file; override only on an air-gapped instance mirroring it internally). A display currency this instance cannot convert to now fails startup with a message naming what is accepted, rather than booting and silently showing USD only. This release supports `EUR`. No action is required to upgrade.

- LLM spend can now be shown with a euro estimate beside the US dollar figures. Dollars remain what is billed, capped and recorded — the euro number is a clearly labelled estimate converted with the European Central Bank's daily reference rate, and every screen states the date of the rate it used. A closed month is shown with a rate from inside that month, so its estimate never changes after the fact, and if rates cannot be refreshed for a week the estimate disappears rather than quietly drifting.

  It is off unless you ask for it: set `HEPHAESTUS_LLM_DISPLAY_CURRENCY=EUR` to switch it on, and leaving it unset changes nothing. Once set, the application server fetches the ECB's free daily reference rates once each weekday — no API key, and no outbound request from the worker or webhook containers.

- A review somebody asks for by hand is now recorded under its own name instead of one that reads like GitHub's "a reviewer was requested" event. Existing history and any practice set up to watch it are moved to the new name on upgrade; nothing needs re-requesting.
- Practice-review screens now consistently call a recorded measurement an **observation** and the intervention derived from it **feedback**. Headings, filters, empty states, deletion warnings, and user documentation use the same terms. Old bookmarked web pages under `/admin/practices/reviews/findings` redirect to their observation equivalents.

  **API clients:** observation payloads now use `artifactKind`, `summary`, `evidenceRationale`, and `deliveredFeedback` in place of `artifactType`, `title`, `reasoning`, and `guidance`. They also expose the observation's origin and claim currentness. The model-reported `confidence` field is removed because it was not a calibrated measurement. The removed API fields have no aliases; the bundled web app already uses the new contract. See `MIGRATION.md`.

- Adds a sort to the practice-review observations list, so you can put the most actionable observations at the top — shortfalls first, worst severity down to informational, then strengths — instead of always reading in date order. The chosen order travels in the URL, so a link to "the worst of last week" opens the same way for whoever you send it to.
- Practice reviews now distinguish a missing review occasion from evidence that cannot support a conclusion. `NO_REVIEW_OCCASION` means the work contains no subject for that practice; `INSUFFICIENT_EVIDENCE` means the subject exists, but the available evidence cannot settle it. The latter records the open question and the existing evidence that would settle it, while the former records the fact that rules the practice out. Required evidence that is unavailable, stale, partial, redacted, or failed stops the practice before an observation is created rather than being mistaken for either result.

  Every observation also records what occasioned it — a review triggered by the work itself or one requested by a person. Trends, summaries, and mentor context compare like occasions, so a bulk review of older work does not masquerade as a change in someone's practice.

- Setting up when a practice is reviewed now shows the life of the work instead of a grid of
  checkboxes. The occasion draws the moments that kind of work actually offers — a pull request
  starts, churns while it is open, and ends by being merged or closed; a document is published,
  changes and is archived; a conversation settles — and a moment that recurs says so, so binding
  "New commits pushed" no longer quietly means a review on every push.

  Choosing what a review reads is one line per source, grouped into the work itself, its
  surroundings, and what has already been said to this person, with a Required / Context / Off
  switch on each — where a pull request previously offered eleven paragraphs of prose to scroll.

- Practice evidence is simpler to author and harder to get wrong. Saying what a practice needs is now a single choice per source — required, optional context, or not used — which settles whether the practice can be measured at all, and how completely a source must be captured is fixed by the source itself rather than restated on every practice that reads it. The named evidence profiles are gone; the sources a practice can name follow from the kind of work it reviews.

  Two behaviour changes come with it: a review of a chat thread now waits for the whole thread rather than judging a fragment, and a practice that reads review comments is skipped when the comments could not be collected instead of being reviewed as though there were none. Both keep a collection problem from being recorded as something a developer did.

- A newer message about a habit now replaces the one still waiting to be read, instead of stacking
  beside it — so you are left with one current message per habit rather than a pile of near-duplicates
  from every review that ran this week.

  Nothing you have already read is ever rewritten. If a message reaches you before the next review gets
  to it, that message keeps its place and the new one is written beside it, linked to what it follows,
  so a habit reads as one thing raised over time rather than as unrelated advice.

- A practice is now reviewed on one occasion rather than a numbered list of them. Every practice shipped with Hephaestus already used exactly one, and where its authors wanted different evidence at a different moment they wrote a second practice — so the second occasion was a setting nobody used and everybody had to read past. To read different evidence at a different moment, write a second practice.

  Asking for a review by hand is no longer offered as a moment to tick. It never was one: a review somebody asks for reads everything the practice can read, whatever state the work is in. The screen now says that where the button lives instead of in the list of moments it is not part of.

  The evidence control says what it does. "Can say what is missing" became "May claim something is absent", and each source now shows the bound it is captured against — up to the 500 most recent inline comments, for instance — at the moment you decide, rather than on the review that later refuses to run.

- A practice can now be turned down without being turned off, and it is one decision instead of one per
  practice. A workspace says how far reviews go on their own — **Off**, **Propose** (it reviews the
  work and records everything it sees, and sends nothing to anyone) or **Deliver** (it sends the
  feedback) — and every area and practice follows that until you say otherwise. An area can override the
  workspace, a practice can override its area, and anything you have not decided shows as inherited,
  naming the level it came from and offering a way to reset it.

  Turning a practice down to Propose keeps its measurements unbroken, which turning it off does not: a
  practice that stops being reviewed leaves a gap in its own history that later reads as a change in the
  team's behaviour. Nothing a quiet practice held back is lost either — the work's review activity shows
  it as reviewed, with what it saw, nothing delivered, and the setting named as the reason.

  The **How much** section of Review, under Administration → Practices, is where all of this is set. A
  line that stays on screen counts how many practices sit at each setting, so you can see what a
  workspace is actually doing without scrolling the list; practices are grouped by area with each area's
  own counts; a filter narrows the list to just the exceptions somebody set by hand; and a whole area,
  or a filtered selection, can be moved in one action.

  Nothing changes on upgrade: a practice that was reviewed and delivered before goes on doing both, and
  one that was switched off stays off.

- Reviewed work is named the same way everywhere. A practice, a review run and a recorded observation
  all now identify what was reviewed by one name each — `scm.pull_request`, `scm.issue`,
  `chat.conversation_thread` — instead of two internal vocabularies that had already drifted apart — a chat thread was called one
  thing where reviews are stored and another where they are run. The bundled practice catalog, the API
  and the admin screens use the new names.

  **Operators:** this is a one-way change — the upgrade rewrites the names in place, so rolling the
  release back requires rolling the database change back with it. Two effects are
  worth knowing about while the first reviews run afterwards. A piece of feedback that was already
  posted on an open pull request or thread may be posted once more rather than updated in place, since
  what ties a re-review to an earlier one is derived from the old name. And practice review rules are
  re-fingerprinted on the first start after the upgrade, so a practice can briefly show as differing
  from its Hephaestus default until that finishes.

- Administration → Practices is down from five entries to three: **Practice setup** (what we look for), **Review** (how it behaves here), and **Practice reviews** (what actually happened). _Review autonomy_, _Review settings_ and _Review past work_ are now the **How much**, **When and where** and **Past work** sections of the one Review page, which opens with a line telling you whether this workspace is reviewing anything at all — the fact all three sections depend on and none of them used to state. The recurring check over recent work moved to **When and where**, beside the other things that start a review, because it is a standing policy rather than a campaign over history; **Past work** now holds only the one-off priced campaign. The review model is shown read-only under **How much**, since nothing on that page runs without one.

  Bookmarks and links to the three retired pages redirect to the section that absorbed them, so nothing 404s and nothing is required of you at upgrade.

- The AI area of the API now says one thing one way. Every address in it is either `llm/…` (the models
  and what they cost) or `agents/…` (the things that run them); the `ai-settings` container is gone, and
  "BYO" is gone from every address, request field and label — a workspace's own connected provider is
  called exactly that, in the API as well as on screen. (The audit log is append-only, so entries it
  already wrote keep the name they were written under.) The two monthly spend caps are also the same
  instrument: an instance admin's cap on a workspace and that workspace's cap on its own provider have
  the same address shape and the same request body, `{ "monthlyBudgetUsd": … }`, differing only in who
  is allowed to set them.

  **Operators:** the addresses below existed in the previous release and have moved or been removed.
  There are no redirects or aliases — a script calling an old address gets a 404, so update it before
  upgrading.

  | Was                                                                                                         | Now                                                             |
  | ----------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
  | `GET /workspaces/{slug}/agent-jobs…`                                                                        | `GET /workspaces/{slug}/agents/jobs…`                           |
  | `GET /workspaces/{slug}/ai-settings`, `PATCH …/ai-settings/practice-review`                                 | `GET`/`PATCH /workspaces/{slug}/practices/review-settings`      |
  | `/workspaces/{slug}/agent-configs…`, `PUT …/ai-settings/practice-config`, `PUT …/ai-settings/mentor-config` | removed — a workspace's AI setup is one binding per purpose now |

  If you read `GET /ai-settings` for `practicesEnabled` / `mentorEnabled`, take them from the workspace
  itself (`GET /workspaces/{slug}`); the review-settings response carries the review policy only.

  The rest of the AI area is new in this release rather than moved, so nothing calls it yet:
  `GET /admin/llm/usage`, `PUT /admin/workspaces/{slug}/llm/budget`, `GET /workspaces/{slug}/llm/usage`,
  `PUT /workspaces/{slug}/llm/budget`, `GET /workspaces/{slug}/llm/settings`, and the per-purpose
  bindings — `GET /workspaces/{slug}/agents` to list them, `PUT`/`DELETE
/workspaces/{slug}/agents/{purpose}` to set or clear one.

  The workspace console's retired `/w/{slug}/admin/ai/*` browser URLs no longer redirect either; those
  pages have been at `/w/{slug}/admin/models` and `/w/{slug}/admin/practices` since the previous
  release.

- Practice setup now shows how far reviews go without you on each practice, without letting you change it there. Each row reads out the tier in force and the level that decided it — "Follows Testing", "Follows the workspace default", or "Set for this practice" — and links to **Review → How much**, which is now the only place the tier is set. Previously the same field had two editors, and because the picker on Practice setup showed the value in force with nothing saying where it came from, choosing a tier there quietly pinned an exception to that one practice and undid the workspace answer you had just set.
- Instance administrators can now see what each workspace spent on AI in a given month, and set a
  monthly spending cap per workspace. Once a workspace reaches its cap, practice reviews and the mentor
  replies pause for the rest of the month — so one runaway workspace can no longer quietly consume the
  whole instance's AI budget — and they resume on their own when the next month begins or when an
  administrator raises the cap. Changes to a cap are recorded in the audit log alongside other
  administrative changes.

  Workspace administrators get a matching view for their own workspace under Administration →
  "AI usage": total spend for the month, a breakdown by day and by kind of work (pull-request reviews,
  issue reviews, conversation reviews, and mentor conversations), and their current cap, which they can
  see but not raise. Mentor conversations are included in these totals for the first time. Where a
  started attempt has no trustworthy usage counters or legacy price snapshot, it is counted separately
  and flagged, so it is clear when a total is understated rather than silently wrong.

- Workspace admins can now move practices between areas or **Unassigned**, and manage the practice
  catalog on narrow screens. Moves and deletions are preserved when the application
  restarts, definition changes appear in the configuration audit trail, and catalog edits update in
  place.
- Workspace admins can prepare practices, model assignments, and review rules before starting
  practice reviews. Turning practice reviews off now prevents new reviews across every work type.
- The practice review screens are rebuilt around what an operator is trying to find out.

  Observations, Delivery and Reviews are now one readable list each, on any screen size, instead of a
  wide table and a separate card list that showed different things. A piece of feedback is listed by
  its opening words rather than by "Feedback for {person}", so twenty-five rows no longer look
  identical. You can filter any of the three by one person, and by a date range, from the toolbar —
  severity and practice status are there too, rather than behind "More filters".

  Opening an observation, a piece of feedback or a review now shows what used to be inside a collapsed
  "Technical details" drawer: which review produced it (as a link, not an identifier), and for a
  review, the model it used and how many tokens it read and wrote. Its configuration is one **Copy
  configuration** button. The evidence behind an observation says which source each passage came from
  in plain words instead of repeating a contract identifier under every quote, and shows a file and
  line only where that location is a real one you could open.

  Links to the old observations address redirect to the new one, keeping any filters in the URL, so
  existing bookmarks keep working and nothing is required of you at upgrade.

- An instance administrator can now enter a workspace directly through owner impersonation, and every such entry is recorded in the audit trail.

  **Operators:** if you run this project's pull request preview stacks, the optional `PREVIEW_SEED_SOURCE_CONTAINER`, `PREVIEW_SEED_SOURCE_USERNAME` and `PREVIEW_SEED_SOURCE_DATABASE` variables override the PostgreSQL source when its Compose names differ from the defaults.

- A deployment's message-queue consumers no longer outlive the deployment itself. Consumers were kept forever, and nothing else removes one, so every stack that shared a broker and was deleted rather than shut down — a pull request preview, a throwaway test environment — left its consumers, and backlogs that would never drain, behind for good. They now expire on their own once nothing has been connected to them for long enough.

  **Operators:** `HEPHAESTUS_INTEGRATION_CONSUMER_INACTIVE_THRESHOLD` defaults to `30d`, where it previously meant never. It measures time with nothing connected, not time without traffic — a running deployment resets it continuously even while its queues are silent, so no restart, deploy or incident reaches it. Set something far shorter, such as `72h`, on any disposable stack that shares a broker. If your deployment may be offline for longer than the threshold and must resume exactly where it left off, set `0s` to switch expiry off. Values between `0s` and `1h` are now rejected at startup rather than quietly expiring a consumer across an ordinary restart.

- Workspace admins can now schedule a recurring check that reviews recent work even when nothing announced it. Until now a review only started when a provider sent an event, somebody asked by hand, or an admin ran a one-off campaign — so a pull request whose webhook was lost was never reviewed, and nothing recorded that it had been missed. Set a daily or weekly check under **Review → When and where**, choose how far back it reaches, and anything overlooked gets picked up on the next pass.

  Work the check finds counts exactly like work a live event triggered, because the window is deliberately bounded to the last few days: at most twice the cadence, and never more than a week. Reviewing further back is still the separate one-off campaign, which stays out of your live trends. Work already reviewed is never paid for twice, so overlapping windows cost nothing.

- When a review does not run, Review activity now offers the way to fix it instead of naming it. A refusal that a workspace admin can undo — no model set up for reviews, practice reviews switched off, work outside the review scope, every watching practice turned off, a spent AI budget, and the rest — carries a link straight to the screen that changes it, both on the timeline and on the answer you get back from **Review this now**. Refusals nobody can act on, such as a cooldown that expires or an hourly allowance that refills, stay a plain sentence, and members who are not admins are not shown links they cannot open.
- Refuses to start a worker whose liveness heartbeat is slower than the lease it renews. Such a worker was judged dead while it was still running, so its in-flight reviews were requeued onto a sibling and the same work ran twice at double the model spend.

  **Operators:** the shipped default needs no change. If you override `hephaestus.agent.heartbeat-interval`, it must now be at most 30s; a larger value fails startup with the limit in the message instead of silently duplicating work.

- Automated code review can now read the rest of the repository at the reviewed commit, not just the changed lines, so feedback about a change accounts for the code it calls into. Practices that review a diff pick this up automatically; a workspace whose repository is not mirrored still gets the review, without the surrounding code.

  Reviews also stop making claims their evidence cannot support: the limits an author records on a practice — that a diff cannot show how the code behaves once deployed, for instance — now reach the reviewing model.

- Practice-feedback opt-outs now apply to authored issues as well as pull and merge requests, and new feedback comments link to the personal **Comments and Slack reminders** setting.

  **Operators:** update API clients that read or write user settings or account exports from `aiReviewEnabled` to `practiceFeedbackDeliveryEnabled`.

- Retires named agent configurations. A workspace's AI setup is now exactly one model binding per purpose — practice reviews and mentor — edited on the **AI models** page, instead of a list of named configs plus separate pointers designating which one each feature used.

  Every configuration that was in use is carried across the upgrade: its endpoint, model name, API key and its timeout, concurrency and internet limits all reappear on the workspace's AI models page as a connection, a model and a binding, under the name the configuration had. That covers both the ones a workspace explicitly pointed at and the ones that were simply switched on — including anything set up through `AGENT_DEFAULT_CONFIG_*`, which never wrote a pointer. No key you were using has to be re-issued. A configuration that was both switched off and unreferenced is dropped, because nothing could reach it.

  **Operators:** two things need your attention after upgrading. (1) Everything carried over arrives **switched off**, so practice reviews and the mentor do not run until you review each workspace's AI models page and enable it. That is deliberate: the endpoint a configuration really called was set by an instance-wide environment variable that is not in the database, so re-enabling on your behalf could silently send a workspace's traffic and its key to a different address than before. The deploy log names every workspace that needs more than a switch — a placeholder endpoint or model id to replace, reviews that used to run on several configurations at once, or a dropped configuration whose key you may want to revoke. See `MIGRATION.md`. (2) The `/agent-configs` endpoints and `PUT /ai-settings/practice-config` / `PUT /ai-settings/mentor-config` are gone; use `GET /workspaces/{workspaceSlug}/agents` to list a workspace's bindings and `PUT` or `DELETE /workspaces/{workspaceSlug}/agents/{purpose}` to set or clear one. There is no `GET` on the `{purpose}` address — reading one binding means reading the list. Agent jobs report the model they ran on rather than a config name. Any script calling the removed endpoints must be updated before upgrading.

- Only people the work belongs to can now ask for a review of it. A `/hephaestus review` comment on a merge request is carried out when the commenter is the author or an assignee of that merge request, or a workspace admin; anyone else's command is declined and logged. Previously any account that could comment could start a review of anybody's work, and the coaching it produced went to the author rather than to whoever asked.

  Requested reviews are also now visible in the artifact trace, which says that a person asked and what came of it, and their observations are kept out of the live trend line — a review somebody asks for is about work they were already unsure of, so counting it alongside automatically triggered reviews made a workspace's numbers look worse than its work was.

- Adopting Hephaestus no longer means starting from zero. Workspace admins get a new **Review past
  work** page under Practices that measures work which already existed — "review the pull requests of
  the last 30 days" — so a workspace has a baseline from day one instead of waiting weeks for one to
  accumulate.

  It is deliberately a two-step decision, because it can spend real money. Choosing a range only
  produces an estimate: how many pull requests or issues are in scope, and roughly what reviewing them
  will cost, based on what this workspace's own reviews have actually cost. Nothing is submitted until
  you confirm that estimate, and the confirmation is recorded on the audit log against the admin who
  gave it.

  While it runs you can watch it and stop it. If the monthly AI budget runs out part-way, the campaign
  **pauses and resumes** where it stopped — it never quietly skips the work it could not afford. When
  it finishes it says plainly whether the baseline is whole: the items it could not review are counted
  and reported separately from the ones it deliberately walked past, so a campaign that hit errors
  cannot announce itself complete over a baseline with gaps in it.

  The observations reach the developers they are about. They flow through the same reads as live feedback —
  the reflective read model, the mentor's history of what it can refer to, and the earlier observations
  a later review is given — each carrying what occasioned it, so a surface can say an item came from a
  review of past work rather than passing it off as something that just happened. The admin observations
  list can filter on the same thing: live, requested by hand, or from a campaign.

  Two things a campaign deliberately does _not_ do:
  - **It says nothing on the work itself.** Commenting on pull requests that were merged months ago
    would notify everyone still subscribed to them about work nobody can act on. Backfilled observations
    are measured and recorded, and delivered nowhere.
  - **It is kept out of your live trends.** Older work has been polished since it was written, so
    mixing the two would show a dramatic improvement on the day you adopted Hephaestus that nobody
    actually made.

  Each artifact is measured once, in the state captured when the campaign runs. Nothing here can reconstruct how a pull request
  looked while it was being worked on — no draft history, no edit history, no review-thread timing is
  retained — so a backfilled measurement describes the work as it is now, not as it was.

- Practice reviews now see what earlier reviews already found and already said. A review triggered by one pull request, issue, document or thread only ever sees that one event, which meant every review started from zero and re-raised advice a developer had already been given. Each review is now also given the observations earlier reviews recorded about the same person and the feedback that was already delivered to them, so it can tell a first occurrence from a recurring one, and can stop repeating a point that has already been made twice. Claims about the earlier record are held to the same rule as everything else — a review must quote what it was actually shown, so it cannot invent a past observation to make a pattern look worse than it is.
- Automated practice reviews now see everything Hephaestus has collected about the work under review,
  not a subset chosen ahead of time. Until now each review was handed only the sources the practices in
  scope had declared, which meant most reviews never saw the rest of the project's issues and pull
  requests at all, and only some saw the repository, the wiki documents, or the conversation on the
  pull request. Reviews can now read all of it and cite any of it, so judgements that depend on
  context outside the change — whether the work is already tracked elsewhere, whether a linked design
  doc says what the change claims, what the code a changed line calls into actually does — no longer fail for want of context that Hephaestus already holds.

  This changes nothing about what is collected or kept: every one of these sources was already gathered
  and stored for every review; the cut only decided what the reviewing model was shown. Sources still
  require an unexpired use decision before they are read, a source with no collector in a deployment is
  reported as such rather than silently missing, and a practice whose required evidence did not arrive
  is still skipped rather than reviewed on a guess.

- Practice reviews no longer go missing when Hephaestus was briefly unable to run them. A review that
  could not start because the workspace was paused, its AI binding was switched off, its monthly AI
  budget was spent, or its chosen model had been removed is now picked up automatically once the block
  is lifted, instead of being silently dropped with nothing left to retrigger it.

  A merge request that leaves draft while Hephaestus is catching up on missed activity is now reviewed
  as ready. Previously that transition was noticed and then discarded, and because the merge request
  already looked up to date afterwards, no later event could recover it.

  Repeated deliveries of the same event from GitHub or GitLab no longer start the same review twice. A
  redelivery used to be recognised only while the earlier review was still running, so one arriving
  after it finished paid for the whole review again; the review cooldown minutes are now purely a rate
  limit rather than the last line of defence.

  How long a blocked review keeps waiting is configurable, and the defaults need no action: it is
  re-attempted hourly and given up on after seven days.

- Instance Silent Mode now fails closed and enforces the brake at every GitHub, GitLab, and Slack
  delivery gateway. Suppressed feedback remains auditable but is never replayed when the brake is
  released, and stale admin pages can no longer release a newer incident response.

  **Operators:** New installations and upgrades whose Silent Mode setting was never explicitly changed
  start engaged. On production, verify workspace delivery settings before releasing the brake from
  **Instance admin → Settings**; leave it engaged on staging clones and during disaster-recovery drills.

  **API clients:** Replace `PUT /admin/settings/silent-mode` with `PATCH`; the `PUT` operation has been
  removed.

- When you tick "can say what is missing" for an evidence source while editing a practice, the editor now
  tells you how much of that source a single capture actually takes — "up to the 500 most recent inline
  comments, beyond that the capture is reported as partial", and so on for every source. That bound is
  what decides whether the claim holds, and until now it was the one thing the screen did not say.
- When a review does not start because the workspace has no AI model set up for practice review, it now says so. It previously reported that every practice watching the work was switched off, which sent operators to the practice catalogue when the fix was in Administration → AI models. The same correction applies to a paused backfill. Occurrences already recorded under the old reason are relabelled on upgrade.
- The hourly allowance on hand-requested reviews is now yours to set. It is the only limit keyed on a
  person rather than on a piece of work, so it is the one that catches somebody asking for a review on
  twenty colleagues' merge requests — the per-work cooldown does not, because those are twenty different
  pieces of work. Two smaller review behaviours become settable at the same time: the run-to-run progress
  footer with its re-review reply, and dropping points an author has already disputed or marked not
  applicable.

  Defaults are unchanged, so an upgrade behaves exactly as before.

  **Operators:** all optional — `PRACTICE_REVIEW_MAX_REQUESTS_PER_REQUESTER_PER_HOUR` (default `5`, `0`
  removes the limit), `PRACTICE_REVIEW_PROGRESS_FOOTER` (default `false`),
  `PRACTICE_REVIEW_REACTION_SUPPRESSION` (default `false`).

- The limits on reviewing work that already existed are now yours to set. A backfill campaign's ceilings
  — the longest window it may cover and the largest number of items it may be confirmed for — and the
  batch size and pricing window it works from can be configured per deployment instead of being fixed at
  the values the product shipped with. The same is true of the pending-review queue: how long a review
  waits before it is offered again, how long it keeps waiting before it is retired, and how many are
  re-offered per pass.

  Defaults are unchanged, so an upgrade behaves exactly as before.

  **Operators:** all optional — `PRACTICE_REVIEW_BACKFILL_MAX_WINDOW` (default `400d`),
  `PRACTICE_REVIEW_BACKFILL_MAX_ARTIFACTS` (default `5000`), `PRACTICE_REVIEW_BACKFILL_BATCH_SIZE`
  (default `25`), `PRACTICE_REVIEW_BACKFILL_COST_HISTORY_WINDOW` (default `90d`),
  `SIGNAL_LEDGER_PENDING_RETRY_AFTER` (default `1h`), `SIGNAL_LEDGER_PENDING_LAPSE_AFTER` (default `7d`),
  `SIGNAL_LEDGER_SWEEP_BATCH_SIZE` (default `200`).

  Two smaller changes for anyone driving the API directly: creating a backfill campaign or a sweep
  schedule now returns the address of what it created, and being told "this workspace already sweeps that
  kind of work" is now its own kind of conflict rather than being reported as a campaign conflict.

- The instance-admin console now has a single "Audit log" with two tabs, "Access" and "Settings",
  instead of two separate pages, so there is one place to answer "who did this,
  and when". Both tabs share the same filter bar: filters accept several values at once (for example
  feature-flag _and_ role changes in one view) and the whole selection now lives in the address bar, so
  a filtered view can be pasted into a ticket or a chat and reopens exactly as it was — including links
  shared before a filter value was renamed, which now open the log unfiltered rather than an error page.
- Practice authoring is now framed as AI-supported practice mentoring. An author states one observable
  habit and then chooses how it is supported: **AI-supported mentoring**, **Human review needed**, or
  **Guidance only**. That choice governs only what Hephaestus may review; it never limits what a
  developer, a peer or a human mentor can observe.

  Review timing and evidence are now stated per occasion, so a practice can ask for different evidence
  when work arrives than when it is merged, with a recommended timing and evidence set covering the
  common path.

  Required evidence that is missing, that could only be captured in part, or that turned out to be
  empty makes Hephaestus skip the practice and say which, instead of guessing from what it had.

  After practice review and its model are enabled, the shipped pull-request and issue practices need no
  additional evidence configuration.

  **Operators:** if Outline is enabled, set the same `HEPHAESTUS_INTEGRATION_OUTLINE_ALLOWED_ORIGINS`
  value on the server, worker and webhook roles, then restart all three. An empty list blocks Outline
  connections, sync, webhook collection, evidence projection and identity linking. See `MIGRATION.md`.

  **API clients:** the AI purpose that runs practice reviews is renamed, as are the ambiguous review
  fields; the names are in `MIGRATION.md` and the removed ones have no aliases.

- An ingestion outage is no longer silent. The readiness check reported only that the process had started: the checks that know whether webhooks can be received, whether the message consumer is connected and whether reviews can run were meant to be part of it and never were. A deployment whose message broker had stopped accepting writes kept answering healthy while it dropped every delivery from GitHub, GitLab, Slack and Outline. Readiness now reports all of them, which is what makes an alert on it possible.

  The server also counts webhooks that were lost rather than how close a stream is to being full: if a message is deleted before the consumer that needed it has read it, that is recorded, named, and logged as an error — the one thing nobody can recover from afterwards.

  **Operators:** readiness now fails while the message broker is unreachable, which on a container that also serves the app takes it out of load-balancer rotation until the broker recovers. If you treated readiness as a liveness signal, it now reports operational dependencies too. Alert on `webhook.stream.unacknowledged.deletions` — any increase is webhook data that is gone for good — and on `webhook.stream.poll.age` beside it, because a check that cannot reach the broker reports no loss and no loss the same way.

- Webhook message streams can no longer fill the disk and take ingestion down with them. They were bounded only by a message count, which says nothing about storage: one deployment's GitHub stream reached 32.3 GB at exactly its cap, filled the host, stopped the broker writing, and dropped every inbound webhook until the broker was restarted by hand.

  Each stream now states both of its bounds: how long a delivery is kept at most, and a disk ceiling under that. Which one you actually get depends on your traffic — at low volume the time limit is delivered in full, at high volume the disk ceiling recycles the stream sooner — and the server reports the answer for your deployment as the age of the oldest message it still holds. It refuses to start if the streams together are allowed more than the broker's own budget. Lowering a limit also takes effect on a stream that already exists, instead of being reported and ignored; a change that would delete messages already stored is held back and logged with exactly what it would cost until you allow it, and a change that would leave a stream with no limit at all is held back regardless.

  Deliveries larger than the broker will carry are no longer accepted and then lost at publish: the broker is configured to take everything the receiver admits, and the receiver says so loudly if the two disagree.

  **Operators:** `NATS_JS_MAX_FILE` is **removed** and nothing reads it any more. Replace it with `NATS_JS_MAX_FILE_BYTES`, in bytes — a deployment that leaves the old variable set silently drops to the new 16 GiB default instead of the 50 GB it had. Set it below the free space on the broker's volume, and keep the per-stream ceilings totalling under it or the server will not start. The 180-day retention limit is unchanged, but a busy GitHub stream now recycles on disk well before that; `HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES` and the new `HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB` set that ceiling. A stream already larger than its new ceiling stays as it is and logs what bounding it would delete, until you set `HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES=true` once. Applying a bound deletes the excess before the broker replies, so on a very large stream raise `HEPHAESTUS_WEBHOOK_STREAM_LIMIT_UPDATE_TIMEOUT` (default `5m`) to give it room. See MIGRATION.md.

- You can now find out why Hephaestus said nothing about a piece of work. Open the trace for a merge
  request, issue or document and every practice your workspace runs against that kind of work is listed
  with what became of it — reviewed, waiting on a budget that refills, skipped because the change is
  outside the branches you review, not measurable because the diff was only captured in part, or turned
  off. A practice that is waiting for an integration nobody has connected yet says so and names the
  integration that would wake it up.

  There is also an index of everything the workspace recorded something about, built from what arrived
  rather than from what was reviewed, so work that was never reviewed appears in it too.

  Measurement and delivery are reported separately: a practice can be assessed and deliberately quiet —
  set to Propose, or after somebody disputed the last piece of feedback — and the trace shows
  both the measurements taken and the reason nothing was sent.

  Any workspace member can read a trace, not just an admin.

- Workspace practices and areas now show where they came from: whether they still match the instance
  catalog, have been changed locally, or have review rules or area details that differ from the current
  instance catalog. A workspace's copies are still never rewritten from above — this only makes it
  possible to see when they have drifted.
- Workspace owners can now permanently delete a workspace from its Danger Zone after reviewing the
  consequences and typing its slug. The flow links to the available personal data export and makes
  clear that content already posted to external providers and operational records remain. Deletion removes locally stored integration data and credentials and is blocked while integration sync or
  AI work is active.

  The workspace status endpoint no longer lets administrators bypass the owner-only deletion check by
  setting the status to `PURGED`.

  **Operators:** If automation purges workspaces with `PATCH /workspaces/{slug}/status`, switch it to
  owner-authenticated `DELETE /workspaces/{slug}`.

- Workspace administrators can now cap what their own connected AI provider spends each month, under
  Administration → "AI usage". It is their own money, so it is theirs to set, change, or remove — and it
  is separate from the budget the instance administrator funds and sets for shared models. The two
  never add up and never pause each other: if the shared-model budget runs out, work on the
  workspace's own provider keeps running, and vice versa.

  The usage page now shows each cap on its own meter, warns at 80% with a projection of when this
  month's pace would reach it, tells you whose cap paused what and who can lift it, and reports the
  average cost per review or mentor turn alongside the monthly totals. Raising or removing either cap
  now resumes the work it paused within about a minute, instead of leaving it queued for up to an
  hour. The instance overview gains a read-only column showing which workspaces have capped
  themselves, how much of each cap is used, and which cap paused a workspace.

  The budget an instance administrator sets for a workspace bounds only work on _shared_ models — the
  spend the instance is billed for. Work a workspace pays for through its own connected provider is
  bounded by that workspace's own cap. Neither cap is a way to stop all AI work in a workspace; the
  workspace's status and its feature switches are.

- A workspace can now say which of its work gets reviewed at all. Under the practice-review settings,
  name the target branches and the repositories in scope; a review only starts when the pull request or
  issue matches. Leave a list empty and that axis is unrestricted, so nothing changes for a workspace
  that never touches this.

  This is the setting for "we only review merges into main" or "review the two repositories that matter,
  keep syncing the rest". A practice cannot express it, because a practice is shared and cannot know
  whether your trunk is called `main`, `master` or `develop` — that is a fact about your deployment.

  Names are matched exactly: there are no wildcards, and there is no path filter, because the files a
  pull request changes are not yet known at the point where the decision to review is made. A branch
  list does not restrict issue reviews, since an issue has no target branch.

### Patch Changes

- Fixes valid observations being discarded when a model faithfully copied source text but normalized straight quotes, dashes, or spaces to typographic equivalents. Evidence verification now normalizes only that closed set before comparing the quote with its source; text that is not present in the source is still rejected.
- A practice a workspace has turned off is reported as turned off everywhere, including in the API. It was previously reported as "silenced", which is what a practice set to Propose does — reviewed, but kept quiet — so the two states read as each other.
- The AI console's warnings now name things that still exist. Turning a model or a connection off, or taking a workspace's access away, said "existing configurations will stop" — configurations were removed in this release. It now says what actually stops: practice reviews and the mentor, and what to do to start them again.

  The exchange-rate footnote on the AI usage screens no longer names a rate provider the figures cannot vouch for, and a closed month now quotes the frozen rate it was converted at rather than only asserting that it will not move.

  Add connection, Add model and Manage access now scroll their own contents on a phone, so the title stays put and the save button stays reachable instead of the dialog running off the top and bottom of the screen.

- Fixes AI spend being reported as zero for a run whose agent finished without writing a usage report. The tokens the proxy already saw go out are now billed to the workspace's month, so budget caps act on what was really spent.

  Also fixes a rare failure when changing a model's price while it already had one, and stops two admins editing AI settings, provider connections, or spending caps at the same time from silently undoing each other's change.

- Fixes AI spend being under-reported, often several-fold, on long reviews. A review's own token report only covered the part of its conversation still in memory at the end, so calls the agent made earlier went unbilled while the proxy had already sent them upstream. Spend is now billed from whichever record saw more, and each entry says which one that was.

  Fixes a workspace staying blocked for the rest of the month after a single AI call could not be priced. Add the missing model price and the block now clears by itself within fifteen minutes; the unpriced-events count on the AI spend page also stops disagreeing with what is actually holding the cap shut.

- A single AI run can no longer spend past a workspace's monthly cap. Previously the cap was only re-checked against spend that had already been recorded, so one long run could make many provider calls before any of them counted — a workspace with a $1 cap could reach $100 in one run. Each run is now refused as soon as its own calls have used up the remaining budget.

  Per-run token counts are also attributed correctly when a run is retried: a slow response arriving after its run was requeued is no longer billed to the retry.

  The runs table and run details no longer show a per-run Cost. That number was recorded before AI spend had a ledger, and it was stored at a precision that could not represent cents exactly. Spend now lives on the AI usage page for the workspace and the instance, where it is broken down by month, job type and who pays.

- Operators can now upgrade practice reviews without losing curated overrides or being blocked by one malformed catalogue entry. Worker readiness reports disabled repository evidence and expired review authorization.
- Stops a container log from filling the host disk. The reverse proxy, the maintenance page, the
  database and the release-pin fetcher were the last services still writing an unbounded log, so a
  retry loop — an unreachable certificate authority, a failing signature check, a rejected database
  connection — could grow until the disk was full and took the whole deployment down with it. Every
  container in the stack now rotates its log with the same caps the application containers already
  used. Existing logs are rotated from the next restart; nothing is lost that was going to be kept.
- The instance practice catalog now states whether a pending Hephaestus update changes wording or changes review behavior, instead of distinguishing the two by badge colour alone.

  Practice authoring explains why AI-supported mentoring is unavailable when no model is configured for a work type, rather than offering an option that cannot be selected alongside copy describing a review that will not run.

  Fixes the "Why is human review needed?" field reporting itself as invalid without stating what is wrong, and no longer renders that same reason twice under two different labels.

- The startup error for a wrong-length encryption key now says which length is wrong. It reported only
  bytes, so an operator who had pasted exactly 32 characters — with one accent or umlaut among them,
  which costs more than one byte — was told to produce a 32-byte key while looking at what they
  already believed was one. The message now gives both counts, says which of the two is the problem,
  and repeats the command that generates a valid key.
- The Communication practice area now shows its own icon and colour instead of a grey placeholder. The catalogue gained the area, but the screen that draws it kept an older list and quietly fell back for anything it did not recognise.
- The settings-change audit trail now covers workspace administration, not just AI configuration: a
  member's role being granted, changed or revoked, a member being hidden or unhidden, features being
  enabled or disabled, a practice's review-autonomy setting being changed, the workspace being paused or
  purged, the SCM access token being rotated, and public visibility being toggled are all recorded with
  who did it and the before/after. Credentials are never stored — a token rotation records only that a token was
  rotated, and when. Connecting or disconnecting an integration continues to be recorded on the
  connection's own history.
- Settings and admin pages now maintain consistent spacing across screen sizes, while Mentor and the
  achievement designer use the available workspace without overflow.
- Evidence source descriptions, practice-authoring copy, and review results now use one vocabulary. A review that cannot run reports that it skipped automated review, rather than describing itself as declined or refused, and the practice editor says plainly that choosing a source neither collects nor authorizes it.
- Conversation threads now appear in review activity like every other kind of work: a settled discussion that was reviewed shows the review it started, and one that was passed over shows the reason. Previously chat was invisible there, so a thread nothing happened to left nothing to explain the silence.

  A conversation review stopped by something that later clears — an exhausted AI budget, a practice switched back on — is now retried instead of being lost, and one whose Slack channel loses consent in the meantime is dropped rather than retried.

- Money now reads correctly across the AI usage screens: nothing spent shows as `$0` instead of `$0.000`, an amount too small to show in cents shows as `<$0.01` instead of rounding to zero, and caps drop trailing cents (`$50`, or `$49.50` when you set cents).

  The AI screens now use one word per idea — "shared models" for what your host pays for and "your provider" for what you pay for — instead of nine different names for the same two things. Every cap says who set it, every pause says who can lift it and by when, and warnings arrive before the wall rather than after it. Amber warning text is also darkened so it meets contrast requirements in the light theme.

  Advanced run limits in AI models are now a proper expandable section that screen readers announce, and clearing a timeout or concurrency field shows an inline error instead of silently saving zero.

- Fixes a fresh install failing to start on its very first boot. The application server wrote a
  per-request access log into a `/var/log/hephaestus` volume, and a newly created volume is owned by
  root, so the server could not write there and aborted instead of coming up. Nothing shipped or
  collected those files anyway; the log and the volumes that held it are gone from the compose stacks,
  and the application server no longer writes a line per request at all. Startup problems, errors and
  sync activity still appear in `docker logs`.
- Written documents are called documents wherever they appear. The practice editor's kind-of-work
  picker, the review activity list and its filter showed the raw `docs.document` identifier beside
  "Pull or merge request" and "Issue", and a document's review results carried a chat-thread icon.

  A practice's review-autonomy setting is also called the same thing on both screens that show it: the practice
  catalog and review activity disagreed over what to call the setting that reviews a piece of work and
  then says nothing.

- Workspaces that upgraded with an empty practice catalog receive it on the next start, and workspaces
  created before the catalog existed are matched back to it where their review rules or area details
  still match the bundled defaults.
- Every practice-review result can now be traced back to what produced it: the run records the model
  and prompt version that ran and a fingerprint of the evidence the review actually saw, so a result
  that looks wrong can be told apart from a result produced from different inputs than you assumed.

  Every piece of feedback the instance prepares is also recorded as either delivered or withheld, with
  the reason it was withheld. Feedback that never left the instance no longer looks the same as
  feedback a developer saw and chose not to act on.

- `GITLAB_WORKSPACE_CREATION` and `PRACTICE_REVIEW_FOR_ALL` now do what the documentation says
  wherever you set them. Both were names the shipped compose files translated into something else, so
  on any deployment that does not use those files — Kubernetes, a plain JVM, your own compose — setting
  them did nothing at all and reported nothing. They are now settings the application reads directly.
  The longer `HEPHAESTUS_FEATURES_FLAGS_…` spellings keep working and still take precedence, so
  nothing has to change.
- Fixes practice feedback silently never arriving when a review finishes while a large provider sync is running. The feedback written for the developer themselves, and the mentor follow-ups, are now prepared on their own capacity, and anything that still slips past is picked up and prepared within the hour instead of being lost.
- Feedback written for you now refers to your work by the number, title and link you would recognise,
  so a reference you follow lands on the change it is about. It previously cited an internal storage
  number, which read as a merge request or issue number and pointed at unrelated work — feedback about
  `!22` could arrive naming `#306`. Work that cannot be named — a chat thread, say — is now described
  rather than numbered.
- Feedback on your own practice pages now opens with what moved rather than restating the standing problem.
  A card that says the same sentence every time it is written is a card you stop reading, so it now leads
  with the change — this is the third piece of work, or it was three and is now one, or it has stopped
  happening — and holds back entirely when nothing moved.
- Filters on the practice-review screens say what they filter and can be taken back off. The date filter is now named after the date it narrows — Observed, Composed, Changed, Occurred — and can be cleared from inside the picker; an over-filtered list offers "Clear all filters" instead of only advising you to remove one; on a phone the applied filters appear as removable chips rather than as a count; and the person filter says when a workspace has more members than it can list, instead of answering "no matches" for someone who is simply further down the list.
- Fixes three ways the AI console could mislead you about what it had just done.

  Reopening the spend-cap dialog after the server rejected an amount no longer shows that rejection against an empty field, so the error you see always belongs to the number in front of you. Deleting two models one after the other without waiting no longer re-enables the first row's Delete while its request is still running — which could send a second delete and report a failure for a model that had in fact been removed.

  Adding a connection or a model from the instance console now checks what a workspace admin's form has always checked: a provider URL carrying an API key, a query string or a fragment is refused with an explanation instead of being sent and rejected by the server, and so is a "priced" model whose rates are all zero.

- Saving a task in AI models now shows what you saved. Repointing practice reviews or the mentor at a different model, or changing its timeout, concurrency or internet access, no longer snapped the card back to the previous value under a "saved" message — which invited you to save again and write the old value back over the new one.

  Delete confirmations across the AI screens now close when you confirm them, instead of staying up over the row that just disappeared and offering Delete a second time, which failed and reported an error for a delete that had worked. They can also always be dismissed with Escape or Cancel, including while the request they started is still running.

  On a past month, the note that replaces the cap editor now tells you where to change the cap instead of only saying it cannot be changed there.

- Fixes the workspace AI models page failing to load when a purpose is bound to a model: listing the bindings returned a server error instead of reporting each purpose's model and readiness.

  Also clarifies the message shown when a review is not started — it now names the two causes an operator can act on (the practice-reviews model unbound or turned off, or the workspace's monthly LLM budget exhausted) instead of referring to the retired agent-config concept.

- Fixes several ways the AI console could lose an edit or say something untrue about one.

  Saving practice reviews and the mentor one after the other without waiting no longer re-enables the first card while its request is still running — which looked idle and accepted a second click. Timeout, concurrency and internet-access edits you have open are also no longer discarded when another admin repoints that purpose at a different model.

  On a past month, the amount field in a cap or budget dialog no longer estimates "at today's rate" using that month's frozen rate, and the instance AI usage table now says why the Set budget buttons are absent instead of just leaving a gap. The workspace access dialog can now be closed while its save is in flight, so a provider that accepts the request and never answers no longer traps you in it.

  Turning off a provider connection that has a single model reads as one model rather than "all 1 models", and a connection with no models on it turns off without asking you to confirm something that stops nothing.

- Fixes two ways the instance AI console could leave you stuck without saying why.

  Adding a model with a context window or max-output value the server won't accept now shows the reason under the field it belongs to. Before, "Add model" simply did nothing: the form rejected the value, no request was sent, and nothing appeared on screen.

  Choosing who may use a model now highlights the option you picked — the whole card tints and takes a coloured border, instead of only a small radio dot changing.

- One busy workspace can no longer hold up everyone else's reviews. A workspace sitting at its
  concurrent-run limit with a long queue of practice reviews was repeatedly picked ahead of workspaces
  that had work ready and capacity to run it, so those waited behind reviews that could not start. Work
  is now shared out per workspace and purpose, which is how models are assigned.
- Fixes practice reviews and the Slack mentor reporting themselves as unavailable for workspaces whose model is bound through a workspace's own connected provider: the readiness check failed while loading the bound model instead of answering, so reviews were skipped and the mentor showed as not ready even though the model was configured and working.
- Fixes the release-image pin check rejecting every valid pinned digest, which stopped the
  application server from starting on a fresh deploy that enforces the digest pin.
- Fixes a workspace admin page that any member of the workspace could open by visiting its URL
  directly. The page's actions were already refused by the server, so it showed only errors rather
  than any data, but it should never have been reachable — every workspace admin page now redirects
  non-admins away. Two smaller fixes ride along: an administrator whose role is revoked mid-session
  no longer keeps the admin UI until they reload, and an instance administrator with no workspaces
  yet can once again reach the "Create Workspace" button.
- On GitLab, a review summary that was already posted on an issue is now recognised as such. Previously
  the check only ever looked at merge requests, so an issue whose summary had been posted just before a
  restart could receive a second copy of the same comment. The same check on merge requests now starts
  from the newest comment, so it finds a just-posted summary immediately instead of paging through a
  long discussion and giving up.
- Approving a GitLab merge request now works. The approval was sent to an endpoint GitLab has never
  offered, so every attempt failed; it now goes through GitLab's approval API, and a refusal (for
  example, a bot cannot approve a merge request it opened itself) is reported with its reason.
- Practice reviews are no longer skipped because a pull request or issue has not changed recently. Hephaestus previously treated a record that had not been modified upstream in the last five minutes as out-of-date evidence, which skipped automated review for established repositories and for every review not started by a webhook.

  Reaching a collection limit no longer skips a practice either. A pull request with several hundred review comments, review threads, or linked issues is now reviewed from the evidence that was collected, and the review records that the evidence was partial.

  An issue reference that points outside the repository, such as one tracked in another system, is now reported as unresolved instead of marking the evidence incomplete and skipping the practices that read linked work.

  Practice authors now see clearer descriptions of what each evidence source contains, including the limits that apply to it.

- Every deployment is now clearly identifiable. Outside production the header shows
  an environment pill (Staging / Preview / Local) instead of a raw commit hash, and
  the footer gains a deployment strip — branch, commit (linked to the exact commit),
  and how long ago it was deployed. Production is unchanged: the header shows the
  release version linking to its notes, and the footer stays clean.
- Practice dashboards and mentor summaries no longer include observations from repositories hidden from
  contributions.
- A review can now tell the difference between evidence it looked at and found nothing in, and evidence
  it never got to look at. Sources that turned up empty — a pull request nobody commented on, a project
  with no other tracked work, a change that links no documentation — used to be left out of the
  review's workspace entirely, which looks exactly like a source that failed to collect. They are now
  always present and simply empty, which removes a class of observations that were confidently right or
  confidently wrong for the same reason.

  The trace says one thing about a source rather than three that contradict each other: a source
  nothing captured is reported as not captured, and "captured only in part" and "captured empty" appear
  only where a capture actually happened.

  Where a source must not be empty, that is now enforced: a pull request whose diff turns out to
  contain no changes is skipped rather than reviewed from its title and description alone. A Slack
  thread in a channel whose consent is paused or withdrawn is reported as withheld rather than as an
  empty conversation, so a developer is never reviewed on messages Hephaestus was not permitted to
  read. And a query that could not be run at all is recorded as a collection error rather than as an
  empty result, which would read as an established fact about the work.

- Fixes per-workspace LLM spend being over-counted when an agent job was retried after an infrastructure failure: each retry attempt's token usage is now billed to the usage ledger exactly once, so monthly spend and budget-cap enforcement reflect real cost. Also stops a job that died without a recorded price from blocking its own terminal cleanup, and prevents deleting a model that is still bound to a workspace's practice-review or mentor purpose.
- Agent job runs now show which **model** ran them, from submission onward — the column previously showed a named agent config and had gone blank for new jobs. The non-functional "Model" filter on that table (it filtered by the retired agent config and matched nothing) is gone.

  Review → When and where no longer offers a second, competing way to bind the practice-reviews model: it reports which model reviews run on, warns when nothing can run, and links to the **AI models** page, which is the single place bindings are edited.

- Fixes login and other database operations intermittently failing. The build's
  10 MB off-heap direct-memory default sits just below the application server's
  steady I/O footprint, so once it filled, PostgreSQL could no longer allocate the
  buffer for its connection handshake and the connection pool drained. The server
  and worker now get 128 MB of direct memory, and `APP_MAX_DIRECT_MEMORY`
  overrides that if a heavy backfill ever approaches the limit.
- Adding a second AI model that points at the same provider model id now says so, instead of failing with a generic server error. The message names the id and the connection, so the fix is obvious: rename the upstream id, or edit the model that already claims it.
- Longer feedback now reads as a sentence in the Delivery list.

  A row used to print the first 320 characters of the composed note exactly as stored, which on a note
  of ordinary length is a bold heading, a file name in backticks and the opening of a code block — so
  the one line that is meant to say what the feedback is about read as markup. It now shows the
  opening words as prose and marks that there is more to read. Opening the feedback still shows the
  whole note, and a code quote inside it no longer scrolls sideways out of reach on a narrow screen.

  Two smaller fixes alongside it: the options list in a filter now announces what it is to a screen
  reader, and a list narrowed to a single row says "1 review matches your filters" rather than "match".

- The mentor now follows its own guidance when it answers you. Its briefing — how to cite an
  observation, which context to fetch, how to phrase feedback — was being dropped, so replies came back
  in the voice of a generic coding assistant.
- A mentor conversation now counts against a workspace's monthly cap while it is happening, not only once the reply is finished. A single long conversation could previously run past an exhausted cap because none of its spend had been recorded yet. Streamed replies are now metered too, and a conversation cut short by a crash is billed for what it actually used instead of being recorded as unknown. A call a mentor sandbox makes outside a conversation — before its first message, or after the reader has gone — is now refused instead of served, because nothing exists to bill it to.
- The API now states what its money actually is. Every amount and per-unit rate is marked `decimal` in the OpenAPI document, so a generated client binds it to an exact decimal type instead of a floating-point one, and the API description spells out the precision each figure carries and the rule that totals are read from the response rather than added up by the caller. Nothing on the wire changed shape, so no client needs updating.

  The euro estimate on the AI usage screens also names its source again: the disclosure now reads "at the European Central Bank reference rate published on …" rather than "at the reference rate", and the rate's publisher travels in the response instead of being assumed by the page.

- Practice review settings now name every way a review can start. The switch that read **Manual
  reviews**, described only as the `/hephaestus review` comment command, is now **Reviews somebody asks
  for** and says outright that it also governs the _Review this now_ button, backfills of past work and
  recurring checks. A workspace that had it switched off was getting none of those, with nothing on the
  screen saying so — and on GitHub, where the comment command is not published at all, the setting
  described the only thing it could not do.
- The consumer-expiry setting now takes effect on deployments that already had consumers, and no longer lengthens the lifetime of the short-lived ones. Setting it previously did nothing unless a consumer happened to be created afterwards, and where consumers were unnamed it extended how long they lingered instead of shortening it. A negative value is now rejected at startup rather than accepted.
- The containers that serve the web frontend and the maintenance page no longer write a line per
  request. Those lines recorded the URL of every page view, and a link to a person's profile page
  carries their username, so the request log was a per-request record of who looked at whom — kept for
  however long the container's log rotation happened to hold it. Turning it off restores what the
  deployment always claimed: no layer of the stack writes a per-request record. Startup problems and
  HTTP errors still appear in `docker logs`, and nothing else changes: container health checks and the
  reverse proxy never read that log.
- A review that says something is missing now holds back when it only saw part of the evidence, instead
  of reporting it anyway. Four practices make that kind of claim — merging past an unresolved review
  thread, not engaging with inline comments, closing an issue with an unmet outcome, and an untraceable
  handoff — and a partial capture of the comments or threads cannot tell "nobody did this" apart from
  "the part we did not fetch is where they did it". Those reviews are now skipped and reported as
  skipped, which is visible on the practice's evidence readiness, rather than producing an observation the
  evidence never supported.
- The two AI cost pages now use one vocabulary. The number a host grants a workspace is called
  **shared-model budget** everywhere — in the instance console's table, its row action, and the dialog
  that edits it. Previously one click path called it four different things ("Set instance cap" → "Set
  shared-model budget" → "Save budget" → "Remove cap").

  Cost figures now say **run** rather than "call" or "event", which is what they actually count: an
  un-priced review shows as "2 runs aren't counted in these totals", and the breakdown tables have a
  "Run type" and a "Runs" column.

  Other copy is clearer about what to do next:
  - When a shared-model budget is reached, the banner now says practice reviews and Mentor can keep
    running on your own models, and links straight to AI models.
  - "Bound model cannot run" is now "The review model is unavailable", with a plain reason.
  - A workspace's status in the instance table names the money stream that stopped it ("Paused ·
    shared models" / "Paused · own provider") instead of an internal cap name.

  The instance console also gained the burn-rate warning the workspace console already had: expanding
  a workspace that is past 80% of a budget now shows when this month's pace would reach it.

- Speeds up the practice pages and the mentor's review history. Checking whether each observation's evidence may be shown used to cost one database round trip per observation, so a developer with a few months of review history waited on hundreds of them; the check now covers a whole page in a single query.
- The words _observation_ and _feedback_ now hold on the screens either side of Practice reviews, not
  just inside them. Deleting a practice warns that it removes the practice and its **observations**,
  and the user and administrator guides say observation everywhere they described the same thing as a
  finding.

  The instance practice catalog no longer hides the work a practice reviews inside a "Technical
  details" disclosure. Which work it applies to, and the contract version its evidence is written
  against, are stated on the card; the three digests that answer "which exact rules produced this
  verdict" sit beside the validation verdict they belong to.

- The API reference and the Review screen now describe the tier ladder as what it is — how much
  the system may do on its own — instead of how loud it is. The API reference previously described a
  practice's tier as "how loudly the workspace runs this practice", which read as a volume control and
  left it unclear that Propose still runs the review and still records every observation.
- Fixes pages that could be dragged sideways on a narrow phone screen while a tooltip, menu, popover or preview card was open.
- Work that could not be reviewed when it arrived no longer stays queued forever. A review blocked by
  something an operator can lift — an exhausted budget, a paused workspace, a practice turned off — is
  retried on a schedule and, if the blocker never clears, is finally retired and marked as such. It
  previously kept its place in the queue indefinitely: the retry deadline could never be reached, so the
  trace showed "Queued for review" for work nothing would ever review, and each of those items re-ran the
  full review gate every hour for as long as the instance lived. Long-stuck items now also stop crowding
  out newer ones.
- Point at a practice on Practice setup or Review and a card tells you what it is for and what good looks like, without leaving the list. It opens on hover and on keyboard focus; on a touch screen the name still opens the practice itself, where the same wording is on the form. Review's list is lighter for it — a workspace with a hundred practices can be scanned rather than read — and the filter above it now shows both choices, "All" and "Set by hand", instead of a switch that only named one of them.
- User settings now make clear that the practice-feedback preference controls comments on pull or
  merge requests and Slack reminders.
- Practice reviews run again. The review agent was started without one of the helper scripts it loads, so every run failed inside the sandbox before it reached the model — the job was recorded as failed with no feedback produced.
- Turning on practice review no longer leaves the instance reporting itself as out of service. The worker that runs reviews was never given the git-checkout setting the rest of the deployment gets, so enabling reviews produced a deployment that reported `GIT_CHECKOUT_DISABLED` and reviewed nothing. The worker now reads the same setting as the application server.
- Practice reviews no longer fail on a deployment whose repository volume was created by an earlier release. The agent writes its evidence store under the git-checkout volume, and a volume created root-owned — or owned by the user id a previous image ran as — left that store unwritable, so the first review of an upgraded instance failed with a permission error instead of running. Ownership is now corrected before the application starts.
- The practice screens now say what happens rather than naming the product doing it, including the footer on every delivered review comment. Feedback held for a developer's next mentor chat reads "Prepared for conversation".

  Segmented pickers show which option is selected: the selected and hovered states were the same colour, and joined groups drew a doubled seam between every segment. The autonomy ladder reads as one control at every window size, the rendered/source switch behaves like the tabs it looks like, and the review settings no longer scroll sideways on a phone.

  Settings pages lost their nested cards in favour of plain sections, and readiness is stated once per page instead of three times.

- Public pages now explain Hephaestus in plain language: it gives developers feedback on the engineering practices they use in software projects, while Heph is the separate conversational AI mentor. Pull requests, merge requests, and issues are used as current examples rather than the boundary of the product. The landing page and documentation no longer present the optional leaderboard as the main product, claim that Hephaestus replaces a human mentor, or advertise workflows that are not available.

  The landing page now pairs a clearer value proposition with an open, responsive preview of project work, practice feedback, and a conversation with Heph. A second animated visual follows the full cycle from project work to developer choice and shows the implemented delivery options, with dedicated phone, tablet, and desktop compositions. The new visuals replace the scoreboard and unshipped pull-request conversation previously used in the hero. The README includes deterministic, theme-aware exports of the same Storybook components, with a tablet composition where the desktop loop would be too dense. Shared links also include a description and social-card metadata.

  The README distinguishes implemented delivery surfaces from the broader feedback loop and links to the release plan for remaining scope. It explains how GitHub, GitLab, Slack, and Outline contribute project context, and describes the three ways to receive feedback without tying them to specific page names. It also explains what pre-1.0 releases mean for self-hosted deployments and provides clear paths to the app, documentation, Storybook, and contribution guide. Theme-aware artwork shows the human story from project work to feedback and developer choice, with phone, tablet, and desktop compositions that remain readable at each size.

  The user guide now matches the shipped multi-workspace GitHub and GitLab setup, current Heph chat, practice-feedback delivery, optional leaderboard and leagues, and configurable Slack digest. Account settings now state clearly that turning off pull-request comments controls delivery only; reviews still run and observations remain available to workspace admins.

- When a review has more to say than one comment can hold, the suggestions that survive are now chosen by how much of your change they were actually seen in, rather than by how sure the reviewer said it felt. Previously every observation carried a self-reported confidence score, and that score decided which suggestions made the cut and which strength got acknowledged. Measured across 580 real observations it never once dropped below 90% and was a flat 100% more than half the time — so it was deciding those cuts on noise. It is gone.

  Observations are now ordered by severity first, then by how many distinct places in the change the observation is quoted at: a habit running through four files leads a one-off, and problems always precede strengths. The order is stable, so re-reviewing the same work reproduces the same list rather than shuffling it.

  The review detail page no longer shows a confidence percentage, because there was never a real measurement behind it. The unmeasured confidence field and previously stored values are removed rather than retained as a misleading signal.

- A pull request whose diff could not be read is no longer reviewed as though the code were fine. The
  failure was swallowed and the review went ahead with nothing to examine, so it could report observations
  about changes it had never seen. The practices that need the diff are now skipped and the review says
  so.
- Removes the retired agent-runtimes screen and its named-agent-config editor from the admin UI. Workspace AI setup lives on the single **AI models** page, where each purpose (practice reviews, mentor) is bound to a model directly.
- Turning off a workspace's mentor now prevents new Slack mentor turns and reminders and stops new suggested prompts from being added.
- Fixes dialogs being unusable on small screens. A dialog taller than the window had no height limit, so it hung off both edges with its title and its save button unreachable — on a 320px-wide phone the AI model form rendered 300px above the top of the screen. Dialogs now fit the window and scroll inside themselves, keeping the header, footer and close button in place. The job details panel also opened at 240px wide on a phone instead of filling the screen, and confirmation dialogs left no margin at all at 320px.

  The AI usage and job screens now reflow properly down to 320px, and at 200% text zoom: wide tables scroll inside a bordered area instead of dragging the whole page sideways, and the instance usage table's expanded detail no longer opens a second horizontal scrollbar inside the first.

- The Review page now says what each practice is. Every row names the kind of work it reviews, so deciding how far its reviews go on their own no longer means recognising a practice by its name alone. The tier controls for areas and practices line up in one column instead of starting at a different place on every row, and on a phone the Off/Propose/Deliver choices are readable again — they were being squeezed down to a single letter each.
- Practice-review settings now reach the container that acts on them. Whether a review posts a progress note, whether it reacts to the comment that asked for it, and whether it may deliver on already-merged work are all decided while a review runs — in the worker, which was never given those values, so setting them changed nothing. The same is true of the guardrails that bound a review of past work and the timings that decide when an unsettled review opportunity is retried or given up on.

  **Operators:** no action. Every setting keeps the value it effectively had, since the worker was falling back to the built-in default. If you had set one of these expecting it to take effect, it will now do so — check `docker/.env.example` for the full list and the defaults.

- The reviews list now shows what each review produced as aligned columns of counts, including the zeroes, so the same number sits in the same place on every row and two reviews can be compared at a glance. Previously the counts were written as a sentence with the zeroes left out, which moved every figure and reflowed while a running review refreshed.
- Filter the practice reviews list by when a review was requested. The list shows that date on every row but offered no way to narrow by it, so answering "what did we review last week?" meant paging through everything. A date range now sits beside the status filter, it combines with the status rather than replacing it, and the range travels in the link — so a filtered list can be bookmarked, shared, and returned to from a review's details. This matches the observations and delivered-feedback lists, which already took a date range.
- Fixes a review that finished normally and then produced none of the feedback written for the
  developer themselves. Composing that feedback was skipped whenever the review had been told partway
  through to start writing down what it had found — ordinary on any review of real size, so at any
  normal time allowance it meant almost every review — and skipped again whenever a review used its
  full allowance, because time was still being held back for a retry that could no longer happen.
  Composing now goes ahead whenever a review genuinely finished with enough time left to write, and a
  review that broke off mid-run still keeps its retry allowance.
- Workspaces created after startup now receive the default practice catalog without requiring a server
  restart.
- Practice reviews recover on their own when the host reclaims the agent image. The image is only referenced while a review runs, so a host that prunes unused images removes it between reviews — and because it was fetched once at startup, every later review failed to start its container and retried into the same failure. The image is now re-established before each run.
- Fixes agent sandboxes failing to start on shared hosts with many processes owned by the container user.
- Fixes the segmented filter and view controls across the admin console so screen readers announce them correctly, and gives the practice-catalog switches and checkboxes a name that is the label alone rather than the label run together with the sentence explaining it.
- Setting a single practice's review tier works again. Turning one practice up or down from the
  practices screen failed with a conflict error every time, while the same change made at the area or
  workspace level went through — so the only way to quiet one practice was to quiet its whole area.
- Silent Mode no longer holds back the feedback a developer reads inside Hephaestus. Silencing an
  instance is meant to stop Hephaestus writing anywhere outside it — comments on merge requests,
  messages in chat. It was also stopping the private, longer-term feedback on a developer's own
  practice pages, which never leaves the instance at all. A recovery pass picked those up within the
  hour, so they arrived late rather than never; they now arrive with the review that produced them.
  What Silent Mode stops is unchanged: nothing is posted on the work, and nothing is said in chat.
- Silent Mode now marks the piece of coaching it actually stopped. When an instance is silenced, feedback prepared for a mentor turn is recorded as withheld by Silent Mode — but only for an observation the turn was allowed to raise in the first place. Feedback about work whose evidence is no longer readable, or whose practice has been re-configured since the review ran, stays prepared instead of being retired under a reason that was never the one that stopped it, and it can still be raised once it is readable again.
- Fixes practice dashboards reporting different numbers each time the same page is reloaded, and —
  where two workspaces review the same pull request or issue — reporting nothing at all for that piece
  of work. Both came from how the most recent review run was picked: the choice was not settled between
  two runs recorded at the same instant, and it was not confined to the workspace being looked at, so a
  run belonging to another workspace could win and drop that work out of every count on the page.
- Stale integration timestamps now include a text label instead of relying on color alone.
- The mentor now composes each coaching turn from structured notes and the live conversation instead of reading out a question prepared earlier. A practice review supplies the situation, the capability to develop, an evidence summary, and a signal that can be observed in the current conversation. The mentor can ask, explain, or wait based on the conversation, and it receives the authorized observation evidence needed to verify and adapt those notes.

  Malformed or oversized briefs are rejected rather than truncated or interpreted through a retired compatibility shape.

- The AI usage and AI models screens now say each thing once. Page subtitles that repeated the page title are gone, field hints that restated their own label are gone, and the explanation of the two separate budgets — the one your host funds and the one you fund — appears once per screen instead of four times. The pause banners on both screens now come from one place, so they can no longer disagree about how to resume; "Add a price in AI models to resume" and "Add a price on this page to resume" were the same pause described two different ways. Pause messages in the mentor chat and in run logs are shorter and no longer mention budgets a student has no way to act on.
- Page controls on the achievements, users and sync-jobs tables are now real buttons, so they can be reached with a keyboard and a screen reader announces the first and last page correctly. Previously they looked greyed out but still took focus and claimed to be available.

  Resetting a league now refreshes the leaderboard on screen instead of leaving the old standings until a reload.

- Practice authoring no longer hides short lists of choices behind dropdowns. The kind of work a
  practice reviews is chosen from radio buttons that show every option and what each one means, and an
  evidence source's role is a radio group showing required, optional context and not used together.
  Whether a review may say something is _missing_ from a source is a checkbox beside it. Those
  controls now sit at the leading edge of their label rather than at the far right of the row.

  A claim a source cannot establish is no longer offered at all, rather than appearing as an option
  that cannot be selected: a source that can never be captured in full loses the checkbox rather than
  showing a greyed-out one.

- Practice reviews can now read the change they are reviewing. The role that runs a review had no GitHub credentials, so it could not fetch the commit the review was pinned to; every review finished as "insufficient evidence" without ever asking a model. The SCM credentials and the local-checkout setting are now shared by both application roles, so a value the operator sets reaches whichever role needs it.
- Fixes practice reviews and mentor replies never arriving on deployments that run the background
  worker as its own container with the Slack integration switched on. The worker never finished
  starting and was restarted over and over, so nothing picked the queued work up, and the people
  waiting on a review or a reply saw no error — only silence. Slack channel syncing was, and remains,
  the application server's job. Deployments that run everything in one container, or that leave Slack
  switched off, were never affected.

## 0.73.2

### Patch Changes

- Fixes a release deploy that never started: the signature check on the pinned agent image rejected every
  valid release, so the application server stayed down.

---

Newest first. Entries are authored as [changesets](https://github.com/changesets/changesets) in each PR
and assembled on release — see the
[release management guide](https://ls1intum.github.io/Hephaestus/contributor/release-management).
Releases up to and including [v0.73.1](https://github.com/ls1intum/Hephaestus/releases/tag/v0.73.1)
predate this file; see [GitHub Releases](https://github.com/ls1intum/Hephaestus/releases) for their notes.
