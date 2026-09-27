# ADR 0046: The browser extension shows its report in the page and confirms changes in its own window

**Status:** Accepted
**Date:** 2026-09-27
**Authors:** Felix T.J. Dietrich

## Context

The Chrome extension ([ADR 0045](0045-installed-clients-sign-in-with-a-pkce-handoff.md) covers its
sign-in) first showed one identical **Hephaestus** button in the provider's header, opening a popover
with practice outcomes and links to the web app. It said nothing until clicked, covered the work it
described, and showed process data — a matrix of practice outcomes — instead of what a developer or an
admin can act on: the observations, the next step, the evidence, and what became of the feedback.

Two constraints stand. Anything in the provider's DOM is readable by the provider's scripts, so
private review data must stay in an extension-origin frame. And the provider page can position, size
and cover that frame to misdirect a real click, so the frame cannot be trusted to confirm a change.

## Decision drivers

- **Useful at a glance, detail on request.** The review's state is visible where the work is read;
  details open in the page's flow, not over it.
- **Feedback first, for everyone.** What a reader acts on is the feedback posted for them. The
  provider's comments are where it is read and answered, so the report points to them rather than
  repeating them; observations and the review's operation are one deliberate step away.
- **Nothing private in the provider DOM**, including through geometry: the collapsed report is the same
  height in every state.
- **No clickjackable mutation.** A change is confirmed where the page cannot reach.
- **Honest about what the lookup sends.** The address of each supported work page is sent once the
  reader has allowed the exact site, a list row's only when the reader asks, and the disclosure says
  so.

## Considered options

1. **Header button and popover** (the previous design). Rejected: no information scent, covers the
   work, and most of its code solved overlay geometry.
2. **A provider sidebar section.** Too narrow for evidence, and collapsed or moved on narrow layouts.
3. **Line annotations in the diff.** Fragile against virtualised diffs, needs a frame per annotation,
   and evidence often cites other revisions or non-code sources.
4. **A report section in the work's content column**, in one extension-origin frame, with review
   changes confirmed in a top-level extension window. Chosen.

Within the chosen section, and on lists:

- **Every surface the same report**, with the admin's delivery ledger, runs and every developer's
  observations as nested disclosures inside it. Rejected: a list row then holds a detail page that
  needs a second click, the work page becomes a small dashboard, and it duplicates the web app's
  reviewed-work output page. Only the report's line opens; its content is flat.
- **A hovercard on list rows.** Rejected for the same overlay reasons as option 1, and hover would look
  work up without intent.
- **One line inside the pressed row**, with the report's sentence and links to the first comments.
  Chosen for lists.

## Decision

- The content script inserts one generic host into a known slot of the work's content column — after
  the description, with a merge request's reports, at the top of a work item's activity, or above the
  changed files — and nowhere else; a page without a known, visible slot gets no report. The host
  holds the extension-origin frame, whose collapsed line is one height in every state.
- The frame looks the work up as soon as it loads, on sites the reader allowed. The worker resolves
  the tab's work and reads, for every reader including admins, the comments Hephaestus recorded
  posting on it for them (`getOwnDeliveredWorkFeedback`): one per provider comment, with its practices,
  delivery time and a permalink only when it is an anchor on the work's own pages, and never a body.
  The collapsed line says what a review is doing now, how many such comments there are, and when a
  review last recorded a result.
- Opened, the report is flat, as a merge request report's second level: the reader's comments, each
  one link to that comment on the provider; then the reader's own observations on that exact work (a
  paired `artifactKind`/`artifactId` filter), loaded when the report opens, each its practice, outcome
  and one sentence; then the precise review time and commit, and links into the web app. Nothing
  inside opens further. The worker checks each returned record is about that work.
- The page shows every reader only their own records, admins included, and no feedback text at all.
  Every developer's observations, the feedback's delivery, the review runs — with cancelling and
  retrying — and the review's history are the web app's: **Open in Hephaestus** leads to the work's
  review activity, and an admin's **Review details** to the work's reviewed output. The only change
  the page can start is a review request.
- On a repository's list of pull requests, merge requests or issues, each row recognised by the
  provider's own title hook gets one generic inspect control, and nothing is looked up until the
  reader presses one. Then one unboxed line opens inside that row, filled at once — the report's
  sentence and links to the first comments — with nothing to open and no request: a list is for
  triage. Because the page can frame a preview itself, the line never wraps, so its height follows the
  reader's font size and never what it says. The row's address reaches the frame through the page, so
  it is only a selector: the worker accepts it only as a canonical work address of the repository and
  kind of the list the tab actually shows, re-reads the tab after every await, and answers only the
  line and the reader's comments.
- The frame never sends a review change. It asks the worker to open `action.html`, a top-level page
  that is not web-accessible. The worker binds one pending intent to the session generation, the
  source tab and work, the run, and the confirmation tab it creates; the window re-reads it, and
  confirming consumes it atomically before the single request. An unanswered request is reported as
  unknown and never retried. Approving or rejecting feedback stays in the web app.
- The reader's choices in a report — opened or not, which workspace — are kept by the worker per tab,
  work and session generation; nothing the page passes to the frame can set them.

## Consequences

- The report adds bounded height to the page when opened; that is the reader's choice.
- Each provider slot is a dependency on provider markup. Slots are recorded from real pages and
  mirrored in fixtures; a redesign removes the report from that page rather than misplacing it.
- The automatic lookup is a disclosed data flow: onboarding, settings, the privacy page, the record of
  processing and the store listing describe it. A list row's lookup is disclosed the same way.
- The first thing a reader sees depends on the server recording each posted comment's provider
  handle and, where it can, its address. A comment without a recorded address is listed without a
  link rather than with a guessed one, and the count is of recorded comments, not a claim about the
  provider's timeline.
- List rows are another dependency on provider markup, handled the same way: an unknown list shape
  gets no controls.

## Revisit trigger

A provider offers a supported extension surface for contextual content, or users need line-level
evidence that navigation from the report cannot give.
