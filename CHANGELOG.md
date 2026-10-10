# Changelog

## 0.89.1

### Patch Changes

- Error-handling review standards judge severity from a path's source-established role and consequence. An optional or best-effort operation does not automatically count as a major shortfall.

## 0.89.0

### Minor Changes

- Workspace activity lists everyone with work in the range in one table that you can sort by each count. It sorts by Contributions first: pull requests opened, pull requests reviewed, and issues opened. A sorted table shows each person's position. Choose 30 days, 90 days, 12 months, all time, or a custom range, then pick a team and repositories. The address keeps your view, so you can share it. The page says how far back the history is complete. A **New** badge marks a first contribution in the range. Each figure uses GitHub's or GitLab's own icon and colour. Bot accounts appear apart under Automation, and a workspace admin can treat a machine user account as automation or count it as a person again. Open a person to see their weekly bars, their counts in each repository, and their work. Your own Activity page gets the same ranges and weekly bars, in place of the daily and monthly bars. On GitLab, drawers, popovers and tooltips now use GitLab's colours too.

  **Operators:** Custom activity API clients read the new `kind` field (`PERSON`, `BOT` or `AUTOMATION`) instead of `automation`. The bundled webapp needs no action.

- A workspace that publishes its activity now has a public page that anyone can open without signing in, at the workspace's address. It shows who contributed to the public repositories, in the same table that members see, in GitHub's or GitLab's own words and icons. The page says what it shows and lets people sign in to hide themselves. It asks search engines not to list it unless the workspace allows that.

  Workspace admins turn the page on under **Workspace settings**, after a confirmation that states what becomes public. They can allow search engines, see how many people are hidden, and hide a person who has no account from **Workspace activity**. Instance admins allow public pages under **Instance settings**. The first time a person opens a workspace with a public page, a step asks **Show me** or **Hide me**. **User settings** has the same choice as **Show me on public activity pages**.

- You can publish human contributions to public repositories with separate instance and workspace controls. Public pages default to off on self-hosted instances. People can hide their activity across all public pages, and workspace administrators can honor objections from contributors without an account. Public-page objections remain in force after account deletion or identity disconnection. Anonymous request limits can be adjusted for shared networks. Public pages exclude repositories when access is lost or a successful repository metadata confirmation is more than 48 hours old by default. You can set the visibility limit to match your sync cadence.

  **Operators:** Whole-workspace anonymous access is removed. Take and verify a database backup before upgrade. Public activity pages must be enabled explicitly after the activity history repair is verified. The optional `HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED=true` setting supplies an instance default. Review the public activity administration guide before publication.

- Operators can prepare DNS-only workspace hosts and a Let's Encrypt DNS-01 wildcard certificate. The workspace subdomain switch stays off by default. Keep it off until a later release supports workspace addresses in the web app. DNS credentials stay in read-only files, outside container environment values and release trees.

### Patch Changes

- Workspace activity loads sooner in workspaces with a long activity history while preserving each person’s first contribution date.
- Sorting people by name starts A–Z even when a search has no matches.
- Workspace activity separates People and Timeline into tabs, keeps your scope when switching, and shows two months when choosing a custom range. Contributions now explain which work they include.
- Historical merge reviews distinguish recorded review gates from incomplete decision history. Dated closure remains usable evidence. Unresolved gates and unknown deciding event times retain their own outcomes.
- Practice feedback on Swift changes now judges code in its context. Ending work quietly after a newer search replaces it counts as normal control flow, not a hidden error, but a cancellation that hides a real failure still does not. Artwork that only repeats the title and other text read with it may stay hidden from screen readers. Preview variants that show the same sample in another appearance are no longer treated as copy-paste. A stable state established by a preview's default setup counts as previewed.
- Queued GitLab reviews now preserve the original code range when Hephaestus recorded that range at the time of the request. New commits or a moved target branch no longer substitute a different range. This applies to new review requests with a recorded range. Unknown original ranges and stopped reviews are not recovered or retried.

  Before posting feedback on a merge request, Hephaestus also checks the captured code range against the current recorded range. A changed range withholds feedback. An unknown current range uses the existing bounded retry. This check also applies to prepared reviews requested earlier. Comments and review status are still read when the review runs.

- A practice review no longer fails at once when its evidence is still held by an earlier attempt or a cleanup. It retries within the existing retry limit instead. A review that still cannot start within that limit fails as before.
- Error-handling review standards distinguish defensive checks of values guaranteed by their type or API contract from paths that can fail. Failures from runtime input, responses and stored data remain in scope.
- Reviews of merged pull requests now accept a written account of what was delivered and deferred for the linked issue, such as delivery bullets in the description, without requiring ticked checkboxes. Generic checks such as a passing build, and a bare closing reference, still do not count as an account.
- For models using OpenAI chat completions or the OpenAI Responses adapter, review trace records include adapter call times and the time to receive response headers. Failed and aborted calls retain their timing, including waits for retries within a call. Debug records also distinguish an adapter-reported failure reason from a missing or unreadable reason, and Responses failures name the stream's own failed, incomplete or error ending. Calls through other provider adapters remain untimed and are identifiable from the timed-call count.
- Review feedback on a pull request or merge request stops when its title or description changed after capture. This also applies when the commit stays the same. Feedback waits and retries when it cannot compare the capture with the current work.

  New reviews of an author's current work use the recorded occasion when their capture proves it retained the original admitted work. A newer result for a practice then stands even if an older review finishes later. Prior reviews and captures without that proof keep their existing ordering.

## 0.88.0

### Minor Changes

- Workspace activity can show all repository contributors in one response. Reviews count each pull request once and exclude your own work. Provider bots appear separately. Workspace admins can classify machine user accounts as automation and reset that choice.

  You can open a contributor's weekly counts, type and repository breakdowns, and paged work list.

  **Operators:** Update custom activity API clients. The people aggregate replaces the activity summary and member-list endpoints. The bundled webapp uses the new response.

- Workspace subdomains can use central sign-in and the apex API. The optional `HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED` switch is off by default. Set `HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN` before you turn it on. Edge and SPA support must be deployed first.

  Workspace names keep their 3–51 character bounds and now follow DNS label rules. Used names remain reserved after a rename or deletion. Old lowercase workspace paths keep their redirects. Tenant-host apps must fetch a CSRF token before other credentialed requests at startup and after sign-in or sign-out.

  If a login provider's registered callback differs from the configured issuer origin and API prefix, update it to the exact callback shown in instance administration. Existing matching apex callbacks need no change. Before enabling workspace subdomains, turn off GitHub callback wildcard matching. Invalid or reserved workspace names change automatically to a safe address during upgrade. See the [workspace subdomains guide](https://docs.hephaestus.build/admin/workspace-subdomains).

### Patch Changes

- Workspace activity restores missing pull requests, merge requests, reviews, issues, and comments from stored history. Activity recording slows synchronization under load instead of dropping events. GitHub and GitLab backfill check suspicious completed scans against the provider and restart incomplete history once per unresolved gap. Dismissed reviews keep review credit. A repository failure reports a warning without stopping repair of other repositories. Workspace administrators can run the repair through the existing backfill action. Erased people stay erased, and replies to review comments do not count as reviews.
- The "Change dependencies deliberately" standard consistently applies to external dependency declarations and resolutions.
  Its scope includes manifest additions, version changes, and lockfile refreshes.
  Importing or using a library alone does not change that scope.
- Review diagnostics now retain native model-call failure types and HTTP status when available, including in metadata-only archives. Earlier failures remain unexplained when those facts were not recorded.

## 0.87.0

### Minor Changes

- Workers can retain practice review transcripts for 24 hours for investigation. Raw transcripts require complete ownership records for captured sources and copied feedback. Otherwise, workers retain only bounded counts of calls, tokens, tool use and errors, marked as metadata only. Reviews which copy history without complete ownership records retain metadata only.

  Transcripts stay outside the database and web app. Person erasure removes them with the review's folder. Hard kills and failed uploads can leave no transcript.

### Patch Changes

- Practice definitions now count a stated user benefit, such as data that is still there after a restart, as the reason for a stored-data decision. A sufficient approval is no longer set aside because a different reviewer's approval was withdrawn. Previews that hold known sample data in the view are recognised as representative.
  Review the available practice updates in workspace administration before applying them.
- Commit-subject reviews assess whether each subject describes a specific step. They no longer ask for code explanations or use commit-scope hints to assess subject clarity.
- Supported release rescans now check every authenticated image even when archived evidence or another image fails validation. Diagnostics distinguish a refused policy from an unavailable scan, and any failure still fails the overall check.

  Release evidence now includes a signed checksum file that authenticates its SBOM, advisory and policy bytes. Older unsigned archives are reported as unauthenticated without trusting their evidence, while their authenticated images are still scanned against current policy.

- Duplication reviews distinguish repeated maintained logic from independent code that follows the same framework pattern. Structural similarities remain inspection leads rather than requests to introduce unnecessary abstractions.
- Issue practice definitions establish the current request before they judge requested work.
  A brief or vague request is still judged, conflicting current requests stay undecided, and an explicit withdrawal leaves no requested work to judge.
  Historical and closure practices keep their own scope.
  Single-concern issue practice definitions assess the issue itself, without requiring a comparison with sibling issues.
  Review the available practice updates in workspace administration before applying them.

  Issue scope reviews include captured discussion when establishing the current request. Unaccepted proposals do not replace that request.

