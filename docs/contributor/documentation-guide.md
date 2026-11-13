---
title: Documentation guide
description: The reader of each docs guide, its page types, the template of each type, and the checks that hold them.
page_type: reference
---

This guide sets the shape of a docs page.
It gives the guide that a fact goes in, the page type, the template, and the rules for images, callouts, code, and file names.
Write the sentences by the [writing standard](./simplified-technical-english.md): voice, words, and sentence length.
Use the product terms of the [product vocabulary](./practice-feedback-language.md).
Read this guide before you add a page or move a fact to another guide.

The rules apply to a guide when all of its pages declare a `page_type`.
The user guide and the admin guide meet this condition.
In the contributor guide, a new page and a page that declares its type follow the rules.

## The three guides

Each fact goes in the guide of the reader who acts on it.

| Guide | Reader | The reader's goal | [Voice](https://github.com/hephaestus-build/Hephaestus/blob/main/AGENTS.md) | Page types |
| --- | --- | --- | --- | --- |
| User (`docs/user/`) | A developer or a workspace admin who uses Hephaestus in the web app, on GitHub or GitLab, or in Slack | Get feedback on their work, understand it, act on it, and set up their workspace | Product voice | overview, tutorial, feature, reference |
| Admin (`docs/admin/`) | An operator or an instance admin who installs, configures, upgrades, and secures an instance | Keep an instance available, safe, and current | STE | overview, tutorial, how-to, reference, explanation, troubleshooting |
| Contributor (`docs/contributor/`) | An engineer or a coding agent that changes the code | Make a correct change and know why the system works as it does | STE | overview, tutorial, how-to, reference, explanation |

