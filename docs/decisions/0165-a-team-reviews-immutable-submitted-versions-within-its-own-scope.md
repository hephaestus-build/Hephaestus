# ADR 0165: A team reviews immutable submitted versions within its own scope

**Status:** Proposed
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0095](0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md), [ADR 0160](0160-a-sealed-review-hands-off-to-the-mentor.md)

**Builds on:** [ADR 0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md), [ADR 0099](0099-aggregates-own-their-lifecycle-and-module-boundaries.md), [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md)

**Relates to:** [ADR 0162](0162-a-team-owns-its-growth-record-in-its-workspace.md), [ADR 0163](0163-the-mentor-builds-a-context-packet-from-current-source-refs.md), [ADR 0157](0157-the-mentor-core-keeps-what-the-person-saves-and-each-answer-shows-what-it-used.md)

## Context

A team wants formative feedback on work in progress that lives outside a repository, for example a board, a design or a model.
People change the source while they work on it.
The server often cannot read it, and several tools produce such work.
An observation is useful only when the team can open exactly what it was about, also after the source changed.
Practice Review already measures a pinned version of an item and verifies each citation against captured bytes.

## Decision drivers

- Never turn a capture failure or an unverifiable criterion into a claim about work.
- A team's work reaches only that team's current members.
- Every claim opens the exact evidence it rests on.
- One review path for every tool.
- No connector platform before two adapters show what they share.

## Considered options

1. **Review the live source.** The server reads the tool when it reviews. Each person must grant lasting provider access. Evidence changes under the observation. Tools with no server API stay out.
2. **A separate reviewer in each tool.** Each host then has its own records, authority checks and feedback.
3. **Treat an upload as a file in a created repository.** It invents commits, authors and access that do not exist.
4. **Teams submit immutable versions to one contract (chosen).** Practice Review measures them. Hephaestus stores the submitted work, and it owns the retention and erasure of that work.

## Decision

1. **The submitted version is the reviewed thing.** The reviewed item has a kind that names its shape. A practice runs only on its own kind. The item belongs to one team project in one workspace. The workspace is the access and budget boundary. The team project is the people and their work. Each version is immutable and holds one or more snapshots. A change is a new version. Neither the item nor a version has a draft state.
2. **Snapshot, source revision and live link are three things.**
   - A snapshot is what the sender saw and then submitted. It is the evidence.
   - The server records a source revision only when it read the source with the submitting member's own access.
   - A live link is a pointer. A review never fetches it, and it is never evidence.
3. **The server checks two authorities.** Source authority says what the member could supply. Either the member supplied the bytes, or the server read them as that member. Receiving-team authority is current team membership. The server checks it at submit, request, capture, admission, delivery and every read. A host identity or a share link grants neither.
4. **Every entry route uses this contract.** The routes are a capture inside a tool and an authenticated link import. They are also an explicit upload and a pinned repository commit. A host starts a review and shows an authorized view of the same record. It keeps no second review.
5. **The server derives every anchor.** An anchor names a version, a snapshot and an element of it. The server derives the element from verified cited text. An observation can cite several anchors. The server verifies each citation on its own. Each citation stays within one element. An anchor resolves only in its own version. Nothing matches elements across versions.
6. **Missing evidence stays missing.** The version records what the capture left out. A practice that cannot read a required source as it declares is withheld with that reason. It records no observation (ADR 0090 decision 6). A claim that something is absent needs that source captured whole. A criterion can need evidence that no source holds. It then ends as **Could not determine**. No gap ends as **Not met**. A claim about visual qualities needs verified visual evidence. Until such evidence exists, the review says that it cannot verify visual criteria.
7. **Team observations belong to the team.** They create no person's observation, standing or score (ADR 0162 decision 4, ADR 0125). They reach no practice page and no shared source. They reach no staff view unless this ADR or a later one names it. `Readers` gains a project team kind (ADR 0127).
8. **Only a team member records a decision** on a problem: open, acted on or dismissed. A new decision supersedes an old one.
9. **Every store has its reverse.** Withdrawal of a version deletes its content. Deletion and erasure paths ship before the first write.
10. **A named review setup chooses what Practice Review measures.** It selects existing practices of one item kind. It names required and optional evidence slots, and the team projects that can use it. A review keeps a copy of its setup. The compiled definitions and the captured sources decide eligibility. No adapter selects practices, and no model guesses a kind. A rationale never satisfies evidence about the work.
11. **The seal chooses the presentation once.** Nothing reads it again as a choice. `OBSERVATIONS` shows the sealed observations and their next steps, labeled as observations. No composer runs.
12. **A team composition is a later extension of ADR 0160, not a change of it.** The seal chooses the `TEAM` producer once, only for a requested review. The composer runs on the mentor runtime after that runtime's release path is safe. Practice Review owns applicability, coverage, observations and the seal. The composer orders and words guidance about sealed problems, or says nothing. It creates no evidence and changes no result. It reads only this item's sealed rows, its previous review and the team's decisions. It never reads a personal record. It has its own audience check at composition, commit and render. It has its own erasure and withdrawal basis. It sends nothing proactively. The owner-only rules of ADR 0160 do not change.
13. **Each invariant has one owning module in the one deployment.**

    | Context | Module | Owns | Never owns |
    | --- | --- | --- | --- |
    | Capture | `integration.submission`, and one package for each tool | the typed immutable version, its elements and anchors, normalization, the source authority check | practices, applicability, observations, team policy |
    | Practice Review | `practices`, with runs hosted by `agent` | practice versions, review setups, applicability, execution, sealed observations, coverage, the presentation | tool types, membership |
    | Project | `project` | teams, membership, team decisions, the receiving-team authority | capture, observations |
    | Mentor | `mentor` | composition over sealed observations under the permitted scope, or silence | evidence, observations, membership |
    | Hosts and the web app | | the same authorized read | state of their own |

    - Modules meet through named interfaces and ports. Practice Review reaches work and its audience only through neutral ports in `integration.core`. It depends on no tool package and not on `project`. Tool types stay inside their capture package.
    - This amends ADR 0095 decision 1. `integration` also holds `submission` and one package for each capture tool. These packages have no provider connection.
    - The pull request that adds the first one also adds it to the layout test and the import guards. It also records the change in ADR 0095.
    - No new service and no plugin framework.

