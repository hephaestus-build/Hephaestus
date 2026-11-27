# ADR 0165: A team reviews immutable submitted versions within its own scope

**Status:** Proposed
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0095](0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md)

**Builds on:** [ADR 0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md), [ADR 0099](0099-aggregates-own-their-lifecycle-and-module-boundaries.md), [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md)

**Relates to:** [ADR 0162](0162-a-team-owns-its-growth-record-in-its-workspace.md), [ADR 0160](0160-a-sealed-review-hands-off-to-the-mentor.md), [ADR 0163](0163-the-mentor-builds-a-context-packet-from-current-source-refs.md), [ADR 0157](0157-the-mentor-core-keeps-what-the-person-saves-and-each-answer-shows-what-it-used.md)

## Context

A team wants formative feedback on work in progress that lives outside a repository, for example a board, a design or a model.
The source changes while people work on it, often cannot be read by the server, and several tools produce such work.
An observation is useful only when the team can open exactly what it was about, also after the source changed.
Practice Review already measures a pinned version of an item and verifies each citation against captured bytes.

## Decision drivers

- Never turn a capture failure or an unverifiable criterion into a claim about work.
- A team's work reaches only that team's current members.
- Every claim opens the exact evidence it rests on.
- One review path for every tool.
- No connector platform before two adapters show what they share.

## Considered options

1. **Review the live source.** The server reads the tool when it reviews. It needs a lasting provider grant for each person, evidence changes under the observation, and tools with no server API are excluded.
2. **A separate reviewer in each tool.** Each host then has its own records, authority checks and feedback.
3. **Treat an upload as a file in a created repository.** It invents commits, authors and access that do not exist.
4. **A team submits immutable versions to one contract, and Practice Review measures them (chosen).** Hephaestus stores the submitted work and its retention and erasure.

## Decision

1. **The submitted version is the reviewed thing.** The reviewed item has a kind that names its shape, and a practice runs only on its own kind. It belongs to one team project in one workspace: the workspace is the access and budget boundary, and the team project is the people and their work. Each version of the item is immutable and holds one or more snapshots. A change is a new version. Neither the item nor a version has a draft state.
2. **Snapshot, source revision and live link are three things.**
   - A snapshot is what was submitted and shown to the sender before it was sent. It is the evidence.
   - A source revision is recorded only when the server read it with the submitting member's own access.
   - A live link is a pointer. It is never fetched for a review and never evidence.
3. **Two authorities, both checked on the server.** Source authority says what the member could supply: either the member supplied the bytes, or the server read them as that member. Receiving-team authority is current team membership. It is checked at submit, request, capture, admission, delivery and every read. A host identity or a share link grants neither.
4. **Every entry route uses this contract:** a capture from inside a tool, an authenticated link import, an explicit upload and a pinned repository commit. A host starts a review and shows an authorized view of the same record. It keeps no second review.
5. **The server derives every anchor.** An anchor names a version, a snapshot and an element of it. The server derives the element from verified cited text. An observation can cite several anchors; each citation is verified on its own and stays within one element. An anchor resolves only in its own version. Nothing matches elements across versions.
6. **Missing evidence stays missing.** What was not captured is recorded. A criterion that needs it ends as undetermined or withheld, never as not met. A claim about visual qualities needs verified visual evidence. Until such evidence exists, the review says that it cannot verify visual criteria.
7. **Team observations belong to the team.** They create no person's observation, standing or score (ADR 0162 decision 4, ADR 0125). They reach no practice page, no shared source and no staff view that this ADR or a later one does not name. `Readers` gains a project team kind (ADR 0127).
8. **Only a team member records a decision** on a problem: open, acted on or dismissed. A new decision supersedes an old one.
9. **Every store has its reverse.** A version can be withdrawn, which deletes its content. Deletion and erasure paths ship before the first write.
10. **A named review setup chooses what is reviewed.** It selects existing practices of one item kind, names required and optional evidence slots, and states which team projects can use it. A review keeps a copy of its setup. Eligibility follows from the compiled definitions and the captured sources. No adapter selects practices and no model guesses a kind. A rationale never satisfies evidence about the work.
11. **The presentation is chosen once, at seal, and never read again as a choice.** `OBSERVATIONS` shows the sealed observations and their next steps, labelled as observations, with no composer run.
12. **A team composition is a later extension of ADR 0160, not a change of it.** `TEAM` is a producer chosen once at seal, only for a requested review, built on the mentor runtime after that runtime's release path is safe. Practice Review owns applicability, coverage, observations and the seal. The composer orders and words guidance about sealed problems, or says nothing; it creates no evidence and changes no result. It reads only this item's sealed rows, its previous review and the team's decisions, never a personal record. It has its own audience check at composition, commit and render, and its own erasure and withdrawal basis. It sends nothing proactively. ADR 0160's owner-only rules do not change.
13. **Each invariant has one owning module in the one deployment.**

    | Context | Module | Owns | Never owns |
    | --- | --- | --- | --- |
    | Capture | `integration.submission`, and one package for each tool | the typed immutable version, its elements and anchors, normalization, the source authority check | practices, applicability, observations, team policy |
    | Practice Review | `practices`, with runs hosted by `agent` | practice versions, review setups, applicability, execution, sealed observations, coverage, the presentation | tool types, membership |
    | Project | `project` | teams, membership, team decisions, the receiving-team authority | capture, observations |
    | Mentor | `mentor` | composition over sealed observations under the permitted scope, or silence | evidence, observations, membership |
    | Hosts and the web app | | the same authorized read | state of their own |

    - Modules meet through named interfaces and ports. Practice Review reaches work and its audience only through neutral ports in `integration.core`; it depends on no tool package and not on `project`. Tool types stay inside their capture package.
    - This amends ADR 0095 decision 1: `integration` also holds `submission` and one package for each capture tool. These packages have no provider connection. The import guard names them.
    - No new service and no plugin framework.

