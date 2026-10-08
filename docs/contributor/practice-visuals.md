---
title: Practice visuals and guides
description: The style guide for the visual and the Read more guide of a bundled practice.
---

# Practice visuals and guides

This page is the style guide for the **practice visual** and the **practice guide** of a bundled practice.
Admins who write their own visuals can use it too.
The [product vocabulary](./practice-feedback-language.md) defines both terms.
[ADR 0052](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0052-practice-visuals-and-guides.md) records why they are inline SVG and Markdown.

Other pages own the related facts:

- [Add a visual and a guide](/admin/writing-practices#add-a-visual-and-a-guide) owns the markup that the server accepts, the limits, and the `pv-*` color classes.
- [Bundled visuals and guides](./practice-catalogue.md#bundled-visuals-and-guides) owns the files and the catalog fields.

## Purpose

The visual shows the one idea of the practice at a glance.
The developer sees it each time that they open the practice.
The guide is for a developer who wants more depth.

Heph and screen readers get the descriptions, not the pictures.
Thus, a description must give the full meaning of its picture.

## Canvas

| Picture | viewBox |
| --- | --- |
| Visual | `0 0 640 320` |
| Figure | `0 0 640 H`, where `H` is 480 or less |

- Do not set `width` or `height` on the root. The panel scales the picture to its column.
- Keep at least 16 units free at the left and right edges.
- Align shapes to whole units.

## Color

- Give every shape and every text a `pv-*` class.
- Do not write color values. A fixed color does not change with the theme.
- Use the accent for the one thing that the reader must see. Use `pv-fill-accent-soft` for a region, such as a band.
- Use muted colors for context. Use `opacity` to show that something fades, such as lines that a reviewer skims.

`PRACTICE_SVG_THEME` in `webapp/src/components/practice-guidance/PracticeVisual.tsx` maps each class to a theme token.

## Typography

Text in the picture inherits the font of the app.

- Use `font-size` 15 or more.
- Use 18 to 20 with `font-weight="600"` for titles.
- Use 15 to 17 for labels.
- Use at most about 12 words in a visual.
- Write labels in sentence case, in the product voice, with no period.
- SVG text does not wrap. Put each line in its own `<text>` or `<tspan>`.

## Shapes

Use a small set of shapes so that every visual reads the same way:

| Shape | Means | How to draw it |
| --- | --- | --- |
| Rounded bar | A line of code, or a file | `rect` with height 10 and `rx="3"` |
| Card | A change, an issue, or a document | `rect` with `rx` 10 to 12, `pv-fill-surface`, and `pv-stroke-line` |
| Dashed line | A limit or a threshold | `line` or `path` with `stroke-dasharray` |
| Bracket | A range, such as what a reviewer reads | A `path` with two short end ticks |
| Arrow | A direction, a cause, or an order | One `path` for the line and one filled `path` for the head, for example `<path class="pv-fill-accent" d="M436 100l-7-5v10z"/>` |

Draw code as bars.
Show exact code text only when that text is the point, and keep it short.

## Composition

Use one of three patterns.
Each picture shows one idea.

1. **Contrast.** Put work that does not meet the practice on the left and work that meets it on the right. Draw both at the same scale. Let them differ in one decisive fact.
2. **Mechanism.** Show a cause and its effect from left to right. Use an arrow or a band to connect them.
3. **Sequence.** Show numbered steps from left to right with arrows between them. Use this pattern only when the order is the point.

The visual of `scope-one-reviewable-change` combines contrast and mechanism.
Its figure `split-order.svg` is a sequence.

## What not to draw

- Do not draw decoration, mascots, people, faces, logos, clip art, or emoji. Decoration that is not relevant makes learning worse.
- Do not show a difference with color alone. Give each difference a label, a shape, or a position too.
- Do not animate.
- Do not show a number, a limit, or a requirement that the practice criteria do not state. For example, do not draw a line limit for a practice that has none.

## Descriptions

- Say what the picture shows and what it means.
- Use the product voice and full sentences.
- Do not start with "Image of" or "Picture of". A screen reader already says that it is an image.

## Guide

A bundled guide uses these level-2 headings, in this order:

1. `## How to do it`
2. `## When it does not apply`
3. `## Common mistakes`
4. `## Sources`

- Write in the product voice. Address the developer as **you**.
- Keep the guide to about 400 words, without the sources.
- Make every statement agree with the practice criteria. A guide cannot add a requirement.
- Make **When it does not apply** agree with the **Occasion** section of the criteria.
- In **Common mistakes**, describe wrong ways to apply the practice. Do not describe flaws of a person.
- Put each figure after the paragraph that it supports.

### Sources

- Cite a standard, a peer-reviewed study, or a recognized practitioner guide.
- Use `https` links.
- Name the author or the organization and, for a study, the year.
- If a source comes from a company that sells a related product, say so in the entry.
- Classify the claim correctly. The [catalog curation rules](./practice-catalogue.md#selecting-a-practice) give the classes.

## Checks

`scripts/practice-guidance.test.ts` checks the canvas, the color classes, the description openings, the guide headings, and the source links of each bundled visual and guide.
It also fails for a file in the guidance folder that no practice uses.
The workflow `practice-guidance-links.yml` checks the web links of the bundled guides on a pull request that changes them, and every week.

A reviewer checks the rest by eye:

- The visual shows one idea, and no picture states more than the criteria.
- No difference depends on color alone.
- Each description says what its picture shows and what it means.
- **When it does not apply** agrees with the **Occasion** of the criteria.
- Each source is authoritative.

## Preview a visual or a guide

1. In Storybook, open `practice-guidance/PracticeIntro`, `practice-guidance/PracticeVisual`, and `practice-guidance/PracticeGuideMarkdown`.
2. Check each picture in the light theme and in the dark theme.
3. For the admin editor, open `admin/practice-editor/PracticeVisualEditor` and `admin/practice-editor/PracticeGuideEditor`.

GitHub previews `guide.md` with its figures, because the figure paths are relative to the guide.
GitHub does not apply the `pv-*` classes.
Thus, the GitHub preview checks the paths and the text, not the colors.
