---
title: Documentation guide
description: The reader of each docs guide, its page types, the template of each type, and the checks that hold them.
page_type: reference
---

This guide owns the shape of a docs page.
It gives the guide that a fact goes in, the page type, the template, and the rules for images, callouts, code, and file names.
The [writing standard](./simplified-technical-english.md) owns the sentences: voice, words, and sentence length.
The [product vocabulary](./practice-feedback-language.md) owns the product terms.
Read this guide before you add a page or move a fact to another guide.

The rules apply to a guide when all of its pages declare a `page_type`.
Today, that is the user guide.
In the admin and contributor guides, a new page and a page that declares its type follow the rules.

## The three guides

Each fact goes in the guide of the reader who acts on it.

| Guide | Reader | The reader's goal | [Voice](https://github.com/hephaestus-build/Hephaestus/blob/main/AGENTS.md) | Page types |
| --- | --- | --- | --- | --- |
| User (`docs/user/`) | A developer or a workspace admin who uses Hephaestus in the web app, on GitHub or GitLab, or in Slack | Get feedback on their work, understand it, act on it, and set up their workspace | Product voice | overview, tutorial, feature, reference |
| Admin (`docs/admin/`) | An operator or an instance admin who installs, configures, upgrades, and secures an instance | Keep an instance available, safe, and current | STE | overview, tutorial, how-to, reference, explanation, troubleshooting |
| Contributor (`docs/contributor/`) | An engineer or a coding agent that changes the code | Make a correct change and know why the system works as it does | STE | overview, how-to, reference, explanation |

The place of a task decides between the user and the admin guide.
A task in the web app for a workspace, such as the feedback mode, goes in the user guide.
A task on the deployment or in the instance admin console goes in the admin guide.
Deployment tasks include chart values, environment variables, the database, and a provider app registration.
Langfuse uses the same line: its [self-hosting docs](https://langfuse.com/self-hosting) are a separate section from its product docs.

### What goes in another guide

- **User guide.** A user page has no deployment settings, environment variables, repository tooling, or source paths. It has no internal names, such as a class or a table. If a task needs an operator, name the role and link the admin page: `Ask your instance admin to connect Slack`. A user page does not link to the contributor guide. A reference page can cite a contributor page as evidence.
- **Admin guide.** An admin page explains internals only as far as the operator acts on them. Link the contributor page for the rest. Link the user guide for a workspace task in the web app.
- **Contributor guide.** A contributor page does not repeat an operator procedure or a user procedure. Link the admin or user page.
- **Decision records.** An ADR in `docs/decisions/` records a decision and its reasons once. A guide page states the current behavior and links the ADR for the reason.

## Page types

Each page has one type, and the reader's goal decides it.
[Diátaxis](https://diataxis.fr/start-here/) names four needs and warns that "crossing or blurring the boundaries" causes most documentation problems.
[Supabase](https://raw.githubusercontent.com/supabase/supabase/master/apps/docs/style-guide/03-page-structure.md) adds troubleshooting and makes each type "one unit of work for the reader".
Declare the type in the front matter as `page_type`.

| `page_type` | The reader wants to | Example |
| --- | --- | --- |
| `overview` | Find the right page in a guide. Each guide has one. | `user/overview` |
| `tutorial` | Get a first result from start to end, on one path | `user/getting-started`, `admin/first-steps` |
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
- Write alt text that says what the image shows, in at most 155 characters, as [GitLab](https://docs.gitlab.com/development/documentation/styleguide/) sets. Markdownlint rejects an image without alt text.
- Draw a diagram in Mermaid, not as an image, with `accTitle` and `accDescr`. `gate:diagrams` checks them.
- Do not add an animated GIF.

### Screenshots of the web app

A user page shows the web app with the `Screenshot` component.
The component shows the image of the reader's color scheme:

```mdx
import Screenshot from "@site/src/components/Screenshot";

<Screenshot src="practice-profile/overview" alt="Practice profile with an open feedback card." />
```

`vp run generate:docs-screenshots` makes every image from the built web app:

- The app reads a synthetic workspace from `webapp/e2e/screenshots/workspace.ts`. No server or database runs. Some data comes from fixture builders that the stories share. A change to them can change an image.
- The live event stream of the app stays open with no events. A new screen with another stream needs the same answer in `workspace.ts`.
- The capture uses a 1280 by 800 viewport, a fixed clock, and the UTC time zone. It blocks every request to another site, so no avatar of a real person appears.
- Chromium encodes each image as WebP. Each screen is captured in the light and the dark color scheme. The images go to `docs/static/img/user/<page>/<name>.<scheme>.webp`.
- An image of more than 150 KB fails the capture.
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
- A user page has no code blocks. It uses inline code only for text that the reader enters, such as a Slack command.

## Files and names

- Put a new page at `docs/<guide>/<name>.md`. The name is the subject in kebab-case, such as `backup-restore`. Do not repeat the guide name.
- Use `<name>.mdx` for a new page only if it uses a component. Docusaurus reads both extensions as MDX, so existing pages keep their extension.
- Add the page to its guide's sidebar, `docs/sidebars.<guide>.ts`, in the category of its task.
- Do not rename a published page in a content change. A rename breaks links from outside the site. Rename pages only in a change to the guide's structure.
- Do not put a date in a file name. A page states the current behavior. Git keeps the history.

## Checks

[Check a docs page](./check-a-docs-page.md) gives the steps before a pull request.
`gate:docs-pages` checks these rules in the user guide:

- Each page has a `title`, a `description` of at most 160 characters, and a `page_type` that the guide allows. No page sets an `id`.
- No page has a `#` heading in its body.
- No section uses a name from the "Do not use" column.
- Only a reference page links to the contributor guide.
- No inline code names a repository command or a source path.
- A page shows the product with `Screenshot`, not with a Markdown image.
- Each `Screenshot` has a light and a dark image.

In the admin and contributor guides, the gate checks on every page that a callout is a note, a tip, or a caution.
It checks the other rules below on a page that declares its type:

- A declared `page_type` is one that the guide allows.
- A tutorial has numbered steps and a `Next steps` section. A how-to page has numbered steps and a `Check that it worked` section.
- A `Troubleshooting` section is the last section.

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
