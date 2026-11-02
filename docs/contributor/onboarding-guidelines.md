---
title: Onboarding guidelines
description: How Hephaestus guides a new person at the moment of need, what each screen without data must say, and the patterns that it uses and rejects.
page_type: explanation
---

Hephaestus teaches through its own pages.
Each page explains itself, shows guidance in the place where it applies, and derives that guidance from the person's real state.
A guided tour, which a person starts, explains what the pages cannot show at once.
This page gives the rules for every change that adds guidance, an empty state, or a first-run step.
The general design principles and the motion rules are in [`webapp/DESIGN.md`](https://github.com/hephaestus-build/Hephaestus/blob/main/webapp/DESIGN.md).
This page applies them to onboarding and does not repeat them.

## The constraint

Three facts shape onboarding in Hephaestus.

1. **People come to do a task.** They skip and forget instructions that come before the task.
   A study of three games found that tutorials improved engagement only in the most complex game.
   The source is [Andersen et al., CHI 2012](https://grail.cs.washington.edu/projects/game-abtesting/chi2012/).
   Thus, an explanation must wait until the person wants it.
   Apple says that people should understand an app "simply by experiencing it" ([HIG Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding)).
2. **The value arrives later.** A review starts from configured events on the work, or from a request.
   A feedback card needs the same problem on at least two different pieces of work.
   On a first sign-in, a member often has no review yet, so pages about reviews have nothing to show.
3. **Feedback is about the reader's own work.** A wrong claim about their work costs trust, and a fast fix does not restore it.
   This is the first [non-negotiable](https://github.com/hephaestus-build/Hephaestus/blob/main/AGENTS.md#what-we-will-not-compromise-on).

## Rules

1. **Land on the home page.** A first sign-in opens the person's home: the Practice profile, or Activity where practices are off.
   Only a decision that gates the processing of their work comes first.
   These decisions are the terms, and the AI choice where the workspace asks for it.
   Apple says to postpone nonessential setup.
   Apple also says to give a clear reason for a permission that the app needs to function.
2. **Do not interrupt on load.** Do not open a modal dialog when a page loads.
   Show information and optional decisions in the page, near the thing that they are about.
   Apple says: "Avoid showing an alert when your app starts" ([HIG Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts)).
   GOV.UK has no modal component.
3. **Teach in the place of use.** Put an explanation next to the thing that it explains, at the moment that it applies.
   Apple prefers "context-specific tips instead of a single onboarding flow".
4. **Derive guidance from state.** Choose the text from the person's real state, such as "no review yet" or "No AI".
   Guidance that follows state changes by itself.
   It needs no "seen" record, no dismissal, and no export or erasure entry.
5. **Never invent a claim about someone's work.** Do not show sample feedback, a demo profile, or an invented review as the reader's own.
   Show an example only when it is clearly an example and is not about the reader.
   A practice's own guidance in its Guide tab is such an example.
   Products that mix samples into real data must warn against it ([Stripe sandboxes](https://docs.stripe.com/test-mode?locale=en-US), [Power Platform sample data](https://learn.microsoft.com/en-us/power-platform/admin/add-remove-sample-data)).
6. **Give one next step.** Each empty state gives the one action that fills it, with a direct link.
   The sources are [NN/g](https://www.nngroup.com/articles/empty-state-interface-design/) and [HIG Writing](https://developer.apple.com/design/human-interface-guidelines/writing).
   The [writing standard](./simplified-technical-english.md#empty-states) gives the wording rules.
7. **State only measured times.** If the person's own action starts the result, name that action and give no time.
   If the system works on the result, name the stage that the result waits for.
   Give a time only when the server measures it for this case ([NN/g long waits](https://www.nngroup.com/articles/designing-for-waits-and-interruptions/)).
8. **Do not let held-back feedback look like a clean result.** A proposal can wait for the approval of a workspace admin.
   The server hides such a proposal from the developer.
   Do not disclose such an item, and do not imply that nothing was found.
   State the fact about the workspace instead, such as "a workspace admin approves feedback before it is posted".
   `FeedbackState.VISIBLE_TO_DEVELOPER` owns what a developer may learn about an item.
9. **Keep crucial facts out of temporary places.** An empty state disappears when data arrives, and a toast disappears on a timer.
   Also put a fact that the person must keep in a place that stays.
   Such places are the legend, User settings, and the user docs.
   The sources are [HIG Writing](https://developer.apple.com/design/human-interface-guidelines/writing) and [HIG Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).
10. **Say who can see what.** Do not reassure with a general claim such as "only you can see this".
    Name each audience: feedback on a pull request is visible to everyone who can see that pull request.
    Workspace admins can see all practice reviews, and instance admins can open a read-only view.
11. **Keep one voice.** Hephaestus is the product. It has no mascot and no narrator.
    "I" belongs only to answers in a conversation ([product vocabulary](./practice-feedback-language.md)).

## Screens without data

Each stage below has a defined screen.
The screen states the cause, the one next step, and an honest expectation.
A pull request that adds or changes one of these screens adds a story for it.

| Stage | The screen must say | The next step | The expectation |
|---|---|---|---|
| Instance, no workspace | That the person is in no workspace | **Create workspace** if the creation policy allows it. Otherwise, name the instance admin. | The workspace appears when the installation finishes. |
| Workspace, no synced repository | Which repository is not connected or not synced yet | Connect the repository, or wait for the first sync | The sync progress that the server reports |
| Repositories, no practices or reviews off | That members get no reviews yet, and why | Choose practices, or turn on practice reviews | Reviews start from the events of the chosen practices. |
| Practices, no review yet | That no review has run in the workspace | **Review recent work**. An admin starts it. Hephaestus never starts it by itself. | Results appear on each member's Practice profile. Feedback on merged work is withheld. |
| Member, no work attributed | Which account the workspace uses | Connect that account | Work appears after the next sync. |
| Member, no review yet, a review can run | The events that start a review for the active practices, and where the result appears | Open a pull request, or read what the workspace reviews | No time. The person's own action starts it. |
| Member, no review can run | The reason from the server, such as reviews off, reviews on request only, outside the review scope, No AI, or no model for the choice | The direct link that removes the cause, or the role that can remove it | Reviews start after the cause changes. |
| Member, review queued or running | Which work the review is on, and its stage | Nothing | The stage, such as "Waiting" or "Running". No time. |
| Member, the workspace approves feedback first | That a workspace admin approves feedback before it is posted. Never say whether an item waits. | Nothing | No time. A person decides. |
| Member, first feedback | The card, which explains itself | The card's next step | The card counts what resolves it. |
| Ask Hephaestus, no review yet | What it can answer now, such as questions about the practices and the person's recent work | Select an example question about the practices. It fills the composer and does not send. | Answers that use observations or feedback wait for the first review. |

The server owns each fact that a screen states.
For example, the reason that no review can run comes from the review gate and the AI rule that admit a review.
A screen does not compute a rule a second time.
It does not state a time that the server did not measure.

## Patterns that we use

| Pattern | Use it when | Home |
|---|---|---|
| Empty state that teaches | A region is empty for a reason that the reader cannot see | `FeedbackEmpty`, `EmptyState` |
| One-sentence page intro and detail on demand | A page needs more than one sentence of explanation | `PageHeader` with an `InfoTip`, or a closed `Collapsible` for a legend |
| Definition at the point of use | A badge, a label, or a figure needs a definition | The description in its `StatusDefs` registry entry, or an `InfoTip` |
| A setup step | The product must not process the person's work without an answer | The terms page and the AI choice page |
| Notice in the page | The person must know or decide something, but not now | `Alert` as a region named by its title, first in the main content, one at a time |
| Setup list from real facts | An admin setting blocks value for members | One row for each fact, with its status and a link to the page that changes it |
| Example questions | The person sees an empty conversation | The conversation greeting. A question fills the composer. |
| Guided tour | A person wants the chain from work to feedback, or the levels under a page | `PracticeProfileTour`, started from the page or from an offer that follows state |

A legend starts closed when the page shows the meaning that the reader needs.
After the reader opens or closes it, it stays in that state until the reader changes it ([NN/g accordions](https://www.nngroup.com/articles/accordions-complex-content/)).

## Patterns that we reject

| Pattern | Reason |
|---|---|
| A tour that starts by itself on a first visit | It covers the page before the person has seen anything of their own, after other first-visit steps. See [the guided tour](#the-guided-tour). |
| A modal dialog for information or for an optional decision on load | Apple: "Avoid showing an alert when your app starts". GOV.UK has no modal component. |
| Sample feedback or a demo profile on the reader's own page | Rule 5 |
| A mascot or a narrator | Microsoft turned off its Office assistant by default in 2001 ([Microsoft](http://web.archive.org/web/20250401114434/https://news.microsoft.com/2001/04/11/farewell-clippy-whats-happening-to-the-infamous-office-assistant-in-office-xp/)). An assistant must be easy to call and easy to dismiss ([Amershi et al., CHI 2019](https://www.microsoft.com/en-us/research/publication/guidelines-for-human-ai-interaction/)). |
| A beacon, a hotspot, or motion that loops to get attention | It pulls attention from the task. This ban is stricter than [WCAG 2.2.2](https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide.html), which also allows a control to stop the motion. |
| A "seen" flag in browser storage that decides whether guidance appears | The guidance comes back on each device. Use state, or the server. |
| A tour library, or a spotlight over the live page | A spotlight needs a positioning engine and a cutout. The drawers also need a change to stay usable under it. A target that an engine finds by a selector or by text needs its own tests to stay correct. A spotlight also needs a populated page, which a new person lacks. Some libraries need an AGPL-3.0 or a paid license, such as [Shepherd.js](https://github.com/shipshapecode/shepherd) and [Intro.js](https://introjs.com/docs/getting-started/license). |
| A "What's new" dialog or a welcome dialog | The release notes own changes. A welcome dialog interrupts on load. |

## The guided tour

The Practice profile has a guided tour.
The page explains itself, but one page cannot show the chain from work to feedback, or the levels under it.
Decide each of the points below from what the person needs at that moment.
The research gives a direction for each point, not a limit.

- **Length follows the questions.** Each chapter answers one question that a person has at a known moment.
  An example is "How does my work become feedback?".
  Fewer people finish a longer tour.
  More people finish a tour that they start than one that starts by itself.
  These sources are vendor data, so only the direction holds: [Chameleon](https://www.chameleon.io/blog/mastering-product-tours) and [Produktly](https://produktly.com/research/saas-onboarding-benchmarks-2026).
  Thus, the person chooses a chapter and can leave and continue, and a chapter is as long as its answer.
- **The start follows the moment.** A person starts the tour, or accepts an offer that the page derives from state.
  The first-run state makes such an offer, and so does a page with a single open feedback card.
  An offer claims nothing about the reader's history.
  An offer that follows state ends by itself, so it needs no dismissal.
  The tour does not open by itself. The first visit already follows the terms, and perhaps the AI choice.
  If the offers prove too quiet, change them there.
- **A dialog is right when the person asked for it.** The tour is the one task on screen, so [`webapp/DESIGN.md`](https://github.com/hephaestus-build/Hephaestus/blob/main/webapp/DESIGN.md#design-principles) principle 3 gives it a dialog.
  The same dialog would be wrong if it opened on load.
- **Truth is structural.** A stage that shows the reader's page holds only the reader's real data.
  An example stands alone, under a caption that says it is an example, and the words say so too.
  This is firm: see rule 5.
- **Show real components.** A stage renders the components that the product renders, so it cannot drift from them.
  A component names a part with `data-tour-slot`, and a story walks every step and fails on a missing part.
  A schematic says that it is a schematic.
- **A real link hands over.** Where the reader can act on their own data, a link closes the tour and opens the real level.
- **Memory can stay in the browser when nothing returns because of it.** The tour stores only a place to continue and the finished chapters.
  A lost record costs a checkmark. A server record would add export and erasure entries for that.

These parts are firm, because they are not matters of taste:

- A complete keyboard path with no trap ([WCAG 2.1.2](https://www.w3.org/WAI/WCAG22/Understanding/no-keyboard-trap.html)).
- Focus that stays visible ([WCAG 2.4.11](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html)).
- Words that carry what each step teaches, because a screen reader cannot reach a picture.
- An announcement when the step changes ([WCAG 4.1.3](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)).
- Reduced motion: every scene shows its final state from the start.
- Lazy loading: the tour adds nothing to the entry chunk.

Apply the same reasoning to a future hint or tour.
State the failure that it prevents and why its length, shape, and start make sense there.

## Motion and accessibility

[`webapp/DESIGN.md` § Motion](https://github.com/hephaestus-build/Hephaestus/blob/main/webapp/DESIGN.md#motion) owns durations, easing, and reduced motion.
For onboarding, motion only shows where something came from or where it went.
A person can act before an animation ends ([HIG Motion](https://developer.apple.com/design/human-interface-guidelines/motion)).
Content that arrives with the page does not animate in.

If focus was inside a notice that leaves after an answer, focus moves to the page heading.
Under reduced motion, the notice leaves at once.
The saved answer stays visible in User settings, because a toast disappears on a timer.

## Checklist for a change

Before you add guidance, an empty state, or a first-run step, check these points:

1. The text follows a real state, and a story shows each state.
2. The server owns each fact that the text states.
3. No text claims a time that the server did not measure.
4. No example is about the reader's own work.
5. Nothing modal opens on load. A new hint or tour states its reasons, as [the guided tour](#the-guided-tour) does.
6. A fact that the person must keep also appears in a place that stays.
7. The keyboard, screen-reader, and reduced-motion paths work.

## Sources

These are the main sources:

- Apple Human Interface Guidelines: [Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles), [Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding), [Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts), [Writing](https://developer.apple.com/design/human-interface-guidelines/writing), [Motion](https://developer.apple.com/design/human-interface-guidelines/motion), [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility), [Offering help](https://developer.apple.com/design/human-interface-guidelines/offering-help).
- Nielsen Norman Group:
  - [Onboarding tutorials vs. contextual help](https://www.nngroup.com/articles/onboarding-tutorials/)
  - [Designing empty states in complex applications](https://www.nngroup.com/articles/empty-state-interface-design/)
  - [Modal and nonmodal dialogs](https://www.nngroup.com/articles/modal-nonmodal-dialog/)
  - [Designing for long waits](https://www.nngroup.com/articles/designing-for-waits-and-interruptions/)
  - [Tooltip guidelines](https://www.nngroup.com/articles/tooltip-guidelines/)
  - [Accordions](https://www.nngroup.com/articles/accordions-complex-content/)
- GOV.UK Design System: [Notification banner](https://design-system.service.gov.uk/components/notification-banner/), [Task list](https://design-system.service.gov.uk/components/task-list/), [Complete multiple tasks](https://design-system.service.gov.uk/patterns/complete-multiple-tasks/), [Confirmation pages](https://design-system.service.gov.uk/patterns/confirmation-pages/). Service Manual: [Writing for user interfaces](https://www.gov.uk/service-manual/design/writing-for-user-interfaces).
- W3C: [WCAG 2.2](https://www.w3.org/TR/WCAG22/), [ARIA APG dialog pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/).
- Research: [Andersen et al., CHI 2012](https://grail.cs.washington.edu/projects/game-abtesting/chi2012/), [Amershi et al., CHI 2019](https://www.microsoft.com/en-us/research/publication/guidelines-for-human-ai-interaction/).
- Design systems: [Carbon empty states](https://carbondesignsystem.com/patterns/empty-states-pattern/), [Primer empty states](https://primer.style/product/ui-patterns/empty-states/), [Google PAIR mental models](https://pair.withgoogle.com/chapter/mental-models/).
- Products: [GitLab: Learn GitLab no longer a separate project](https://gitlab.com/gitlab-org/gitlab/-/issues/388970), [GitHub new user dashboard](https://github.blog/changelog/2025-06-12-new-user-dashboard-refresh), [Stripe sandboxes](https://docs.stripe.com/test-mode?locale=en-US), [Mattermost site health](https://docs.mattermost.com/administration-guide/manage/site-health-dashboard).
