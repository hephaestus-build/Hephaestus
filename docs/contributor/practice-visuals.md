---
title: Practice visuals and guides
description: The style guide for the visual and the guide of a bundled practice.
---

# Practice visuals and guides

This page is the style guide for the **practice visual** and the **practice guide** of a bundled practice.
Admins who write their own visuals can use it too.
The [product vocabulary](./practice-feedback-language.md) defines both terms.
[ADR 0054](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0054-practice-visuals-and-guides.md) records why they are inline SVG and Markdown.

Other pages own the related facts:

- [Add a visual and a guide](/admin/writing-practices#add-a-visual-and-a-guide) owns the markup that the server accepts, the limits, and the `pv-*` color classes.
- [Bundled visuals and guides](./practice-catalog.md#bundled-visuals-and-guides) owns the files and the catalog fields.

The rules below are strict.
All bundled practices use one grammar, so a developer learns it once.
The [starter templates](/admin/writing-practices#start-from-a-template) are the reference for the contrast pair, the fork, and the sequence.
The visual of `scope-one-reviewable-change` is the reference for the [whole versus parts](#whole-versus-parts) pattern.
Its figure is the reference for a sequence figure.

## Purpose

The practice panel shows the lead (**Why it matters**), then the visual, then the caption (**What good looks like**).
The [product vocabulary](./practice-feedback-language.md) defines lead, caption, and lapse.
The visual shows the one idea of the practice before the caption names it.
The guide is for a developer who wants more depth.

- Draw one idea: the **REVIEW FOCUS** of the practice criteria.
- Show a relation, an order, or a structure. Never draw only to decorate.
- Test each mark. If you remove it and the picture says the same thing, delete it.
- Draw nothing that the criteria do not state: no count, no threshold, no limit, and no format.
- Never repeat a whole clause of the lead or the caption in a label, a description, or a guide.

Hephaestus and screen readers get the descriptions, not the pictures.
Thus, a description must give the full meaning of its picture.

## Clear at a glance

A developer looks at a visual for a few seconds.
The developer has not read this page, so the picture must explain itself.

- Show the developer's own work, such as a pull request title, a review comment, or a line of code. Real content is clearer than a symbol.
- Make each mark explain itself. A mark is text, a code token, a control with a label, or a context line that nobody must read.
- Do not let a context line carry the difference. If the difference is in the lines, write the words.
- Do not draw a mark that needs a legend. Examples are a dot with no label, a lone dashed tick, and a glyph with no word.
- Name a piece of work with words, such as "Pull request", "Issue #57", or "Commit". A kind glyph can stand next to the word, but it does not replace the word.
- Write consequence labels in the words of a developer, such as "Reviewer guesses why".

Do a cold read before you add a picture.
Show only the practice name and the picture to a person who did not draw it.
That person must say what each side shows and which side the practice asks for.
If that person needs the caption to understand the picture, draw it again.

## Canvas

Each picture has the viewBox `0 0 640 H`.
`H` is 320 or less for a visual and 480 or less for a figure.
Each pattern sets its height:

| Picture | `H` |
| --- | --- |
| Visual, contrast pair or whole versus parts | 136 to 224 |
| Visual, fork | 104 |
| Visual, sequence | 200 |
| Figure | 76 to 248 |

- Do not set `width` or `height` on the root. The panel draws the picture at most 560 px wide (40em beside its 14 px text). It never draws larger than its canvas. A label at 16 then shows at 14 px. A label at 15 shows at about 13 px, the size of the words around it. In a narrower column the picture shrinks with it.
- Set `H` to the bottom of the lowest mark. Do not add space below it. When the lowest mark is a label, `H` is its baseline plus 4. `H` does not have to be a multiple of 8.
- Draw to the edges. Put the first title at the top and the outermost shapes at x 0 and x 640.
- In the fork, the shapes end at x 448, and the outcome labels end the picture. Write the outcome labels long enough to reach toward x 640.
- On a phone, the panel scales the picture to about 340 px, about half size. A label then shows at about 8 px.

## Color

Each class has one meaning in every visual and every figure.

| Class | Meaning | Use it for |
| --- | --- | --- |
| `pv-fill-accent`, `pv-stroke-accent` | What the practice asks for | The one part, path, or label that the practice wants. One meaning for each picture, in one or more places. Each [pattern](#composition) says where the accent goes. |
| `pv-fill-accent-soft` | A region that holds the accent | The fill of a required node, or one zone such as the checked side of a boundary. Always with an accent edge or a label. |
| `pv-fill-ink`, `pv-stroke-ink` | The subject and its names | Titles, labels that name a thing, the trigger node, a lapse part, kind glyphs, and a boundary. |
| `pv-fill-muted`, `pv-stroke-muted` | Context and absence | Content bars, frames, connectors, the lapse path, missing parts, and labels for context or a consequence of a lapse. |
| `pv-fill-surface` | Knockout only | The inside of an outlined shape, so that lines behind it do not show. It is the page color, never a card. |
| `pv-stroke-line` | A divider with no meaning | Do not use it in a bundled picture. It stays for the visuals that admins draw. |

Two facts about the theme set these rules.
Accent and muted have about the same lightness.
Line and accent-soft are too faint against the panel background to carry a meaning.
Their contrast is below the 3:1 of [WCAG 1.4.11](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).
`webapp/src/styles/theme-tokens.css` has the values.

- Give every accent mark a second channel: its example text, its position or its shape. Color alone never carries the difference.
- At phone width, text in a picture is small. The caption and the description carry the same meaning, so a reader who cannot read the picture still gets it.
- Show a lapse with muted shapes, a dashed mark or a lapse part. Add a consequence label if the pattern has one. There is no red and no "bad" color.
- Do not use `opacity`, `fill-opacity`, or `stroke-opacity`. The same opacity gives a different contrast in each theme.
- Do not write color values. `PRACTICE_SVG_THEME` in `webapp/src/components/practice-guidance/PracticeVisual.tsx` maps each class to a theme token.

The prompt in [Draw a visual with an AI assistant](/admin/writing-practices#draw-a-visual-with-an-ai-assistant) repeats these meanings, because an admin sends it alone.
When you change this table, change the prompt too.
If the prompt and this page disagree, this page is correct.

## Shapes

Use only these shapes.
Each shape means the same thing in all practices.
Each snippet is drawn at its usual size, so copy it and change only the position and the width.

| Shape | Means |
| --- | --- |
| Code line | One line of code. |
| Prose line | One line of written text: a description, an issue body, a message. |
| Title bar | The title of a piece of work, or the heading of a section in a document. A muted code line above its prose lines. Ink only when the title itself is the difference. |
| Log line | One line of program output: a code line for its level and a prose line for its message. |
| Frame | One piece of work: a change, a commit, an issue, a test, or a document. The [product vocabulary](./practice-feedback-language.md) names the kinds of reviewed work. |
| View | One screen of an app. It replaces the frame when the work is a user interface. |
| Message | One chat message, one review comment, or one status update. The sharp corner points to its author. |
| Kind glyph | The kind of a frame: a change, an issue, a commit, a document, a test, or a package. Always next to a word that names the work. |
| Asked-for part | The part that the practice asks for, as its example in accent text. Accent lines only when no short text can show it. Outside a container, the shape itself with an accent outline. |
| Lapse part | The part that goes against the practice, such as a swallowed error, as its example in muted text. |
| Missing part | A part that the practice asks for and that is not there. A dashed muted outline of the part, with a muted label that names what is missing, such as "No test". |
| Check box | One acceptance criterion or one sub-task, with the name of the item. Empty is open. A check is done. |
| Tag | One label on a piece of work, such as a type, a priority, or a state. |
| Person | The author of a message, or the person who owns or approves a piece of work. Always with a label that names the role. |
| Approval | One approval on a change: a check with the word "Approved". |
| Control | One button in a view. An icon-only control is a circle. |
| Node | One step or one response in a flow. |
| Bracket | A range of lines that someone reads, checks, or skips. |
| Arrow | Order, cause, or flow, and nothing else. |
| Link | A reference with no direction, such as a change that names its issue. |
| Step marker | A position in an order. Only in the sequence pattern. |
| Region | One zone that holds the accent, such as the checked side of a boundary. |
| Boundary | A wall that the criteria name, such as a trust boundary or a layer. Always with a label on each side. |

Many practices show the same subjects.
Draw each subject with these shapes and no others:

| Subject | Shapes |
| --- | --- |
| Code with one line that matters | A frame of code lines. The line is an asked-for part or a lapse part. |
| A test | A frame with the test glyph. |
| Program output | Log lines in a frame. |
| A question and its answer | A thread of two messages. |
| A status update | A message that starts with a tag for its state. |
| A dependency | The package glyph, with the name of the package as a code token. |
| A document with headings | A frame with the document glyph, then a title bar and prose lines for each section. |
| A decision record | A document with headings and the label "Decision". |
| A change that names its issue | A change frame and an issue frame, joined by a link. |
| A checklist | Check boxes, each with a prose line. |
| An app screen | A view. |
| A permission prompt | A view that shows only the prompt: a title bar, its reason as prose lines, and a control. |
| An app in each appearance | The [appearance](#appearance) variant. |

### Lines

Code lines and a prose line:

```svg
<rect class="pv-fill-muted" x="16" y="48" width="112" height="10" rx="3"/>
<rect class="pv-fill-muted" x="16" y="64" width="88" height="10" rx="3"/>
<rect class="pv-fill-muted" x="16" y="82" width="232" height="6" rx="3"/>
```

Vary the widths in steps of 8, so that a block looks like text.
Indent a code line by 16 for each level of nesting.
Draw code and prose that only give context as bars.

Show the difference itself as real content: on each side, one example at the place that differs.
An example is 6 words or fewer, or one code token such as `try?`.
Color the practice's example accent and the lapse's example muted.
Put no rule or other mark next to an example, because the text itself marks the difference.
Use an accent bar for the difference only when no short text can show it.
Give a code token the class `pv-mono`, so that it shows in the code font of the app.

Example pairs: `fix` and `Validate email before invite` for commit subjects, `Task { … }` and `.task { … }` for a task that outlives its view, `Fix this` and `This throws on an empty list` for a review comment.
With the titles covered, the two examples must still show the difference.

Log lines:

```svg
<rect class="pv-fill-muted" x="16" y="48" width="32" height="10" rx="3"/>
<rect class="pv-fill-muted" x="56" y="50" width="160" height="6" rx="3"/>
<rect class="pv-fill-muted" x="16" y="64" width="32" height="10" rx="3"/>
<rect class="pv-fill-muted" x="56" y="66" width="128" height="6" rx="3"/>
```

Stack log lines at the code line pitch.

### Containers

A frame, a view, and a message are the containers.
Only a container holds other shapes.
Never put a container in a container or on a region.
Never draw a box around a whole pane or around a group of shapes.

Frame, with its kind glyph and its label:

```svg
<rect class="pv-fill-surface pv-stroke-muted" x="0.75" y="32.75" width="142.5" height="72.5" rx="8" stroke-width="1.5"/>
<g transform="translate(16 48)"><path class="pv-stroke-ink" d="M5.75 0.75h4.5M6.75 0.75v5L1.6 13.5a1.2 1.2 0 0 0 1 1.75h10.8a1.2 1.2 0 0 0 1-1.75L9.25 5.75v-5" fill="none" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></g>
<text class="pv-fill-ink" x="44" y="61" font-size="15">Test</text>
<rect class="pv-fill-muted" x="16" y="80" width="96" height="10" rx="3"/>
```

Put the glyph in the top left corner of the content, and the rest of the content 16 below the glyph.
Leave out the glyph and its label when the title bar or the pattern already names the kind.

View, with a muted bar at its top, a control, and an icon-only control:

```svg
<rect class="pv-fill-surface pv-stroke-muted" x="0.75" y="32.75" width="94.5" height="158.5" rx="12" stroke-width="1.5"/>
<rect class="pv-fill-muted" x="32" y="48" width="32" height="6" rx="3"/>
<rect class="pv-fill-surface pv-stroke-muted" x="16.75" y="152.75" width="62.5" height="22.5" rx="12" stroke-width="1.5"/>
<circle class="pv-fill-surface pv-stroke-muted" cx="28" cy="124" r="11.25" stroke-width="1.5"/>
```

Put the content of a view 16 below its bar.

Thread of two messages:

```svg
<path class="pv-fill-surface pv-stroke-ink" d="M36.75 32.75H259.25A12 12 0 0 1 271.25 44.75V67.25A12 12 0 0 1 259.25 79.25H24.75V44.75A12 12 0 0 1 36.75 32.75Z" stroke-width="1.5"/>
<rect class="pv-fill-muted" x="40" y="48" width="200" height="6" rx="3"/>
<rect class="pv-fill-muted" x="40" y="60" width="144" height="6" rx="3"/>
<path class="pv-fill-surface pv-stroke-muted" d="M60.75 88.75H283.25A12 12 0 0 1 295.25 100.75V123.25A12 12 0 0 1 283.25 135.25H48.75V100.75A12 12 0 0 1 60.75 88.75Z" stroke-width="1.5"/>
<rect class="pv-fill-muted" x="64" y="104" width="184" height="6" rx="3"/>
<rect class="pv-fill-muted" x="64" y="116" width="120" height="6" rx="3"/>
```

Use an ink outline for the message under review and a muted outline for the other messages.
Draw an author only when the role is part of the idea.
Then put a [person](#marks-in-a-container) with its role label above the message.
Start the text of a message 16 from its top and its left edge.
Stack messages 8 apart, and indent a reply by 24.

Status update, a message that starts with a tag:

```svg
<path class="pv-fill-surface pv-stroke-ink" d="M36.75 32.75H283.25A12 12 0 0 1 295.25 44.75V91.25A12 12 0 0 1 283.25 103.25H24.75V44.75A12 12 0 0 1 36.75 32.75Z" stroke-width="1.5"/>
<rect class="pv-fill-surface pv-stroke-muted" x="40.75" y="48.75" width="78.5" height="22.5" rx="8" stroke-width="1.5"/>
<text class="pv-fill-muted" x="52" y="65" font-size="15">Blocked</text>
<rect class="pv-fill-muted" x="40" y="82" width="208" height="6" rx="3"/>
```

### Marks in a container

Kind glyphs, in the order change, issue, commit, document, test, package:

```svg
<g transform="translate(0 0)"><circle class="pv-stroke-ink" cx="4" cy="3" r="2.5" fill="none" stroke-width="1.5"/><path class="pv-stroke-ink" d="M4 5.5v5" fill="none" stroke-width="1.5"/><circle class="pv-stroke-ink" cx="4" cy="13" r="2.5" fill="none" stroke-width="1.5"/><circle class="pv-stroke-ink" cx="12" cy="13" r="2.5" fill="none" stroke-width="1.5"/><path class="pv-stroke-ink" d="M12 10.5V6a3 3 0 0 0-3-3H7.5" fill="none" stroke-width="1.5" stroke-linecap="round"/></g>
<g transform="translate(32 0)"><circle class="pv-stroke-ink" cx="8" cy="8" r="6.75" fill="none" stroke-width="1.5"/><circle class="pv-fill-ink" cx="8" cy="8" r="1.75"/></g>
<g transform="translate(64 0)"><path class="pv-stroke-ink" d="M0 8h4.5M11.5 8H16" fill="none" stroke-width="1.5"/><circle class="pv-stroke-ink" cx="8" cy="8" r="3.5" fill="none" stroke-width="1.5"/></g>
<g transform="translate(96 0)"><path class="pv-stroke-ink" d="M2.75 0.75h7l3.5 3.5v11h-10.5z" fill="none" stroke-width="1.5" stroke-linejoin="round"/><path class="pv-stroke-ink" d="M9.75 0.75v3.5h3.5" fill="none" stroke-width="1.5" stroke-linejoin="round"/></g>
<g transform="translate(128 0)"><path class="pv-stroke-ink" d="M5.75 0.75h4.5M6.75 0.75v5L1.6 13.5a1.2 1.2 0 0 0 1 1.75h10.8a1.2 1.2 0 0 0 1-1.75L9.25 5.75v-5" fill="none" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></g>
<g transform="translate(160 0)"><path class="pv-stroke-ink" d="M8 0.75l6.5 3.75v7L8 15.25 1.5 11.5v-7zM1.5 4.5 8 8.25l6.5-3.75M8 8.25v7" fill="none" stroke-width="1.5" stroke-linejoin="round"/></g>
```

Show a count by repeating a glyph or a frame, never by making one bigger.
Name a package with a code token beside the package glyph.

The three states of a part: missing, lapse, and asked for.
Missing and lapse are alternatives at the same place in the lapse pane, so copy one of them.

```svg
<rect class="pv-fill-surface pv-stroke-muted" x="16.75" y="96.75" width="110.5" height="30.5" rx="8" stroke-width="1.5" stroke-dasharray="5 4"/>
<text class="pv-fill-muted" x="28" y="117" font-size="15">No test</text>
<text class="pv-fill-muted pv-mono" x="16" y="117" font-size="15">catch (e) { }</text>
<text class="pv-fill-accent pv-mono" x="352" y="117" font-size="15">throw SaveError(e)</text>
```

A pane shows one state of the part.
A part can be one code line, such as the one line that handles an error.
Put no box around a lapse part or an asked-for part.

Checklist, with one item done and one open:

```svg
<rect class="pv-fill-surface pv-stroke-muted" x="16.75" y="48.75" width="14.5" height="14.5" rx="3" stroke-width="1.5"/>
<path class="pv-stroke-accent" d="M20 56.5l3 3 5.5-6" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<rect class="pv-fill-muted" x="44" y="53" width="160" height="6" rx="3"/>
<rect class="pv-fill-surface pv-stroke-muted" x="16.75" y="72.75" width="14.5" height="14.5" rx="3" stroke-width="1.5"/>
<rect class="pv-fill-muted" x="44" y="77" width="120" height="6" rx="3"/>
```

Stack check boxes 24 apart.
Draw a check that the practice does not ask for with `pv-stroke-ink`.

Tag:

```svg
<rect class="pv-fill-surface pv-stroke-muted" x="0.75" y="0.75" width="78.5" height="22.5" rx="8" stroke-width="1.5"/>
<text class="pv-fill-muted" x="12" y="17" font-size="15">Feature</text>
```

Make a tag 24 wider than its label.
Draw the tag that the practice asks for with `pv-stroke-accent` and an accent label.

Person, with its role label, and an approval:

```svg
<circle class="pv-fill-muted" cx="8" cy="8" r="8"/>
<text class="pv-fill-ink" x="28" y="13" font-size="15">Owner</text>
<path class="pv-stroke-accent" d="M3 40.5l3.5 3.5 6.5-7" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text class="pv-fill-accent" x="24" y="45" font-size="15">Approved</text>
```

Draw a person only as this circle with its role label, never as a face or a figure.

### Flow and order

A node stands free on the page, as in the fork.
A missing node and a required node:

```svg
<rect class="pv-fill-surface pv-stroke-muted" x="200.75" y="0.75" width="214.5" height="38.5" rx="8" stroke-width="1.5" stroke-dasharray="5 4"/>
<text class="pv-fill-muted" x="216" y="25" font-size="15">Missing step</text>
<rect class="pv-fill-accent-soft pv-stroke-accent" x="200.75" y="64.75" width="214.5" height="38.5" rx="8" stroke-width="1.5"/>
<text class="pv-fill-ink" x="216" y="89" font-size="15" font-weight="600">Practice</text>
```

A neutral node is the same rectangle with `pv-fill-surface` and `pv-stroke-ink` (the trigger) or `pv-stroke-muted` (a lapse).

Bracket, with its label 12 to the right of the spine, for a range read and a range skipped:

```svg
<path class="pv-stroke-accent" d="M152 48h8v42h-8" fill="none" stroke-width="2"/>
<text class="pv-fill-ink" x="172" y="74" font-size="15">Read carefully</text>
<path class="pv-stroke-muted" d="M152 104h9M152 170h9" fill="none" stroke-width="2"/>
<path class="pv-stroke-muted" d="M160 105v64" fill="none" stroke-width="2" stroke-dasharray="5 4"/>
<text class="pv-fill-muted" x="172" y="142" font-size="15">Skimmed</text>
```

Draw the range that the practice wants read or covered with a solid accent bracket.
Draw a range that someone skips with solid muted ticks and a dashed muted spine.
Keep 8 between the bracket and the frame edge.
Where the bracket splits a range is an example, not a threshold.

Arrow and link:

```svg
<path class="pv-stroke-muted" d="M424 20h16" fill="none" stroke-width="2"/>
<path class="pv-fill-muted" d="M448 20l-8-5v10z"/>
<path class="pv-stroke-accent" d="M176 56h40" fill="none" stroke-width="2"/>
<circle class="pv-fill-accent" cx="176" cy="56" r="4"/>
<circle class="pv-fill-accent" cx="216" cy="56" r="4"/>
```

In the fork, give an arrow the class of its path: muted on the lapse, accent on the practice.
Draw a link from the edge of one frame to the edge of the other, level with their glyphs.
Put its dots on the frame edges.

Step marker, here for the step that the practice is about:

```svg
<circle class="pv-fill-accent" cx="236" cy="12" r="12"/>
<text class="pv-fill-surface" x="236" y="17" font-size="15" font-weight="600" text-anchor="middle">2</text>
```

A solid marker against outlined markers is a difference of shape, so a phone and a grayscale screen show it too.
Draw the other steps with `pv-fill-surface`, `pv-stroke-muted`, a `pv-fill-muted` numeral, and weight 400.
If every step is the practice, use no accent: draw each marker with `pv-stroke-ink`, `pv-fill-ink`, and weight 400.

Boundary and region, in the [placement](#placement) variant of the contrast pair:

```svg
<text class="pv-fill-ink" x="0" y="49" font-size="15">Outside</text>
<line class="pv-stroke-ink" x1="0" y1="64" x2="304" y2="64" stroke-width="2"/>
<line class="pv-stroke-ink" x1="336" y1="64" x2="640" y2="64" stroke-width="2"/>
<text class="pv-fill-ink" x="0" y="93" font-size="15">Inside</text>
<rect class="pv-fill-accent-soft" x="336" y="72" width="304" height="80" rx="8"/>
```

Use one region at most.
Put marks on a region, never containers.

Draw a limit line only when the criteria state a limit, and label its value.

## Composition

Use one of four patterns.
Practices with the same structure use the same pattern with the same coordinates.

### Contrast pair

This is the default.
Use it for a property that the work has or does not have.
Examples are a description that gives its reason, or a test that covers a failure.

| Part | Layout |
| --- | --- |
| Panes | Lapse at x 0 to 304, practice at x 336 to 640. The gutter is 32, with no divider and no arrow. An arrow would say that one pane causes the other. |
| Titles | 16, weight 600, ink, baseline 16, at x 0 and x 336. Name each state in 2 to 4 parallel words, never "Bad" and "Good". |
| Content | Starts at y 32 in both panes. Same kind of work, same scale, same skeleton. Matching parts at the same y. |
| Difference | One element. Accent on the practice side only. A missing part or a lapse part at the same place on the lapse side. |
| Consequences | One 15 label for each pane, at the same baseline: muted for the lapse, ink for the practice. Content bottom plus 24, or level with the part it explains. |

Variants keep the panes, the titles, and the consequences.

#### Excess

Use it when the lapse is a part too many, such as duplicated code or a permission that is too broad.

| Part | Layout |
| --- | --- |
| Lapse | The extra part, as a lapse part. |
| Practice | The shared or narrower part, as an asked-for part at the same place. |

#### Placement

Use it when the practice is about where something lives.

| Part | Layout |
| --- | --- |
| Both panes | The same layers or the same tree. Only the place of one element differs. |
| Layer | A 40-high node with an ink label. Layers stack 8 apart. |
| Tree | Code lines, indented by level. |
| Boundary | An ink line across each pane at the same `y`, with the outside above it. Label each side once, in ink, at x 0. A region can fill the checked side in the practice pane. |

#### Appearance

Use it when the picture must work in each appearance.

| Part | Layout |
| --- | --- |
| Frames | The same work twice: once in a frame or a view with `pv-fill-surface`, and once in one with `pv-fill-ink`. |
| Marks in the ink frame | `pv-fill-surface`. |
| Accent | Only in the surface frame. In the dark theme, accent and muted are below 3:1 on ink. |
| A mark that vanishes | Leave its place empty in that frame, at the same position as in the other frame. |
| Labels | Do not name an appearance on a frame or in a label. In the dark theme, the two frames change places. |

### Whole versus parts

Use it when the practice splits one piece of work into several.
The accent marks the lines that get full attention.

| Part | Layout |
| --- | --- |
| Panes and titles | As in the contrast pair. |
| Lapse | One tall frame of width 80. Group its lines like the smaller frames, with 8 more between groups. |
| Practice | One frame of width 80 for each group, at x 336, 448, and 560. Each holds the lines of its group, with the same widths in the same order. Size each frame to its content. |
| Stagger | Put the top of each smaller frame 16 above the first line of its group in the tall frame. Each group is then level with its match, and the frames step down like stacked changes. |
| Accent | In both panes: the lines that get full attention. The amount of accent and the cut of the frames carry the difference. |
| Consequences | Beside the tall frame, one bracket for each state of its lines. Label the range read in ink and the range skimmed in muted. |
| Practice label | At x 448, the left edge of the second frame, at the baseline of the first bracket label. It names what the practice asks for. |

### Fork

Use it when the practice is about what happens after a trigger: an error, an input, a failed test, or an open review ask.

| Part | Layout |
| --- | --- |
| Trigger | Ink node at x 0 to 136, y 32 to 72. Label baseline 57. |
| Paths | A stem at y 52 from x 136 to 156, then up to y 20 (lapse, muted) and down to y 84 (practice, accent). Heads end at x 192. |
| Responses | Nodes at x 200 to 416: the lapse at y 0 to 40, the practice at y 64 to 104. Top: a missing node or a muted node. Bottom: required node. |
| Outcomes | Arrows from x 424 to 448 at y 20 and y 84. Labels at x 460: muted on top, ink below. |
| Canvas | `H` 104. No titles, because the trigger is the subject. |

### Sequence

Use it only when the order is the subject of the practice, or in a guide figure that shows steps in order.

| Part | Layout |
| --- | --- |
| Columns | 3 columns of 192 at x 0, 224, and 448. For 2 steps, use columns of 304 at x 0 and 336, the panes of the contrast pair. For 4 steps, use columns of 136 at x 0, 168, 336, and 504. |
| Markers | [Step markers](#flow-and-order) at column x plus 12, y 12. |
| Arrows | Muted, at y 12, from 8 after one marker to 8 before the next. |
| Labels | Step title 16, weight 600, ink, baseline 48. Sub-label 15, muted, baseline 72, only in a guide figure. |
| Canvas | `H` 76 for a figure with one row. Use no frames. |

A visual uses the sequence only to contrast two orders.
Use it when the practice is about the order of two different events.

Stack two rows 32 apart, with the lapse on top.
Name each row with a title of 16, weight 600, ink: at baseline 16 for the lapse and baseline 156 for the practice.
Move the marks of each row 32 down, below its title.
The second row ends at `H` 248 in a figure.
In a visual, which has no sub-labels, the second title is at baseline 132, and the second row ends at `H` 200.

Only the practice row has the solid accent marker.
Every marker in the lapse row is outlined and muted.
Arrows stay muted in both rows.
A sequence visual has no consequence label.
The row titles name the two orders, and the word limit leaves no room for more.

## Typography

Text in the picture inherits the font of the app.
The class `pv-mono` sets the code font of the app, for one code token.

- Use only two sizes. Titles are 16 with `font-weight="600"`. Labels are 15.
- Use weight 600 for one label only: the label that names what the practice asks for. Color it accent, or ink inside an accent node.
- The numeral in the solid step marker is weight 600, and it does not count as a label.
- Give every other label weight 400: ink for a name or a gain, muted for context or a lapse.
- In the visual, 15 shows at about the size of the lead. The guide scales a figure so that 15 shows at about the size of the guide's body text. The picture does not compete with the headings around it.
- Use 24 words or fewer in a visual, with the titles, the labels, and the examples together. Each example is 6 words or fewer.
- Write labels in sentence case, in the product voice, with no period.
- Reuse the words of the lead for the same idea.
- SVG text does not wrap. Put each line in its own `<text>`.
- Measure text before you place it. Weight 400 is about 8 units for each character, and weight 600 about 9.5.

## Spacing

Put panes, frames, nodes, gutters, and content tops on a grid of 8.
Line heights, pitches, radii, baselines, and stroke insets use the values in this table.

| Item | Value |
| --- | --- |
| Title baseline, content top | 16, 32 |
| Padding inside a container | 16 at the top and the left. At the bottom, 14 to 16, so the next container stays on the grid of 8. |
| Code line height, prose line height, node height | 10, 6, 40 |
| Kind glyph | 16 by 16 |
| Code line pitch, prose line pitch | 16, 12 |
| Code line to the prose line after it, as in a title bar | 18 (a gap of 8) |
| Pitch from one group of lines to the next | The line pitch plus 8 |
| Gutter between panes or columns | 32 |
| Label beside a mark | 12 away, baseline at the mark's center plus 5 |
| Label in a 40-high node | Baseline at the node's top plus 25 |
| Clearance around arrows and lines | 8. A fork stem starts at the edge of the trigger. |
| Stroke width | 1.5 for outlines, containers, and glyphs. 2 for arrows, links, brackets, checks, and fork paths. |
| Corner radius | 3 for lines and check boxes, 8 for frames, nodes, tags, and regions, 12 for messages, views, and controls |

Inset a stroked shape by half its stroke, so that its outer edge sits on the grid.
For a stroke of 1.5, use `x="0.75"`.
An empty area must not be larger than about a sixth of the canvas.

## Descriptions

- Write 1 or 2 full sentences, 250 characters or fewer.
- Follow the order of the picture: the lapse and its consequence, then the practice and its consequence.
- Use the words of the labels. Describe the meaning, not the drawing: never "band", "bar", "dashed", or a color.
- Do not start with "Image of" or "Picture of". A screen reader already says that it is an image.

## Guide

A bundled guide uses these level-2 headings, in this order:

1. `## How to do it`
2. `## When it does not apply`
3. `## Common mistakes`
4. `## Sources`

Before you write, read the criteria.
Find the **Standard** and **Judge**, the **Applies when**, and the sentences that reject a misreading.
Each sentence of the guide must come from one of them or from an entry in **Sources**.
Use **Defer** to find what to leave out: do not teach what **Defer** gives to another practice.

- **How to do it**: 3 to 5 bullets. Each bullet gives one instruction in the imperative. A condition can come before it. Give exactly one realistic example in the form "instead of …, do …".
- **When it does not apply**: one bullet for each fact in the **Applies when**, in the developer's words. Add no case and drop no case. If the criteria name a case that still counts, end with "It still applies when …".
- **Common mistakes**: 2 to 4 bullets. Each names a wrong action and what to do instead. Start with the misreading that the criteria reject, such as a line limit.
- **Sources**: 2 to 4 entries. The next section gives the quality bar.

More rules:

- Do not write an introduction.
- Keep the guide to 200 to 400 words, without the sources.
- Write in the product voice. Address the developer as **you**. Use the words of the product vocabulary.
- Do not state a number, a limit, or a requirement that the criteria do not state. Put a study's numbers only in its source entry.
- Do not mention review internals, such as an outcome, evidence, or a collection gap.
- Add 0 to 2 figures, only for an order or a structure that words carry badly. Use the shapes and the colors of this page.
- Put each figure directly after the paragraph that it supports. Its description follows the rules for descriptions.

## Sources

- Cite a standard or official documentation, a peer-reviewed study, or a recognized practitioner guide from a named organization or author.
- Prefer the source that the criteria already rely on, and a meta-analysis or a replicated finding to a single study.
- Use `https` links. For a paper, use its DOI. You can also use the page of the authors' institution if it has the abstract and the full text.
- Name the author or the organization and, for a study, the year.
- After the name and the year, write one sentence that says what the source supports.
- Say so in the entry when a source argues for a kind of product that its publisher sells. An example is a study of code review by a seller of review tools.
- Official documentation of a platform or tool needs no such note.
- Do not state a claim more strongly than its class allows. The [catalog curation rules](./practice-catalog.md#authoring-experience) give the classes.
- Read the claim in the source itself, not in a summary. Do not cite a source for a number that the practice does not use.
- Do not cite blog platforms, AI summaries, marketing pages, Wikipedia, or pages behind a paywall with no public abstract.

## Draw, check, and review

1. Copy the reference of the pattern. The top of this page names it.
2. Draw in a text editor. Keep the coordinates on the grid.
3. In Storybook, open `practice-guidance/bundled-practices`. Look at the practice in the **Default**, **Dark**, and **Phone** stories, and compare it with the other practices.
4. In the **Phone** story, cover every label. The lapse and the practice must still look different from their shapes and positions alone.
5. Fix overflowing text, empty areas, and marks that touch. Then look again.
6. Do the [cold read](#clear-at-a-glance).
7. Run `node --test scripts/practice-guidance.test.ts`.
8. Run `vp run lint:prose <guide.md>`, and add the path of the guide to `.vale/enforced-paths.json`.

`scripts/practice-guidance.test.ts` checks the rules on this page that a machine can check.
A reviewer checks the rest with the checklist below.
The workflow `practice-guidance-links.yml` checks the web links of the bundled guides on a pull request that changes them, and every week.

GitHub previews a guide with its figures, because the figure paths are relative to the guide.
GitHub does not apply the `pv-*` classes.
Thus, the GitHub preview checks the paths and the text, not the colors.

### Review checklist

- The visual shows the **REVIEW FOCUS** and nothing else.
- A person who sees only the practice name and the picture can say what the practice asks for.
- No mark needs a legend. Each dot has a label, each glyph has a word, and no rule or tick stands alone.
- No picture states a count, a limit, or a requirement that the criteria do not state.
- The picture uses one pattern, with its coordinates.
- The accent has one meaning, and each accent mark also differs by its example text, position or shape.
- The lapse side has a consequence label, if its pattern has one.
- With the titles covered, the examples or the shapes still show the difference.
- No container is in a container or on a region.
- No box surrounds a group of shapes, and no arrow joins the panes.
- No text overflows its pane or its node.
- The picture reaches the edges, and no large area stays empty.
- Both themes look correct.
- The description follows the picture and names no drawing device.
- Each guide sentence comes from the criteria or a source. **When it does not apply** agrees with the **Applies when**.
- Each source is authoritative and supports the claim that the guide makes.

## Basis for these rules

- [Alfieri, Nokes-Malach and Schunn (2013)](https://doi.org/10.1080/00461520.2013.775712): a comparison of cases teaches more than single cases. This is why the contrast pair is the default and the caption names the principle after the picture.
- [Matlen, Gentner and Franconeri (2020)](https://doi.org/10.1037/xhp0000726): matching parts at the same position make a comparison faster and more accurate.
- [Mayer (2008)](https://doi.org/10.1037/0003-066X.63.8.760): coherence, signaling, and spatial contiguity. Draw one idea, mark one thing, and put each label next to its mark.
- [Sundararajan and Adesope (2020)](https://doi.org/10.1007/s10648-020-09522-4): interesting but irrelevant details reduce learning. Draw no decoration.
- [Heiser and Tversky (2006)](https://doi.org/10.1207/s15516709cog0000_70): readers see an arrow as a process or a cause. Use arrows only for order and cause.
- [Kalyuga and others (2003)](https://doi.org/10.1207/S15326985EP3801_4): help that novices need can slow down experienced readers. Keep text short and do not repeat the lead or the caption.
- [WCAG 2.2, Use of Color](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html) and [Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html). Color must not be the only cue. A meaningful graphic needs 3:1 contrast.
- [W3C, Complex images](https://www.w3.org/WAI/tutorials/images/complex/): a picture that carries information needs a description that carries the same information.
