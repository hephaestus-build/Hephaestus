# ADR 0069: Practices run when they are ready and stay current by their sources

**Status:** Accepted
**Date:** 2026-10-28
**Authors:** Felix T.J. Dietrich

**Amends:**

- [ADR 0056](0056-one-practice-model-for-items-and-scans.md): a result stays current while its declared sources keep their fingerprints.
  An unchanged item version no longer decides it.
  The item version stays the capture pin and the delivery fence.
- [ADR 0061](0061-review-seals-then-feedback-composes.md) § Compose: the feedback composer works with tools and sees its own earlier feedback on the work.
  It decides what to say, how much, where, and whether to reply to or edit its earlier feedback.
- [ADR 0062](0062-the-item-is-the-review-scope.md):
  - § Quiet window: the edge derives the practices that run.
  - § Supersede: supersede compares fingerprints, at claim and at practice start.
  - § Input digest: its minimum form and its precondition ship in 1.0.
- [ADR 0063](0063-each-practice-is-staged-when-it-ends.md) § Seal and § Retry: the seal checks freshness by fingerprint.
  A retry at a moved version seals what it staged.

**Relates to:** [ADR 0070](0070-1-0-is-a-cutover-release.md) (the 1.0 cutover), and [ADR 0068](https://github.com/hephaestus-build/Hephaestus/pull/3389) (Proposed).
This ADR changes no part of W′.
It changes the review loop's prompt layout, its practice order and its start stagger.

Practice concurrency is an execution bound within a review.
For Pod admission, see [decision G](0068-reviews-and-heph-run-on-one-agent-runtime.md#g-review-pod-admission-checks-quota-and-provider-demand).

Driver 7 of ADR 0068 says that a review sends its results once, at the end. With [ADR 0063](0063-each-practice-is-staged-when-it-ends.md), each practice stages when it ends.

Each amended ADR has a dated update block that points here.

## Context

A practice review measures work and gives feedback about it.
This ADR decides when a practice runs and how long its result stays true.
It also decides how a review uses prompt caching, and what the feedback composer decides on the work.
The maintainer set the goal on 2026-10-27: feedback quality and timing first, current practice, and prompt caching, in the long-term 1.0 design.
The maintainer also set the bound: no templates and no rule-based composition where a model writes better feedback. Machinery that no measured problem needs stays out.

### What runs today

- **Each pinned item event starts a review at once** (`ItemEventReviewListener`).
  [ADR 0062](0062-the-item-is-the-review-scope.md) decides a quiet window. It is not shipped.
- **Currentness compares the whole version string** (`ResultCurrentness`).
  The pull request version is the base, the head, a digest of the title and body, and linked context (`ScmItemService`).
  Comments and CI runs are not in it.
- **A submitted pull request review ends every current result of the item** as `WORK_CHANGED`.
  This is a freshness rule in the wrong place.
- **Every session of a review gets the same opening:** the task prompt and one brief of all captured sources (`pi-runner.ts`, `pi-review-brief.ts`).
  `readsSources` filters only the coverage accounting, not what the model sees.
  The citation checks of the result tools and `ResultSourceAuthorization` must test the source kinds that the practice's session could read.
  Today they test the source kinds of the whole job.
- **The brief starts with `INDEX.md`**, which holds the digest of every source.
  Any change to any source thus ends the shared prompt prefix at its first byte.
  The volatile sources (comments, threads) come before the diff.
- **All sessions of a review start at once**, with no stagger.
- **The gateway knows five wire shapes. None is Anthropic Messages** (`LlmApiProtocol`).
  Usage parsing assumes the inclusive totals of OpenAI.
  The body policy refuses a tool whose `type` is not `function` or `custom`, so it refuses every Anthropic tool.
- **Forced tool choice is gated by a model-name regular expression** (`model-capabilities.ts`).
  It misses Fable 5.1, Mythos 5.1 and every alias without `claude-`.
- **On GitHub, a suite conclusion `action_required` maps to FAILURE** in `head_checks` (`GitHubHeadCheckProcessor`).
  A fork pull request that waits for workflow approval can then read as "CI failed".
- **On GitLab, line notes post one discussion at a time.** There are no draft notes.
- **The default catalog has 52 practices.** 32 select `opened` and `ready`, 26 select `synchronized`, 6 select `merged`, and none selects `edited`.
  16 pull request practices read only `diff` and `tree`.
- **The domain has `PullRequestReopened`, `PullRequestDrafted` and more.** The item event recorder maps them to nothing.

### What the providers document

Fetched on 2026-10-27. [Sources](#sources) lists the pages.

- **Anthropic:** 4 cache breakpoints, in the order tools, system, messages. A 20-block lookback. An entry becomes available only after the first response starts.
  One top-level `cache_control` places an automatic breakpoint and uses one of the 4 slots.
  `input_tokens` excludes cache reads and writes.
- **Anthropic:** Opus 5.5, Sonnet 5.5, Fable 5.1 and Mythos 5.1 refuse `tool_choice` `any` or `tool` with a 400.
  The native API refuses it too.
  On older models a change of `tool_choice` invalidates the cached message blocks.
- **No OpenAI wire reaches a Claude prompt cache.** Anthropic's compatibility layer does not support caching. Bedrock has no Chat Completions or Responses for Claude.
  Bedrock and Google Cloud cache Claude with `cache_control` on the Anthropic body.
- **OpenAI:** caching is automatic. Explicit breakpoints exist on GPT-5.6 and later. A cache key influences routing but does not pin a request.
- **vLLM:** automatic prefix caching is on by default and caches full blocks for each replica.
  It reports `cached_tokens` only with `--enable-prompt-tokens-details`.
  Prefix-aware routing exists in the vLLM production stack, llm-d and the SGLang router.
- **GitHub:** 80 content-generating requests each minute and 500 each hour.
  One `addPullRequestReview` carries a body, new threads and replies, and sends one notification for the review.
  Anyone with read access can read a comment's edit history.
- **GitLab:** a bulk publish of draft notes sends a single email.

## Decision drivers

- **Feedback earns trust or it is not sent** (non-negotiable 1). A result that is current must describe what its practice saw. Every stop and every skip has a recorded reason.
- **Fast feedback where it does not cost quality.** A practice that does not read CI never waits for CI.
- **One fact, one home.** Selection, readiness and freshness are derived from rows that exist. No plan table, no workflow engine.
- **Natural feedback.** A model composes what a person reads. Hard rules exist only for trust, safety and tenancy.
- **Quiet on the work.** At most one notifying write for each review.
- **No machinery without a measured problem.** An edge case gets a recorded reason, not a mechanism.
- **Caching as a property of the layout, not of one provider.** The same prefix helps every engine. Claude gets the one wire that caches it.
- **Each adapter absorbs its provider's shape.** The review pipeline stays pure.

## Considered options

### Trigger model

1. **One review per event (current).** Rejected by [ADR 0062](0062-the-item-is-the-review-scope.md).
2. **A quiet window held open until CI settles.** Rejected.
   It couples the latency of 26 push practices to CI because one practice reads CI.
   It needs a cap and a second phase when the cap fires.
   Forks that wait for approval and repositories with no CI make it wait for the cap.
3. **Readiness per practice with no window.** Rejected. Each push of a burst starts a review, and the model call pool binds first.
4. **Readiness per practice with a CI cap timer.** Rejected. The cap needs a timer and a reason for "ran without CI".
   A CI practice cannot judge CI that has not finished.
5. **Measure early, compose at a milestone.** Rejected. It needs composition across reviews, which reverses [ADR 0061](0061-review-seals-then-feedback-composes.md).
6. **A review plan table.** Rejected. It gives the plan a second home. Every fact in it is derivable.
7. **One review for each practice.** Rejected. Each practice pays for its own Pod, capture and cache write.
8. **Merge a later CI review into a running review.** Rejected. It changes a running attempt's practice set under its fence.
9. **Readiness per practice behind one quiet window per item.** Chosen.

### Freshness

1. **Version equality (current).** Rejected. A description edit ends every diff result. A comment ends nothing, and a submitted review ends everything.
2. **A map from version parts to practices.** Rejected. It is a second home for what each practice reads.
3. **One verdict per version.** Rejected. It has the same defect as option 1.
4. **Fingerprints of the declared sources, with a shared brief.** Rejected. A practice that sees an undeclared source can write about it without a citation. The result then stays current after that source changes.
5. **Fingerprints of the declared sources, and each practice sees only its declared sources.** Chosen.

### Prompt layout

1. **Intent first:** the description before the diff. Rejected. It reads better for a person, but the 16 diff-only practices lose the shared prefix.
2. **A tool schema for each practice**, with an enum of its declared kinds. Rejected. Tools come first in every cache's prefix, so it splits the prefix at the first byte.
3. **Canonical layers, the change first, each practice includes only its layers, tools identical in a job.** Chosen.

### Caching

1. **Native Anthropic only.** Rejected. The self-hosted engines gain from a stable prefix, which costs nothing.
2. **Automatic caching only.** Rejected. Claude gets no cache on any OpenAI wire. The only route is a third-party translation that self-hosters must run.
3. **Both: a stable layered prefix for every engine, and a native Anthropic Messages wire.** Chosen.
4. **The 1-hour TTL.** Rejected. It doubles the write cost, and the change layer changes on each push.
5. **Batch APIs for reviews.** Rejected. Sessions with tools cannot be batched. Evals use them.
6. **A custom routing header to self-hosted pools.** Rejected. The gateway forwards no client headers, and prefix-aware routers need no key.

### Feedback on the work

1. **A summary that the model writes for each review, with no memory of earlier feedback (current).** Rejected. It repeats what the developer already read and cannot say what changed.
2. **A fixed template over stored copies of each problem, with rules for placement and volume.** Rejected. Rule-based text reads mechanical, and each rule needs its own exceptions.
3. **A one-shot composer that writes once, with placement checked only at send.** Rejected. A position that the provider refuses loses the note, and nothing checks what was posted.
4. **A second model that checks the facts of the composer's draft.** Rejected. The provider's readback and the tool checks are external ground truth for facts. A second model adds cost and gives no new signal there.
5. **The provider writers in the composer's process.** Rejected. A model that reads PR text would sit one call from a write token.
6. **A feedback composer with tools: it checks, publishes once, reads back and corrects, with the guardrails in the tools.** Chosen.
7. **Comment commands such as `@hephaestus review`.** Rejected for 1.0. The authenticated request on the practice page applies consent and identity checks. No comment parser sits on a trust boundary.

### Quality

1. **A self-critique pass that judges whether a result or a claim is correct.** Rejected. Intrinsic self-correction does not help there. Facts are checked by tools against external evidence.
2. **A rubric critique of the words, kept only with a measured gain (D18).** Chosen, beside the fact checks.
3. **A gate on the model's stated confidence.** Rejected. It is poorly calibrated outside its domain.
4. **Probation formulas that reroute a practice (D7 of the proposal).** Rejected. No measured precision problem needs them, and automatic rerouting suppresses feedback silently.
5. **An eval set for practices and for composition, as a rollout gate.** Chosen.

### Source contract versions

1. **A registry that holds several contract versions side by side**, with customized practices valid on their pinned version. Rejected.
   It is a second code path for each version, which the 1.0 cutover forbids.
2. **One contract version. A layer that changes it migrates every pinned practice.** Chosen.

## Decision

### Readiness per practice behind one quiet window per item

An occasion is an item event.
It joins the item's quiet window, as [ADR 0062](0062-the-item-is-the-review-scope.md) decides.
`merged`, `closed`, a submitted review and a request never join a window. Each is a [readiness edge](../contributor/practice-review-glossary.mdx#review-terms) at once. Every other occasion reaches its edge when its window closes.
At each edge, for item I at its current version, the server derives:

```text
selected   = practices whose reviewOn names an occasion that joined this window
current(p) = p has an unended result whose recorded fingerprints equal I's current ones
in_flight  = p has an unsettled membership in a queued or running review of I
pending    = selected − current − in_flight     for continuing occasions
pending    = selected − in_flight               for milestones
ready(p)   = (p declares no CI source ∨ I's checks settled for the head)
             ∧ (p is not merge-only ∨ I is merged)
run        = { p ∈ pending | ready(p) }
```

- A **milestone** measures what it selects, current or not: a request, `opened`, `ready` and `reopened`.
  It is the moment of first or renewed feedback on the work.
- A **continuing occasion** measures only what is pending: `synchronized`, `edited`, a linked issue update, the checks event and an issue update.
- If `run` is empty, the event is withheld with `NO_ACTIVE_PRACTICE` or `ALREADY_CURRENT`.
- Otherwise one review runs `run`.
  Each practice in `pending − run` gets a `WITHHELD` membership with its readiness reason: `CHECKS_PENDING`, `NO_CHECKS_REPORTED` or `CHECKS_AWAITING_APPROVAL`.
- **The checks edge.** When the head's checks settle, a checks event is recorded. It joins the window like a push.
  When that window closes, the CI practices are pending and ready, and only they run.
- **No CI cap and no timer.** A practice whose checks never settle shows "Not reviewed: checks had not finished".
  The scheduled sync reads the runs again for heads with a `CHECKS_PENDING` membership younger than 24 hours.
- Nothing new is stored except the fingerprints below and the new reason values.

The cases that this model must hold:

| Case | Behavior | Reason |
| --- | --- | --- |
| Pushes A, B and C within 2 minutes | One review at C | `COALESCED` on A and B |
| A push while a review is queued | At claim, a newer waiting occasion cancels the review with no Pod. With none waiting, the review pins the current version again before capture. | `REPLACED` |
| A push while a review runs | At each practice start, the runner asks the gateway if the practice is still current by its fingerprints | `REPLACED` for each membership |
| A busy pusher | The server orders the runner's queue: no current result first, then the oldest last verdict | Coverage rotates |
| A retry after a lost Pod, at a moved version | The claim seals the review with its staged results. Staged results whose fingerprints still match stay current. | `REPLACED` for the rest |
| A fork that waits for approval (GitHub) | A suite with conclusion `ACTION_REQUIRED` makes the run list incomplete with the limitation `AWAITING_APPROVAL`. This holds for any run count and any rollup state, and it is never FAILURE. CI practices wait. | `CHECKS_AWAITING_APPROVAL` |
| No CI | A CI practice with an optional CI source runs without it. Its capture says "no checks reported". | `NO_CHECKS_REPORTED` |
| A flaky CI rerun | A rerun that settles differently records a new checks event. Its key includes the digest of the settled run set. Only CI practices run again. | A new checks event |
| Title, body or linked issue edit | Core readers end. Diff-only results stay current. | `WORK_CHANGED` on the ended results |
| A comment or a submitted review | Discussion readers end. No occasion selects comments. | `WORK_CHANGED` on the ended results |
| Merged | Merge practices run. Earlier practices run only if their sources changed. Private destinations only. | `ALREADY_CURRENT` |
| Converted to draft | Pending feedback for the work is withheld | `ITEM_IN_DRAFT` |

GitLab captures no CI in 1.0. No GitLab practice declares the CI source.

### Freshness by source fingerprints

A result R of practice P on item I is **current** when all of these hold:

1. R has not ended.
2. Each source kind that R's session could read has the same fingerprint on I now as in R's capture.
3. P's practice version produced R.
4. I still exists.

**A result depends on every source that its session could read.** That one rule decides which fingerprints a result records.
The source contract of each kind computes its [source fingerprint](../contributor/practice-review-glossary.mdx#review-terms), next to the capture renderer.

- **Git sources** use content addresses. `tree` is the head object ID.
  `diff` is the review base, the head object ID and the renderer version.
  The target tip is not part of it. A merge to the target that keeps the review base ends no diff result.
- **Record sources** use the SHA-256 of a canonical form of the rendered content, from the renderer that capture uses.
  The canonical form leaves out the capture, sync and observation times of the record's `basis`.
  These times are not in the cached opening either. They move to the practice's `INDEX.md`.
  Equal timestamps sort by native ID, so equal content renders equal bytes.
- **Status that changes without a push leaves the core source.** `head_checks` and `merge_state_status` move to the CI source. `review_decision` moves to the discussion source.
  Hephaestus's own posted comments are already excluded from these bytes, so its own feedback changes no fingerprint.
- **CI gets its own source kind**, `scm.pull-request.checks`, not a field of the core source. Otherwise a CI rerun ends every core reader.
- Capture records the fingerprint of each captured kind.
  The runner reports which kinds each session could read. Admission copies the fingerprints of those kinds onto the result.
- Today `bash` in a session can read every captured file, so a result records every captured kind.
  ADR 0068 moves captured files to a volume that only the loop mounts. Then `bash` sees only the checkout, and the same rule records the declared kinds, `diff` and `tree`.
- The server computes the current fingerprints from synced rows, with no sandbox. Each source-change domain event of the item reconciles its results.
- **The safe direction must hold:** an equal fingerprint means equal captured bytes. A fingerprint that changes while the bytes stay equal only runs a practice again.

One predicate, `ResultFreshness.current(result, item)`, decides currentness at selection, claim, practice start, seal, egress and render.
`ResultCurrentness` ends results by it, with the end reason `WORK_CHANGED`.
The changed kinds are derivable from the fingerprints, so they need no column.
The blunt end of every result on a submitted review goes.

### Each practice sees only its declared sources

A practice's opening holds only what it declares. This keeps its prefix shared, and it narrows what the session reads.

- **Each practice's opening holds exactly its declared sources**, as one content block for each layer, in this order:
  1. The task prompt.
  2. **Change:** `files.json`, `diff_stat.txt`, `diff.patch`.
  3. **Core text:** metadata, description, the authored description, commits.
  4. **Linked work items.**
  5. **Discussion:** review threads, comments, general comments.
  6. **CI.**
  7. The practice's own `INDEX.md`, which lists only its sources.
  8. The practice text.
- Practices that declare the same sources share the whole opening. Practices that share leading layers share that part of the prefix.
  The change layer is first because almost every pull request practice declares it. Discussion and CI change most often at a fixed head, so they are last.
- **The tools are identical for every session of a job.** The tool handler refuses a citation of an undeclared kind with a repair turn.
- **The host refuses reads outside the declared sources.** The read tool refuses a context file of an undeclared kind. `grep`, `read` and `ls` over the checkout need `tree`.
- The read tool is not a boundary while `bash` can read the same files. What a session could read decides its result's fingerprints ([Freshness](#freshness-by-source-fingerprints)).
- **Admission refuses an undeclared citation** with the new candidate refusal `UNDECLARED_SOURCE_CITED`, as defense in depth.
- Practices that rely on undeclared context must declare it. The first layer audits the `reads` of all 52 catalog practices.

### Prompt caching

- **A byte-stable prefix:** the tools, the system prompt (constant for each runner version), the opening layers, then the practice text.
  No timestamp, attempt number or ID goes into the prefix.
- **Breakpoints for each wire:**
  - Anthropic: the system prompt, the end of the change layer, the end of the core layer, and the automatic slot.
    Pi's breakpoint on the last tool goes. The system breakpoint covers the tools.
  - OpenAI Responses on GPT-5.6 and later: explicit breakpoints at the same layer ends. Earlier models cache implicitly.
  - Completions engines: no markers. Only the identical prefix matters.
- **No `tool_choice` change inside an Anthropic session.** Sessions use `auto`, strict tool definitions and the existing repair turn.
  A catalog capability, `forced_tool_choice`, which the probe sets for each model, replaces the model-name regular expression.
- **A stagger for each opening.** The runner groups its queue by declared-source set.
  The first session of a set starts at once. Each other session of that set waits for the leader's first streamed event, at most 20 seconds.
- **Order.** The server supplies the queue order: no current result first, then the oldest last verdict.
- **A native Anthropic Messages wire shape** in the gateway. It sends `x-api-key` and `anthropic-version` upstream.
  Its body policy accepts Anthropic tools and refuses server tools.
  Its usage parser has Anthropic semantics, for streams too.
  Native Anthropic cache entries use a five-minute TTL.
  Bedrock and Google Cloud authentication are a separate decision.
- **Routing.** Hephaestus sends `prompt_cache_key` for each job where the wire defines it.
  Prefix-aware routing on a self-hosted pool is the operator's router.

### The feedback composer works with tools

Composition stays a worker step for each destination ([ADR 0061](0061-review-seals-then-feedback-composes.md)).
The feedback composer is a model in a bounded loop with tools.
It writes the feedback, publishes it, sees what the provider shows, and corrects it.
This is the evaluator-optimizer pattern of [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents), with ground truth from the provider at each step.
The check reads what the provider accepts and shows, so it is not intrinsic self-correction.

**Its input for one destination:**

- The admitted results of the sealed review, with their problems, citations and lineage states.
- The work, as the cited texts that the seal kept ([ADR 0063](0063-each-practice-is-staged-when-it-ends.md)).
- Its own earlier feedback at this destination on this work, with the replies and thread states of people.
  Only content on the work goes in, never private feedback.

PR text, comments and replies are marked as untrusted data. No tool takes an instruction from them.

**Four tools.** Each destination adapter implements the three that touch the provider, so the provider's shape stays at the boundary.
They are thin calls: the worker holds no provider token.
The server runs each write through the existing send path, with its fence, its write-started record, its readback and its retries.

| Tool | What it does | Writes |
| --- | --- | --- |
| `critique_package` | Reviews a draft against the feedback rubric below. It returns notes for each criterion and each entry, not a score. | No |
| `check_package` | Checks the facts of a draft package without a write. It returns one result for each entry, with what was already said that makes the same point. | No |
| `publish_package` | Posts the package: the body, the inline notes and the replies. It runs the check first and refuses on any failure. It returns the readback: the rendered text and each position as the provider shows them. | One notifying write |
| `edit_own` | Edits a comment that Hephaestus posted at this destination on this item. It runs the same check and returns the readback. | A quiet edit |

A tool takes the IDs that its input listed. The server checks that each ID belongs to this item and to Hephaestus.

**A tool result is feedback for the model**: a typed reason with the next step, not an error code ([Writing tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents)). Examples:

- GitLab: "Line 42 of `src/a.ts` is not in this merge request's diff. The nearest changed lines are 40–41. Move the note there, or put it in the body."
- GitHub: "Line 42 is on the left side of a deleted hunk. Use side LEFT, or use the body."
- "This entry cites a result that is not admitted for this review. Cite one of: …, or remove the entry."
- "This entry makes the same point as your note in thread T: '…'. You can reply in T, merge the two, or keep both."
- "The provider does not render suggestion blocks here. Use a plain code block."
- "The work changed since this result was reviewed. Remove the entry."

No rule table decides placement in advance. The composer moves a note, merges it into the body, or rewrites it.

**Guardrails in the tools, not in the prompt:**

- **Admitted results only.** Each entry cites an admitted, current result. The check refuses an entry without one.
- **No claim from a non-result.** An entry may say that a practice was not reviewed. It cannot cite a withholding, a runtime non-result or a failed capture as a fact about the work.
- **A person's state wins.** The tools refuse a reply to, or an edit in, a thread that a person resolved, reopened or deleted. On GitLab a reply unresolves its thread, so this also keeps a person's resolution.
- **Tenancy.** The tools act only on the item of this composition, with the workspace's installation.
- **Exactly once.** The draft ID belongs to the composition: the sealed review and the destination. Every posted copy carries it in its marker.
  A retry, on any attempt or worker, reads back by that ID and never posts again.
  Before a write, `publish_package` adopts or deletes a pending GitHub review or GitLab drafts that carry the same marker.
- **Budgets.** A composition is one review at one destination. It has one `publish_package`, one `critique_package` and at most 3 edits.
  An attempt has at most 12 model calls, the critique included.
  The edit count is on the feedback row, so a retry does not reset it. A further call returns "budget spent: stop and report".
- **Approve first.** The composer checks and corrects the package before it asks for approval, so the draft that waits is final. The wait does not count against the deadline.
  After approval the server publishes it. After publish, the composer may only correct what keeps the approved words.
  Those are placement, rendering, a broken link or a moved line.
  A change of words goes back to Approve first. The admin who approves drafts for the workspace approves it.
- **Text policy.** The policy of [#3341](https://github.com/hephaestus-build/Hephaestus/pull/3341) applies in the check. Mentions stay escaped, so an edit never notifies a new person.

**Resolution is not a model tool.** The server resolves a thread that Hephaestus opened when lineage marks its problem No longer present, from complete proof.
It never resolves a thread with a reply from a person newer than Hephaestus's last note. On GitLab the resolve of the last open thread mails "all threads resolved".

**Withdrawal and erasure** stay server paths with no model: the existing retraction of a copy.

### Two kinds of check, kept apart

**Facts are checked by tools against external evidence, never by self-critique.**
`check_package` and the readback test citations, admission, currentness, placement and rendering.
On reasoning tasks, a model that checks itself with no external signal does not improve reliably.
It can make a correct answer wrong ([Huang et al. 2023](https://arxiv.org/abs/2310.01798)).

**Feedback quality can be checked by a critique and one revision**, off until the eval shows a gain (D18).
`critique_package` is a separate prompt that judges only how the feedback helps the reader, against the rubric. It returns notes.
It never decides whether a claim is true. It may ask the composer to cut, reorder or reword, or to add a part that the result already states.
It never asks for a consequence or a fact that the result's rationale does not state.

Self-Refine reports gains on writing tasks from feedback and refinement, over up to four rounds ([Madaan et al. 2023](https://arxiv.org/abs/2303.17651)).
One round is an assumption that the eval tests.

This is not the self-critique that [Quality](#quality) rejects. That option judges whether a result is correct. The critique judges only how the words help the reader.

**What the fact checks do not cover.** They test that each entry cites an admitted, current result. They do not test that every sentence follows from that result.
The guard is the critique's limit above, and the eval grades unsupported claims.

### The feedback rubric

The rubric is for the composers only, not for Heph. Its home is `server/application/src/main/resources/agent/feedback-style.md`, which both composers read today.
`critique_package` and the eval read the same file. Most criteria are already there or in [§ Writing feedback](../contributor/practice-feedback-language.md#writing-feedback). L13 adds only the new ones.

| Criterion | The feedback… | Home today | Source |
| --- | --- | --- | --- |
| Current state | says what the work does now, at the exact place, with its evidence (feed back) | `feedback-style.md` | Hattie and Timperley 2007 |
| Next step | gives one useful step for the developer (feed forward) | § Writing feedback | Hattie and Timperley 2007, Nicol and Macfarlane-Dick 2006 |
| About the work | is about the task or the process, never about the person | § Writing feedback | Hattie and Timperley 2007, Kluger and DeNisi 1996, Google code review guide |
| Short | gives only the detail that the point needs | `feedback-style.md` | Shute 2008 |
| Why | states the consequence that the result's rationale states, and no other | new | Google code review guide |
| Weight | says in plain words whether a change is needed or optional, with no code or label | new | Google code review guide |
| Goal | says what good looks like, only in the body and only when the standard is not obvious (feed up) | new | Hattie and Timperley 2007 |

The critique also checks that notes do not all open the same way, as `feedback-style.md` already asks.
Feedback that turns attention to the self often lowers performance (Kluger and DeNisi 1996). So the critique always reports "About the work".

### No redundancy

- The input holds the composer's earlier feedback at this destination, the other threads, and every admitted result of the review.
- `check_package` finds repeats by identity, not by a model.
  A repeat is two entries that cite the same problem.
  An entry whose problem already has an open earlier thread is a repeat too.
  It returns the earlier note with a short quote, and it does not refuse the entry. The composer merges it, replies in the earlier thread, drops it, or keeps it.
- The rule holds within one destination. A summary may point to its line notes, and the same point may appear in another destination, as `feedback-style.md` allows.
- The readback flags a posted note that repeats another posted note at this destination.

### Tool results are feedback that is useful

The tool results are specific, actionable and short, and they name the next step.
The model gets plain text. The trace stores the typed reason beside it, for audit and the eval.

- A fact result names the entry, the fault and the fix.
  Example: "Entry 3: line 42 is not in this diff. Lines 40–41 changed. Move it, or put it in the body."
- A critique note names the entry, the criterion and a change.
  Example: "Entry 2, Next step: it says what is wrong but not what to do. Add the one step that the result gives."

**The loop.** The composer drafts, checks, corrects and publishes. With D18 on, it critiques and revises once before the check.
With Approve first, it stops after the last passing check, and it publishes after approval.
It calls the model again only when a check refuses or the readback differs from the draft.
On a later review it may edit its earlier comment because the work changed, for example "This is fixed now".

The loop ends with a stop on the composition row. The new values extend `CompositionStop`:

| Stop | When |
| --- | --- |
| Delivered | The package is posted, and the readback shows each entry at the position it was sent to, with no provider error. |
| `NOTHING_NEW` | The composer publishes nothing, because nothing is new. |
| `STEP_LIMIT` | The turns or the edits are spent. A posted copy stays. With nothing posted, the destination is withheld with this reason. |
| The existing stops | Budget, model, ownership, provider, work time and no decision, as [ADR 0061](0061-review-seals-then-feedback-composes.md) records them. A provider stop hands on, and the retry resumes by the draft ID. |

**The trace.** Each tool call, its arguments and its result are stored on the composition row, for audit and for the eval set.

**Freshness at send.** The check refuses an entry whose result is no longer current. A package with no current result is withheld with `REVIEWED_VERSION_CHANGED`.
At the seal, a staged result that is already stale is admitted and ended at once as `WORK_CHANGED`. It is never delivered.

**Destinations.**

| Destination | `publish_package` | `edit_own` | Resolve (server) |
| --- | --- | --- | --- |
| GitHub pull request | A pending review with the body and threads, replies added to it with `addPullRequestReviewThreadReply`, then one submit with event `COMMENT` | `updatePullRequestReview`, `updatePullRequestReviewComment`, `updateIssueComment` | `resolveReviewThread` |
| GitLab merge request | Draft notes and reply drafts, then one bulk publish with the body as `summary_note` | `updateNote` | Resolve the discussion |
| Issue (both providers) | One issue comment. The check has no positions. | The same comment | Not supported here |
| Slack, Outline | Not supported here: no destination on the work. The private package and Heph do not change. | – | – |

**Quiet edits for each provider.** GitLab notifies an edit only for added mentions, and escaping prevents them (GL-PUBLISH, source read).
GitHub is open (GH-EDIT). Until the spike answers, an edit counts as a possible notification, and the edit budget bounds it.

**Cost.** A composition with no correction makes the same number of model calls as today: one draft, then the publish.
Each correction adds one call. With D18 on, the critique and the revision add two, and the critique has its own prompt prefix.
The system prompt, the tools and the input form a stable prefix, so the composer's calls read it from the cache.

### Drafts and the missing events

- A draft gets feedback on the practice page and in conversation only. It gets no feedback on the work.
- Ready is a milestone. It measures again and gives the first feedback on the work.
- New occasions: `scm.pull_request.reopened` (a milestone) and `scm.pull_request.converted_to_draft`.
  The second selects nothing and withholds pending feedback for the work with `ITEM_IN_DRAFT`.
- Practices that judge the title, the description or the linked issue select `edited`.
- Comments and threads change fingerprints only. No occasion selects them.
- The bundled practices get the new selectors in the layer that adds them.
  A workspace-customized practice gets them only when an admin takes the offered catalog upgrade.
  Reset returns a customized practice to the bundled version.

### Contract changes are a cutover

1.0 is a full cutover. Each layer deletes the path that it replaces, in the same layer.
No layer keeps an old path until a later layer, and no layer adds a compatibility shim.
The 1.0 upgrade stops the old version, then starts 1.0. It has no window with mixed versions.
[ADR 0070](0070-1-0-is-a-cutover-release.md), the 1.0 cutover, records this rule.

- **The source contract has one version.** `ClasspathSourceCatalogRegistry` loads one catalog, and `SandboxLayout.SOURCE_CONTRACT_VERSION` pins it.
  A layer that adds or changes a source kind raises the contract version.
  The same layer migrates every pinned practice, bundled and customized, to the new version.
  L6 does this for `scm.pull-request.checks`.
- **Schema changes ship in one layer.** L6 keys the checks event by the digest of the settled run set.
  Its migration replaces `uk_item_event_key` in that layer. It does not expand in one release and contract in the next.
- **Each replaced path goes with its replacement:**
  - L1 deletes the shared brief.
  - L4 deletes the model-name regular expression.
  - L7 deletes currentness by version equality and the end of every result on a submitted review.
  - L13 deletes the composition without earlier feedback, and the GitLab writer that posts one discussion at a time.
    It also deletes the in-process wire records of the removed composition routes, and the `ConversationDecision` values that nothing decides.
- **What stays, by decision of [ADR 0070](0070-1-0-is-a-cutover-release.md):**
  - The `LEGACY_*` stop and request reasons of migrated history. A rewrite to a current reason would be a false claim.
  - Results of evidence version 0, as a read-only earlier review.
  - The resolver of earlier feedback links.

### Quality gates

- Every repair in a session comes from an external signal: an admission refusal, the unproved-absence send-back, or the undeclared-citation refusal.
- **An eval set and a rollout gate.** 20–50 cases for each practice, seeded from `criteria-cases.json`, and a set of composition cases from real reviews.
  People label the composition cases on the feedback rubric. The eval reports the grades with and without the critique pass (D18).
  Code grades what admission can decide. A judge model grades the rest, with its agreement with human labels reported.
  The set runs when the practice version, catalog, model, runner prompt, brief layout or composition prompt changes.
  A drop in precision beyond the noise blocks the rollout revision.
- **A lifecycle trace replay with no model.** Recorded event sequences run against the trigger model.
  They assert the reviews, practice sets and reasons.
  The cases are a burst, flaky CI, a fork that waits for approval, and no CI.
  Draft to ready, an edit storm and a busy pusher are cases too.

### Removed rules

Each rule below was in the proposal of 2026-10-27. None protects trust, and no measured problem needs it. The decision and layer numbers of the proposal are given.

- **The living summary template and stored problem copies.** The composer writes better text when it sees its earlier feedback.
- **D5: the severity table for placement and the cap of 5 threads.** The composer decides placement and volume. The publish and edit budgets bound the writes. The eval set measures noise.
- **D6 and L17: the item review budget and the item pause.** The workspace AI budget bounds spend.
  Fingerprints and the window already stop runs of unchanged practices.
- **D7 and L20: probation formulas.** No precision problem is measured yet. The eval gate blocks a regression before rollout, and admins keep the existing delivery controls.
- **L15: a write budget for each installation.** One notifying write for each review keeps the volume low. A rate-limited send retries after the provider's `retry-after` and is never dropped.
- **A window and a cap for each item kind.** One window and one cap for every kind until M-WINDOW measures a need.
- **D11 and L18: the neutral check run on GitHub.** The feedback on the work says what was reviewed. A check run adds a permission and a write for each head.
- **The draft setting with three values.** Drafts get private feedback only. No workspace asked for a different choice.
- **The deadline switch between longest and shortest first.** The order of no current result first already rotates coverage.
- **The resolve rule that needs a changed interdiff.** Lineage already needs complete proof at a changed version for No longer present.
- **Reasons for a summary removed by a person and for a locked conversation.** The send records the provider's refusal with the existing failure reasons.

### New reason values

Each value gets its glossary entry and its operations guide entry in the layer that ships it.

| Kind | Values |
| --- | --- |
| Item event | `ALREADY_CURRENT` |
| Membership | `CHECKS_PENDING`, `NO_CHECKS_REPORTED`, `CHECKS_AWAITING_APPROVAL` |
| Candidate refusal | `UNDECLARED_SOURCE_CITED` |
| Feedback stop | `ITEM_IN_DRAFT` |
| Runtime non-result | `REPLACED`, which exists and becomes allowed |

A reason is never shown as a fact about the work.

### Glossary

The [practice review glossary](../contributor/practice-review-glossary.mdx#review-terms) defines readiness edge and source fingerprint.
This ADR does not repeat their definitions.

## Decisions

The maintainer accepted this ADR on 2026-10-28 with each decision below as written. D18 ships off until the eval shows a gain.
The default numbers go into the operations guide when they ship.

D5, D6, D7 and D11 of the proposal are removed ([Removed rules](#removed-rules)). D16, D17 and D18 are new. The numbers of the others stay.

### D1. Drafts get private feedback only

A draft gets feedback on the practice page and in conversation. Ready is a milestone that measures again and gives the first feedback on the work.

**Decision: adopted.**
**Consequence:** authors get private feedback before reviewers see the pull request. Each ready transition costs one full review. No setting.

### D2. Readiness per practice behind one quiet window per item

No CI cap. CI practices wait for the checks edge. One window of 2 minutes, with a cap of 10, for every item kind.

**Decision: adopted.** Validate the values against measured inter-arrival times (M-WINDOW) before the operations guide fixes them.
**Consequence:** practices that do not read CI never wait for CI. Every skip has a reason. Two small reviews can run at one version.

### D3. The feedback composer works with tools

A model in a bounded loop with three provider tools (check, publish and edit) and the critique of D18.
Tool results are actionable reasons from the provider, and the guardrails sit in the tools. Writes run through the server's send path.

**Decision: adopted.**
**Consequence:** feedback reads like a reviewer who remembers the last round. A placement that the provider refuses is corrected, not lost.
Quality depends on the composition prompt, the tool messages and the eval cases.

With Approve first, the composer checks and corrects before it asks for approval. After approval it may only correct placement, rendering, a broken link or a moved line.
A change of words goes back to Approve first, and the admin who approves drafts approves it.

### D4. At most one notifying write for each review

`publish_package` is the only tool that notifies, and it runs once for each review. Edits are quiet where the provider allows it. The server resolves threads.

**Decision: adopted**, subject to spikes GH-REVIEW, GH-EDIT and GL-PUBLISH.
**Consequence:** GitLab line notes move to draft notes and one bulk publish. GitHub replies move into the pending review.

### D16. The composer holds no provider token

The composer runs on the worker, as composition does. Its tools call the server, which writes through the existing send path, fenced on the composition.

**Decision: adopted.**
**Consequence:** a steered model is never one call from a write token. The send state machine, its retries and retraction stay. Each tool call is one internal request.

### D17. Budgets of the loop

One publish and at most 3 edits for each composition, counted on the feedback row. At most 12 model calls for each attempt.

**Decision: adopted.** Tune with the eval traces.
**Consequence:** a loop that does not converge stops with `STEP_LIMIT`. It keeps what it posted, or it withholds with that reason.

### D18. A rubric critique pass, on only with a measured gain

One critique against the rubric, then one revision, before the fact checks. It ships off.

**Decision: adopted behind the eval.** The eval runs a paired comparison: the same composition cases, with and without the pass, 3 samples each.

People label at least 60 composition cases on the rubric. The pass turns on only if the rubric grades improve and the 95 % interval of the gain is above zero.
**Consequence:** no cost until a gain is shown. Then two more model calls for each composition. The rubric has one home, read by the composer, the critique and the eval.

### D8. Freshness by source fingerprints

A result depends on every source that its session could read, and it records their fingerprints. Each practice's opening holds only its declared sources, in canonical layers.
The read tool refuses undeclared context files. Admission refuses undeclared citations. CI gets its own source kind.

**Decision: adopted.**
**Consequence:** while `bash` can read every captured file, a result records every captured kind. When ADR 0068 moves captured files to the loop's volume, freshness narrows to the declared kinds, `diff` and `tree`, with no change to this rule.
Then a description edit keeps diff results current, and comments and CI reruns make only their readers stale. Practices that rely on undeclared context must declare it, so the first layer audits the 52 `reads` blocks.
The freshness layer adds the migration for `result.read_fingerprints`.

### D9. Merged and submitted reviews go to private destinations

**Decision: adopted.**
**Consequence:** no public comment after merge, and none on a reviewer's review. The call class of a review needs a derivation from the event name.

### D10. Practice concurrency

Practice-session concurrency is adaptive within a measured maximum, `c_max`.
The footprint benchmark qualifies changes to that maximum while reviews, composition and conversation turns run together.
Each physical model request still obtains a grant from the shared provider call pool.

**Decision: amended on 2026-10-30.**
**Consequence:** practice concurrency bounds work within a review Pod.
Review Pod admission follows [ADR 0068 G](0068-reviews-and-heph-run-on-one-agent-runtime.md#g-review-pod-admission-checks-quota-and-provider-demand).

This practice-concurrency amendment changes no practice width or measured footprint value.
The separate resident-turn CPU rule and its 32-turn ceiling are in ADR 0068 G.
The tools tier keeps its eight active plus two warm Pods. It does not grow with resident turn admission.

### D12. Batch APIs only for evals

**Decision: adopted.**
**Consequence:** the eval runner needs a batch adapter for each provider, or it runs in the backfill class.

### D13. Cache TTL and metric

5 minutes on every protocol. Keep the system breakpoint. The admin review view shows the cache read share of each review, for every protocol.

**Decision: adopted.**
**Consequence:** the operator can see whether caching works. vLLM operators must turn on token details.

### D14. A native Anthropic Messages wire shape in 1.0

It covers the catalog value, the upstream path and authentication, and the body policy.
It also covers a usage parser with Anthropic semantics for streams, the probe with `forced_tool_choice`, the admin preset and the docs.

**Decision: adopted.**
**Consequence:** Claude users get a first-party route and caching. The runner never changes `tool_choice` on this wire. The model-name regular expression goes. The preset is in the shared provider form, so both admin consoles get it.

### D15. Prefix routing on self-hosted pools belongs to the operator

Hephaestus sends a stable prefix, and `prompt_cache_key` only where the wire defines it. No custom routing header.

**Decision: adopted.** Run spike LOGOS with the Logos operators.
**Consequence:** the admin docs name the routers and their flags. On a GPU pool the cache hit rate is throughput, so D13 shows it.

## Spikes

Each behavior that no primary source settles has a named spike or test.
Its result is recorded here before the layer that depends on it merges.

| ID | Behavior to prove | Spike or test | Blocks | Result |
| --- | --- | --- | --- | --- |
| ANT-CACHE | Anthropic cache hits across concurrent sessions with and without the stagger, lookback hits across declared-source sets, explicit and automatic breakpoints together, usage in the stream | A live script with two openings and 6 sessions | L5, D14 | open |
| OAI-STAGGER | OpenAI hits for concurrent requests with one prefix, and the number of explicit breakpoints | The same script on Responses | L3 | open |
| VLLM-APC | vLLM shares blocks between concurrent requests, accepts `prompt_cache_key` in the body, and reports `cached_tokens` with the flag | A local vLLM with 6 concurrent sessions through the gateway | L3, D15 | open |
| LOGOS | The engine behind Logos, its routing policy and its cache metric | Ask the operators, then measure one staging review | D15 | open |
| GH-EDIT | GitHub notifies or does not notify on an issue comment edit, a review body edit, and `resolveReviewThread` | Two accounts in a test organization | D3, D4, L13 | open |
| GH-REVIEW | `updatePullRequestReview` edits a submitted review body. A reply with a pending review ID, then a submit, gives one notification. A crash leaves a pending review that a retry can adopt by its marker. | The same test organization | L13 | Schema: both mutations exist. The live write is open. |
| GH-FORK | The suite and run state of a first-time contributor's fork workflow before approval | Public fork pull requests | L6, L10 | Done. Gated suites are `COMPLETED` with `ACTION_REQUIRED` and 0 runs. The rollup can still be SUCCESS. |
| GL-PUBLISH | A bulk publish of a general note, line notes and a reply sends one email. `updateNote` without new mentions sends none. One resolve sends none. | A GitLab test project with two users and mail capture | D4, L13 | Source read: one email for each publish, none for an edit without new mentions. A resolve that leaves every thread resolved sends one email. The live run is open. |
| F-FP | Equal fingerprint means equal captured bytes, for every source kind | `SourceFingerprintPropertyTest` over the fixture corpus and random edits | L7 | Code read: sound with the rules in [Freshness](#freshness-by-source-fingerprints). The test is open. |
| M-WINDOW | Push and edit inter-arrival times | The p50 and p90 gap within a burst, from staging and course `item_event` rows | D2 defaults | open |
| M-FOOT | Memory of the real model at a concurrency of 6 | The footprint benchmark | D10 | open |

## Order

The pull requests that implement this ADR are the plan.
The Spikes table names them by these layers. Removed layers keep their numbers, so references stay stable.

| Layer | Content |
| --- | --- |
| L1 | The declared-source brief, the host's refusal of undeclared reads, and the catalog `reads` audit |
| L2 | `UNDECLARED_SOURCE_CITED` at server admission |
| L3 | Stagger, the server-supplied order, OpenAI breakpoints at layer ends, and the cache read share |
| L4 | The native Anthropic Messages wire shape and `forced_tool_choice` |
| L5 | The runner's Anthropic layout |
| L6 | CI evidence ([#3366](https://github.com/hephaestus-build/Hephaestus/pull/3366)) in its own source kind, the contract version raised, every pinned practice migrated |
| L7 | Source fingerprints and freshness |
| L8 | Supersede at claim and at practice start |
| L9 | The quiet window |
| L10 | Readiness per practice and the checks edge |
| L11 | Staging and one seal ([ADR 0063](0063-each-practice-is-staged-when-it-ends.md)) |
| L12 | Lineage |
| L13 | The composer tools `check_package` and `publish_package` for GitHub, GitLab and issues. They write through the server's send path. The guardrails, the draft ID, the trace and one notifying write come with them. |
| L14 | The correction loop: the readback, `edit_own`, the budgets, the stops, and the server's resolve from lineage |
| L15 | Removed: the write budget |
| L16 | Drafts, the new occasions, and the catalog upgrade that admins take for bundled practice updates |
| L17 | Removed: the item budget and pause |
| L18 | Removed: the check run |
| L19 | The pool and claim order, and `c_max` |
| L20 | Removed: probation |
| L21 | The eval harness for practices and composition, the rollout gate, and `critique_package` behind its measured gain (D18) |

These ordering constraints bind them:

- Each layer ships alone, with its tests, its docs by audience and its changeset. A layer ships no model that a later layer removes.
- The declared-source brief and the server's undeclared-citation refusal come before fingerprint freshness. Freshness by declared sources is unsound with the shared brief.
- The CI evidence of [#3366](https://github.com/hephaestus-build/Hephaestus/pull/3366) comes before readiness per practice.
  It needs its own source kind and the approval state.
- A layer that changes the source contract migrates every pinned practice in that layer ([Contract changes are a cutover](#contract-changes-are-a-cutover)).
- Staging and lineage come before the composer's use of earlier feedback.
- The composition eval cases come with L13, so the composer has a gate from its first release.
  They include GitLab placement failures, a refused suggestion block and an injected instruction in PR text.
  A duplicate of earlier feedback and a provider outage after publish are cases too.
- The runner's Anthropic layout comes after the native wire shape and the declared-source brief.

## Consequences

Good:

- A practice that does not read CI never waits for it. A practice that reads CI never judges unfinished CI.
- A current result describes what its practice saw. An edit ends only the results that read it.
- Feedback reads naturally, remembers the last round, and notifies at most once for each review.
- Every skip, stop and wait has a recorded reason on the event, the membership or the feedback.
- The same prefix helps every engine. Claude gets prompt caching through the first-party wire.
- No new table for selection, readiness or freshness.

Bad:

- Each ready transition costs one full review.
- Two small reviews can run at one version when CI settles after the window.
- Practices that relied on undeclared context must declare it, and their openings get longer.
- The stagger delays the other sessions of an opening until the first session's first streamed event, at most 20 seconds.
- The gateway gets a sixth wire shape, with its own body policy and usage parser.
- Composition quality depends on a model. The eval set must cover it before rollout.
- GitHub's visible edit history keeps the text of an edited comment.

## Revisit trigger

- A practice needs to judge CI that has not finished, or CI that never settles. Then a cap with its own reason.
- The measured window delay harms the feedback loop (M-WINDOW).
- An item needs more than one active review at a time, against [ADR 0062](0062-the-item-is-the-review-scope.md).
- Measured noise or spend on the work: feedback volume, rate-limit responses, or reviews of one item. Then the matching rule from [Removed rules](#removed-rules), with its measurement.
- The cache read share stays low on sparse traffic with repeated writes. Then the 1-hour TTL for the tools and system slot only.
- A provider documents a routing key that the gateway can forward. Then D15 again.
- Reuse of results across reviews is proposed. Then the full input digest of [ADR 0062](0062-the-item-is-the-review-scope.md).

## Sources

Fetched 2026-10-27.

- [Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching): breakpoints, lookback, automatic caching and usage fields.
- [Anthropic tool definitions](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools): forced tool choice and cache invalidation.
- [Anthropic OpenAI SDK compatibility](https://platform.claude.com/docs/en/api/openai-sdk): no prompt caching.
- [Bedrock prompt caching](https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-caching.html) and [Bedrock API compatibility](https://docs.aws.amazon.com/bedrock/latest/userguide/models-api-compatibility.html).
- [Claude prompt caching on Google Cloud](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/claude/prompt-caching).
- [OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching).
- [vLLM automatic prefix caching](https://docs.vllm.ai/en/latest/design/prefix_caching.html), [vLLM production stack](https://github.com/vllm-project/production-stack), [llm-d router](https://github.com/llm-d/llm-d-router) and [SGLang](https://arxiv.org/abs/2312.07104).
- [GitHub REST rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api), [check runs](https://docs.github.com/en/rest/checks/runs), [GraphQL schema](https://docs.github.com/public/fpt/schema.docs.graphql), [comment history](https://docs.github.com/en/communities/moderating-comments-and-conversations/tracking-changes-in-a-comment) and [fork workflow approval](https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-workflow-runs/approving-workflow-runs-from-forks).
- [GitLab draft notes](https://docs.gitlab.com/api/draft_notes/), [reviews](https://docs.gitlab.com/user/project/merge_requests/reviews/), [commits API](https://docs.gitlab.com/api/commits/), [settings API](https://docs.gitlab.com/api/settings/) and [merge request pipelines](https://docs.gitlab.com/ci/pipelines/merge_request_pipelines/).
- [Huang et al. 2023, Large language models cannot self-correct reasoning yet](https://arxiv.org/abs/2310.01798): self-correction without an external signal.
- [Madaan et al. 2023, Self-Refine](https://arxiv.org/abs/2303.17651): iterative refinement with self-feedback, over up to four rounds. It reports gains on writing tasks and mixed results on math.
- [Hattie and Timperley 2007, The power of feedback](https://doi.org/10.3102/003465430298487), Review of Educational Research 77(1), 81–112.
- [Shute 2008, Focus on formative feedback](https://doi.org/10.3102/0034654307313795), Review of Educational Research 78(1), 153–189.
- [Kluger and DeNisi 1996, The effects of feedback interventions on performance](https://doi.org/10.1037/0033-2909.119.2.254), Psychological Bulletin 119(2), 254–284.
- [Nicol and Macfarlane-Dick 2006, Formative assessment and self-regulated learning](https://doi.org/10.1080/03075070600572090), Studies in Higher Education 31(2), 199–218.
- [Google engineering practices: how to write code review comments](https://google.github.io/eng-practices/review/reviewer/comments.html).
- [Kadavath et al. 2022](https://arxiv.org/abs/2207.05221) and [Kamath et al. 2020](https://aclanthology.org/2020.acl-main.503/): stated confidence and calibration.
- [Zheng et al. 2023](https://arxiv.org/abs/2306.05685): a judge model's agreement with human labels.

## Update — 2026-10-28

OpenAI Responses keeps the provider's default cache retention.
The runner sends no retention override for that protocol.
The [OpenAI prompt-caching guide](https://developers.openai.com/api/docs/guides/prompt-caching) specifies a default of 30 minutes for models with explicit cache control.
The API does not accept the five-minute value in this decision.
Anthropic keeps the five-minute TTL in L5.

L3 proves the wire shape, start stagger, deadline, queue order and cache read share calculation.
Live cache gain is a performance property, not a correctness condition for L3.
After L3 merges, measure the gain on staging with real providers and the admin review's cache read share.
Until that measurement, the gain remains unverified.

L4 adds the native Anthropic Messages protocol to the catalog, gateway, runner, practice script toolkit, and feedback composer.
Its connection test uses native authentication.
Its stream parser merges cumulative usage and keeps cache reads and writes separate from uncached input.
Each saved model has a nullable `forced_tool_choice` capability from a synthetic probe.
An unknown capability uses automatic tool choice.

Anthropic sessions keep automatic tool choice even when a probe confirms support.
The runner caches the system, change and core layers with a five-minute TTL.
It removes the SDK's last-tool breakpoint and keeps its moving session breakpoint.
The four breakpoints preserve the exact text and roles, also on a repair turn.
If compaction removes the original opening, only the system and session breakpoints remain.

The native feedback composer uses provider defaults for thinking and sampling.
It does not infer supported options from an opaque model name.
The review runner uses the native SDK's model metadata when the SDK recognizes the model.
An unrecognized native alias keeps provider defaults for these options.
The probe changes no frozen review method or model identity.
An inconclusive probe keeps the last confirmed capability.

The native SDK keeps hosted tool discovery and system updates inside the conversation disabled.
The gateway serves custom client tools and a top-level system prompt with one fixed API version.
Known model metadata still supplies its supported thinking mode.