- Release evidence capture resources when cleanup fails, so later reviews on the same worker thread can start. Keep the original failure and any cleanup errors visible.
- Release vulnerability exceptions now apply only to the exact image they were reviewed for. A reviewed claim that vulnerable Go code is absent can carry over to a rebuilt image only when its Go binaries are byte-for-byte unchanged. Such a claim is still reviewed again when the advisory changes or the exception expires. Verifying an earlier release fails if its stored result relied on an exception for a different image. Release SBOMs now also record a SHA-256 for every file in the image.
- Security checks now recognize that the reviewed launch helpers do not contain the HTTP/2 processing code affected by a newly classified vulnerability. The assessment remains bound to the exact helper files and advisory source.

  The bundled proxy retains a known HTTP/2 CPU denial-of-service risk in ordinary client and server traffic, including cleartext HTTP/2. Disabling the dashboard does not remove this risk. The reviewed vendor images have a temporary security-policy exception until October 16, 2026 at 00:00 UTC, with no automatic renewal. A fixed vendor replacement is tracked in [#2659](https://github.com/hephaestus-build/Hephaestus/issues/2659).

- Reviews of large issues no longer treat unknown child-work counts as proof that a breakdown is missing.
- GitHub sync clears outdated child-issue totals when the provider reports zero. Deleted child issues no longer count toward progress.
- The bundled NATS broker includes Go security fixes while retaining the existing JetStream configuration.
  NATS images now come from `docker.io/synadia/nats-server`; include that repository if you maintain an image allowlist or mirror.
- Issue reviews now account for clarifications in open-issue discussions. When a comment is added, edited or removed, earlier observations stop representing the current request and pending public feedback cannot use the old evidence.
- Practice reviews keep text on each side of an HTML comment separate.
  Template comments can no longer join issue numbers or turn separate words into a placeholder.
- Feedback on reviewed work can compare the code and practice revisions behind current assessments and earlier feedback. Missing revision identities remain unknown.
- Practice reviews avoid unavailable positive-feedback references while preparing summary and line comments.
- When composing feedback, Hephaestus now retains cited evidence explaining what remains undecided or why a practice did not apply.
- Delayed pull request and merge request reviews no longer receive check results from a newer revision. A review sees check results only when they were reported for the revision it reviews.
- Browser session cookies require Secure outside local HTTP E2E tests.
  The server rejects insecure cookies with an HTTPS issuer or a `__Host-` cookie name.
- The bundled reverse proxy now uses Traefik 3.7.14. The web interface image includes updated nginx and OS packages.

  Dashboard-enabled Traefik configurations retain a known CPU-exhaustion risk from malicious Range headers until a fixed vendor image is available. The reference proxy configurations keep the dashboard disabled. The vendor replacement is tracked in #2659.

## 0.86.1

### Patch Changes

- Reviews that receive model replies but record no observations no longer repeat as if the provider never answered. Failed recording remains a failed review with its unreached practices preserved.
- Reviews no longer treat a description that was not captured as a missing description. An unknown description stays unknown. Reviews also distinguish details saved when the review was requested from details read later.
- Worker cleanup, review admission and person erasure no longer remove the same review evidence folder at the same time. Admission still deletes the verified evidence as soon as it is recorded, and cleanup retries a folder that another removal holds on its next pass.
- Practice reviews now distinguish a first observation from a correction with an explicit draft reference. Invalid corrections leave the recorded observation unchanged.
- GitLab practice reviews wait for the merge request's diff range before assessing its code. If GitLab is still preparing it, the original review retries automatically.

  With local Git storage disabled, reviews can still assess captured metadata. Code evidence is recorded as not collected.

- Release validation runs database upgrade and baseline checks with provider and startup checks.
- Heph and the practice pages stay quiet about a practice as already said only when feedback about that practice was delivered on that same channel. When one decision covers concerns about several practices, each practice needs such feedback. A card on the practice pages no longer counts as a mentor conversation, and feedback that was only prepared counts as neither.
- Delayed pull request and merge request reviews keep the title and description saved when the review was accepted. Later status and checks are labelled as current context. Missing saved text remains unknown.
- Reviews on pull requests and merge requests are now written and checked as one whole. Concerns are shown beside their standards, limits and references to earlier feedback. A review reads a strength's complete evidence and standard before acknowledging it. It still decides on every problem: it raises it or deliberately holds it back. Earlier comments on the same work count as a record of what was said, not as proof of the current work or as wording to repeat. Recognition considers whether the same choice was already acknowledged and whether the current evidence adds anything new.
- Practice review preparation now reads a repository's comments, reviews, threads and delivered feedback records in batches. It keeps the same captured evidence without repeating queries for each issue or pull request.
- Build validation prepares GitHub actions sequentially so they do not share concurrent writes to event data. Independent jobs and test shards still run concurrently.
- Retried practice reviews can reclaim sandboxes from recorded earlier attempts before they start. Sandboxes without a matching earlier attempt remain protected.
- Practice reviews can record observations when the selected model supplies unused evidence fields as null, without accepting evidence for the wrong outcome.
- Feedback and observation counts on reviewed work now refresh when review results finish processing. This also works when you open the work after the review finishes.

## 0.86.0

### Minor Changes

- Instance administrators now see where the instance runs and which releases it ran. The **Release** card on the overview names the deployment environment and says when the instance started the running release. **Show release history** lists the last ten releases that the instance started, so an upgrade or a rollback is visible after the fact. Error reports and exported traces carry the same environment and version, and staging errors no longer show as `prod`.

  **Operators:** rename `SENTRY_ENVIRONMENT` to `DEPLOYMENT_ENVIRONMENT` in each environment file. If you send errors to Sentry, update alerts and saved searches that filter on `prod`.

### Patch Changes

- Practice reviews no longer fault a short commit subject that names what it changed because the commit also touched other files. Reviews treat a value that the code reads from storage or settings as unknown, not as its declared default. Feedback on the work no longer presents a formative practice as something to settle before merging.
- A practice review that stops without recording its observation is asked once more, in the same session and within the same budget, to record it from the evidence it already read. If it stops again, the practice stays not reached. A practice that reaches the refusal limit is no longer reported as recorded.
- Queued push, description and linked-work reviews can be replaced by a newer review that covers the same author’s practices on current work. The older review names the replacement and links to its progress. Ready, merge and reviewer reviews retain their own occasions.
- Reviews no longer reuse a diff base from an earlier revision when a provider changes the head without supplying its base. Providers that supply the review’s diff range must provide a paired base before code is captured.
- A pull-based host that already applied its environment's promotion no longer restarts its stacks or stops its worker when another environment is promoted. It still retries its own promotion until it applies, and a new promotion of the release it already runs still applies again.
- Worker containers now retry pending person-erasure and workspace-purge requests for the review evidence they hold, including when the server role runs separately. They also periodically remove ended-attempt evidence after the existing one-hour retention period.

## 0.85.1

### Patch Changes

- Practice reviews of work with a very large evidence capture can record their observations again. Each citation must still name evidence that the review captured.
- Deployments no longer walk every file in the repository caches after a successful ownership migration. New or unmigrated caches still receive the required ownership before application services start.

## 0.85.0

### Minor Changes

- The workspace admin lists of practice reviews, work, observations, and feedback load more rows when you scroll to the end of a list. They no longer show numbered pages. If more rows do not load, the list tells you and you can retry. If the list cannot update, the rows that you have stay on the screen. You can also use **Show more** at the end of a list with the keyboard. A row does not show two times when a new review starts while you scroll.
- Feedback on a pull request, merge request or issue addresses its author's work. Feedback about reviewing stays with the reviewer on their own practice page or in their own conversation, subject to the existing delivery checks.

  **Operators:** API responses that report why feedback was withheld can now contain the reason `PUBLIC_SUBJECT_INELIGIBLE`. If a custom API client rejects unknown enum values, update it to accept this value before you upgrade. The clients bundled with this release already accept this value.

- Practices across the workspace shows every count, however small. A bar shows each part that holds a developer, also a part of one, and each figure shows its typical range as soon as one developer is counted. Before, the page held back a part of one to three developers and a typical range over fewer than seven developers. It still names nobody, but in a small group other members can sometimes tell where a developer stands. The page, the user docs and the TUM privacy notice say so. The page also says that a standing comes from AI review, can be wrong and is not a grade. **Operators:** before you upgrade, update your privacy notice and record a decision for this audience; see the migration guide.

### Patch Changes

- Practice review guidance asks for relevant context around deciding evidence, without requiring unsupported consequences. Its examples cite the facts they describe.
- Worker shutdown settles all active reviews before waiting for container cleanup. Container stops run in parallel within one bounded cleanup period, so a slow stop cannot delay settlement of the other reviews. Interrupted reviews retain their recorded observations and usage.
- Usage accounting keeps cache writes separate from ordinary input and preserves uncertainty when recorded totals disagree.
- Practice reviews declare the complete structure of an observation report, including its required fields and allowed values, and refuse a report that does not match it before anything is recorded. The checks that decide whether a report's evidence, practice and session are accepted are unchanged.
- Source builds resolve shared build dependencies directly from Maven Central while retaining the Gradle Plugin Portal for plugins.
- Closed issue reviews assess outcome accounting only when the issue states a checkable completion condition.
  A broad activity name does not create a separate closure obligation, while a concrete title can state the outcome without a checklist.
- When feedback chooses what to raise on your work, it now has the complete practice standard in view. Practice-page explanations and next steps have separate roles.
- Practice review explanations say why the work does or does not fall under each practice before they describe how it meets or falls short of it. Feedback distinguishes the recorded assessment from what the captured evidence supports.
- Practice reviews now present each practice’s complete standard before recording examples and advisory leads. The recording task uses that standard to qualify the observation, while recorded observations and review limits stay unchanged.
- Pull-based deployments let the running worker drain within the grace period it was started with before replacing its dependencies, also when the new release removes the worker. A failed stop applies nothing and records no release. This applies once a host adopts the updated reconciler.
- The next step on a feedback card and the reason for an observation in the admin view now show their formatting, such as code and links. They no longer show raw Markdown characters such as backticks. Code in feedback text also no longer shows a backtick on each side.
- Reviews on pull requests, merge requests and issues can recognize an evidenced response to earlier advice and useful initial choices, while keeping useful next actions first. A changed result alone does not establish a fix.
- Citation refusals identify the invalid reference and where to find a valid artifact without listing every captured repository file.
- GitHub commit synchronization can recover after empty or unusable provider responses.
- Feedback composition considers each recorded observation together with its evidence and the practice standard, and can withhold a claim that these do not support. Reviews on the work place each acknowledgement once, in the summary or on a line. Recorded observations stay unchanged.
- Default practices distinguish adopted outcome confirmations from generic checks, implementation residue from descriptive artwork, and representative SwiftUI preview values from empty or unknown defaults. Workspaces can adopt the updated practices without changing earlier observations.
- Closed, merged or changed work can still contribute to feedback on your practice pages and in the mentor conversation when its observations remain current and authorized. Withholding a note on the work no longer stops private feedback preparation. Prepared feedback still follows your delivery settings.
- Practice page feedback now uses the supporting work selected after a review records its observations. If that support cannot be read, feedback on the work and in conversation can still be prepared.
- Feedback on a pull request, merge request or issue can now compare against advice Hephaestus already gave on that work after its practices change. That earlier advice shows what was said before. It is not treated as a current claim about the work.
- Reviews on work can take account of feedback delivered while the review was running, without treating that later advice as something the captured work already answered.
- Feedback on pull requests and merge requests can now use complete cited code from the reviewed revision and the relevant cited diff. Files that cannot be shown are identified with the reason.
- Reviews on pull requests, merge requests and issues keep each point in one place. Line notes carry the points about the code at that line, and the summary gives the overall priorities and the asks about the work as a whole. Acknowledgements appear once.
- New feedback delivered on the reviewed work now keeps the revision it reviewed, as feedback awaiting approval already did.
- Public feedback composition records its conversation in the review attempt’s temporary diagnostic storage, like practice measurement and private composition. The record is removed when the attempt is cleaned up; it is not a durable archive.
- Guidance for feedback on a pull request, merge request or issue now asks that each recorded concern be checked against its practice standard and the captured work before it is raised. A concern that this evidence does not support can be left out, and supported concerns stay in the feedback. Recorded observations do not change.
- Bundled practice guidance distinguishes workflow status from product terms in titles. A stated user goal, impact or task context can explain why a change exists without a separate rationale sentence. Existing workspace definitions change only when administrators adopt the update.
- Release and image rescan verification now accepts a vulnerability advisory snapshot up to 48 hours old. Scans still reject older snapshots and snapshots dated in the future.
- When a review on your work chooses which observations to raise, it now has the full standard behind each chosen practice available, including the responses that standard accepts, such as a reasoned decline or a clarification.
- Focused practice reviews can reuse their shared reviewed-work context on supported Responses models while keeping each practice assessment separate.
- Earlier feedback remains available when a later review posts a separate comment on the same work.
- Practice reviews record each practice through one complete observation while preserving evidence validation.
- Practice reviews keep what a change's source shows apart from consequences it does not show, such as erased stored data. The removal of a feature with persisted state is judged from its removed lines as well as its added ones.
- If a finished review returns text the database cannot store, Hephaestus records a terminal failure and retains its recorded usage rather than leaving the review running. The rejected result is not delivered. An invalid transcript is replaced with an omission note.
- Bundled practice guidance now treats a plain issue reference, such as "Related to #12", as linking the change to its issue, including for work that delivers only part of it. A recorded need, such as data that must survive a restart or stay on the device, counts as the reason for a stored-data change without a list of rejected alternatives. Existing workspace definitions change only when administrators adopt the updated defaults.
- Practice reviews accept observations in one structured form. Invalid submissions leave previously recorded evidence unchanged.
- Reviews that run on a separate worker can prepare practice-page feedback from the authorized supporting work. Before this fix, a split deployment left that feedback out.
- Practices across the workspace loads faster in large courses, including reviews that cite earlier observations. Each request checks current access and privacy choices. The page loads its overview and tiles together and shows a loading message if the first read takes a moment.

## 0.84.0

### Minor Changes

- Reviews select useful advice before writing complete comments, and withholding advice as already given requires a recorded prior statement.

### Patch Changes

- Practice feedback retains the original support and revision of earlier delivered feedback. Reviews can distinguish captured advice from a developer’s own notes and from words edited after the capture.
- Recurring practice feedback has a shorter writing guide, with explicit distinctions between listed checks, reported results and earlier advice.
- Each practice is reviewed in its own model session with its complete criteria, under the review's existing shared work budgets. Recording refuses observations addressed to another practice before changing drafts. Private feedback is composed from admitted observations and authorized context in a fresh session.
- Practice reviews use the evidence and qualifications recorded in earlier observations, together with the practice revision used.
- Practice reviews distinguish recorded observations, prior feedback delivery and prepared feedback. Supplied history does not establish a complete chronology of work or communication.
- Reviews preserve available feedback when composition stops early and show that some feedback could not be composed. Delivery recovery no longer reports incomplete composition as successful delivery.
- The "Ship a preview with each new view" practice now judges the sample data and states a preview supplies, instead of guessing what stored defaults hold or what the preview shows when it runs.
- Private feedback receives the applicable practice criteria and recorded qualifications. Earlier feedback remains available in its original records.
- Practice feedback can acknowledge a useful choice without adding new tasks. Recurring feedback focuses on what the work shows without guessing why it happened.
- Practice reviews keep recorded posted feedback out of captured pull request and merge request discussion, even if its marker is removed.
- The work and review lists update as soon as a review you requested starts.
- Handoff reviews now tell checks a reviewer is asked to do, conditions before merge and planned follow-on work apart from unfinished work in the change itself. A request to wait before reviewing still counts as not ready.
- Review results keep code quoted in observations exactly as written, including backslashes before Swift string interpolation. A review whose result cannot be read is recorded as failed instead of completed.
- Practice reviews retain feedback previously published on the work when a newer review replaces it. Guidance that was withdrawn or is no longer current is still withheld.

## 0.83.1

### Patch Changes

- A bug report that names a concrete malfunction or a way to reproduce it, but is still incomplete, is now a minor gap in "State a problem a maintainer can act on". A report that names neither stays a major gap.
- A practice review whose observations were already accepted is no longer run again when its worker stops or is lost before it finishes. It ends as failed with a message that no completed feedback was saved, and its observations stay as recorded.
- Observations link to the comments that carry feedback on your work. If no comment link was recorded, you can open the reviewed work instead. An empty practice Feedback tab explains the two kinds of feedback and lets you open Observations.
- Capture of commit details now uses an indexed lookup for the target commit's file changes, reducing database load during synchronization.
- Practice reviews reserve space for the complete next turn and restore their instructions after compaction. Essential input that cannot fit remains unassessed rather than producing an unsupported observation.
- Reviews of newly opened issues now stop before model execution when the submitted issue snapshot has changed. This avoids reviewing superseded issue content.
- A stopping worker now keeps its sandbox gateway open while it waits for active reviews, so a review can keep making model requests until it finishes or the drain timeout runs out.
- The worker now starts with a larger database connection pool, which leaves room for several reviews to prepare code evidence at the same time. The other roles keep their pool size. Set `HIKARI_MAXIMUM_POOL_SIZE` to choose a different size for the worker.

## 0.83.0

### Minor Changes

- Practice reviews on pull requests, merge requests and issues now contain complete, evidence-bound feedback, with independent line notes where useful. Public reviews use only the captured work and prior public feedback on that same work, and now start from the work's captured title, description, state and linked issues; private practice-page guidance and conversation notes remain separate. Feedback is withheld as a whole when one of its supporting observations cannot be delivered, and failed composition no longer publishes raw observations. Technical syntax in Markdown code, such as generic types and Swift property wrappers, is preserved by comment safety formatting.

### Patch Changes

- The bundled issue practice accepts a clearly stated capability without a separate acceptance checklist or design document. Who benefits and why are useful context, not prerequisites. The bundled handoff practice describes Draft and Ready presentation without implying that the work is complete. Existing workspace practices keep their definitions until an administrator accepts the catalogue update.

  Issue reviews no longer receive the provider's total comment count, which also counts feedback Hephaestus posted and could be mistaken for comments missing from the review. This applies to every new review right away; earlier reviews are not rewritten.

- Review cards show observations with actionable feedback first, so you can read and respond without expanding successful observations. Expanding the card still shows the complete review.
- Practice reviews no longer stall while validating indented multiline evidence quotes. Quotes still have to match captured evidence. Citations beyond the captured change are rejected.
- Feedback posted on the work no longer shows raw comment markers and `<sub>` tags in its rendered view and list previews; its disclosure and settings links read as plain words and links. The source view still shows the text exactly as stored.
- A GitHub App installation with many repositories no longer delays the startup of other workspaces, such as GitLab workspaces. Each GitHub App workspace now brings its monitored repositories up to date while it starts, and records this work in its sync job history.
- On GitLab, practice feedback about specific lines is now posted as a merge request comment that starts with a link to the lines in the reviewed commit, instead of a comment attached to the diff. Review history and admin pages show such feedback as a comment linking to the lines. Before posting each new comment, Hephaestus checks the change's latest synchronized commit; if it differs from the reviewed one, the remaining comments are withheld and the review history says the change was updated first. Comments already posted stay and are still recognized. A push that arrives after this check can still be followed by a comment.
- Hephaestus preserves feedback already posted beside code. A retry creates a note only when the earlier attempt is positively known not to have created it. When a write is unconfirmed, Hephaestus looks for the existing copy without creating another. Prior copies count as delivered only when their author, exact text and required location match. Ordinary fallback notes use their stated file and line rather than a native code anchor. Summary recovery also verifies Hephaestus's own exact comment, so a copied marker cannot mark a review as delivered.
- The bundled practices judge colors, commits, review follow-up and readiness more accurately. Workspaces that adopted them see the corrections as an update to accept or decline.

  - A color is judged by what it resolves to. SwiftUI's standard colors, such as `.green`, count as adapting to light and dark appearance. UIKit's fixed constants, such as `UIColor.green`, and colors built from fixed values still count as fixed. A color your app defines is judged from its definition.
  - When the code changes of a pull request could not be read, Hephaestus skips the commit practices for that review instead of recording an open question. The commit history it reads now holds every commit of the pull request, not only the newest 500.
  - An explicit request to hold the merge counts as an open request, even outside a review thread. Optional advice for later work does not. A thread resolved before the merge, or a draft marked ready, needs no further date.
  - A merge request template's checklist left as supplied no longer counts as saying the work is unfinished. What you write about unfinished work still does.
  - To tell a small chore from a feature, the handoff practice can read the changed lines, not only the file names.
  - On GitLab, an approval keeps the date GitLab itself records for it. Hephaestus no longer claims which commit an approval was given on, since GitLab does not say.

## 0.82.0

### Minor Changes

- Developers can open **Across the workspace** under **Practice profile** in the sidebar. The page shows where the developers in the workspace stand in each practice group. It counts developers and never names one. **You** marks your own part of each bar that shows its parts.

  Each bar counts every developer by the standing that their Practice profile shows now. Thus, your marker always agrees with your profile. A bar shows its parts only when each part holds at least four developers. Thus, each part stands for at least three other developers, whoever reads it, and every reader sees the same bars. A bar is also held back if a comparison with its group, or of the groups together, would single out one to three developers. A held back bar shows at most how many developers it counts, with **Split held back** under it.

  Four figures show your own values beside the typical range of the workspace, from seven developers. They count pieces of work reviewed, practices going well, practices needing attention, and open feedback. **Last 30 days**, **Last 90 days**, or **All time** changes the first three. Open feedback counts what is open now.

  Select a group to see its bar and a bar for each of its practices. To see your own standing, trend, and next step there, select **Open in your Practice profile**.

### Patch Changes

- On your Practice profile, your standing, your trend, and the resolution of feedback by your work count only reviews that run on their own. A review that you request and a Past work campaign still show their results in your reviews. They do not replace the result of an earlier review of the same work. Thus, a review that you request cannot resolve feedback on its own. There is one exception: a practice that no review judged on its own takes its standing from the other reviews, and says so. When a practice reads **Needs attention** but your newest review of it is one you requested that found no problem, its **Observations** tab says that a requested review is evidence only.
- A standing on your Practice profile shows how many pieces of your work it is read from. If fewer than three pieces back it, it says that it is an early read. One problem on your newest piece of work gives the same standing whether one, two, three, or four pieces were reviewed. A trend that waits for more work now gives the correct number of pieces that it still needs.
- To dispute a card on your Practice profile, select **Disagree** and say what is wrong. Workspace admins read your explanation. **Not helpful** no longer offers reasons: **Not accurate** disputed the card, and the other reasons were not recorded. A rating now opens an optional note, and taking back a rating keeps your dispute.
- Feedback on your Practice profile now cites each piece of work by its latest review that ran on its own. A review that you request takes its place only when it finds that the problem is gone. A Past work campaign never takes its place. Before, such a later review could hold back feedback about work that was already judged.
- Heph stays out of a member's workspace navigation until a usable Heph model is configured. Administrators can still open AI models to set it up. Until then, that workspace's setup page asks for your AI choice without Heph speaking, and its choice cards list practice feedback rather than Heph. Your AI choice still applies in all your workspaces.
- On your Practice profile, older reviews load when you scroll to the end of the list. **View earlier reviews** stays for the keyboard. If older reviews cannot load, select **Retry**.
- Your Practice profile keeps its layout while your feedback loads. A response on a feedback card shows "Saving…" only when it takes more than a second, and only on the control that you selected. A link to a practice group that no longer exists shows the same message in every panel.
- Your Practice profile now says that a practice was met across your recent work, where it said that the practice held. The summary now uses the same word as the counts beside it.
- A workspace whose stored credential the server can no longer decrypt no longer stops the other workspaces from starting, receiving events, or syncing, and no longer blocks their manual syncs. The server skips only that workspace and logs which connection needs a replacement credential. A manual sync of that workspace still reports the unreadable credential.
- A feedback card that you mark as addressed or not applicable closes as **Marked by you** on a neutral card. Only a card that your work resolves turns green, and your card says that your next work confirms your answer.

## 0.81.0

### Minor Changes

- Administrators can set how hard a reasoning model thinks. Every model, shared or a workspace's own, has
  a **Reasoning effort** — None, Minimal, Low, Medium, High, Extra high or Max — sent with every call a
  practice review or the mentor makes, or **Provider default**, which sends nothing and leaves the
  provider's own default in place. A model marked as supporting reasoning before keeps the medium effort
  it was already sent. The reasoning tokens a review spends are now recorded with its usage instead of
  reading zero.
- A practice review now reads three more facts from the record, on GitHub and GitLab alike: what the
  checks said about the pull request's head (GitHub's status check rollup, GitLab's head pipeline),
  which issues the provider records as closing candidates for the pull request — including links made in the
  provider's UI that no `#N` in the text names — and review-thread resolution times when available. The
  schema migration that stores them applies automatically.

  **Operators:** GitHub Apps created from an earlier manifest need the `check_suite` and `status`
  event subscriptions added under the app's Permissions & events, and the Checks and Commit statuses
  read permissions if the app predates them; GitLab group webhooks registered by an earlier release
  need Pipeline events enabled, or the hook deleted so it is registered again. Until then the head's
  check state arrives only with the scheduled sync.

  A practice set to **Review before sending** can still deliver feedback to the developer's practice
  pages and Heph, subject to channel rules. Only feedback posted on the work waits for approval.

- Practice reviews can inspect the captured repository and reachable Git history with native tools.
  Binary assets and hidden or vendored source are no longer omitted individually. Snapshots above
  `GIT_MAX_SNAPSHOT_BYTES` are refused whole. The repository is read-only, has no upstream credentials,
  and supports verified historical citations.

  A review now uses one model session for grouped practice checks and feedback composition. It records
  valid observations independently, stages linked issues as citable text, and records per-turn calls,
  tools and refusals. Practice-page feedback requires recurring negative observations across work;
  a single occurrence does not create a card about a recurring lapse.

  Bundled criteria now use a common decision-procedure format and can exclude assessed cells that do
  not apply to the practice. The new **State how to verify the change** practice checks verification
  guidance. Catalog updates do not rewrite workspace-adopted copies.

  Private execution archives are no longer collected. Reviews retain verified citation results rather
  than archives of complete inputs, model requests and session transcripts. Practice reviews always
  use an internal network; internet access remains a Heph-only setting.

  **Operators:** deploy matching server and agent images, remove the retired repository size limits, `SANDBOX_DOCKER_CLI`, `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS` and the execution-capture setting, and review the expanded repository-history scope with your privacy owner before updating installed policies to source contract 1.2.0. Existing policy revisions are not rewritten at startup. The migration guide has the steps.

- A workspace now opens on your **Practice profile**, or on the new **Activity** page where it does not review practices. **Activity** shows what needs you — review requests, and your pull or merge requests returned to you or approved — with the ones waiting on someone else, including requests other reviewers have already covered, folded away; the issues assigned to you; your pull requests, reviews, comments and issues over the last 7 days, 30 days, 90 days or 12 months, each with its own bar chart; and a timeline with one row per pull request or issue, which you can copy as Markdown for a standup or a 1:1. Icons and colours are GitHub's or GitLab's own, and GitLab workspaces now show GitLab's own state colours throughout the app, where some places used GitHub's before. **Workspace activity** replaces the leaderboard: everyone's activity, or one team's, with members listed by name and searchable, and each member one click away. Nothing is scored or ranked anymore — the leaderboard, leagues, league points, XP, levels and the weekly Slack leaderboard digest are gone, and activity is visible only to people with a role in the workspace, never through a public workspace.

  **Operators:** the upgrade deletes league points, XP and the leaderboard and league settings from the database, and a rollback cannot bring them back; take a backup before upgrading, and export that history first if you want to keep it (the migration guide has the commands). Delete `LEADERBOARD_NOTIFICATION_ENABLED`, `LEADERBOARD_SCHEDULE_DAY` and `LEADERBOARD_SCHEDULE_TIME` from your environment.

- Workspace admins can mark an observation as incorrect, with a reason, from its page under **Practice reviews → Observations**, and restore it later with a second reason. An observation marked incorrect stays in the review record and in the developer's review history, labelled with the reason, but no longer counts toward their standing, practice page, Heph's context or new feedback, and any feedback about it that had not reached anyone yet is stopped. No new attempt to post feedback citing it is made, including approved and retried deliveries; while a delivery citing it is in progress the correction asks to be retried, and a comment that attempt had already sent may still arrive, in which case Hephaestus finds it and corrects it like any other. For comments already on a pull request or merge request, Hephaestus tries to add a correction notice to each summary and records the outcome on the observation page: updated, pending, inline comments remaining (these cannot be edited), or unresolved, including when the provider accepted a comment without saying which one it is.
- The practice "Confirm the outcome before closing the issue" now ships as needing human review. It asked
  whether an issue's checklist and sub-issues were finished when the issue was closed, but a review only sees
  the issue as it is later, after boxes may have been ticked, unticked or added, sub-issues finished or
  attached, or the issue reopened and closed again, so it could record a lapse the developer never made or a
  clean close that was not. The instance catalog shows it as needing human review, including where an
  administrator customized it to ask for a review; a customization set to guidance only stays guidance only.
  Every workspace copy recorded as coming from the catalog entry, including an edited one, is no longer
  reviewed from the upgrade on: a review already under way records no new result for it, the upgraded server
  switches it off at startup with the change in the configuration audit log, and it cannot be switched back
  on. If that repair cannot finish, the server's health reports it. A copy made and edited before Hephaestus
  recorded where copies came from has no such record, is treated as the workspace's own and is still
  reviewed. Results recorded before the upgrade stay as they were, and the practice page no longer shows a
  phrase for this practice.

  **Operators:** after upgrading, find workspace practices with the slug `issue-closed-with-unmet-outcome`
  and no recorded source, decide with each workspace whether it is an old catalog copy, and switch those off;
  see the migration guide.

- A Chrome extension shows the practice review of the pull request, merge request or issue you are viewing on GitHub.com or GitLab, including self-hosted GitLab, in the page itself: the comments Hephaestus posted there for you, each a link to the comment, when the work was reviewed, and what the review concluded about your own work. On a repository's list, a row's Hephaestus button previews that work in one line. Asking for a review is confirmed in the extension's own window. On sites you allow, it sends your Hephaestus only the address of the work you open, or of the row you press. It is not yet published in the Chrome Web Store.

  Operators who distribute the extension allow it by listing its extension id in the new optional `HEPHAESTUS_AUTH_BROWSER_EXTENSION_IDS`. Hephaestus now records the address of each comment it posts on a pull request, merge request or issue; comments posted before this release may have none.

  A review requested for work whose repository several workspaces monitor now follows the requesting workspace's own settings. Installed-client sign-in and development account switching also work before an account has accepted the transparency notice; workspace data still requires it.

- Practice observations now distinguish whether a practice was assessed from whether the specified behavior was present and whether that behavior is desirable or undesirable in context. Not applicable and undetermined observations no longer masquerade as presence values. Invalid combinations are rejected instead of silently rewritten, and positive and negative outcomes are derived from presence and assessment. Severity belongs only to negative outcomes.

  **Operators:** This changes the observation API and runtime output contract. Upgrade the server, sandbox runtime and webapp together; update custom consumers to read `assessmentStatus` and nullable `presence`, `assessment` and `severity`, plus the read-only `outcome`. Standing observations expose their descriptive `kind` separately. Back up the database before upgrading. The migration preserves existing outcomes by translating historical absence assessments and stops rather than inventing a severity for an inconsistent historical bad observation. See the migration guide before upgrading.

- Practice reviews of a pull request now read its commits — each commit's subject, body, timestamps and file count, in history order — so feedback about commit messages and commit scope no longer comes back inconclusive because no commit list reached the review. **Operators:** a pull request review now needs the repository checkout for every practice, not only the code practices; an installation that enables `GIT_CHECKOUT_ENABLED` together with `AGENT_ENABLED`, as the install guide requires, needs no change.
- Practice reviews can evaluate a verified empty code-change range under source contract 1.1.0. Each practice still determines whether the work presents an occasion; an empty capture does not automatically produce praise, criticism or a not-applicable observation. Missing, failed and incomplete required captures remain blocked.

  New reviews use the updated source policy. Recorded 1.0.0 evidence retains its original policy and digest; this runtime does not re-derive those historical readiness decisions.

  **Operators:** Pause new reviews before upgrading, then review and explicitly update stored custom and overridden practice policies to source contract 1.1.0 through the normal administration API before resuming them. Adopt the updated bundled catalogue through its existing adoption flow. Historical policies are not silently reinterpreted.

- A **Feedback** button in the header is now the one place to reach the Hephaestus team: **Share an idea**, **Report a bug** or **Send feedback**, with a dialog that asks for what that kind needs, and a link to the public issue tracker for those who prefer the open. Surveys wait in the same menu with their length; each new one is announced once and never opens by itself. A survey asks one question at a time, marks optional questions, takes **Something else** where its author allows it, keeps your draft when you close it, and lets you undo an accidental decline. Page and browser details go with a report only when you tick the box — ticked by default for a bug.

  Instance administrators can publish a survey for research as well as for product improvement. A **product** survey is read by the Hephaestus team and is never research. Where the instance names a research organisation, a **research** survey is offered only to members who currently take part in that study, is labelled as research with a link to leave the study, and its answers are that study's data. Results pages show invited, responded and declined counts, a summary per question with averages and a Net Promoter Score, every response, and a CSV export. The inbox badges ideas, bugs and feedback and lets you resolve or reopen them.

- Practice reviews assess each evidenced behavior in context, distinguishing useful actions, harmful actions, missing needed behavior and bounded avoidance. Developer practice pages retain both kinds of positive observation, and review guidance ties verification instructions to the change they actually exercise.

  Review history retains individual observations without inferred resolved or regressed statuses. Automatic cross-review progress footers are removed: a shared location or a missing observation does not establish that the same concern changed.

  Reactions and delivery receipts apply to the exact observation they reference. Different behaviors at the same location remain independent, so addressing or delivering one does not silently suppress another.

  **Operators:** Upgrade the server and review runtime together after draining in-flight reviews and feedback dispatches. Adopt the updated bundled practice definitions, review customized criteria for contextual behavior assessment, and remove `PRACTICE_REVIEW_PROGRESS_FOOTER` or `hephaestus.practice-review.progress-footer` from deployment overrides.

- Comments Hephaestus posts on pull requests, merge requests and issues no longer ask for reactions or
  replies it never read. They end with a link to answer or dispute the feedback in Hephaestus, which opens
  your own reviews of that work. A dispute now holds: a later review of the same work does not raise the same
  point about the same place again until you withdraw the dispute, and the setting that turned this off by
  default is gone. Workspace admins see each dispute, with your explanation, on its observation under
  Practice reviews, can filter for disputed observations, and can mark the observation incorrect or withdraw
  the feedback. The dispute forms say that admins read your explanation.
- Hephaestus can now send email. Set `SPRING_MAIL_HOST` (plus `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
  `SPRING_MAIL_PASSWORD` for an authenticated relay) and `HEPHAESTUS_EMAIL_FROM` on the application
  server to turn it on; leave the host unset and nothing changes. Authenticated SMTP requires TLS. Instance admins verify the relay with
  **Instance settings → Email → Send test email**, which reports relay acceptance, configuration problems, or why a test was withheld.
  People whose account has a verified email address receive a confirmation when they delete their
  account, naming its scheduled deletion deadline. These confirmations are queued in the same transaction
  as the account change and retried until that deadline when the relay is unavailable. Permanent SMTP
  send rejections are not retried. SMTP can deliver
  duplicates after a failed acknowledgement; relay acceptance does not guarantee inbox delivery.
  Silent Mode withholds email through the shared outbound guard. The local development stack gains a Mailpit inbox at
  `http://localhost:8025` by default (configurable with `MAILPIT_UI_PORT`). When you choose to activate email, the relay operator becomes a recipient of
  personal data; complete the processor checklist before setting the host. No relay configuration is required to upgrade.

  Optional email subscriptions start off and belong to each account. Administrators can opt in to an email for each new
  product-feedback submission; people can choose product and research survey invitations
  separately. Every optional email offers unsubscribe without sign-in. Survey invitations require an
  explicit administrator action, respect current eligibility and keep relay acceptance separate from
  in-app participation counts. Administrators may request one reminder after 72 hours and subscribe to
  end-of-survey summaries without receiving individual answers. Workspace administrators can opt in to
  Slack credential-revocation and GitHub suspension alerts, with recovery notices. Account linking,
  unlinking and administrator-access changes also send
  security notices to the affected account's verified address. Shared SMTP attempt budgets reserve
  capacity for essential mail; configure them to fit your relay before inviting a large audience.

  Turning a subscription back on does not restart optional email requested before the new opt-in.
  You can turn subscriptions off, including after losing administrator access or a verified
  contact, and while the instance's relay is unconfigured. Survey pauses and schedule edits remain
  consistent with concurrent invitation requests and summary scheduling. Retry batches share turns
  across failed notifications instead of repeatedly retrying only the oldest batch.

  Workspace connection alerts name the affected workspace and link to its current settings. Incomplete
  notification-preference updates are rejected instead of clearing choices, and the API documents the
  required version precondition. Slow preference saves show a saving indicator while choices remain
  unchanged until the server confirms them.

  Webhook receivers now wait briefly for monitoring to stop during shutdown and no longer report
  intentional cancellation as a broker outage.

  Self-hosted instances can run without email: personal settings hide unused email choices rather
  than showing setup warnings, while existing subscriptions remain available to turn off. Instance
  administrators retain setup guidance; survey invitations cannot be queued until sending is configured.
  Surveys and product feedback continue to work in the application without SMTP.

  Research participation now has one control in User settings, also linked from Slack. Account exports
  report that consent decision rather than an unrelated historical preference.

  **Operators:** custom clients must use the dedicated research-consent endpoint instead of the removed
  `participateInResearch` field on `/user/settings`. The shipped webapp already uses the consent endpoint;
  no SMTP setup is required to upgrade. See the migration note for custom-client details.

- Operators can set up encrypted off-host PostgreSQL backups with WAL archiving. The self-host stack now includes pgBackRest backup and restore overlays and example systemd timers. Configure a dedicated S3 bucket, keep an off-host copy of the encryption passphrase, use a separate read-only key for restores, and test backup failure alerts before unattended delivery.
- Each practice group in a review now gets independent limits for model calls and output tokens per practice. Operators can set them with `PRACTICE_REVIEW_PRACTICE_MODEL_CALLS` (default 12) and `PRACTICE_REVIEW_PRACTICE_OUTPUT_TOKENS` (default 16000). The model binding's timeout remains the whole-review safety ceiling, so later practices may not be reached when an earlier group takes a long time. A group that shows no sign of life for five minutes stops instead of waiting for that ceiling. When a long review fills the model's context, the next practice group gets the captured work again.
- Feedback on your Practice profile resolves by itself once three pieces of your reviewed work in a
  row come back clean on that practice, and the card names them. You can also mark a card addressed
  or not applicable yourself, and press the same answer again to reopen it. A newer card about a
  practice replaces the one still open about it, read or not. If your workspace changes how a practice
  reviews work, its open card closes and says why. A closed card leaves the page 30 days after it
  closed.
- Practice reviews now report whether a practice was met, not met, not applicable, or undetermined. Practice authors define the review occasion and evidence directly, without a nested occasion list. Automatic review conditions use each work type’s recorded state, rather than a shared draft flag. Changing when a practice is reviewed does not invalidate assessments of its unchanged standard.

  A met or not-met result recorded under an earlier assessment scheme, or with no scheme recorded, judged a single behavior rather than the whole practice: your review history still lists it as recorded, but a review's summary counts it as undetermined. A catalog update for a practice whose adopted version cannot be proved no longer preselects the offered version: choose current or offered for each changed field. A workspace copy made by an earlier release can compare updates against a recorded version matched by its saved source fingerprint; because that version may not show the original guidance or delivery, you also choose each changed field there.

  **Operators:** This pre-1.0 release removes the previous practice and observation API fields. Back up the database before upgrading, stop running reviews, and deploy the server, review runtime, webapp, and browser extension together. If observations serve a research or audit purpose, export the original observation fields first. The upgrade stops without changing anything if a stored practice definition carries a field the new contract does not read. Keep automated reviews paused until every active practice's criteria describe its positive standard. Update custom catalogue files and API clients to the new contract. Read the migration guide before upgrading; restoring the verified backup is the recovery path.

- Practice reviews now refuse bot authors and bot reviewers with a clear reason. Workspace admins can mark generated paths for each GitHub or GitLab repository in Review settings. Reviews keep that content as generated output, not hand-written work, and show the patterns and changed paths used for the review.
- Connecting a GitHub App installation to a workspace now confirms, through GitHub, that the person who installed the app owns the account it is installed on, and an installation can be connected to only one workspace at a time. If another workspace already holds it, the refusal names that workspace to its administrators. People who install the app from GitHub or from the workspace wizard land on the Hephaestus home page, and their workspace appears as before. **Operators:** turn on **Request user authorization (OAuth) during installation** on the GitHub App, set its callback URL to `https://<your host>/oauth/callback/github`, and set `GH_APP_CLIENT_ID` and `GH_APP_CLIENT_SECRET` to the App's client credentials.
- Each GitLab workspace now gets its own group webhook, which delivers only for that workspace. Events from nested subgroups, from projects created in or moved into the group, and group membership changes are picked up without waiting for the next sync, and a workspace connected to a subgroup no longer depends on how another workspace's group is named. What such a webhook reports is checked with GitLab before it is stored: projects, subgroups, user profiles and memberships are recorded as GitLab reports them to the workspace's own connection, so a webhook cannot rename, move or delete another workspace's project or subgroup, change a user's profile, or grant or remove access that GitLab does not show. A project moved out of the group, deleted, or no longer visible to the connection stops being monitored by that workspace, and a deleted subgroup's team is removed by the next scheduled sync. The group webhook registered by earlier versions is left in place and keeps working as before; delete it on GitLab once the new one shows up.

  **Operators:** set `WEBHOOK_ROUTING_SECRET` on the application server and the webhook receiver before upgrading; see the migration guide.

- GitLab reconciliation now removes comments deleted on GitLab, including merge request diff notes, only after a complete note listing for each issue or merge request. Incomplete listings keep the mirror unchanged.

  GitLab tokens are checked daily and rotated before expiry when permitted. Workspace owners and administrators who enable Workspace alerts receive an email when a token needs replacement and when it recovers. Refused tokens show a degraded connection with replacement guidance, and the connection page shows the observed token expiry without exposing the token. Use a dedicated token: rotation immediately revokes the old token.

- Heph starts getting ready as soon as you open it, in the webapp or in the Hephaestus app in Slack, so your first message no longer waits for Heph to start up. Heph now stays ready for 15 minutes after your last message instead of 5. Going back to an earlier conversation while Heph is ready no longer makes it forget what was said there. Operators can see how long developers wait for the first words of a reply, split into turns that found Heph ready and turns that had to wait, in the new `mentor_turn_first_token_seconds` metric; a custom proxy setup that pins Heph traffic to one replica should keep its affinity for as long as `hephaestus.mentor.idle-ttl-seconds`, now 900 seconds by default.
- Heph no longer has a workspace switch: it is offered to every member of a workspace where a Heph model is ready under **AI models**, on the web and in Slack direct messages, and each member's AI choice still decides whether it answers them. Workspace admins turn Heph off or on by turning its model rows off or on there. The floating Heph panel appears only where a Heph model is ready, and workspace settings now show where each capability is decided.

  **Operators:** the **Chat with Heph** switch is removed. Before upgrading, turn it off in any workspace that should not offer Heph; the upgrade carries that over by turning off the Heph model rows of every workspace where it is off, so no workspace gains Heph. The restore-clone lockdown now turns off every Heph model row; after lifting it, turn them back on under **AI models**.

- A member who finishes workspace setup can now open Heph as soon as a workspace admin turns on **Chat
  with Heph**. Before, the web app also needed a hidden per-account grant that only a database edit
  could add, so members were turned away from Heph. Heph now works the same way on the web
  and in Slack direct messages: it is available to every member of a workspace that has it on, and
  each member's AI choice still decides whether it answers. Turning Heph off or on takes effect
  without signing in again. A link to Heph in a workspace that has it off now says so instead of
  silently returning to the workspace home. When Heph can't answer a member, it now gives the actual
  reason on the web and in Slack alike: they chose No AI, they still have to make a required AI
  choice, or no Heph model in the workspace is within their choice. Before, every one of these was
  reported as Heph not being set up for their AI choice. In Slack, Heph no longer starts a
  conversation or shows that it is reviewing their feedback before it declines. Instance admins can
  still administer a workspace they are not a member of, but no longer get Heph there.

  **Operators:** Hephaestus no longer reads per-account `mentor_access` grants. If you used them to
  open Heph to only some members of a workspace, every member of that workspace can now use it. Before
  upgrading, turn off **Chat with Heph** in any workspace you are not ready to open to all members.

- **Operators:** the limit on one message from a Heph sandbox is now set with `hephaestus.mentor.max-frame-bytes` and counts UTF-8 bytes. If you set `hephaestus.mentor.max-frame-chars`, rename it before upgrading, as the migration guide describes.
- Heph conversations now run their sandboxes on connected workers. Web chat and Slack keep their admission, context and thread history on the application server. When all workers are full, Heph reports that it is busy and you can retry. A worker drain ends its conversations; your next message restores the saved thread on an available worker.

  **Operators:** You must run at least one connected worker for Heph and upgrade the server and workers together. Set its `HEPHAESTUS_HUB_URL` and `HEPHAESTUS_WORKER_REGISTRATION_TOKEN`. Heph has no in-process sandbox fallback. The application server can run with `hephaestus.runtime.worker.enabled=false` without a Docker client.

- Sandbox cleanup no longer removes a conversation's sandbox while the application container that started it is still running, however long the sandbox takes to start. Previously a reply from Heph that had to start a fresh sandbox could fail when cleanup, on any application container sharing the Docker host, ran before the sandbox's container existed. Each conversation sandbox now records the application container that started it, and automatic cleanup removes its network, storage and containers only once that container has stopped or restarted. Cleanup also no longer disconnects the model proxy from a network a sandbox is attached to.

  **Operators:** cleanup now keeps mentor sandbox networks whose owner it cannot establish, including those from before this upgrade, for you to remove by hand; a leftover from an application that cannot identify its own container blocks its conversation until you do. See the migration guide. Practice review sandboxes are cleaned up as before.

- Instance administrators can use **View as user** to open the normal workspace app as a member, including one who has never signed in: their profile, activity, practice pages, feedback, and saved Heph conversations. The view is read-only. Each view needs a stated reason and a recent sign-in, and every read is recorded with that reason. It cannot change anything for the member, does not complete their setup, and does not count as them having seen their feedback. When the administrator's recent sign-in lapses during a view, the app asks them to confirm access and returns to the same page. Profile pages and league stats show the right developer when two members share a username on different providers. Every read about the viewed member counts against the user-view rate limit (120 per minute per administrator by default), and views are refused while the rate-limit store is unavailable.
- Instance administrators can now answer a person's access or erasure request under **Instance
  administration → Person data**, without database access. Find the person by account ID or by exact
  provider user IDs from GitHub, GitLab, Slack or Outline, including people who never signed in.
  Names, logins and email addresses are never used to match.

  The preview counts the person's rows in every store and lists feedback still posted on a provider, so
  you can remove it there first. **Download JSON export** returns one file with the person's
  observations, feedback and delivery state, Heph conversations, activity, memberships, profile,
  preferences and collected Slack messages. It holds no credentials and no other person's feedback or
  profile. Erasure uses exactly the previewed rows, records per-store counts and the acting
  administrator but no erased content, and can resume after a failure.

  After erasure, Hephaestus does not review, give feedback on, record activity for or chat with the
  erased identities again, even when provider sync mirrors their upstream records. Observations and
  feedback about other developers stay with them. Hidden Heph memory in the person's workspaces is
  reset; visible conversations stay. Git history is not rewritten: the export lists repositories whose
  history may contain the person's commits, and the repository owner removes authorship at the source.

  **Operators:** Heph's hidden conversation memory resets once on upgrade. Visible messages, titles and
  times stay. Users may need to repeat earlier context to Heph.

- Workspace administrators can now review catalog updates field by field and accept or decline each practice without changing other workspaces. Accepting creates a new practice revision; declining leaves the definition in use and does not offer the same version again. Instance administrators can make the same field-by-field choice for customized bundled practices. Feedback delivery choices now live in each practice definition, so custom practices can use them and updates show when they change.

  Older adoptions record whether their comparison base came from a matching bundled practice or their current definition. Review an approximate base with care: content that was never saved cannot be recovered exactly.

- A new Practice profile page shows you how your recent work stands across practice groups, what held
  and what changed, and the feedback written from it. At the top, Heph sums up what happened since the
  review before the latest one, from your own reviews: the practices you keep holding, each with a
  line on what you keep doing; every standing, trend, group or feedback that moved; and the pull
  requests, issues, conversations and documents the reviews looked at. With fewer than two reviews so
  far, it covers the last 90 days. **What needs your attention** lists at most two practices you can
  act on today, and each row links straight to the card that says what to do about it.

  The feedback cards are split into newest, open, resolved and all. Every practice group is a row in a
  table ordered by standing, which you can reverse. Opening a group shows what it is about, its next
  step and its practices; the reviews and observations behind a practice open one level deeper when
  you select it, and you can respond to each observation there. A path above each panel's title names
  where you are, and each step in it takes you back there. Each observation carries the next step the
  review wrote for it even when nothing was posted on the work itself, and an observation that found
  nothing, had nothing to judge or could not decide shows where the review looked and why it stopped
  there.

- Practice reviews can read permitted work across connected repositories, Slack channels, Outline collections and developer history from one frozen workspace folder. Reviews no longer lose context to record-count caps or history windows. Access refusals are explicit, a workspace folder above 512 MiB stops before launch, and every accepted quote is checked against the files the review read. The folder is removed after admission or after an unsuccessful attempt's retention period, and a worker restart removes the folders of finished or unknown attempts.

  **Operators:** Upgrade the server and review runtime together. Apply updated bundled practice definitions, and move customized automated-review policies to source contract `1.3.0` through practice authoring. Historical approvals and observations keep their original contract; old policies do not silently gain the wider capture scope.

- The lists behind the Practice reviews overview are its tabs: **Reviews**, now at `…/practices/reviews/runs`, **Observations**, and **Feedback**, which was **Delivery**. **Reviews** can be filtered by more than one status at once and by **Result processing**. **Observations** can be filtered by **Outcome** and **Origin**, and the filter that was **Result** is now **Behaviour**. **Feedback** can be filtered by **Practice** and listed oldest first.
- Reviews, observations, feedback, reviewed work and practices open as panels over the Practice reviews page you are on, and links in a panel open the next record over it, so you keep your place; the page's address names the open panels, so you can share it. Feedback counts on reviews and observations now include every delivery state, so feedback that waits for approval no longer reads as "No feedback composed". These addresses no longer open, and nothing redirects from them; open the record from its list instead. Under `/w/<workspace>/admin/practices/`: `reviews/<review>`, `reviews/delivery`, `reviews/delivery/<feedback>`, `reviews/findings`, `reviews/findings/<observation>`, `reviews/observations/<observation>`, `reviews/targets/<kind>/<work>`, `runs`, `settings`, `autonomy`, `backfill`, `new`, `available`, `available/<practice>` and `<practice>`. For instance admins: `/admin/catalog/practices/new`, `/admin/catalog/practices/<practice>`, `/admin/catalog/groups/new` and `/admin/catalog/groups/<group>`.
- Practice reviews opens on an overview. Feedback that waits for your approval comes first, oldest first. **Review N pieces of feedback** opens the oldest; the arrows beside its place in the queue, such as **2 of 7**, step through the rest, and approving or rejecting one moves on to the next without leaving the panel, closing it once nothing else waits. Feedback opened from a list, a review or an observation opens on its own. One line below counts the reviews that failed or timed out, the reviews whose results could not be processed or delivered, and the feedback that failed to deliver. Then you see what the reviews did over the last 7 days, 30 days, 90 days or 12 months, stage by stage and against the period before, and how each practice turned out. Feedback is counted as awaiting approval, prepared, delivered, unconfirmed, withheld and failed to deliver, and each count opens the list of exactly the rows it counts. The upgrade adds an index on observations, which speeds up the overview and the date filter on the Observations list.
- The bundled catalog covers more of the work a developer does. Three practices follow a change from
  the issue to the merge: whether the change names the issue it implements, whether the merge leaves
  that issue's stated outcome confirmed, and whether a review ask the author deferred was turned into
  tracked work or explicitly waived. A new group, Building iOS apps well, reviews SwiftUI code — I/O kept
  out of views, state owned where it belongs, an interface a screen reader can use, structured
  concurrency, a preview with each new view, permissions declared truthfully, and colors that hold in
  both appearances — and runs only on changes that touch Swift files. "Log through the platform logger,
  not print" joins the code-craftsmanship group for every language the review reads. The tracking group is now named "Tracking work from issue to merge", and "Point the issue at its context" sits with the other issue-writing practices in "Writing issues a maintainer can act on".

  Two more lifecycle practices follow the work to its landing: "Merge only after someone else approved"
  judges the merge against the approvals that stood at that moment and is reviewed when the person who
  merged is the author the review is about, and "Plan the work in an issue before starting it" reads the
  linked issue's opening against the change's first commit. "Classify the issue the way the project
  classifies issues" now judges an issue against the labelling convention the project actually keeps
  instead of waiting for the issue to ask to be routed. A lapse the same developer has already heard about
  on three or more recent pieces of work is named in one line on the work and explained on the practice
  page, and two security practices run only on changes that touch a security surface.

  **Operators:** the pull request record a review reads now carries `merged_by`, linked issues carry their
  opening and closing time, and the project inventory carries the label scheme; no configuration changes.

- Operators can scrape Prometheus metrics without a user token from every runtime role on management port 9090. The supported Compose stacks keep this port on the private container network, without a host publish or proxy route. The public application port refuses the metrics endpoint. The observability guide now includes scrape configuration and alert rules for integration poison events, stale stream polls, LLM budget limits and failed agent jobs.

  Liveness and readiness remain available on the application port at `/livez` and `/readyz`. The supported Compose stacks update their health checks automatically.

  **Operators:** If you use a custom deployment, before upgrading, restrict port 9090 to trusted private services. Outside Compose, management binds to loopback (`127.0.0.1`) by default. For a remote scraper, set `MANAGEMENT_SERVER_ADDRESS` to a trusted private interface and restrict ingress with firewall policy. Update custom management probes to port 9090 (or a `MANAGEMENT_PORT` distinct from both the application and sandbox gateway ports). Update application-port health checks from `/actuator/health/liveness` and `/actuator/health/readiness` to `/livez` and `/readyz`. Do not publish or proxy the management port to the internet.

- Instance administrators see which release, commit and image digest the server reports from its verified release lock, and whether a newer release is published. To learn that, the server asks `api.github.com` once a day, unauthenticated and without any instance data; set `HEPHAESTUS_RELEASE_CHECK_ENABLED=false` on an air-gapped host to switch it off. The overview keeps a newer release apart from a failed, rate-limited, disabled or never-completed check, says whether the newer release carries schema migrations, and links its notes and the upgrade guide. Every runtime role reports the same identity under `release` in `/actuator/info`.
- Signing in or linking an identity now associates the account with its existing synced user; existing matching links are backfilled during the upgrade.

  **Operators:** Read-only user views replace impersonation. Remove clients of the impersonation endpoints and the write-override header, drop the impersonation lifetime setting, and replace the impersonation rate-limit settings with `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_CAPACITY` and `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_PERIOD`. Administrators who were inside an impersonation session sign in again.

- Slack conversation and Outline document practice reviews follow the workspace's people selection without requiring review of every monitored repository.

  **Operators:** A selected repository list, including an empty list, now limits repository work only. If you used it to stop all reviews, turn **Start practice reviews** off before upgrading. Existing channel and document collection permissions, people selections and AI choices still apply.

- Setup and User settings now ask one clearer research question: may the instance's research organization use your data for research on how developers work and learn, and how AI systems can review and support that work? That includes building and running benchmarks and evaluation datasets for such AI systems. The page says what data is used, who sees it, and what withdrawal can and cannot undo, and **What this means** gives the details. The terms and every answer are reworded. **Continue** is now **Save and continue**, and the settings switch is **Allow research use of my data**.

  **Everyone is asked once more.** After the upgrade, each account sees setup again, once. An earlier "yes" is not carried over to the wider question, so research use and research survey invitations stay off until the account answers.

  **Operators:** if you set `HEPHAESTUS_RESEARCH_ORGANIZATION`, read the operator obligations in the Legal Pages guide before you upgrade. If you cannot meet them, unset the variable first. See the migration note.

- Workspace administrators can see which monitored GitHub or GitLab repository or Slack channel failed to sync. Recent sync and historical backfill errors stay visible independently until their own sync paths recover.
- **Review this now** is on Activity: in a workspace that reviews practices, each of your own open pull or merge requests and each issue assigned to you can be reviewed on the spot, and if no review starts, a message says why. The **Review activity** page is gone from the sidebar. The reviews of your own work are under **Reviews of your work**, opened from the chip beside the title of your Practice profile, and you now read a review's details only where it recorded something about your work. Workspace admins find every piece of work Hephaestus recorded, reviewed or not, on the new **Work** tab under **Administration → Practices → Practice reviews**: opening one shows its observations and feedback, what was noticed and whether a review started, with the reason when none did, what every practice made of it, and **Review this now**.
- Practice reviews spend fewer model calls. A review turn now ends as soon as each of its practices has a recorded result. The reviewing model can also batch its searches and reads into one step. The leads the practice scripts find now appear with the practice they belong to, and no longer drop out of large reviews. File searches outside the checked-out repository work again. The agent image now runs on Debian 13 with Pi 1.0.
- Your Practice profile now lists the reviews of your work. Choose the chip beside the title,
  **Latest review** or **Review running**, to see each review that recorded something about your work,
  newest first. Each review shows the practices it found to improve, how many practices it reached and the
  feedback it left you, linked to its comment on your work in GitHub or GitLab. Narrow the list by kind of
  work or timeframe, ask for another review with **Review this now** where you may, and open a review to
  see what each practice saw in your work and everything recorded about it. Dates from an earlier year
  show the year.

  Review lists, here and in workspace administration, name a pull request or issue by its current title.

- The application server no longer holds the Docker socket. AI sandboxes, for practice reviews and Heph alike, run only on the worker container, and a sandbox without internet access can reach that worker's sandbox gateway and nothing else: not the application server, the database, the message broker or the internet. Each release now checks this on the supported install before it is published.

  **Operators:** The single-host install now runs the `application-worker` container as well. Run `./setup.sh` again before upgrading: it adds the worker registration token to `.env` and leaves your other values alone. The application server no longer joins the Docker group; only the worker needs `DOCKER_GROUP_ID`. Plan memory for three Java containers rather than two (5 GB, 3 GB and 2 GB by default), and set `SANDBOX_MAX_CONCURRENT` for the worker if you had raised it. `SANDBOX_DOCKER_APP_SERVER_CONTAINER_ID` is gone: each worker joins its sandboxes' networks as itself, so remove it from `.env`.

- Instance administration → Overview now shows configuration readiness. Settings that need action come first, each with a link to its section of the configuration guide. Optional settings you have not set are listed as optional, not as problems, and no setting's value is ever shown. The install guide points to this view after first boot and ends with your first practice review, whose feedback waits for your approval.
- The sidebar counts the feedback waiting for your approval beside **Practice reviews**, and beside **Practices** while that section is folded, so a decision you owe is seen from anywhere in the workspace. Under **Practices**, **Practice reviews** is now first and **Review** is now **Review settings**.
- Instance settings and connection lifecycle history now record the acting administrator by stable account reference instead of a display login.

  **Operators:** The upgrade drops the old administrator login attribution for silent-mode and instance model settings. It retains the settings and their change times. Old logins are not matched to accounts. It also clears `actor_ref` and free-text `detail` from existing `ADMIN` and `USER` connection audit rows. Event types, state changes and times remain. New connection history uses typed account references; provider and system event references remain. Take a verified backup before upgrading if you need the historical attribution for your retention policy.

  Membership-history subject references that have no exact contributor ID are also cleared. Role
  changes and their times are kept. Person erasure preserves another administrator's attribution on
  shared history rows and removes only the erased person's actor or impersonator reference.

  Pending integration authorizations must be started again after the upgrade. Older signed OAuth states are rejected rather than interpreting their historical display-login attribution as an account reference.

  New pending integration authorizations are tied to the initiating account and removed by person erasure. Existing nonce rows remain without account attribution; no old identity is inferred. Erasure revokes sign-in sessions as soon as the job starts, including when a later store step needs a retry.

  Restored clones still engage Silent Mode before boot. The offline restore lock leaves the account
  reference empty; it does not invent an administrator from an operator label. The lock reason and
  change time stay.

- **Needs you** on Activity shows GitHub review requests to a team you are in under **Waiting on others → Requested from your team**, each naming the team it asks ("via payments"), without counting them as yours. When a GitLab author asks for your review again after you approved or requested changes, the merge request is under **Review requested**. A review request is covered only by another reviewer's approval or request for changes; your own earlier review never covers it.
- Webhook monitoring no longer reports false losses for filtered consumers on shared streams. It now
  reports each stream's current pending and unacknowledged work instead.

  **Operators:** replace alerts on the removed `webhook.stream.unacknowledged.deletions` and
  `webhook.stream.unacknowledged.gap` metrics with sustained backlog alerts. See the webhook ingestion
  operations guide for the new metrics and their limits.

- Workspace admins can withdraw a card from a developer's Practice profile when what it says is wrong but the
  observations behind it are right, and restore it later. Each action needs a reason and stays listed on the
  feedback's page. A withdrawn card the developer had not seen never appears; one they had seen becomes a short
  notice that it was withdrawn, and what it said is left out of the feedback given to later reviews and Heph.
  The observations are unchanged, notes posted on the work are not changed, and nothing is sent again on
  restore.
- Choose once whether your work may use in-house AI, approved cloud AI, or no AI for practice reviews and Heph. Your answer applies across your workspaces and can change in User settings. Workspace setup compares what each answer permits and shows which models are ready there. Where declared, it shows the model maker and the service that receives requests, including Logos, separately from who operates the model. Workspace owners can ask members to choose on their first visit and can set required account links separately.

### Patch Changes

- A feedback card that closed because your workspace changed its practice no longer counts clean work
  toward a resolution it cannot reach. The tab that lists finished cards is now **Resolved and closed**,
  since it holds closed cards as well as resolved ones.
- A practice review now reads a pull request's or merge request's closing link as a candidate that may
  close the issue on an eligible merge, and whether the issue is closed only from its recorded state.
  Before, the evidence a review read could describe an open issue as already closed by an open merge
  request. A merge request stored as closed by its merge now counts as merged when a review checks that
  the linked issue's outcome was confirmed.
- Practice reviews no longer treat issue numbers inside HTML comments in a pull request or merge
  request description as linked work items. This excludes commented template examples.
- Saving a customized practice group in the instance practice catalog, or keeping a customization after a Hephaestus update changed that group, no longer fails. Groups customized under an earlier release keep their saved history.
- Disconnecting a GitHub, GitLab, Slack, or Outline connection no longer marks it disconnected when erasing its mirrored data fails. The connection stays connected with its credentials, so the disconnect can be retried until the data is gone, and nothing is removed at the provider until the disconnect has completed. A provider that cannot be reached still does not block the disconnect. Disconnecting a connection whose integration is disabled on the instance is now refused instead of leaving its data behind.
- A practice review no longer ends without observations when the model cites a pull request record as if it were a changed line, and no longer loses a practice group when the model cites the change by the line numbers of its diff view instead of the changed file. When the model quotes a binary file, the review now points it to the commit that adds the file, which it can cite, and no longer to a file list that is then refused as well.
- A new AI provider connection now uses the Responses API unless you clear the checkbox. For models
  and providers that support it, this API can preserve reasoning between tool calls. Support depends on
  the provider, model, and deployment. Existing connections keep their API; to change it, create a
  Responses API connection and recreate the model entries and bindings on that connection.
- Hephaestus now describes a practice as a way of working everywhere it speaks about one: the practice
  editor, the Slack home tab, feedback on practice pages and the review guidance it writes from. The
  wording of the review criteria of 29 bundled practices changed to match, so workspaces that adopted
  them are offered an update in their practice catalog; accepting it is optional.
- A GitLab issue's latest review now stays current when the next scheduled sync finds nothing new. Previously, a review of an issue that was created, edited or closed in GitLab could disappear from the developer's practice standing a few minutes later, without anyone touching the issue. Real edits to the issue's text, state, type, milestone, labels or assignees still retire the review as before.
- The bundled "Plan the work in an issue before starting it" practice now reviews a partial merge request
  or pull request that explicitly names its issue with `Related to #N`. It compares the issue's opening
  time with the first commit without claiming that the partial change closes the issue. Unresolved or
  excluded references leave the outcome undetermined or not applicable, and oversized linked-issue
  captures are withheld rather than silently truncated. Workspaces that already adopted the practice
  keep their version until an administrator accepts the offered update.
- When you answer feedback on an open pull or merge request, by editing its title or description or by pushing a fix, Hephaestus now reviews the practices that feedback was about again once your changes settle. The new result replaces the earlier one on your practice page and in conversation with Heph. Previously only a Draft/Ready toggle or a manual review request did this. Several edits and pushes in quick succession still lead to one review, only practices whose latest result on that work was a problem are reviewed again, and a label or assignee change does not start one. Practice authors can also choose "Title or description edited" as an occasion for a practice.
- A practice review now finishes when a practice does not apply to the work or the evidence cannot settle it, instead of failing after its observations were recorded.
- Practice reviews cancel context compaction when its turn expires and wait for the session to stop
  before starting another turn. A slow compaction no longer causes later turns to be refused as busy.
- A practice review reads the record as it was made. A GitLab approval now carries the moment the
  reviewer gave it, read from the approval's own note, where it used to carry the merge time and so
  looked given at the merge. The description's own words are told apart from the merge request
  template the project ships, so a template's example issue, checklist or heading is no longer read
  as something the author wrote. Whether the work was planned in an issue first, what became of each
  review ask, and which linked issues state an outcome are now laid out fact by fact before the
  review decides, and the practices that read them say which fact decides.
- A practice review now reads the whole review record. The commits of a change are staged as a record
  a review can quote — each with its message and the files it touched — so feedback about commit
  subjects and cohesion cites the commit, not the pull request title. Inline comments carry their
  thread, reply and side, threads carry their id and opening time, every submitted review decision is
  kept with its summary text, and the pull request record names its labels, assignees, milestone and
  whether it merged. A GitLab note on a removed line keeps its line. The practices that judge review
  engagement, unresolved threads at merge and merging after approval read the record as rows the
  review decides on, and a review is told which record files were not captured so it does not look
  for them. Hephaestus's own inline notes are no longer fed back to a review as a reviewer's comments.
- Practice reviews handle feedback submissions one item at a time, so one invalid item does not
  discard valid items in the same call. The review reserves context for composition and reports
  refused submissions with their reasons. Bounded recovery handles empty submissions and repeated
  commands without extending the review deadline. Delivery remains subject to approval and channel rules.
- Practice reviews handle drafts, pushes and merges consistently across GitHub and GitLab.

  - A GitLab merge request opened ready for review is reviewed once, not twice. Students no longer earn
    double activity points for opening it.
  - New commits pushed to a GitLab merge request are now reviewed, as they already were on GitHub. Pushes are
    grouped for review at the latest commit after ten quiet minutes or sixty minutes from the first
    push. Cooldown and capacity can delay execution. A push during cooldown waits instead of being dropped.
  - A draft is reviewed only for the practices set to review drafts.
  - Feedback from a review that ends after the work was merged now reaches the developer's practice page
    and conversations. The setting for merged work now controls only comments on the merged work itself.
  - A lapse that a later review of the same work found fixed no longer counts toward a developer's
    recurring lapses.

- Practice-review instructions now ask the review to check that a practice applies before judging the work against it. The instructions make clear that finding nothing wrong is not a reason to apply a practice. Summaries and rationales are asked to separate new behavior from existing behavior, and to claim test coverage only for the behavior a test exercises.
- A review on your practice profile now counts the practices it assessed itself apart from those an earlier review of the same code already answered, and from every practice it lists. A practice an earlier review answered links to that review. For workspace admins, its delivery note now says this review recorded no new observations, rather than that there were none.
- GitLab merge requests are reviewed when a separate worker runs practice reviews. If you run the
  worker outside the reference Compose files, give it the application server's `GITLAB_ENABLED` and
  `GITLAB_DEFAULT_SERVER_URL`.
- Practice reviews now accept evidence quotes copied exactly from annotated diffs. Line numbers,
  file paths and quoted content remain verified against the captured change.
  The review runtime preserves quoted whitespace and asks for a correction when indentation or
  punctuation differs, rather than accepting quotes that admission would later refuse.
  Quotes from metadata and other text artifacts are also checked at their cited lines before admission.
- "Say which acceptance criteria are done" now uses process review framing for the author's done and deferred statements. Catalogue adoption applies the updated criteria; existing workspace criteria and their history are preserved.
- Replace the web application's styling utility dependencies with shadcn's maintained class-merging library, preserving component style overrides without requiring upgrade steps.
- An issue edited soon after its last review is now reviewed once the workspace's review cooldown has passed. Previously, a fix made in answer to feedback inside that window was skipped for good, so the issue kept the old feedback until someone edited it again. Several edits made during the window still lead to one review of the issue as it stands when the window ends.
- An observation's summary on your practice page is now always the whole phrase the review wrote. A
  summary that ran too long used to be cut at a comma or colon, which could end it mid-quote; it is
  now sent back to be rewritten, and one that stays too long is not recorded.
- The Slack integration page no longer shows a connection as Connected, or promises that the weekly digest will post, when its stored bot token can't be read. It now says the token is unreadable and that you can fix it by restoring the original server key or reconnecting Slack. The test message stays unavailable until then, and your saved digest settings are kept.

  The Disconnect Slack confirmation explains what happens to data stored in Hephaestus and messages already sent in Slack, without claiming whether Slack will still show the app as installed.

- You can respond to feedback that was posted only as line comments on a pull request or merge request, even when there is no summary comment.
- Approved feedback is now posted to the pull request, merge request or issue when you approve it. Before, approved feedback was never posted: it was recorded as withheld because the approval looked stale. Feedback approved before this release stays withheld and cannot be approved again. To send feedback on that work, open it under **Administration → Practices → Practice reviews → Work**, use **Review this now**, and approve the new proposal.
- Approved feedback on a GitHub or GitLab issue is now posted on that issue. It used to stay prepared,
  approved and sending without ever appearing, because it was addressed as if the issue were a pull
  request. Feedback that fails before it is sent, because GitHub or GitLab was rate limited or could not
  find the pull request, merge request or issue, is now retried and marked failed if it still cannot be
  sent, instead of being held indefinitely.

  Upgrading requires no action. Feedback this fault left with an uncertain delivery outcome before the
  upgrade stays held; Hephaestus does not resend it automatically. A missing comment on the reviewed work
  does not prove the feedback was never delivered. Check the provider before any operator-approved recovery.

- **Merge only after someone else approved** no longer credits an approval from an automated account, such as a bot or service account, as another person's review: a merge whose only approvals came from automation now counts as merged without review. A bot's request for changes likewise no longer counts against a person's approval. Workspaces that adopted this practice can accept the change under **Review updates**; until a workspace administrator does, their reviews keep using the earlier criteria, and an instance catalog entry you customized keeps its own wording.
- Reviews no longer post, or queue for approval, generic praise for practices they wrote no note about; those practices still count on the developer's practice page. Automatically posted comments no longer open with the review's free-form introduction, and a comment about a problem is no longer held for approval because a practice that needs approval did not apply. Review records now list only the observations a posted comment was written from, and a line comment that failed to post is recorded as failed.
- Database upgrades now stop before baseline synchronization when an installation has not completed v0.77.4. The error names the required release and links the synchronization instructions. Release checks retain the v0.77.4 upgrade path and verify that v0.76.0 is refused.

  **Operators:** Before upgrading to 1.0, follow the upgrade-path table. Databases older than v0.77.4 must run v0.77.4 once before baseline synchronization; databases already synchronized on v0.78.0 or later upgrade directly.

- Bouncy Castle is updated to 2.73.12, which fixes a name-constraints bypass through a trailing dot
  (CVE-2026-8763) and a denial of service through lazy ASN.1 sequences (CVE-2026-13506).
- Updates the server's cryptography dependency to fix certificate name-constraint validation and nested ASN.1 parsing vulnerabilities. No operator action is required.
- Screen readers no longer announce GitHub, GitLab, Slack and Outline logos as extra images next to text that already names the provider.
- Three bundled reviewer practices now read inline review comments from the file that holds them, so a reviewer's inline reasoning or vague note is no longer missed. Two of them also read the text that a reviewer writes with an approval or a change request, as the third already did. A printed API or error response no longer counts as a credential leak unless it can carry a credential. The bundled criteria no longer quote text from real pull requests or issues: every example is invented.
- Remove an unused documentation dependency and simplify internal styling and mentor text handling without changing displayed content or requiring upgrade steps.
- You can adopt "Record the outcome of a closed issue" to review whether its body and discussion account for completed, dropped or deferred work. Later comments can correct the record. The existing before-close practice remains unavailable for automatic review.

  Queued closure reviews now stop before model calls when capture finds their submitted evidence has changed.

  Human issue comments remain review evidence when they quote a Hephaestus marker. Hephaestus’s delivered comments are excluded by their recorded provider identities, not their text.

- Practice reviews now follow changes to closed issues, including later outcome confirmations and repairs. Updates assess the current issue record without repeating its historical closure assessment.
- Practice-review instructions check that an observation's derived outcome agrees with its evidenced rationale. Appropriate omissions do not justify negative observations or automatic positive credit. Severity follows each practice's consequence-based criteria, and a missing changed test file does not establish that tests were not run.
- Feedback comments stay focused on the current pull request, merge request or issue. A problem is no longer cut down to a one-line reminder because earlier reviews found it on other work.
- When connecting Slack or Outline asks you to confirm a recent sign-in, the page now offers **Confirm access** with an account you have already linked and brings you back to where you started, such as the accounts step of onboarding or Settings, so you can connect again. Previously its sign-in link sent a signed-in developer straight to the workspace instead.
- Connecting a Slack workspace that another Hephaestus workspace already uses now says so in plain words instead of showing an error code, and names that workspace only to people who administer it.
- Reconnecting an integration now opens its sync history on the first page without briefly showing the previous connection's jobs.
- Study-enabled instances reject research organisation names that cannot fit in the consent record before users sign in. Upgrade checks now answer the research question with the organisation shown in the current notice.
- Bundled practices use consistent evidence and outcome boundaries. Reviews no longer skip security or dependency changes merely because familiar keywords are missing, treat missing capture as a judgment about your work, or use a pull request's creation time to claim that planning preceded development. Review-size guidance uses the actual review burden rather than a universal line or file limit.

  Issue classification remains available as guidance but needs human review: the captured issue metadata does not establish the project policy that requires particular labels, owners or milestones. Hephaestus no longer infers that policy from label frequency.

- Heph receives a bounded summary of your workspace's stored work and recorded review outcomes on each turn, including whether reviewed text and code still match the stored work. Unavailable evidence and provider freshness remain unknown.
- Practice summaries no longer count observations with an outdated or unknown review basis, and practice standing histories do not restore older claims when newer claims are hidden. The browser extension distinguishes an unknown review basis from a changed practice standard.
- Simplify internal styling dependencies without changing component appearance or requiring upgrade steps.
- Environment examples no longer list the unused PRACTICE_REVIEW_FOR_ALL setting. Configure practice
  reviews through workspace administration. AI price validation uses No metered API cost, matching
  the option in the model editor.
- The landing FAQ explains your AI choice, private Practice profile, and approval before feedback is
  sent on the work. Slack reminders count pieces of feedback.
- Hephaestus is easier to use with a keyboard or a screen reader. Every page has its own title, a screen reader announces the page you reach, and the sidebar is a navigation landmark. The skip link shows when it gets focus instead of hiding under the sidebar, dialogs open on their first field, and a panel you reach with Tab shows that it has focus. Heph's mark and the landing figure stop moving after a few seconds. A pressed button is visible in the dark theme, and two colours that were too faint there are readable. The header and filter toolbars no longer scroll sideways in Firefox at a narrow width, and the practice list no longer hides what a catalog label means in a tooltip: the explanation is on the practice itself.
- Workspaces connected to multiple integrations keep receiving events from each one when another integration's event subscription needs a retry. Stopping a workspace also accounts for every active subscription, including one that initially failed to stop.
- Bundled practices distinguish missing evidence from missing behavior and use context-specific expectations for test changes, review explanations, generated files, dependency updates and documented decisions. Shared review guidance no longer assumes a fixed set of available sources or treats an unavailable quotation as proof of absence. A new authoring guide explains how to define and evaluate custom practices.

  Existing workspace practices remain independent copies. Review and adopt revised criteria deliberately; these changes do not enable automatic feedback.

- Practice reviews preserve nonblank citation file identifiers exactly instead of stripping meaningful surrounding spaces. Progress replies describe recorded results without calling them exhaustive review, and context-budget reminders request supported claims without a per-practice observation quota.
- Private feedback preparation now rejects guidance attached to a practice that has no negative observation, so it can be corrected before delivery instead of disappearing from conversation. Feedback on the work can still reinforce a demonstrated strength.
- Practice feedback keeps actionable advice about deferred work. Your review details show the authored next step without substituting a whole provider comment when no next step is available.
- Practice feedback and Heph's replies use more direct writing guidance, preserving evidence and uncertainty while avoiding repetitive openings and assumptions about how a developer worked.
- Practice feedback and Heph are now told to describe what recurs in your work as a practice, a way of working or a repeated pattern, including when they refer to earlier feedback or earlier replies that used older wording.
- Your Practice profile no longer shows lapses your work does not contain for five practices:

  - **State how to verify the change** no longer treats the `Closes #12` example in a merge request
    template as the issue your change adopts. It accepts a sentence that says where to look and what
    is now there, a declared prerequisite such as your own API key, and a generate-and-build route for
    a project rename. It still flags a check that needs a secret nobody says how to provide.
  - **Keep each commit to one logical change** judges what each commit holds, not how its subject is
    punctuated. "Add cast fetching, and adjust movie fetching" is one change. A README commit that also
    renames the source tree or adds views is two.
  - **Validate inputs and edge cases at the boundary** stops flagging values Swift already makes
    optional, slashes inside strings, framework callbacks and guards that sit in another file. It still
    flags a slice such as `cast[0..<2]` behind only an `isEmpty` check.
  - **Avoid crashing on recoverable problems** judges only the crash operators you write, such as
    `!`, `try!` and `fatalError`. A `try!` or `fatalError` inside a preview, and data you ship with
    the app, are not lapses. An unchecked subscript is judged once, under the input practice.
  - **Respond to each review comment** no longer counts Hephaestus's own notes, praise, or advice a
    reviewer posted with their approval for a later iteration as open threads. It also accepts the
    reviewer's own "thank you for incorporating the changes" as a closed loop.

  Workspaces that adopted these practices receive the change as an offered practice update.

- Container image builds now apply available operating-system security updates even when the same commit is rebuilt, while retaining the cache for application dependencies.
- When an issue on GitHub is marked as blocked by another issue, or unmarked, Hephaestus now records the change as soon as the `issue_dependencies` webhook arrives. Before, it waited for the scheduled sync. A sub-issue or blocking issue in another repository is no longer stored in the wrong repository. Before, it could overwrite the issue with the same number there. The GitHub guide now lists every fine-grained token permission that Hephaestus uses, and what stays empty without each one.
- On GitHub, review requests to several people within the same second are all stored, so Heph and **Needs you** see every one; before, all but the first went missing until the pull request changed again.
- GitLab approvals no longer inherit a merge request's update time or a webhook's arrival time. For a merged merge request, Hephaestus recovers the approval dates GitLab reports; when an approval cannot be dated reliably, Hephaestus keeps its time unknown rather than claiming it happened before the merge, and keeps the approved commit unknown when GitLab does not say which one it was.
- GitLab bot and service accounts are now recognised as bots from what GitLab reports about them, whatever their username, so an approval by an automated account no longer counts as a person's review. An account already known to be a bot stays one when a later update does not say, and an account GitLab reports as a person is corrected to one.
- GitLab inline review discussions now keep their resolved or reopened state in practice reviews and in conversations with Heph, including discussions Hephaestus first saw through a comment webhook, and an older read of a merge request's discussions no longer overwrites a newer one. GitLab documents an event when all discussions on a merge request become resolved, and Hephaestus picks that up right away; other resolution changes appear the next time Hephaestus reads the merge request's discussions.
- A GitLab workspace on a subgroup now includes everyone GitLab lists with access to the group: people who reach it through a parent group or a group invited to it, tutors listed in a team subgroup, and students who are members of one project only. Their merge requests are reviewed when marked ready, and a merge request marked ready before the member sync picked up its author is reviewed once it has, instead of being passed over. People keep the highest role GitLab lists for them, and someone only a subgroup or a project lists is a member but not an administrator. Someone who loses access leaves the workspace at once when GitLab reports it, or otherwise at the next sync that GitLab answers in full; a pending e-mail invitation no longer stops that sync, and removing a direct membership someone also holds through a parent or invited group keeps them. The workspace owner is kept. A member hidden from workspace activity who loses access is hidden again if they return. A merge request opened under **Administration → Practices → Practice reviews → Work** now tells an author who is not a workspace member yet apart from a repository or base branch outside review coverage, and the review screen's **People** count no longer includes the owner's linked profile from another provider.
- Practice reviews of a merged GitLab merge request now know who merged it. Hephaestus keeps the merge commit GitLab reports and reads who merged the merge request, and when, from GitLab after the merge. A practice about merging, such as merging only after approval, is reviewed when you merged your own merge request; when someone else merged, it is not attributed to you. Until Hephaestus learns who merged, the review waits and shows "Who merged this is not known yet" instead of running without that practice. An approval given after a new push counts as an approval of the new commits rather than of the commits before the push.
- On GitLab, removing everyone assigned to an issue or merge request, or a merge request's last reviewer, now takes effect as soon as GitLab reports it; before, the old assignees and reviewers stayed until a later sync corrected them.
- A GitLab project that is renamed or moved inside the connected group keeps a single monitored repository, with its sync progress and practice review selection, even when work on the new path arrives before the rename itself or a sync finds the new path first. A workspace that already monitors a project twice is repaired the next time a sync or a project event reports that project, and the remaining entry keeps the review selection of both. Adding a GitLab project by hand no longer creates a second entry for an already synced project the workspace monitors under another path. Syncs and project events no longer add a monitored repository once the GitLab connection is disconnected, or for a project outside the connected GitLab instance and group. Adding a repository by hand to a workspace with no active GitHub or GitLab connection is refused with a request to connect one first.
- Heph and the activity pages no longer report GitLab approvals or requests for changes that nobody gave. A merge request in a project that requires no approvals is not called approved until someone approves it. A standing request for changes is shown even when the approval rules are met. A comment on a merge request no longer turns the commenter's approval into a request for changes. Approvals that still leave required approvals missing are now recorded as the reviewer's own approvals. On upgrade, approvals that were wrongly turned into requests for changes are withdrawn. Merge requests that were marked approved with no approval behind them show no decision until the next sync reads them again.
- GitLab webhooks you have given a signing token (GitLab 19.0 and later) are now verified by their
  signature. Before, the signature was not recognised and such a hook was accepted on its secret token
  alone. A delivery whose signature is blank, malformed or wrong is now rejected even when its secret
  token is correct, so `WEBHOOK_SECRET` must be that hook's `whsec_…` signing token. Hooks Hephaestus
  creates still use only a secret token and keep working unchanged.
- The GitLab workspace wizard now waits until it knows your server's GitLab instance and creates the
  workspace there, instead of briefly showing gitlab.com and validating your token against the wrong
  server. GitLab workspaces are created only on the instance Hephaestus syncs from
  (`GITLAB_DEFAULT_SERVER_URL`); any other instance is refused before your token is sent or stored.
  The new workspace belongs to your GitLab account on that instance, so link it first. When GitLab
  refuses your token or does not return your groups, the wizard says so instead of showing an empty
  list. A GitHub workspace you create now belongs to your GitHub account, even if you linked GitLab
  first.

  The workspace connections API (`POST /workspaces/{slug}/connections`) no longer adds GitLab
  connections; GitLab is connected by creating a GitLab workspace.

- The build no longer resolves a vulnerable FreeMarker while generating provider clients. It is used only to generate code at build time and never reached a running Hephaestus, so no deployment is affected.
- Chatting with Heph reads more easily: your newest message moves to the top so the reply reads down from it, and the view follows the reply only while you stay at the end. When Heph has to start up before its first reply, the chat now says so instead of only "Thinking…", and screen readers announce it.

  New chat in the floating panel starts a separate conversation and keeps your previous chat available in the sidebar.

- Heph's review-discussion guidance gives stored work precedence over its earlier explanations.
- Heph now finds the stored review detail of a developer's pull request or merge request, and the evidence of an observation, from the exact reference listed beside each item, instead of guessing a path from a number. When it asks for a path that does not exist, it is told where to find the right one.
- Heph keeps more of your recent discussion word for word when shortening long conversations.
- Heph reads a developer's review history as a short overview and looks up an observation's evidence only when a question needs it. It shortens a long conversation before answering, and says so with an error when it cannot, instead of sending the whole conversation again. When Heph has already shortened a conversation and its answer still runs out of time, asking again continues from the shortened conversation instead of starting over.
- Hephaestus now refreshes a GitLab merge request's readiness — its pipeline, merge status and approvals — after activity on it: opening, updating or pushing, and approving or withdrawing an approval. Newer pipeline and approval information is kept when an older notification or read arrives late, and a push that resets approvals no longer leaves the merge request reported as approved. The readiness Heph reads tells a commit GitLab reports without a pipeline apart from a skipped pipeline and from checks Hephaestus could not read.

  For GitHub and GitLab alike, what Heph reads for merge advice now also includes your merge request's description and the text of the issues the provider records it as closing, both shortened when long, so an open condition can inform the next step. A closing link does not mean the issue's conditions are met.

- Heph can now read the stored review discussion of a developer's closed or merged pull request or merge request — approvals, notes, inline threads and the issues it closes — and says whether that work was merged, when and by whom. What it reads is Hephaestus's stored copy, not a live check with GitHub or GitLab. Its merge readiness list still shows only open work.
- Heph now sees earlier review results beside the latest one for the same practice on the same pull request, merge
  request, or issue, so after you fix your work it can tell what the earlier review found from what the later one
  found. It also sees results where a practice did not apply or could not be decided, when they were measured under the
  practice's current review rules and may be used in conversation, so it can tell them apart from a review it cannot
  see. What Heph sees is a bounded recent sample, not your full history; your practice page shows your current standing.
- Heph now considers reviewer conditions and the latest recorded checks and merge status before suggesting
  that you merge a pull request or merge request. It includes conditions in general comments and inline
  threads, and tells you when its record is incomplete or may lag GitHub or GitLab.
- The thumbs up or thumbs down you give one of Heph's replies now stays selected when you reopen the conversation. Previously your choice was saved but appeared cleared after a reload.
- Heph's replies no longer lose words while they stream, and Heph only shows a reply as finished once it has been saved. A reply that cannot reach you intact, or cannot be saved, now ends with a clear error instead of passing as a finished answer, and when you reopen the conversation that reply is marked as interrupted and incomplete.
- Heph no longer answers that no note was proposed, rejected or delivered for your pull request or merge request
  just because it cannot see one. It sees only the records of your feedback it may use in conversation — not feedback
  waiting for a reviewer, a reviewer's decision, or feedback it may no longer use even if it reached you — so when it
  sees no note for a piece of work, it now says it cannot tell what happened there instead, and still tells you what
  reviews observed on that work. Changing your work no longer by itself removes delivered feedback from Heph's view;
  when the work or the practice has changed since the review the feedback is based on, Heph treats it as possibly out
  of date.
- Heph now sees which version of a pull request, merge request or issue each recorded review read, and whether your work still matches it, so it can tell you when a repair has not been reviewed yet. Marking feedback as addressed counts as your reply, not as a new review.
- Heph can check recent retained practice reviews on work you authored, including reviews that recorded no observations.
- Heph only shows feedback about an observation that is really about you and that it may still raise. Feedback naming an observation that does not exist, belongs to someone else, or may no longer be raised is no longer shown in the conversation, in the web app or in Slack.
- When Heph raises practice feedback in a conversation, the feedback now appears in its reply, in the app and in
  Slack, and is recorded as delivered only once that reply reached you. Conversation feedback recorded earlier
  without a record that it was shown is now marked unconfirmed rather than delivered, and is not raised again.
- Heph can now tell a practice that simply went well from a recorded delivery failure. For your most recent
  feedback, it sees what Hephaestus recorded as delivered to you and where — on your pull request or merge request, on
  your practice page, or in conversation — whether feedback prepared for your practice page has been opened, and when a
  delivery was recorded as failed.
- Heph now tells your own replies on a pull request or merge request from what others wrote, and an automated account's approval from a person's. It no longer takes an edit to your description for new commits, and it names something you did well only when it can point to it.
- Issue feedback now shows earlier observations as historical when the issue changes, even if a later review cannot finish. Edits also retain the person who made the change without treating that person as the developer under review.
- Practice reviews of issues, Slack conversations and documents read the reviewed item again. Since the workspace folder change they could stop with missing evidence even though the issue, thread or document was available.
- Updates the server's JSON library to fix a denial-of-service vulnerability, in which a request with an
  oversized duration or date value could hold a server thread for minutes. No operator action is required.
- Practice edits now keep the work a practice applies to and the person it judges. Changing either setting requires an explicit edit. Catalog previews show both settings before a practice is added. The new review fingerprint can mark older catalog copies as having an update available and older review results as stale after the next definition edit.

  Previously saved practices are not repaired automatically. Check practices edited before this fix if their gate or person judged may have changed without notice.

- **Confirm the linked issue's outcome** credits the issue as Hephaestus captured it: a checklist ticked or a closing sentence added after the merge counts. The review does not say whether that happened before or after the merge unless independently dated evidence shows it.
- When you edit the title or description of a closed issue that merged work linked, Hephaestus reviews the issue's outcome confirmation for that merged work again if the issue differs from what the earlier review read. Feedback from the earlier review that has not reached you yet is withheld only when the new review finds the practice met; earlier feedback stays in your history.
- Heph distinguishes the review results available in a conversation from the observations a completed review may have recorded.
- Heph uses the workspace's configured mentor timeout for each turn, including compaction, and stops an unresponsive runtime before another turn can reuse it.
- When a Heph reply stops early, the server and sandbox logs say why: the browser disconnected, the connection timed out or failed, or the reply was aborted. Each entry names the workspace and conversation and carries no conversation content.
- Heph can read the practice criteria used for a recorded observation when discussing its result. Criteria that cannot be loaded in full are marked unavailable.
- Heph, practice reviews and feedback speak of practices, and ask for a missing rationale instead of supplying one the work does not show.
- Heph is explicitly instructed to use the current practice vocabulary, including when discussing older feedback or conversation history.
- Heph offers a next step when it answers your question or there is work left to do.
- Heph reports a failed reply in web and Slack chats when the model cannot respond after its retries, so you can try again.
- Mentor usage and cost include reported calls before an automatic retry. If that turn's accounting is unavailable, its usage and cost remain unknown.
- When your workspace withholds a comment because the pull request or merge request has merged, eligible feedback for your practice page and conversations with Heph is prepared promptly.
- Practice criteria, rationale and examples can be updated with multiline Markdown without being rejected as blank. Whitespace-only updates remain invalid, and omitted fields remain unchanged.
- "Log through the platform logger, not print" now treats `NSLog`, the `os_log` functions and Android's `Log` calls as platform logging, whatever tag a `Log` call uses, rather than as print statements.
- Observation severity now appears for missing desirable behaviour as well as present undesirable behaviour, and never for positive outcomes.
- Workspace members can no longer read other developers' observations on a pull request through the
  API. Observations are shown only to the developer they are about, and to workspace admins in
  Practice reviews.
- The Outline collection picker stays open while adding collections, preventing a dismissed batch from interrupting a newly opened picker. It closes on success and can be dismissed again after a failure.
- Error messages in the web app, the browser extension, emails, and the notes that Hephaestus posts to Slack, GitHub and GitLab now say what happened and what to do next. Buttons name the action they run, and everyday contractions such as "you’re" are back. Turning off silent mode now asks you to type "turn off" instead of "release". The wording of some API error messages changed.
- Practice definitions reject unknown fields instead of silently ignoring them. Switching reviewed work types preserves the visible precondition draft, and renaming or moving a practice, or editing its criteria or evidence capture, refreshes the displayed automated review validation.
- Practice reviews accept relevant context outside the wiki and decision records added with the change.
  They recognize supported SwiftUI storage and permission requests made from the feature, including a root view.
  A valid fixed URL is not an unhandled runtime error.
  Notable dependency changes need a reason, not just a package name, and no longer receive duplicate feedback for a missing architectural rationale.
  Reviews also avoid duplicate feedback for diagnostic prints, credential exposure, and permission descriptions.
  Earlier observations keep the practice revision that judged them.
- On a practice group's page, the response you choose on an observation stays pressed while it
  saves, instead of showing your previous answer until the page catches up, and your Practice
  profile shows the same answer straight away. When the reviews loaded so far hold nothing for
  the practice you picked, the page says so and offers the earlier reviews, rather than saying no
  review has reached the practice yet.
- Practice reviews get more accurate starting points. An issue whose body begins with or mentions its title is no longer treated as empty, an unreported sub-issue count is no longer read as zero, and untested code is judged even in a project without a test target. A pull request with a single commit is judged for commit cohesion, and a linked issue the change only mentions is no longer treated as one it must confirm. The iOS practices no longer call a scaled custom font fixed, a system photo picker a library permission, or a keychain item without an explicit accessibility class unsafe. Reviewer-comment and commit-history practices are framed by what people wrote rather than by the code, and a bare approval with no comment no longer counts as reviewer speech to judge. A missing issue link is reported even when the same change also lacks a test, and "Break large work into trackable subtasks" judges largeness by independent deliverables rather than by counting items. Updated criteria are offered for each workspace to adopt; its existing criteria and history are preserved. Advisory and review-admission corrections apply when the instance is updated.
- When your account links more than one GitHub or GitLab identity, your profile, practice pages, feedback and Heph now use the identity on the instance the workspace is connected to, instead of the one you linked first. Your earlier conversations with Heph in that workspace stay listed.
- Practice reviews can store a corrected observation after its first submission was refused, without repeatedly refusing the corrected draft.
- Practice reviews get clearer guidance about what the captured record can show. A review thread whose resolution or merge time is not recorded, a manifest line that only looks like a dependency change, a Maven or XcodeGen dependency edit, or a lockfile elsewhere in the repository is presented as something to check rather than as settled. A SwiftUI image in template mode is no longer suggested as a place where untrusted input is rendered. "Log through the platform logger, not print" now judges only logger versus print. A fully captured decision record that leaves its alternatives unfinished is judged as written rather than treated as missing evidence.
- A practice review no longer treats a record that was not captured as empty, and an empty testing section no longer reads as "no way to check the change". A review also refuses fewer citations: a range copied from the change view and a repository file named by its workspace path are now recorded at the lines they name.
- Practice reviews, the practice editor and instance administration name things the way you know them,
  never by internal codes.

  - The moments a practice watches for, the evidence a review could not read and the integrations that
    report them are named in words, such as "Marked ready for review", "Code changes" and "GitHub".
  - A practice set to "Human review needed" or "Guidance only" reads as skipped for that reason on a
    review's practice list, no longer as evidence the review could not read.
  - A document is shown with the name of its Outline collection rather than the collection's link id.
  - A piece of work is named the way its provider writes it: "Pull request #1423" on GitHub, "Merge
    request !1423" on GitLab. The Work list shows that number again.
  - Why a review did not start reads the same everywhere, in plain words, whether you asked for the review
    or are reading about someone else's work.
  - The practice editor explains what to change when it cannot save a practice, naming the moment, evidence
    source or person it means, and names the person a practice judges as "Author", "Reviewer" and so on.
  - A kind of work, moment or evidence source that this version does not recognise yet is called "Other
    work", "A moment this version no longer offers" or "Another source" instead of an internal code.
  - The instance admin workspace and user tables show statuses, roles and providers in words, and an
    account with full access is an "Instance admin" in the table, the role dialog and its confirmation.
  - A Hephaestus default or offered update shows its feedback-delivery choices as sentences, and the
    catalogue filters and the practice editor say "kind of work" throughout.
  - Reviewing past work says what was reviewed rather than what was "measured".

- Practice setup keeps its work filters and catalog actions separate when space is limited, so each control can be clicked reliably.
- Precompute now resolves the Node executable before clearing its environment, so a version-manager shim cannot prevent review preparation from running.
- Practice reviews preserve the exact text of submitted evidence quotations, including indentation and line endings. Local citation checks now use the same strict text-matching rules as server admission rather than accepting altered indentation, display coordinates or substituted punctuation. Valid indented quotations are no longer damaged before submission.

  Review tools also state their durable-submission boundary: citation-format experiments are not practice observations. Time-budget reminders request only supported claims, never an observation quota.

  Source lines that resemble diff headers remain source content for evidence and secret checks, and inline feedback retains the correct source location. Citation coordinates outside the supported positive 32-bit range are rejected before matching.

- Renaming a practice keeps its identifier. Reviews reject unsupported evidence fields, and historical practice revisions cannot be assigned a new assessment fingerprint.
- Practice reviews preserve the severity of supported observations instead of silently lowering it based on the practice's name. Custom practices follow their own review criteria; feedback approval and delivery controls remain unchanged.
- Two bundled practices now judge what a change actually adds. "Ship a preview with each new view" accepts a preview that renders a new view through the screen hosting it, and no longer asks for an extra preview of a view that only starts and stops work for content that is already previewed. Navigation, toolbars and other parts such a view shows itself still need a preview. "Change dependencies deliberately" no longer reviews a change that only edits a project file's internal targets, test targets or schemes; it applies when an external dependency, its version, its source or its resolution changes. Workspaces adopt the new definitions through the usual practice update.
- Privacy information now explains the wider review context, member AI and Slack choices, private feedback, and operator-assisted person export and erasure, including remaining provider and backup copies. Operators have a complete data-protection checklist and a DPIA screen that indicates a full assessment. The revised TUM notice still requires its legal owner's approval before release.
- A host on pull-based deployment no longer fills its disk with a checkout for every release or commit it has applied. After each successful apply, it removes the checkouts and release locks it no longer needs. It keeps the release it runs, the one before it for a rollback, the tooling it runs, and any checkout that is locked with `git worktree lock` or that holds files of its own, such as an older proxy's `acme.json`. Docker volumes, and the certificates in them, are never touched. Pruning starts with the second apply after this upgrade, because that is the first apply the new reconciler makes.
- Queued practice reviews now dispatch correctly in a workspace that binds a separate model for each AI data-handling tier. Each tier's concurrency limit applies only to reviews in that tier, so a busy tier no longer holds back reviews in another one, and a second tier binding no longer stops queued reviews from being picked up.
- A push or edit made while a pull request or merge request was a draft now waits for the review that starts when it is marked Ready. That extra wait ends once the oldest waiting push or edit has waited an hour, even if the Ready review has not finished. Its own review then skips the code practices the Ready review already answered on the same commit and base, and the review details list each skipped practice with a link to the review that answered it. Practices that also read the description, commits, comments, linked issues or documents are still reviewed, as are practices only a push or edit checks. When the Ready review already answered every ready practice, the push or edit review completes without running a model and shows "Answered by an earlier review" instead of an empty result.

  The bundled practices "Include tests with the change", "Keep the test suite honest" and "Track generated artifacts only when justified" now declare the description and commits they already read as evidence.

  Workspace administrators must adopt these three evidence updates through the practice catalogue before using review reuse with existing copies. Customized practices must declare every source their criteria read. Existing practice revisions and observations are not rewritten.

- Delayed pull request and merge request updates no longer repeat practice feedback for work that was already reviewed at the same revision. Real title, description and code repairs still receive a new review after the quiet period.
- Workspace administrators can reconnect Slack from the Slack integration page to replace an unreadable or outdated token without disconnecting. Choosing the Slack workspace already connected keeps its channels, their messages and its retention settings.
- GitLab reviews use the recorded merge-request diff base rather than an advanced target branch, including legitimately empty changes. Unavailable revision objects remain explicit evidence gaps. Reviews stopped before model execution now record result processing as complete without claiming feedback was delivered.
- Recurring feedback now gives a brief reminder without promising separate guidance on your practice page.
- Captured issue mentions retain bounded source context and are identified as text candidates, including template examples. Review instructions also distinguish displayed diff annotations from exact source quotations; evidence admission remains strict.

  Issue-closure reviews distinguish current checklists and sub-issue counts from evidence of their state at closure. A current snapshot alone does not establish a historical outcome or a developer-wide pattern.

- Practice reviews retry a temporarily busy result upload rather than treating it as completed. Missing files in historical citations are reported as missing evidence instead of failed repository operations. Repository citations show the full commit identity without requiring a hover.

  Long-running repository preparation no longer loses its temporary files to stale-file cleanup.

- Reopening a saved conversation with Heph shows its earlier messages again. Previously, reloading the page or coming back to a conversation showed only Heph's greeting and the error "Couldn't load this conversation's earlier messages."
- When a worker replaces its connection to the server, the old connection is always closed. Before, it could stay open.
- Practice reviews can recover when a sandbox workspace download stops early. A stopped review can upload its result for ten minutes after its work deadline, and upload retries no longer mistake a conflict for success. Precompute cites captured records at their task-declared paths when a workspace layout changes.
- Practice reviews reject observations and refusal updates from attempts that were cancelled, retried, or reassigned while a submission was being processed. A fetch into a repository mirror is serialized against the reviews reading that mirror, so concurrent operations on one repository cannot bypass each other.
- Your review history shows when an observation was marked incorrect and why, while keeping the original result. Profile reviews refresh after an observation is corrected or restored.
- Practice reviews can correct a result before it is recorded. Each practice receives one final result per review; ambiguous repeated results are refused before feedback is prepared.
- Saving practice review coverage now keeps exactly the repositories, base branches and people you select. Changing a selection that kept some repositories or people no longer drops them or fails to save, and switching back to all repositories and all people, then to a selection again, stores the new selection.
- The review-ask deferral practice now explicitly requires a deferred ask before crediting tracked follow-up work. Fully addressed asks remain outside its review occasion.
- Failed practice reviews no longer appear completed merely because the runtime saved a draft. Admission refusals retain their recorded reason, while observations already accepted by the server remain available.
- When a review writes new practice feedback, it no longer reads the wording of earlier feedback about work that has
  changed since. It still sees that the earlier feedback was given, so new feedback is less likely to repeat claims
  about work you have already fixed. When an automatic review's note on your pull request, merge request, or issue has
  no line comments and reads word for word like the note already recorded as delivered there for you, it is not posted
  again; it is recorded as withheld, and the review's feedback for your practice page and for Heph can still be
  prepared. Two reviews of the same work finishing at the same time can still each post a note.
  While a Slack channel's consent is paused, new reviews no longer read the earlier feedback and results from its
  conversations; they read them again once the channel is resumed.
- Grouped push reviews can finish without a database lock conflict. Waiting for an earlier model turn
  to stop now uses the review's time budget, and a finished composition deadline prevents another call.

  Practice reviews retain separate duplicate-code candidates and no longer mistake a version or a
  number with a suffix for a closing issue reference. Model settings explain that reasoning support and
  defaults depend on the provider and model, with instructions available to screen readers.

  The initial review brief now counts line numbers, headings and truncation notices against its size
  limit, so newline-heavy files cannot expand the prompt beyond the configured bound.

- Review details distinguish completed result processing from feedback publication, including when feedback still awaits approval.

  Retrying result processing keeps the review status and prepared feedback up to date without requiring a page reload.

- A fully captured practice-review trace can be complete even when the review fails. Trace completeness now records finalized, closed capture with no dropped events; the review's exit status, errors and evidence-admission result remain separate. Interrupted or incomplete capture is still reported as incomplete, and historical traces retain their recorded completeness.
- Practice reviews spend fewer model calls on recordings that are refused and sent again. A refused observation now names every problem at once, with what the corrected one needs, and no longer leads the model to record a different verdict just to be accepted. A review also no longer sends the model to cite files it cannot cite, fills in what it already knows instead of refusing it, and stops a model that keeps sending an identical refused recording. In-app feedback no longer describes a pattern across your work when the only other occurrence is an earlier review of the same merge request.
- When you merge your own pull requests or merge requests, feedback about merging can now appear on your practice page when the same problem is supported by two pieces of work.
- Rebuilding the PostgreSQL, webapp and practice runtime images now carries current operating-system package updates into the shipped image, including when the build reuses registry caches.
- Sign-in sessions in your settings show dates the way the rest of Hephaestus does, and a session list that fails to load offers **Retry**.
- Silent mode no longer stops feedback from reaching a developer's own Practice profile. Silent mode
  still holds back everything that would leave the instance, such as comments on pull requests,
  merge requests and issues and feedback in conversations; the feedback about a developer's ways of working
  is still written and shows on their Practice profile.
- Slack Home reflects your current AI choice and explains when Heph is off or unavailable for you, while keeping account settings and channel-message privacy controls accessible.
- Heph checks your current AI choice and eligible mentor model before sending a Slack invitation to discuss feedback.
- Heph’s Slack replies keep distinct work links and the text of its answer intact.
- A Slack thread review now judges only the messages of the person it is about. A lapse that cites only another participant's messages is withheld. Before, a vague question or status update from one participant could become feedback for every participant of the thread.
- Every status badge in the practice administration, review and trace pages now says what it means
  when you hover or focus it, and a piece of reviewed work is named the same way on every page: its
  number, its title and where it lives.
- Text in the web app, the browser extension, emails, API errors, and the comments and messages that Hephaestus posts to Slack, GitHub and GitLab now uses short, plain sentences. The wording of some API errors and email subjects changed. A script that matches on that text must change.
- The privacy statement of the TUM-operated instance no longer mentions the retired leaderboard, leagues or Slack leaderboard digest, and describes Activity instead: counts and lists of your pull or merge requests, reviews, issues and comments, and of the review requests waiting on you or your team, shown only to members of your workspace and never ranked.
- Improve light- and dark-mode consistency in mentor controls, attachments, development sign-in and feature flags. Keep message actions visible when navigating by keyboard or using a device without hover, restore larger action targets for coarse pointers, and give unranked league icons a defined neutral color.

  Use theme-aware colors for environment indicators and merged-work previews. Let reviewed-work popovers grow with wrapped repository names instead of clipping them to an assumed row height, and give their copy action an accessible name.

  Report review-link copying as successful only after it completes, show pending and failure feedback, and retain readable work with unusable links without making it clickable. Copy repository labels as text rather than interpreting them as HTML. Let long empty-state descriptions grow without losing their actions, and announce the selected mentor vote to assistive technology.

  Keep action spacing consistent across forms, tables and mentor controls using shared button sizes. Restore the outlined sidebar action’s theme-colored border and preserve its visible keyboard focus ring.

  Keep dropdown menus within the available screen width, including the feedback menu on narrow screens.

  Set small labels, counters and badges on one shared type size instead of nine slightly different ones, so the smallest text — avatar initials and count badges among it — is legible everywhere it appears. Round small controls on one shared corner scale. Give the message editor the same surface as the composer below it in dark mode.

  Draw the dashed edge on placeholder cards and on repositories hidden from contributions — it was declared but never rendered. Bring review-activity and issue cards onto the same card look as the rest of the app, and give every search field with a leading icon the same input group, so the icon, padding and focus ring match everywhere.

  Open Heph in a side panel that behaves like every other panel: the page stays readable behind it, it takes focus, and Escape, a press outside or a swipe closes it. It is full width on a phone.

- Try again now asks Heph to answer your question again after a reply fails, instead of reporting that another reply is already in progress. The conversation keeps your question once and shows the new answer after a reload.
- The TUM privacy statement and imprint no longer contain unfinished placeholders. The privacy statement now names TUM as the controller, explains what workspace administrators decide and how your AI choice selects the models for reviews about you, and describes the retention criteria for research data and backups. It says that no error-reporting service is currently connected, and it no longer claims an ethics approval or a default cloud model.
- Copy in the web application now uses typographic apostrophes and quotation marks (’ “ ”) instead of typewriter ones. In the Outline **Add collections** dialog, the search box has focus as soon as the dialog opens, and pressing ↓ then Enter selects a collection without clicking or typing first.
- Unavailable GitHub and GitLab repositories keep their monitored work and show “Repository not found or not accessible”. After repeated unavailable responses, sync checks them once a day instead of fetching them on every pass. Restoring access resumes sync automatically; workspace admins can use Sync now to check immediately.
- Updates the HTTP client inside the practice review agent image to fix a TLS certificate validation
  bypass and a WebSocket denial of service, and updates build tooling for four further advisories. No
  operator action is required.
- Database migration and schema checks now work with strict dependency verification on fresh Gradle installations.
- The maintenance page now runs from the same signed, scanned webapp image as the application, rather than a separate unpatched Nginx image. Its files and startup remain separate from the application. Release locks select the same verified webapp version for both services.
- Prevent changed or reused provider usernames from granting another developer's workspace access, exposing their account preferences, or attributing Slack and Outline activity to them. Workspace membership responses and exports report the strongest role across linked identities while keeping the selected developer independent of role changes and newly linked accounts.

  Slack consent changes and feedback notifications use the verified developer. Suspended accounts and accounts awaiting deletion cannot start Slack mentor turns or receive Slack feedback notifications.

  Account settings preserve provider-synced profile details. When a profile must be created and its saved username now belongs to another identity on the same provider, settings report a conflict rather than changing that other developer's profile.

  Workspace admins cannot demote owners. Manual role changes and membership removals preserve the last workspace owner.

  GitHub installations no longer attach to an existing workspace solely because an account name matches. Reinstallation and automatic PAT promotion require the same recorded GitHub organization identity; personal-account and legacy workspaces without that identity stay separate. Existing installation bindings continue working across account renames. Workspace creation requires a connected SCM account, and administrator elevation cannot reveal another developer's private mentor conversations through a matching display name.

- Webhook stream monitoring finishes its active poll during shutdown before broker resources are released, avoiding polling against a closed connection.
- After a server restart, webhook events for repositories a workspace already monitors, and its Slack and Outline activity, are processed while the startup sync runs instead of after it, including events that queued up while the server was down.

  When a GitLab group is connected, Hephaestus registers the group webhook only after it has listed every project in the group and is ready to receive their events, then runs the full sync while those events are processed. If it cannot list every project or cannot get ready, it leaves the webhook unregistered, and the integration's sync status shows the webhook as missing; the next scheduled sync, or **Sync now**, tries again. A deployment with NATS disabled no longer registers GitLab group webhooks, because nothing there would receive their events.

  A group webhook that is already registered is unchanged: events for projects Hephaestus does not monitor yet, such as newly created ones, are still not picked up.

- Practice reviews now see the same labels on related issues and pull requests in a repository as on the issue under review. An unlabelled issue is recorded as having no labels rather than leaving them out.

## 0.80.0

### Minor Changes

- Signing in no longer takes you off the page you were reading: public pages open a sign-in dialog you can dismiss, and shared links and reloads still get the full sign-in page. Every sign-in and setup screen links to the instance's privacy notice and imprint, which had no footer to reach them from before. The sign-in page also loads when the server is slow to answer whether you are already signed in.

  First-time setup is now one short page. Heph introduces itself and marks off each decision as you make it, and the five paragraphs of notice text became three plain points — what Hephaestus reads, that its feedback is written by an AI model and can be wrong, and where your instance's privacy notice is. The terms you accept are one sentence beside the box: keep to the work you are entitled to see, and treat feedback as guidance for the person it is addressed to rather than an assessment to pass on. Accepting the terms and answering the research question stay separate decisions, both visible before either is answered, neither preselected, and nothing is recorded until you press **Continue**. You can change the research answer later in user settings.

  The setup screen no longer carries operator-specific text, so it reads correctly on any deployment rather than only on the one it was written for. Because the wording changed, everyone accepts it once more.

  **Operators:** the optional research question is now asked only where `HEPHAESTUS_RESEARCH_ORGANIZATION` names the organisation running the study, and that name is shown beside the choice and in account settings. Set it if you run one; leave it unset and setup is the terms alone. Configure `/imprint` and `/privacy` before upgrading — the setup screen now points at them for everything operator-specific. No data is dropped: `consent_decision.notice_sha256` only loses its `NOT NULL`, `research_organization` is added beside it, and each decision identifies its wording by notice version. Changing the organisation later asks everyone the research question again, and leaves terms acceptance alone.

- Operators can export practice-review execution and model-request traces to an OpenTelemetry collector using the optional `TRACING_OTLP_ENABLED`, `TRACING_OTLP_ENDPOINT` and `TRACING_SAMPLING_PROBABILITY` settings. Export and sampling remain off by default. Private execution archives link native request bodies to the actual requests forwarded upstream, without putting prompts, conversations or credentials in telemetry attributes.

  Timed-out review sessions now retain Pi’s final aborted response and settlement events before disposal, so an interrupted model call remains inspectable in its native transcript.

- Operators can set the optional `PRACTICE_REVIEW_EXECUTION_CAPTURE_ENABLED=true` to opt in to private practice-review execution archives for evaluation and diagnosis. Archives retain staged inputs, collected outputs, native Pi session transcripts and final provider request bodies, as operator-owned files rather than downloadable product API resources. Benchmark observers use a dedicated evaluation database role and read-only Context Fabric access; workspace-administrator credentials do not grant transcript downloads. Capture is off by default, follows Context Fabric retention, and distinguishes missing or interrupted evidence from complete capture.

  Oversized native sessions are omitted with an explicit incomplete-capture status instead of preventing review results from being collected.

- Sandbox cleanup and capacity accounting are now scoped to an installation. **Operators:** Set a distinct `SANDBOX_DOCKER_OWNER` for installations sharing a Docker daemon, and use the same value across roles sharing a database. Drain active reviews and conversations before upgrading; legacy sandbox resources are not automatically adopted.

### Patch Changes

- The instance overview's “View audit log” link now retains native link semantics for keyboard and assistive-technology users.
- Building the API contract or container class archive no longer starts server background scheduling or attempts sync-job recovery and signing-key seeding against an unavailable database. Production still validates sealed signing keys and seeds the active key at startup.
- Production profile groups now reject missing encryption keys just like the production profile itself. System-key validation is consistent for encrypted fields and JWT signing keys, including non-ASCII key lengths. Build-only profiles no longer supply placeholder secrets or run container-image bootstrap, and cannot be combined with production profiles.
- Stopping a streamed model request now cancels the upstream connection and releases queued network buffers instead of retaining a replay of the abandoned response. Practice reviews and conversations keep streaming usage accounting without retaining the whole response in memory.

  Provider stream failures and client disconnects now mark the model operation as failed in exported traces even when HTTP 200 headers were already sent. The trace preserves the committed HTTP status separately from the stream outcome.

- Server builds no longer emit avoidable serialization warnings. Request-local and managed service state remain in-process rather than being silently discarded for serialization.
- Silent mode no longer causes an exception while evaluating issue and pull-request feedback for developers’ practice pages. Recipient preferences and the remaining delivery checks still apply; external feedback remains blocked by silent mode.

  Reviews awaiting human approval now also trigger the separate checks for practice-page and conversational feedback, rather than leaving them to scheduled recovery. Those channels still enforce their own delivery policies.

- Loads optional mentor chat only for eligible signed-in users, reducing the initial application download. The rest of the application remains usable if optional mentor chat cannot load. Landing-page animations follow changes to your reduced-motion preference without reporting it as a warning.
- Container class-archive training identifies PostgreSQL without a database connection or an unnecessary explicit-dialect warning. Production database configuration is unchanged.
- Practice definitions retain validation of nested evidence and review settings without deprecated validation warnings.
- Practice reviews now record a refusal when an observation praises the harmful behaviour a practice checks for, instead of incorrectly declaring the practice inapplicable and retrying a failed review.
- Reviews whose observations all fail quoted-evidence verification are now recorded as refused instead of being retried as an unavailable server. Unverified claims remain blocked.

  Final result processing now respects that recorded refusal instead of attempting to deliver feedback that was never composed.

- HTTP request metrics cover all supported routes without accepting unbounded request paths. Starting a mentor conversation no longer produces misleading database insert-ordering warnings, and competing turns still roll back without leaving partial messages.
- Shared HTTP error diagnostics no longer repeat full upstream URLs, SQL error text, or rejected row values; structured exception types, HTTP status, and valid SQLSTATE codes remain available for troubleshooting. GitLab webhook and Outline request diagnostics no longer include raw provider responses or transport error text. Startup records expose the enabled runtime roles as queryable fields. Credential masking also covers more common provider tokens and authentication fields without replacing source-level privacy controls.
- Local evaluation instances can connect to an explicitly configured loopback SCM simulator under the E2E profile. Production endpoint restrictions remain unchanged.
- Opening a page no longer fails when the shared session check is still running as the interface remounts. Signing out still discards pending identity results, and server outages remain visible rather than being treated as a signed-out session.
- Paginated audit, observation, sync-job, and review-job responses retain their existing JSON format across framework updates.
- Server startup initializes sync subscriptions only after their service is fully constructed. Server builds use supported library APIs and type-safe provider fixtures, reducing avoidable compiler diagnostics without disabling checks.
- Practice-review guidance now distinguishes missing automated test files from evidence that a developer did not test their change. Feedback keeps internal review reasoning out of fallback comments and preserves complete sentences around abbreviations when filtering internal terminology.

  Issue-review guidance now calls for an observable triage need before recommending classification metadata, rather than treating every unlabelled task as a workflow problem.

## 0.79.0

### Minor Changes

- Retires achievements, including badges, skill trees, unlock notifications, and achievement administration. Activity history, practice feedback, leaderboards, leagues, and XP progression remain available.

  **Operators:** Remove links and integrations that use achievement pages or API endpoints, and stop sending `achievementsEnabled` in workspace feature updates. This upgrade permanently deletes stored achievement progress and the old workspace flag. Back up the database and stop all application runtime roles before upgrading; older versions must not run against the upgraded schema.

- Practice reviews and precomputed analysis use the input locations declared by each task instead of assuming a fixed folder layout. Precomputed analysis also supports script paths containing URL-special characters such as `#`.

  Precomputed analysis now has a whole-stage deadline and a per-file output limit, so a stuck or excessively noisy script can fall back to review without precomputed hints.

### Patch Changes

- Repository collaborator permissions now sync again. Workspaces whose repositories use collaborator permissions saw the sync abort partway with a tenancy error, leaving the permissions Hephaestus held for that repository stale until the next full resync.
- Source builds now fail when static analysis cannot finish, instead of accepting an incomplete check as a pass.

  Source builds also reject missing dependency locks instead of silently resolving unpinned dependencies.

  API contract generation no longer starts scheduled background jobs or pulls container images.
  Scheduling now respects the runtime-role switch even with Spring Modulith on the classpath.

- The migration guide's current release lookup, compatibility policy, documentation and help links now point directly to the current repository and documentation site. Historical release image locations and signing identities remain unchanged.
- Container builds no longer wait for an unavailable database during startup optimization. Production startup continues to reject unsealed signing keys.
- The build no longer resolves a vulnerable `graphql-java` or `handlebars` while generating provider clients. Neither reached a running Hephaestus — they are used only to generate code at build time — so no deployment is affected.
- Source builds now use a verified Gradle wrapper, reuse unchanged compilation outputs, and select server tests explicitly by tier. Release images continue to use the same packaged application verified by the API and browser checks. Existing deployments require no configuration changes.
- An instance whose NATS server URI is blank or missing its `nats://` scheme now starts far enough to print the configuration readiness report that names the setting, instead of stopping on an internal error that said nothing about which value was wrong. The URI is required of the server and webhook roles, and the shipped default is blank, so this was every first production start of those roles that had not set it yet. A NATS URI whose scheme is written in capitals is also no longer reported as needing action, since the client accepts it.
- Updates the server's bundled Bouncy Castle cryptography library to fix [CVE-2026-8149](https://github.com/advisories/GHSA-mx76-r943-rf8g). No configuration changes are required.
- A preview deployment's header badge now says which pull request it is of, and links to it. Every preview called itself "Preview", so a browser tab open on one gave no way to tell which change it was showing, or to get back to the pull request it came from.
- A host that has not been promoted yet now says so. The deployment reconciler reported the missing channel as an unhandled error, so the first thing a new self-hosted instance logged was a stack trace rather than the sentence naming the environment and the workflow that publishes it.
- Startup now derives the abandoned mentor-turn cleanup window from the maximum allowed turn duration instead of reporting the built-in default as unsafe. Explicitly configured windows still cannot interrupt a turn that is allowed to run.
- Signing out works again. The browser could not find the CSRF token the server requires, so the server rejected the request and the app reported that it could not confirm the sign-out. Other actions that change something were rejected the same way. Instances that never set `XSRF_COOKIE_NAME`, which is every instance following the shipped configuration, were affected.
- Stay signed in across breaks with a default 24-hour renewable cookie and a fixed 7-day sign-in limit. Sensitive actions still require a recent sign-in. Tabs coordinate session renewal, and temporary renewal failures no longer redirect you to sign-in. Explicit operator timeout overrides remain unchanged.

  Cookie-authenticated actions now consistently require CSRF protection, including when a bearer header is also present.

  Failed sign-out now reports an error instead of appearing successful. Impersonation changes coordinate with session renewal, and revoked sessions no longer prevent the sign-in page from listing providers.

  Sessions that end during renewal now return an authentication refusal instead of reporting success.

- The workspace isolation check is exact about which single-row statements it exempts. A statement whose key predicates sat behind a SQL comment, or whose assignment read from a second table, could be treated as addressing one keyed row when it did not. Both keyed exemptions are now held to the same rule: every assignment must be a bound parameter. No released version exempted these shapes.
- Worker-only deployments now reclaim idle mentor sessions and orphaned sandbox resources periodically. Stalled writes to a mentor runtime are now interrupted by the configured timeout, even while Docker cleanup is waiting for a response. Generating the API contract no longer runs sandbox cleanup against the local Docker daemon.

## 0.78.0

### Minor Changes

- Require PostgreSQL 18 and initialize new databases from a compact v0.77.4 baseline instead of replaying the full migration history.

  **Operators:** If you run PostgreSQL 17, complete the documented PostgreSQL 18 upgrade using v0.77.4 before installing this release. All existing installations must back up their database, verify the v0.77.4 cut-point, and synchronize the baseline before starting this release. Fresh installations initialize automatically.

### Patch Changes

- Account exports now record a failed attempt after a database transaction rolls back, instead of remaining queued or processing when generation fails. Successful exports are counted only after their data commits.
- Stops a sync started in one workspace from appearing to run in another. Switching workspace kept the integration overview's cards mounted, so a sync the new workspace had never asked for could still show as pending on the matching integration.

  The certificate migration in the pull-based deployment guide no longer risks the certificates a host is already serving: the copy refuses when the volume already holds an ACME store, instead of overwriting it and warning about it afterwards.

- GitLab sign-in options show the GitLab icon even when their login provider has a custom registration name.
- Practice reviews no longer treat a substantive issue body as a repetition of its title solely because the title is missing, empty, or contains no Latin letters or digits.
- Short practice-review timeouts now reserve time for the review to finish and save its result before the sandbox deadline, instead of extending the work budget beyond the available shutdown time.
- Updates the cryptography libraries used for secure Docker connections to maintained releases without requiring operator configuration changes.
- Release a mentor conversation promptly when its browser connection closes before the response starts, so a new message does not wait for an inactive turn to time out.
- Practice reviews continue when a preparation step fails. Completed observations are preserved, and practices that could not be reviewed remain explicitly unevaluated.
- Profiles now show a retry action when workspace settings or activity cannot load, instead of silently showing missing activity. The loaded developer profile remains visible.

  Changing an activity filter now shows loading placeholders instead of presenting the previous range’s results under the new selection.

- Workspace administrators can replace GitHub and GitLab personal access tokens directly from the integration page, restoring connections whose stored token can no longer be read without removing repositories or synced work.
- Outline connection forms no longer carry a previously entered server URL or token into another workspace or a new connection.
- Keeps the recovery pass for practices nothing observed when the first pass runs long. The pass that retries them was being given the review time left unspent, which is none after an overrun — so on exactly the slow reviews where practices are most likely still unobserved, no retry ran at all, even with minutes left before the review's deadline. It now keeps the share reserved for it whenever that time genuinely exists, and is skipped only when it does not.
- Practice reviews no longer start another analysis or composition turn after its available time has expired. Admitted observations remain available for delivery if feedback composition cannot start or finish.
- Practice review output archives reject nested path traversal and ambiguous file paths.
- Keep review requests and profile data tied to the work and developer currently open, even when you navigate while a request is running. Bookmarked practice-group pages now respect disabled practice reviews, filters preserve your scroll position and bookmarked custom dates survive Back navigation, and responding to feedback refreshes the group's cached review filters.
- Active sessions renew reliably when you resume activity after an idle renewal check. Inactive sessions still expire normally.
- Protects instances with API documentation enabled against unbounded locale-cache growth. API documentation remains disabled by default, and no operator configuration change is required.
- Keeps unhighlighted code in practice feedback and legal pages readable in light and dark themes while preserving line wrapping. Updates interface icons and motion dependencies without changing operator configuration.
- Opening a bookmarked leaderboard interval or using Back preserves its exact dates instead of silently resetting them. Custom dates near weekly and monthly boundaries remain editable, and All time is recognized consistently across time zones.
- Practice review activity no longer reports an all-clear when a completed review has no record of whether it reached a practice. Existing observations remain visible, and missing coverage is explained separately from practices the review explicitly did not reach.
- Switching workspaces now resets integration job-history pages and pending form state. A token replacement still refreshes the workspace it was submitted for, without disabling another workspace's token form or showing its previous job history.

## 0.77.4

### Patch Changes

- Removes the review activity surface from workspaces that do not run practice reviews. The sidebar entry no longer appears, and a link kept from before — or from another workspace — lands on the workspace home instead of a page whose only content would be an explanation of its own emptiness.

## 0.77.3

### Patch Changes

- Stops workspaces that do not run practice reviews being told practices are merely unconfigured. A profile no longer shows an empty "practice groups" section when practice reviews are off, and the review activity page now says practice reviews are off — rather than suggesting the work simply has not synced yet, which was the one explanation it offered for silence.

  On a phone, a contributor's league tier now sits beside their name instead of on a row of its own below the profile.

## 0.77.2

### Patch Changes

- Fixes teams and leaderboard pages failing to load on instances with synced labels. The tenancy self-check treats a keyed fetch as safe, but did not recognise the batched form Hibernate emits when it fills several repositories' label collections in one round trip, so it rejected the query and the request failed. Workspace isolation is unchanged — the rejected query was already pinned to keys the caller held.

## 0.77.1

### Patch Changes

- A release no longer waits behind an unapproved production deployment. The approval for production
  used to sit inside the same run that serialises tag promotion, so a release nobody approved held
  that lock and every later release queued behind it without starting — for two days, in a state the
  default run listing does not show. The lock now covers only the promotion of the version, series and
  latest tags.
- An instance that used the mentor before its chat storage changed can now upgrade without a
  hand-run migration. The upgrade previously stopped at a step that refuses to remove the old
  chat-parts table while it still holds rows, and nothing ever emptied it, so the application stayed
  down on exactly the installations that had chat history. The history itself is carried onto the
  message, as that step always intended.
- Keeps the edge proxy's TLS certificates across deployments. They were stored next to the Compose file, which on a pull-based host is replaced with every release, so each deployment re-issued every certificate and a handful of deployments in one week were enough for Let's Encrypt to start refusing — leaving the site on an untrusted certificate. Certificates now live in their own volume and survive upgrades.

  An instance that already serves TLS from the bundled proxy issues its certificates once more on the first start after this upgrade, which needs nothing from you. To skip even that, copy `acme.json` out of the `letsencrypt` directory beside your Compose files into the new `proxy_letsencrypt` volume before starting.

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

- Sensitive instance-admin actions now ask an administrator to confirm access when their last sign-in
  is more than five minutes old: changing an account's role, forcing an account out of every session,
  starting an impersonation, changing a login provider, and registering or removing an LLM connection.
  Linking a new identity to an account asks the same of every user, because a new link is a permanent
  second way in. A stolen admin session is therefore only useful for a few minutes, and every refused
  attempt appears on the audit trail.

  Confirming access uses a provider the account is already linked to, and the action is never replayed
  afterwards — review it and submit it again. Set `HEPHAESTUS_AUTH_STEP_UP_MAX_AGE` to change the
  window. This is a local confirmation, not multi-factor authentication: an identity provider that
  still holds a session may complete it without asking for anything, so enforce MFA at the provider.

- Issue practice reviews now react to a title, description, label, assignment, milestone or reopen change on GitHub and GitLab, and to a native issue type change on GitHub — where before only a new label did. Closing an issue again after reopening it is now reviewed again too, instead of only the first close being. Practices already bound to the labelled occasion move to the wider one automatically on upgrade, keeping their customisations. Expect issue reviews to run more often: each round of triage that changes what a practice can read occasions its own review, there is no delay that batches a burst of edits into one, and the first backfill campaign after the upgrade reviews each already-closed issue once more. Locking an issue, pinning it, or editing a due date, weight or time estimate occasions nothing, and a replayed event or a return to an issue state already reviewed no longer starts another review.
- Docker sandbox settings now live under `hephaestus.sandbox.docker.*`. Interactive sessions use the configured Docker connection rather than an inherited Docker context.

  **Operators:** Rename custom Spring property overrides and replace `SANDBOX_TLS_VERIFY` and `SANDBOX_CONTAINER_RUNTIME` with `SANDBOX_DOCKER_TLS_VERIFY` and `SANDBOX_DOCKER_CONTAINER_RUNTIME`. TLS verification requires an explicit certificate directory. Removed names have no aliases: a worker-role process refuses to start while any of them is still set. Follow the migration guide before upgrading. The Docker host environment variable and gateway port are unchanged.

### Patch Changes

- A review no longer discards a finding for writing a quoted line without the change marker or the
  indentation in front of it. The line it names still has to be the line it read, on the same side of
  the change; only the leading whitespace is forgiven.
- A review no longer discards its own finding for copying a line exactly as the change displays it. The
  diff a review reads prints a line number in front of every line, and quoting that back was the most
  common reason an observation was thrown away — on one repository, a median of three per review, and
  up to fourteen. The quote still has to be the line the citation names.
- A review transcript now says why a model turn failed instead of only that it stopped, and reports the
  provider retries the run rode out. A review that gave up because its provider kept refusing can be
  told apart from one that simply had nothing to say.
- A practice review that has finished its work no longer throws it away when the server it reports to
  does not answer for a moment. It waits and tries again for a few seconds instead of ending with
  nothing to show for the review it just did.
- Listing a workspace's agent jobs no longer reads every review transcript into the server's memory to
  throw it away. A page of a hundred jobs cost tens of megabytes of heap per request and grew with how
  much each review had to say; it now reads only what the listing shows. The delivery-recovery sweep
  reads a job whole only for the delivery it actually re-attempts.
- A practice review no longer loses a whole practice when its model provider is briefly overloaded. A
  failed model turn is now repeated for just over two minutes before the review gives that practice up,
  where it previously stopped after about fourteen seconds and reported the practice as unevaluated.
- When a review quotes a line that is not in the change it is reviewing, the run now records which part
  was wrong: the line number, the side of the diff, or the text itself. The quote is still checked
  against the change exactly as before; only the explanation is new, and it is what lets a review
  correct itself instead of dropping the practice.
- A practice review's log now names every observation it refused to record, and why. A review that was
  refused all of them read exactly like one that found nothing, so the difference could not be seen
  from the outside.
- A practice review of a very large change no longer finishes with nothing to say. A review could spend
  everything it had reading and record none of what it had already worked out. It is now asked to write
  down what it has settled while it still has room to, and asked again as that room runs out.
- A practice review whose work finished while the server was being restarted now runs again instead of
  ending with nothing. The sandbox can only reach the server at the address it had when the review
  started, so a server that comes back elsewhere is unreachable for the rest of that run. The review is
  queued for another attempt unless its observations did reach the server, in which case they are
  already on record, and what the first attempt reported about itself stays on record either way.
- A practice review that does not get to every practice now reports the practices it did reach, instead
  of being thrown away whole. What it could not get to is recorded as unevaluated on the developer's
  page, and a review in that state never reports that there was nothing to find, because that would be a
  claim about work it never looked at.
- A review that reads a change and finds none of its practices in it is no longer thrown away. That
  outcome was treated as a sign that the review had been handed an empty change, so the whole review was
  refused and the developer's practice page kept no record of it. A review is only refused now when it
  never quoted the change at all.
- A practice review that recorded nothing because the model stopped answering now runs again, instead
  of being recorded as a review that found nothing. A restart or a provider outage then costs a delay
  rather than the review, and the run says how many calls went unanswered. A review that reached some
  of its practices still delivers what it reached, as before.
- A practice review can now use its whole time budget when it needs it. Part of that budget was held
  back even while the review was running short of time, so a review could end with practices unreviewed
  and deliver no feedback at all, minutes of its budget unspent. A review that needs a second pass now
  holds its sandbox for the full budget, so those reviews take longer and cost more model time than
  before.
- A practice review's transcript is now kept whole, so what a review actually did can be read back. Only
  its ending was stored, and that ending was itself collected from the last few hundred lines the run
  printed, so what the review was asked, what it read and what it was refused had been thrown away before
  anyone could look. A run large enough to threaten the server now keeps its beginning and its ending with
  a line between them naming exactly how much is missing, and collection that gives up early says so
  rather than stopping quietly. Transcripts take correspondingly more room until retention clears them, on
  the schedule it always did.
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
- A review whose observations Hephaestus will not record now ends with that reason on the run, instead
  of failing as an internal error with nothing to show. The reason appears in the run's transcript and
  on the run itself, and the review stops rather than re-submitting what the server has already
  declined.
- Feedback on a change with nothing to fix now says what the review saw, rather than how it searched.
  Where a line used to read "I walked all six sink classes across every added line", it now reads
  "Added lines reduce external values to booleans; none reach a sink". The reasoning behind each
  observation is still recorded with the review.
- A practice review interrupted mid-run can start again. Restarting the application while reviews were
  running left each one's isolated network behind, and because a review's network is named after the
  review, every later attempt was refused for a name already in use. Those reviews spent every attempt
  they had on the same refusal and ended as failed without producing feedback. A review now clears the
  network its interrupted run left behind.
- An observation on your practice page now names what the review saw. A summary of a single word, such
  as "Test", sat above the practice's own name and told you nothing about the work; a short phrase is
  recorded there instead.
- A practice page no longer reports a practice as assessed and clean while its review is still running,
  or after that review has died. Both now say what is actually true of the run: one is under way, the
  other did not finish.
- The workspace wizard no longer offers GitHub App setup when its installation URL is blank.
- Serving an instance on more than one hostname is now entirely the reverse proxy's job: the extra
  names, their shared certificate, and the redirect that sends a browser back to `APP_HOSTNAME` are all
  configured in one place. An instance that names a single hostname routes and serves exactly as
  before, and one fronted by a proxy that does not read this stack's routing configuration answers on
  whatever names that proxy sends it — configure the extra names and their redirect there instead.
- Repository and release links now open Hephaestus in the hephaestus-build organization, and contributor credits are fetched directly from the transferred repository. New default workspaces monitor the transferred Hephaestus repository while continuing to monitor Artemis in ls1intum.
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
  flight and answering 503 meanwhile. The host now carries the PostgreSQL image it runs across
  applies. It takes the commit's image when that commit changes what the image is built from, and
  otherwise on the first apply of a new day, so the security updates the rebuild carries still reach
  the host within a day. Promote with **refresh-database-image** to take a commit's image at once. A
  release still applies exactly the images it was signed with.
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
- Impersonating an account now ends when it should. It cannot outlive the operator's own session, it
  ends as soon as the operator stops being an instance administrator, and it ends if the account being
  impersonated is promoted to instance administrator — the case an operator could previously reach only
  by starting over. The time-box running out or the target being promoted return the operator to their
  own session and are recorded in the audit viewer as the end of the impersonation, with the reason; the
  operator's own session ending or the operator no longer being an instance administrator end the
  impersonation outright.

  Leaving an impersonation that has already ended is now refused instead of quietly handing back a
  session, and an ordinary session can no longer be renewed past its absolute lifetime.

- Issue updates are reviewed again. Since updates to an issue began being grouped into a single review,
  every one of them was refused as it was recorded and then dropped, so no practice review ever ran for
  it. Nothing already stored changes, and the next update to an issue is picked up normally.
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

- Feedback about a code review you left is now addressed to you rather than to the author of the pull request when the review had to be retried after a pause, such as a spent budget or an inactive workspace.
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
- A practice review shares what it read about a change with every practice it then checks. The step that
  does that reading was being cut off before it could answer, so most reviews threw it away and each
  practice started from nothing: the review spent its time re-reading the same change and had less to
  say at the end of it.
- Writing a personal access token to a workspace whose GitHub connection is an App installation, or reading a credential the server's current keys cannot decrypt, now answers with the conflict that names the connection's state instead of an internal server error.
- Practice reviews no longer treat missing or upstream-deleted issues, pull requests, merge requests and their discussions as empty work: the review stops with the work reported unavailable instead of producing feedback about a change nobody can open. New feedback checks that its reviewed work is still available before it is delivered, on the work itself, on the practice page and in conversation, including feedback prepared for a conversation in advance. Previously delivered feedback remains in your history, subject to access and privacy controls.
- A stored integration credential that none of the server's configured encryption keys can read is
  now shown for what it is. When the key version itself is missing from the server's configuration,
  the request fails as a configuration fault instead, as the operator guide explains. The workspace's integration pages say the stored token cannot be read and what to
  do, and a request that needs the credential answers with that same explanation instead of a generic
  server error. Replacing the credential clears it.
- A worker's log no longer fills with warnings about the heartbeats its own hub sends. The hub answers
  every capacity report with a heartbeat to keep an idle control channel alive, and the worker was
  reporting each one as a protocol violation, roughly three warnings a minute per worker.
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
