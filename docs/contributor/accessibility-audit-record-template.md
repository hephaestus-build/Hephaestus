---
title: Accessibility audit record template
description: Evidence template for the WCAG 2.2 AA web application audit.
---

# Accessibility audit record template

Create a dated record for each audit, named `accessibility-audit-record-<date>.md`, and retain it with
the release evidence. Publish only public-safe information required by the accessibility statement.

Copy this file, fill every cell, and delete no section. A blank cell is a result nobody recorded, so
write `Not tested` instead.

## Results

Every result cell holds one of these four words.

| Result | Meaning |
| --- | --- |
| `Pass` | Someone ran the check in every environment the row names and found nothing. |
| `Fail` | The check found a barrier. Link the issue or the change that fixed it. |
| `Not tested` | Nobody ran the check. Give the reason. It counts against a conformance claim. |
| `Not applicable` | The surface has no such content or step. Explain why. |

`Not tested` and `Not applicable` are different answers. The first says the evidence is missing; the
second says there is nothing to gather. A check that could not be run, because the tester, the
assistive technology or the test data was missing, is `Not tested`.

When a result differs between environments, or ran in only some of them, name the environment in the
cell, for example `Fail (E2)` or `Pass (E1)`.

## Audit

| Field | Value |
| --- | --- |
| Dates | |
| Revision and release | |
| Scope and sample rationale | |
| Deployment and test data | |
| Evaluator | |
| WCAG-EM report | |
| Storybook report, revision and browser | |
| Finding query | |

## Environments

Give each environment an ID (`E1`, `E2`, …) and list what was run in it. An environment that was
planned and not run stays in the table as `Not tested`.

| ID | Operating system | Browser | Assistive technology | Versions and relevant settings | Run |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## Findings

One row per failure, with the issue or change that fixes it and the story or test that fails without
the fix.

| Finding | Surface | Criterion | Barrier | Fix or issue | Coverage |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## Surfaces

`Automated` is the axe run, plus the title, reflow and motion checks. `Keyboard` is a Tab walk of the
surface: order, focus indicator, focus not hidden and no trap. Link each failure to its issue.

| Surface | Route, state and role | Automated | Keyboard | NVDA | VoiceOver | Evidence or issue |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

## Complete processes

One row for each process in each environment. A process is complete when a person finishes it from
the first step to the last, including the error they make on the way.

| Process | Roles and states | Environment | Success path | Error recovery | Evidence or issue |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## Remaining work

List each `Not tested` result and what a tester has to run to close it.