### What this does not decide

- Team growth records, commitments, check-ins and proactive mentoring stay in ADR 0162.
- Until decision 12 ships, a review of a team item takes no producer of ADR 0160. It writes no mailbox event, work comment, advice or contact. ADR 0160 keeps governing reviews about a person.
- The mentor runtime's own decision defines the runtime shapes of a team composition: owner key, scope stamp and claims.
- A later team mentor can follow an authorized team commitment. It cites a team version only by reference, with a current access check (ADR 0163). It shows the version under **What Hephaestus used** (ADR 0157). 1.0 conversations load none.
- Four questions for the maintainer and the course owner are open before acceptance. They are staff visibility, the governing AI choice, erasure against team custody, and retention after revocation.

## Consequences

- Good: one review path for every tool, and evidence that stays openable after the source changes.
- Good: no standing provider grant and no invented repository.
- Bad: Hephaestus now stores submitted work, so it owns size limits, retention, withdrawal and erasure. ADR 0090 covers short-lived evidence copies, not this store.
- Bad: a member-supplied snapshot does not prove that the source holds it.
- Bad: each tool needs a normalizer, a server index and a read-only view.
- Bad: visual criteria stay unverified until someone designs visual evidence.
- Bad: until a team composition exists, a team reads observations that nothing ordered or reworded for it.
- Bad: a review setup is one more thing for staff to maintain.

## Confirmation

Not enforced yet. The implementation must add:

- An authority table as tests: each route, an outsider, a removed member and a guessed id.
- One anchor test with two citations for each adapter, with one bad quote that refuses the candidate.
- A test that a renamed or deleted element still resolves in its own version.
- A test that a missing source withholds and never records **Not met**.
- An architecture test: no person route, staff route, research export or public read returns a team observation.
- Erasure and withdrawal tests before any writer.
- A test that a seal with `OBSERVATIONS` starts no composer run, mailbox event or feedback row.
- Before a team composition: tests that its prompt holds no personal record and that it writes no result or evidence.
- Module and architecture tests: no review module imports a capture tool package or `project`.
- One review journey with two capture adapters. Practice version, observation shape and composition input stay the same.

Until each check exists, a reviewer compares team pull requests with decisions 2, 3 and 7 to 10.
These pull requests add a team route, a store or a capture package.

## Revisit trigger

Two shipped adapters need the same code outside their normalizer, index and view.
Or teams need evidence that a text citation cannot carry.

## Sources