The place of a task decides between the user and the admin guide.
A task in the web app for a workspace, such as the feedback mode, goes in the user guide.
A task on the deployment or in the instance admin console goes in the admin guide.
Deployment tasks include chart values, environment variables, the database, and a provider app registration.
Langfuse uses the same line: its [self-hosting docs](https://langfuse.com/self-hosting) are a separate section from its product docs.

### What goes in another guide

- **User guide.** A user page has no deployment settings, environment variables, repository tooling, or source paths. It has no internal names, such as a class or a table. If a task needs an operator, name the role and link the admin page: `Ask your instance admin to connect Slack`. A user page does not link to the contributor guide. The overview links it among the other guides, and a reference page can cite a contributor page as evidence.
- **Admin guide.** An admin page explains internals only as far as the operator acts on them. Link the contributor page for the rest. Link the user guide for a workspace task in the web app.
- **Contributor guide.** A contributor page does not repeat an operator procedure or a user procedure. Link the admin or user page.
- **Decision records.** An ADR in `docs/decisions/` records a decision and its reasons once. A guide page states the current behavior and links the ADR for the reason.

### One home for each fact

Give each fact one page, and link to it where the reader needs it: `Hephaestus deletes feedback after its retention period ([Data retention](/admin/data-retention))`.
On a docs page, do not write a sentence that only says which page owns a fact.
Start a page with what it covers.

## Current release only

Every page describes what the current release does.
Delete a page or a section that describes removed or interim behavior, an old setting, or an upgrade path that the release drops.
Do not write a note about what a feature did before.
The release note carries the steps that an operator takes to upgrade.

- Do not name a release version, such as `1.0` or `rc.5`, on a page.
  Release notes, upgrade pages, and the [compatibility policy](/admin/compatibility-policy) are the exceptions.
  A version that the product requires is a fact, such as `PostgreSQL 18`.
  A version in a path or a tag is part of a name. Write it as code.
- Do not compare the product with an earlier or a later release.
  Avoid words such as `now`, `new`, `currently`, `no longer`, `used to`, and `soon`.
  A change of state at run time is correct: `Check that the service no longer runs`.
  `STE.Timeless` flags some of these words.
- Do not describe a feature that is planned. State what the product does not do as a plain fact.
- These rules also apply to diagrams, captions, alt text, anchors, and file names.
- Decision records and dated audit records describe their date. These rules do not apply to them.

[Google](https://developers.google.com/style/timeless-documentation), [Kubernetes](https://kubernetes.io/docs/contribute/style/style-guide/), and [Grafana](https://grafana.com/docs/writers-toolkit/contribute/release-notes/) use the same rules.

## Page types

Each page has one type, and the reader's goal decides it.
[Diátaxis](https://diataxis.fr/start-here/) names four needs and warns that "crossing or blurring the boundaries" causes most documentation problems.
[Supabase](https://raw.githubusercontent.com/supabase/supabase/master/apps/docs/style-guide/03-page-structure.md) adds troubleshooting and makes each type "one unit of work for the reader".
Declare the type in the front matter as `page_type`.

| `page_type` | The reader wants to | Example |
| --- | --- | --- |
| `overview` | Find the right page in a guide. Each guide has one. | `user/overview` |
| `tutorial` | Get a first result from start to end, on one path | `user/getting-started`, `admin/first-steps`, `contributor/first-change` |
| `how-to` | Finish one task | `admin/backup-restore` |
| `feature` | Use one area of the product. Only in the user guide. | `user/practice-profile` |
| `reference` | Look up a fact | `admin/configuration-reference`, `user/accessibility` |
| `explanation` | Know why the system works as it does | `contributor/system-design` |
| `troubleshooting` | Fix one symptom. Only in the admin guide. | `admin/troubleshooting` |

The user guide uses `feature` pages, because its reader looks for a product area, not for a type of text.
A feature page follows the topic order of [GitLab](https://docs.gitlab.com/development/documentation/topic_types/): concepts, then tasks, then troubleshooting.
Admin and contributor pages are longer, so each page keeps one type.
Split a long page that mixes types into a how-to page and a reference page.

### Front matter

Every page starts with this front matter:

```yaml
---
title: Back up and restore
description: Back up the database and the encryption keys, and restore an instance from them.
page_type: how-to
---
```

- `title` is the page title. Do not write a `#` heading in the body. Docusaurus shows the title as the heading.
- `description` is one sentence of at most 160 characters. Search results and link previews show it.
- Do not set `id`. The file name is the ID.
- Add other Docusaurus keys, such as `sidebar_label`, only when the page needs them.
- The first paragraph follows the front matter. No heading, image, or callout comes before it.

### Overview

- **Title:** the name of the guide, such as `Admin overview`, or a welcome for the user guide.
- **First paragraph:** who the guide is for and what they get from it. The user overview uses the product's one-liner and lead paragraph from the `README.md`, word for word.
- **Sections:** the first task, then one link for each main area, then the other guides.

### Tutorial

- **Title:** the start that the reader makes, such as `Getting started` or `First steps`.
- **First paragraph:** what the reader has at the end.
- **Sections:**
  1. `## Before you start`: the accounts, access, and tools that the reader needs.
  2. One section for each stage, with numbered steps. End each stage with what the reader sees.
  3. `## Next steps`: two to four links to the pages that the reader needs next.
- A tutorial has one path. If the reader can choose another path, link a how-to page for it.

### How-to

- **Title:** an imperative verb and its object, such as `Rotate the credential key`.
- **First paragraph:** the goal and when the reader needs it.
- **Sections:**
  1. `## Before you start`: the role, access, versions, and expected downtime. Leave the section out if the task needs nothing.
  2. The procedure: numbered steps, as the [writing standard](./simplified-technical-english.md#procedures) defines them. Start a step that the reader can skip with `Optional:`.
  3. `## Check that it worked`: the command or the screen, and the result that the reader must see.
  4. `## Undo`, if the task can be undone. Give the steps that return the instance to its previous state.
  5. `## Troubleshooting`, if the task has known failures. Put it last.

### Feature

- **Title:** the name of the area as the web app shows it, such as `Practice profile`.
- **First paragraph:** what the area gives the reader, in at most three sentences. If only some roles can use it, say which.
- **Sections:**
  1. Concepts: a noun heading, and an explanation of what the reader sees.
  2. Tasks: an imperative heading, such as `## Respond to feedback`, then numbered steps, then the result that the reader sees. If a task needs a role, name it in the first sentence: `Workspace admins can …`.
  3. `## Troubleshooting`, if the area has known problems. Put it last. Give each symptom a `###` heading that says what the reader sees.

### Reference

- **Title:** a noun phrase, such as `Configuration reference` or `Network egress`.
- **First paragraph:** what the page lists and where the data comes from. For a generated page, name the command that generates it.
- **Body:** tables or lists in a fixed order, such as alphabetical order or the order that the system reads them.
- Describe and do not instruct. Diátaxis: reference "should be austere" and "describes the machinery". Link a how-to page for each task.
- A reference page can cite evidence from another guide, such as the accessibility statement that cites its audit record.

### Explanation

- **Title:** a noun phrase that names the subject, such as `System design`. Do not use `Overview` or `How it works` as a title.
- **First paragraph:** the main fact about the subject.
- **Sections:**
  1. The constraint that shapes the design.
  2. How the parts work together.
  3. The trade-offs and the rejected alternatives.
  4. Links to the related how-to and reference pages.
- Use a Mermaid diagram when the reader must see how parts connect.

### Troubleshooting

- **Title:** `Troubleshooting`, or `Troubleshoot` and the area.
- **First paragraph:** the area, and the data to collect first, such as the logs and the version.
- **Sections:** one `##` heading for each symptom, in the words that the operator sees. Put an exact error message in code. Under each heading, give the cause, the fix as numbered steps, and the check.

### Section names

Use the same name for the same section on every page.
`gate:docs-pages` rejects the names in the right column.

| Use | Do not use |
| --- | --- |
| `Before you start` | `Prerequisites`, `Requirements`, `Before you begin` |
| `Check that it worked` | `Verify`, `Verification`, `Validation` |
| `Troubleshooting` | `Common problems`, `Known issues` |
| `Next steps` | `See also`, `Related`, `What's next`, `Further reading` |

## Images

- Use a screenshot when the reader must find something on a screen and a sentence cannot point to it. [Google](https://developers.google.com/style/images) and [GitLab](https://docs.gitlab.com/development/documentation/styleguide/) give the same rule.
- Never put a fact only in an image. The text must give it too.
- A screenshot shows the current UI with synthetic data only. Do not show a real person, repository, organization, or secret. [PostHog](https://posthog.com/handbook/docs-and-wizard/docs-style-guide) gives the same rule.
- Write alt text that says what the image shows, in at most 155 characters, as [GitLab](https://docs.gitlab.com/development/documentation/styleguide/) sets. Markdownlint rejects a Markdown image without alt text. `gate:docs-pages` rejects a `Screenshot` without alt text or with a longer one.
- Draw a diagram as code, as the next section says. Never put a diagram only in an image that has no source.
- Do not add an animated GIF.

### Diagrams

Each diagram answers one question that a reader asks, at the point in the page where the reader asks it.
Two sentences before it state the question and what to look for.
[System design § Diagram map](./system-design.mdx#diagram-map) lists the architecture diagrams.
These rules come from the [C4 model](https://c4model.com/diagrams/notation), [arc42](https://docs.arc42.org/home/), *Documenting Software Architectures: Views and Beyond* (Clements et al., 2010) and [Diátaxis](https://diataxis.fr/):

- Show about 9 elements or fewer. Open a box in its own view instead of crowding one view.
- Label each arrow with what crosses it, such as `SandboxClaim` or `model calls, API key`. Do not write "uses".
- Put no notes in the diagram. The text gives the responsibilities and the reasons.
- Map every box and arrow to code or to an ADR. A catalogue table under a structural view names the package.
- Leave out what is implied, such as a standard OAuth flow or an arrow from every part to PostgreSQL.
- Never mark a meaning by color alone. Add a keyword such as `«cluster admin»`, a word such as "Stop:", or a line style. Show it in the legend or the lead-in.

Choose the tool by what the diagram is:

| Diagram | Tool | Cost |
| --- | --- | --- |
| A structural map that readers come back to and that changes rarely: the system context, the containers, the deployment, the components of the server | HyLiMo | A hand layout, a render step and committed SVGs |
| A flow, a lifecycle, a data model, or a diagram that changes with the code | Mermaid | An automatic layout that you cannot place by hand |

#### Structural maps in HyLiMo

[HyLiMo](https://github.com/hylimo/hylimo) draws UML component and deployment diagrams with the layout that the source sets. That gives a grid, orthogonal routes and few crossings.
It has no C4 module. The C4 model is independent of notation, so a C4 level is drawn as a UML component or deployment diagram.

1. Edit or add the source in `docs/diagrams/<name>.hyl`. Keep the line `// @shared-style`, and call one legend once. `docs/diagrams/style.hyl` holds the shared style and the legends.
2. Run `vp run generate:diagrams`. `scripts/render-diagrams.ts` renders each source with the pinned `@hylimo/cli` into `docs/static/img/diagrams/<name>.light.svg` and `<name>.dark.svg`.
3. Show it with `<SystemDiagram name="<name>" title="<view>: <scope>" alt="…">` from `@site/src/components/SystemDiagram`. The title states the view and its scope. The alt text has at most 155 characters. The children are a table with every connection of the view. The page shows it under "Connections as text", and the image refers to it with `aria-describedby`.
4. Commit the source and both SVGs. `gate:system-diagrams` fails when an SVG does not match its source.

Draw each frame first, then place every box at an absolute position, `apos(x, y)`. HyLiMo lays out the children of a nested frame on its own, so a box is never a child of a frame. Compute the outline position of each end of an edge, so that each edge is straight or orthogonal.
A UML node or device is a 3-D box: its front face is 20 units lower and 20 units narrower than the box. Keep each box at least 16 units inside the front face of every frame it is in.

Each kind of view has one meaning for each line, and its legend or lead-in states it:

| View | Boxes | Lines |
| --- | --- | --- |
| Context | Actors, Hephaestus as one box, the services | An arrow from the side that starts the exchange |
| Containers | The parts that run on their own, the services | An arrow from the side that opens the connection. The label names what crosses and the credential. A label with no protocol means HTTPS. A dashed `«instantiate»` arrow means "creates". |
| Components | The parts of one container | Ball and socket: the provided and the required interface, named after the Java type or route. A dashed arrow from a socket to a ball is a routed connection. An event type is provided by its publisher and required by its listener. A port on the frame passes a request to the part that handles it. |
| Deployment | Nodes, Pods as execution environments, and the image (`«artifact»`) in each Pod. A namespace is a tagged value of the Pod, not a box. | A communication path with its protocol and port, from the side that opens the connection |

A deployment view answers who deploys and runs each box. Its keyword names that owner, and its fill repeats the keyword, so the view reads in greyscale and on paper:

| Box | Who deploys and runs it |
| --- | --- |
| White, `«Hephaestus chart»` | The Hephaestus chart, in the namespaces of the operator |
| White, `«created at runtime»` | The agent-sandbox controller, from a template that the chart deploys |
| Yellow, `«cluster admin»` | The cluster admins |
| Grey, dashed, `«external»` | Nobody in the deployment: a service outside it |

Write `«operator-provided»` on a box that the operator deploys beside the chart, such as an external PostgreSQL, and add it to the legend of that view.
A context, container or component view shows no owner. Its white boxes are part of Hephaestus, and its grey dashed `«external»` boxes are outside the deployment.

Call the legend that matches the view: `legendDeployment(x, y)` for a deployment view, `legendComponents(x, y)` for a component view, and `legendExternal(x, y)` for every other view.
Draw each view at most 960 units wide. The docs column is at most 720 px wide, so the 16-unit text is then at least 12 px.

Each SVG has the page background of its scheme, so its contrast does not depend on the viewer.
The SVGs draw their text as paths, because embedded fonts make the render unstable. A reader cannot select or search that text, so the alt text and the connections table carry the words.

To move a box by hand, put the content of `docs/diagrams/style.hyl` in place of the `// @shared-style` line, and paste the result into the [HyLiMo editor](https://hylimo.github.io). The legends are defined only in `style.hyl`.
Each drag changes the text. Copy back only the lines below the style.

#### Flows and lifecycles in Mermaid

Write the source inline in the page, with `accTitle` and `accDescr`.
Use the type that fits:

| Diagram | Mermaid type |
| --- | --- |
| A flow between parts | `sequenceDiagram` |
| A lifecycle, with the state names of its enum | `stateDiagram-v2` |
| A few core tables | `erDiagram` |
| The steps on one page | a small `flowchart` |

Leave every color to the site theme. The site draws Mermaid in the monochrome `neutral` theme in the light scheme and in the `dark` theme in the dark scheme. Its font is Roboto, the font of the HyLiMo views. Thus all diagrams read as one set. A `classDef` sets a stroke width or a dash only, and the label carries the same meaning.

Use only the subset that [Mermaid 13](https://mermaid.js.org/intro/v13-alpha.html) keeps. The maintainers track the upgrade, and this subset survives it:

- No `C4*`, `architecture`, `block` or `requirementDiagram` type. Mermaid 13 does not port them.
- No HTML in a label, no `click` callback, and no `style` on an element id.
- No `htmlLabels`, `securityLevel` or `themeCSS` setting.

`gate:diagrams` parses every Mermaid block and refuses each of these.

### Screenshots of the web app

A user page shows the web app with the `Screenshot` component.
The component shows the image of the reader's color scheme:

```mdx
import Screenshot from "@site/src/components/Screenshot";

<Screenshot src="practice-profile/overview" alt="Practice profile with an open feedback card." />
```

`vp run generate:docs-screenshots` makes every image from the built web app:

- The app reads a synthetic workspace from `webapp/e2e/screenshots/workspace.ts`. No server or database runs. The user, workspace, membership, conversation, and feedback mode data come from the fixtures that the stories and tests share. A change to them can change an image.
- The live event stream of the app stays open with no events. A new screen with another stream needs the same answer in `workspace.ts`.
- The capture uses a 1024 by 640 viewport at twice the pixels, a fixed clock, and the UTC time zone. It blocks every request to another site, so no avatar of a real person appears. It hides the floating Hephaestus button, which covers the content.
- Chromium encodes each image as WebP. Each screen is captured in the light and the dark color scheme. The images go to `docs/static/img/user/<page>/<name>.<scheme>.webp`.
- An image of more than 150 KB fails the capture and `gate:docs-pages`.
- A request that `workspace.ts` does not answer fails the capture, so no image shows an error by accident.

[Add a user-guide screenshot](./add-a-user-guide-screenshot.md) gives the steps.
A pull request that changes a screen that a user page shows also commits the new images.

## Callouts

Docusaurus calls a callout an admonition.
Each callout type has one STE meaning.
The [writing standard](./simplified-technical-english.md#warnings-cautions-and-notes) defines what each one holds and where it goes.

| Callout | STE meaning |
| --- | --- |
| `:::note` | Note |
| `:::tip` | Note that gives an optional, shorter way |
| `:::caution` | Caution |

Do not put two callouts in a row.
[GitLab](https://docs.gitlab.com/development/documentation/styleguide/) gives the same rule.

## Code blocks

- Give each code block its language, such as `sh`, `bash`, `yaml`, `json`, or `text`. Markdownlint rejects a block without one.
- A command block holds commands only. Do not add a prompt or the output.
- Show output in a separate `text` block after the command block.
- Write a placeholder in angle brackets, such as `<release>`. Under the block, say what the reader must put there.
- If a block shows a file, add its path after the language, such as `title="values.yaml"`.
  Under `docs/admin/`, `title="values.yaml"` means the Helm values of the chart: `scripts/kubernetes-chart.test.ts` validates each such block against the chart's schema.
- A user page has no code blocks. It uses inline code only for text that the reader enters, such as a Slack command.

## Files and names

- Put a new page at `docs/<guide>/<name>.md`. The name is the subject in kebab-case, such as `backup-restore`. Do not repeat the guide name.
- Use `<name>.mdx` for a new page only if it uses a component. Docusaurus reads both extensions as MDX, so existing pages keep their extension.
- Add the page to its guide's sidebar, `docs/sidebars.<guide>.ts`, in the category of its task.
- Do not rename a published page in a content change. A rename breaks links from outside the site. Rename pages only in a change to the guide's structure.
- Do not put a date in a file name. A page states the current behavior. Git keeps the history.

## Checks

[Check a docs page](./check-a-docs-page.md) gives the steps before a pull request.
`gate:docs-pages` checks these rules on every page of the user guide and the admin guide:

- Each page has a `title`, a `description` of at most 160 characters, and a `page_type` that the guide allows. No page sets an `id`.
- No page has a `#` heading in its body.
- No section uses a name from the "Do not use" column.
- A tutorial has numbered steps and a `Next steps` section. A how-to page has numbered steps and a `Check that it worked` section.
- A `Troubleshooting` section is the last section.
- A callout is a note, a tip, or a caution.

In the user guide, it also checks these rules:

- Only the overview and reference pages link to the contributor guide.
- No inline code names a repository command or a source path.
- A page shows the product with `Screenshot`, not with a Markdown image.
- Each `Screenshot` has alt text of at most 155 characters.
- Each `Screenshot` has a light and a dark image of at most 150 KB.

The gate also checks that an upgrade page in `docs/admin/` names the heading of each `.migration` note.
In the contributor guide, the gate checks on every page that a callout is a note, a tip, or a caution.
On a page that declares its type, it also checks that the guide allows the type, and the rules for numbered steps and closing sections.

A guide gets the full check when all its pages declare a type.
Vale checks the sentences, as the [writing standard](./simplified-technical-english.md#machine-checks-and-human-review) describes.

## Sources

- Diátaxis: [start here](https://diataxis.fr/start-here/), [how-to guides](https://diataxis.fr/how-to-guides/), [reference](https://diataxis.fr/reference/).
- GitLab: [topic types](https://docs.gitlab.com/development/documentation/topic_types/), [task](https://docs.gitlab.com/development/documentation/topic_types/task/), [concept](https://docs.gitlab.com/development/documentation/topic_types/concept/), [style guide](https://docs.gitlab.com/development/documentation/styleguide/).
- Supabase: [page structure](https://raw.githubusercontent.com/supabase/supabase/master/apps/docs/style-guide/03-page-structure.md), [elements](https://raw.githubusercontent.com/supabase/supabase/master/apps/docs/style-guide/02-elements.md).
- PostHog: [docs style guide](https://posthog.com/handbook/docs-and-wizard/docs-style-guide).
- Langfuse: [self-hosting](https://langfuse.com/self-hosting), [docs agent instructions](https://raw.githubusercontent.com/langfuse/langfuse-docs/main/.agents/AGENTS.md).
- Stripe: [Markdoc](https://stripe.dev/blog/markdoc). Stripe validates its docs in CI.
- Google: [procedures](https://developers.google.com/style/procedures), [images](https://developers.google.com/style/images).
