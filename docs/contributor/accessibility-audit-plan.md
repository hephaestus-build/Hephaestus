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

- public, authenticated, loading, empty, error and permission-denied states;
- authentication, workspace creation, developer feedback, mentor and settings processes;
- workspace and instance administration for every supported role; and
- Hephaestus controls and presentation around imported content.

Linked sites and identity-provider pages are outside the application scope. The handoff to and return
from those pages remain in scope.

## Surface inventory

| Surface | Representative routes and states |
| --- | --- |
| Public and legal | `/`, `/about`, `/imprint`, `/privacy`, not found |
| Authentication | `/login`, workspace login, callback and error |
| Workspace creation | provider selection, GitHub, GitLab, validation and errors |
| Workspace home | dashboard, teams, achievements and user profiles |
| Developer feedback | reviews, trace, observations, feedback delivery and targets |
| Mentor | thread list, greeting, transcript, composer and copilot |
| Personal settings | settings, integrations and destructive actions |
| Workspace administration | members, practices, review operations, models, usage and integrations |
| Instance administration | users, workspaces, audit, catalogue, providers, models and usage |

Use the [audit record template](./accessibility-audit-record-template.md) to expand this inventory to
the routes, states and roles present in the tested revision.

## Evaluation

Record the revision, deployment, test data, tester, date, and exact operating-system, browser and
assistive-technology versions.

1. On every surface, use only the keyboard to check operation, focus visibility and order, skip
   navigation, overlay dismissal and focus restoration, and keyboard traps.
2. On every surface, inspect landmarks, headings, names, roles and states with NVDA and Firefox on
   Windows and VoiceOver and Safari on macOS. Complete every end-to-end process in both combinations,
   including validation and error recovery.
3. Build the structured and random samples required by WCAG-EM. Across those samples, evaluate every
   applicable Level A and AA criterion, including 200% zoom, 320 CSS-pixel reflow,
   [text spacing](https://www.w3.org/WAI/WCAG22/Understanding/text-spacing.html), contrast, use of
   colour, target size and motion. Record the results with the
   [WCAG-EM Report Tool](https://www.w3.org/WAI/eval/report-tool/).
4. Run axe on the integrated sampled pages and reconcile its results with the manual evaluation.

## Findings and completion

Link every failure to an issue with its context, reproduction steps, expected and observed behaviour,
user impact, environment, applicable WCAG criterion and evidence. Use GitHub assignment, priority and
milestones for ownership and scheduling. Check other callers when the failure is in a shared component.

`vp run --filter webapp test:storybook` runs axe against the maintained Storybook states in Chromium.
Retain its report, revision and browser version with the audit, and document any rule exclusions.

An AA conformance claim requires every scoped page and complete process to pass every applicable Level
A and AA criterion. Follow W3C's
[conformance-claim requirements](https://www.w3.org/TR/WCAG22/#conformance-claims) when updating the
public accessibility statement.