- W3C, [Web Annotation Data Model](https://www.w3.org/TR/annotation-model/) §§ 4.2 and 4.3.
  A selector names a segment of a resource.
  A state names which version of a changing resource is meant.
  This ADR stores the version, and the server derives the selector.
  Thus no reader must recover the state from the source.

## Update — 2026-11-27: the team experience is a Team Mentor

The maintainer decided that January 2027 delivers a Team Mentor.
A team does not only read observations: the mentor of [ADR 0160](0160-a-sealed-review-hands-off-to-the-mentor.md) words guidance over them, under the team scope of [ADR 0162](0162-a-team-owns-its-growth-record-in-its-workspace.md).
This update replaces decisions 11 and 12, and the second, fourth and fifth points under What this does not decide.
Decisions 1 to 10 and 13 stay.

### Replaced decisions

**Decision 11: each team seal chooses once.** A sealed review of a team item chooses `TEAM` or `OBSERVATIONS` at seal, and the server stores the reason. Nothing chooses again. `WORK_PLACE` does not apply, because a team item has no work place at a provider.

- `TEAM` applies to a requested review in an enabled workspace, when the conditions for a model run hold. These include the workspace's model assignment and payer, and the AI choice rule below.
- Otherwise the seal records `OBSERVATIONS`. It starts no composer and writes no mailbox event. The team reads the observations and the reason.
- Under both values, the observations stay available as evidence. A person reads them in the anchored view and in a host.
- An answer that cites an observation also lists it under **What Hephaestus used**.
- `TEAM` is a new producer value beside those of ADR 0160 decision 1. A review of a person's work keeps the values of that decision.

**Decision 12: one composition pipeline words team guidance.** The mentor's review handler of ADR 0160 alone orders and words guidance over sealed observations. Its scope is typed: an account, or a team project in a workspace. There is no second composer, and no host owns a review, a chat or state.

- A `TEAM` seal writes one `REVIEW_SEALED` event under the team's key. Retries, delivery keys and readback follow ADR 0160 and [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md).
- Practice Review still owns applicability, coverage, observations and the seal. The composer creates no evidence and changes no result.
- The composer reads the item's sealed rows, its earlier review, the team place and the team's current decisions and guidance. It never reads a personal record.
- It reads an earlier review only if that review sealed after the latest current member joined. It reads team place discussion only from after that time.
- Current decisions and guidance have their own reader rule. Every current member reads them, whatever their join date.
- It writes team guidance and the team place only. It writes no work comment, contact or Slack message.
- Team guidance takes the `TEAM` audience that ADR 0160 reserves. Only current members of that team read it (ADR 0162 decision 4).
- Each read, send and model load checks current team membership. A non-member gets the same refusal as for a missing record.
- The reader sees what Hephaestus used ([ADR 0157](0157-the-mentor-core-keeps-what-the-person-saves-and-each-answer-shows-what-it-used.md) decision 8).

### The team place

- Each team project has one mentoring place in the app, with threads for its reviews and topics.
- The mentor module owns the threads and messages. The project module owns membership and team decisions.
- This adds the threads and messages to what the mentor module owns in decision 13.
- A member asks under their own identity. The question enters the mentor's intake with the team as owner and the member as requester.
- An answer cites an observation with its anchor and proposes an option. After a new review, it revisits a recorded decision.
- The mentor can propose a decision. Only a member records one, by an explicit act (decision 8).
- The place gives no grade or score and assigns no person. It never claims that the group agreed because one person acted.
- A person can bring authorized team evidence into their personal conversation by reference. The server checks access at each load.
- Nothing personal moves into the team place.

### Withdrawal and erasure

- Withdrawal of a version also hides the guidance and replies that cite it, and marks them stale. No later composition reads them.
- Erasure of a member removes their messages and the versions that they captured. It also redacts replies and guidance that quote them.
- A decision that an erased member recorded stays without their name.
- A member who leaves a team loses access at once. Their messages stay under their name until an erasure removes them.
- Each new store ships in three releases: tables and deleters, then erasure registration, then the first writer.

### Open until the maintainer and the course owner decide

The stricter rule applies until each is decided.

- **Staff.** A workspace admin sees that a team exists, its members and its counts, but no message, guidance or version.
- **New members.** They see the team place from the time they join, with the current decisions and guidance.
- **AI choice.** `TEAM` needs an explicit AI choice that admits the model from each current member. One refusal or missing answer gives `OBSERVATIONS`.
- **Review under a refusal.** It is open whether Practice Review can process a team item when a member refuses AI. This needs a decision before the first writer.
- **Erasure against team custody.** The erasure rule above applies.
- **Retention.** It is the shortest option.

### Consequences of this update

- Good: personal and team mentoring share one pipeline, one retry contract and one provenance rule.
- Bad: January depends on the mentor runtime, its erasure gates and the release sequence.
- Bad: under the stricter AI rule, one member's refusal leaves the whole team without the mentor.
- This replaces the earlier consequence about a team that reads observations until a composition exists.

### Confirmation of this update

Not enforced yet. Before the team mentor is enabled, tests must add these checks:

- A non-member gets the same refusal for the team place, a thread, an observation and a host.
- A canary personal goal and message reach neither the team place nor a team composer request.
- After a member leaves, their next personal load returns no team evidence.
- A retried seal gives one team revision.
- Withdrawal hides the guidance and replies that cite the version.
- A review that runs although a member refuses AI records `OBSERVATIONS` with its reason.
- No team step writes to Slack.
- After a member joins, a team composer request holds no discussion and no earlier review from before that time.