### What this does not decide

- Team growth records, commitments, check-ins and proactive mentoring stay in ADR 0162.
- Until decision 12 ships, a review of a team item takes no producer of ADR 0160: it writes no mailbox event, work comment, advice or contact. ADR 0160 keeps governing reviews about a person.
- The runtime shapes of a team composition (owner key, scope stamp, claims) belong to the mentor runtime's own decision.
- A later team mentor can follow an authorized team commitment and cite a team version only by reference with a current access check (ADR 0163), and show it under **What Hephaestus used** (ADR 0157). 1.0 conversations load none.
- Pending before acceptance, for the maintainer and the course owner: staff visibility, the AI choice that governs a team review, personal erasure against team custody, and retention after revocation.

## Consequences

- Good: one review path for every tool, and evidence that stays openable after the source changes.
- Good: no standing provider grant and no invented repository.
- Bad: Hephaestus now stores submitted work, so it owns its size limits, retention, withdrawal and erasure. ADR 0090 covers short-lived evidence copies, not this store.
- Bad: a member-supplied snapshot does not prove that the source holds it.
- Bad: each tool needs a normalizer, a server index and a read-only view.
- Bad: visual criteria stay unverified until visual evidence is designed.
- Bad: until a team composition exists, a team reads observations that nothing ordered or reworded for it.
- Bad: a review setup is one more thing for staff to maintain.

## Confirmation

Not enforced yet. The implementation must add:

- an authority table as tests: each route, an outsider, a removed member and a guessed id;
- one anchor test with two citations for each adapter, with one bad quote that refuses the candidate;
- a test that a renamed or deleted element still resolves in its own version;
- a test that a missing source withholds and never records not met;
- an architecture test that no person route, staff route, research export or public read returns a team observation;
- erasure and withdrawal tests before any writer;
- a test that a team item's seal starts no composer run, mailbox event or feedback row;
- before a team composition: tests that its prompt holds no personal record and that it writes no result or evidence;
- module and architecture tests: no review module imports a capture tool package or `project`;
- one review journey run with two capture adapters, with the same practice version, observation shape and composition input.

## Revisit trigger

Two shipped adapters need the same code outside their normalizer, index and view. Or teams need evidence that a text citation cannot carry.

## Sources

- W3C, [Web Annotation Data Model](https://www.w3.org/TR/annotation-model/) §§ 4.2 and 4.3: a selector names a segment of a resource, and a state names which version of a changing resource is meant. This ADR stores the version and derives the selector on the server, so no reader has to recover the state from the source.
