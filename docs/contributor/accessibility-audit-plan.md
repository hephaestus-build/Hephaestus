---
title: Accessibility audit plan
description: Scope and evidence requirements for evaluating the web application against WCAG 2.2 AA.
---

# Accessibility audit plan

Evaluate the web application using
[WCAG-EM](https://www.w3.org/WAI/test-evaluate/conformance/wcag-em/) against
[WCAG 2.2 Level AA](https://www.w3.org/TR/WCAG22/).

## Scope

The scope is the Hephaestus SPA at one tested commit and deployment, including:

- Public, authenticated, loading, empty, error, and permission-denied states.
- Authentication, workspace creation, developer feedback, mentor, and settings processes.
- Workspace and instance administration for every supported role.
- Hephaestus controls and presentation around imported content.

Linked sites and identity-provider pages are outside the application scope. The handoff to and return
from those pages remain in scope.

## Surface inventory

| Surface | Representative routes and states |
| --- | --- |
| Public and legal | `/`, `/about`, `/imprint`, `/privacy`, not found |
| Authentication | `/login`, workspace login, callback and error |
| Workspace creation | provider selection, GitHub, GitLab, validation and errors |
| Workspace home | dashboard, teams and user profiles |
| Developer feedback | reviews, trace, observations, feedback delivery and targets |
| Mentor | thread list, greeting, transcript, composer and copilot |
| Personal settings | settings, integrations and destructive actions |
| Workspace administration | members, practices, review operations, models, usage and integrations |
| Instance administration | users, workspaces, audit, catalogue, providers, models and usage |

The dated audit record, filled from the [record template](./accessibility-audit-record-template.md),
expands this inventory to the routes, states and roles present in the tested revision. The latest is
[the 2026-10-03 record](./accessibility-audit-record-2026-10-03.md).

## Evaluation

Record the revision, deployment, test data, tester, date, and exact operating-system, browser and
assistive-technology versions.

1. On every surface, use only the keyboard to check these behaviors:
   - Operation.
   - Focus visibility and order.
   - Skip navigation.
   - Overlay dismissal and focus restoration.
   - Keyboard traps.
2. On every surface, inspect landmarks, headings, names, roles, and states with both combinations:
   - NVDA and Firefox on Windows.
   - VoiceOver and Safari on macOS.

   Complete every end-to-end process in both combinations, including validation and error recovery.
3. Build the structured and random samples required by WCAG-EM.
   Across those samples, evaluate every applicable Level A and AA criterion.
   Include these checks:
   - 200% zoom.
   - 320 CSS-pixel reflow.
   - [Text spacing](https://www.w3.org/WAI/WCAG22/Understanding/text-spacing.html).
   - Contrast and use of color.
   - Target size and motion.

   Record the results with the [WCAG-EM Report Tool](https://www.w3.org/WAI/eval/report-tool/).
4. Run axe on the integrated sampled pages.
   Reconcile its results with the manual evaluation.

   `vp run --filter webapp test:e2e accessibility.spec.ts` does this against the built application and a running server.
   It checks:
   - Every route in both color schemes.
   - The page title.
   - A Tab walk.
   - Reflow at 320 and 640 CSS pixels.
   - Looping motion.
   - Menus and dialogs.
   - The announcement of a page change.
   - Three complete keyboard-only processes.

   It decides nothing that NVDA or VoiceOver decide.

## Findings and completion

Link every failure to an issue.
Include its context, reproduction steps, expected and observed behavior, user impact, environment, applicable WCAG criterion, and evidence. Use GitHub assignment, priority and
milestones for ownership and scheduling. Check other callers when the failure is in a shared component.

`vp run --filter webapp test:storybook` runs axe against the maintained Storybook states in Chromium.
Retain its report, revision and browser version with the audit, and document any rule exclusions.
A story shows a component alone.
A defect that appears only where components meet needs a story that renders them together.
One example is a control nested in another.

Record a check that did not run as `Not tested`, never as `Not applicable`.
List it under *Remaining work* in the record. A statement that claims conformance has no `Not tested` rows.

An AA conformance claim requires every scoped page and complete process to pass all applicable Level A and AA criteria. Follow W3C's
[conformance-claim requirements](https://www.w3.org/TR/WCAG22/#conformance-claims) when updating the
public accessibility statement.
