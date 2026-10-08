# ADR 0052: Practice visuals and guides are themeable inline SVG and Markdown

**Status:** Accepted
**Date:** 2026-10-08
**Authors:** Felix T.J. Dietrich

## Context

A practice explains itself to a developer with two short text fields: **Why it matters** and **What good looks like**.
Many practices are about the shape of work, such as the size of a change or the order of steps.
Text alone shows that shape badly.
A developer who wants more than two sentences has no place to go in the product.

Research on instruction supports a picture under conditions:

- Two cases that differ in one decisive fact help a learner find the principle [1].
- Decoration that is not relevant makes learning worse [2].
- A static picture is as good as an animation, unless the topic is a process in time [3, 4].
- Help that a novice needs can slow an expert down [5]. Thus, the expert must be able to skip it.
- Progressive disclosure shows the main idea first and the detail on request [6].
- A complex picture needs a text description [7].

The webapp has a light and a dark theme.
A picture that admins can upload is user-supplied markup.
OWASP ASVS 4.0.3 requirement 5.2.7 requires that the application sanitizes, disables, or sandboxes scriptable SVG content [8].

## Decision drivers

- A picture follows the app theme in light and dark mode.
- An admin can change a picture as easily as a text field, at every scope.
- A picture never changes how a practice is reviewed.
- Uploaded markup cannot run code or load anything from outside.
- A developer and Heph can get the meaning of a picture without seeing it.
- The cost to maintain the content stays bounded.

## Considered options

What a picture is:

1. **SVG shown through `<img>`.** Rejected. An image document cannot read the classes of the page, so it cannot follow the theme.
2. **A raster image.** Rejected. It cannot follow the theme, it is blurred at other sizes, and an admin cannot adjust it as text.
3. **Lottie, Rive, or Mermaid.** Rejected. Each adds a large runtime and its own theme model. Animation adds no value for a static idea [3, 4].
4. **Structured JSON cards that React components draw.** Rejected. The result is not visual enough, and an admin cannot adjust it as artwork.
5. **Inline SVG whose shapes take theme colors from `pv-*` classes.** Chosen.

Where the long explanation lives:

1. **MDX.** Rejected. MDX executes code.
2. **Long articles in a separate education area.** Rejected. Readership is low and maintenance is high.
3. **Markdown with a few SVG figures, behind "Read more" on the practice panel.** Chosen. A **Sources** section links to authoritative work instead of a long article.

How visuals are made:

1. **An AI job in Hephaestus.** Rejected. An admin can use any AI tool with a documented prompt. A person must review the result before it is saved.
2. **People draw them, with a style guide, a starter template, and a prompt.** Chosen.

Where the bundled files live:

1. **Split the whole catalog into one folder for each practice.** Rejected. That change does not depend on this decision, and it changes every consumer of `default-catalog.json`.
2. **`default-catalog.json` names the files. The files live in `practices/guidance/<slug>/`.** Chosen.

## Decision

A practice definition has two optional developer guidance fields at all three scopes.
A **practice visual** is one SVG with a description.
A **practice guide** is Markdown with SVG figures.
The practice panel shows the visual between **Why it matters** and **What good looks like**, and the guide behind **Read more**.
A developer can hide the introduction of a practice.

The fields follow the rules of other developer guidance:

- Each change creates a revision. A release proposal offers each field separately.
- The definition digest includes both fields. The review-rule fingerprint excludes them, so a change keeps the recurrence chain.
- The review model never receives them. Heph receives the descriptions and the guide text.

`PracticeGuidanceRules` is an allowlist of shape and text elements and of geometry and presentation attributes.
The server applies it when a definition is saved and when the bundled catalog loads.
It refuses ids, styles, links, `url(...)` references, and a DOCTYPE.
The webapp sanitizes the markup again with DOMPurify [9] and draws it inline.

[Add a visual and a guide](../admin/writing-practices.mdx#add-a-visual-and-a-guide) owns the limits and the classes.
[Practice visuals and guides](../contributor/practice-visuals.md) is the style guide.

## Consequences

- One file follows both themes.
- Revisions, release proposals, and the reset to the Hephaestus default work as they do for text fields.
- The allowlist refuses SVG files from most drawing tools until a person removes ids, styles, and gradients.
- Without ids, a picture cannot use markers, gradients, masks, filters, or clip paths. An arrow is two paths.
- The server and DOMPurify each hold a copy of the rules, and the copies can drift. The server is the gate. DOMPurify is the second layer.
- Each bundled visual and guide is content that maintainers review like code.
- Heph explains a practice in the same words as the panel.

## Revisit trigger

- A theme change that the `pv-*` classes cannot express.
- A practice whose idea is a process in time, for which a static picture fails [3, 4].
- A security finding that requires a sandbox, such as an `<iframe>`, instead of inline markup.
- Evidence that developers do not open **Read more**, or that the guides cost more to maintain than they give.

## Sources

The titles below are quotations.

<!-- vale STE.Words = NO -->

1. Alfieri, Nokes-Malach and Schunn 2013, *Learning through case comparisons: a meta-analytic review*: <https://doi.org/10.1080/00461520.2013.775712>
2. Sundararajan and Adesope 2020, *Keep it coherent: a meta-analysis of the seductive details effect*: <https://doi.org/10.1007/s10648-020-09522-4>
3. Tversky, Morrison and Bétrancourt 2002, *Animation: can it facilitate?*: <https://doi.org/10.1006/ijhc.2002.1017>
4. Berney and Bétrancourt 2016, *Does animation enhance learning? A meta-analysis*: <https://doi.org/10.1016/j.compedu.2016.06.005>
5. Kalyuga, Ayres, Chandler and Sweller 2003, *The expertise reversal effect*: <https://doi.org/10.1207/S15326985EP3801_4>
6. Nielsen Norman Group, *Progressive disclosure*: <https://www.nngroup.com/articles/progressive-disclosure/>
7. W3C Web Accessibility Initiative, *Complex images*: <https://www.w3.org/WAI/tutorials/images/complex/>
8. OWASP ASVS 4.0.3, V5 Validation, Sanitization and Encoding: <https://github.com/OWASP/ASVS/blob/v4.0.3/4.0/en/0x13-V5-Validation-Sanitization-Encoding.md>
9. DOMPurify: <https://github.com/cure53/DOMPurify>

<!-- vale STE.Words = YES -->
