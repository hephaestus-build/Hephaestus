---
title: Writing standard
description: The project policy for prose. Simplified Technical English for contributors and operators, and a plain, friendly voice for the product and its user docs.
---

# Writing standard

This project uses two writing profiles.
Each profile fits the reader and the task.

| Profile | Surfaces |
| --- | --- |
| **Product voice** | UI text and accessible names in `webapp/src` and `extension/src`. User-facing server responses, Slack and email text, and comments that Hephaestus posts on a provider. The user docs in `docs/user/`. |
| **Simplified Technical English (STE)** | Admin and contributor docs. Changesets, templates, repository instructions, and skills. Code comments. Prose for Heph and practice reviews. |

The product voice is for people who use Hephaestus.
STE is for people who operate it, build it, or follow a procedure.
Both profiles share the product terms and the sentence limits.
Never add or drop a fact when you rewrite.

## Scope

Apply this standard to prose.
The following content is not prose that we can rewrite:

- Executable code, identifiers, API keys, URLs, commands, paths, schema values, and configuration keys.
- Provider payloads, exact error output, test data that must reproduce external input, and quotations from external sources.
- Generated or third-party content, license notices, and historical release records.
- Accepted ADRs are historical decision records and remain unchanged during a prose-only rewrite.
- User content received or stored by the application.

Keep exact external text unchanged.
Mark it as a quote or code, and explain it in the correct profile when necessary.
A sentence beside code is still in scope.
Do not hide prose in code spans to stop a check.

Comments and instructions are contributor text, even when a tool also reads them.
They use STE.
The [repository guide](https://github.com/hephaestus-build/Hephaestus/blob/main/AGENTS.md) treats comments as explanations of constraints and skills as contribution instructions.
Do not change a prompt's meaning to pass a style check.

## Words and product terms

Use one word for one meaning, and use it in the same part of speech.
Use American English.
Use a short, common word when it expresses the same fact.
Do not change a technical fact to replace a word.

The [product vocabulary](./practice-feedback-language.md) is the project's list of technical names and technical verbs.
It owns each term and its meaning.
The [review glossary](./practice-review-glossary.mdx) owns review terms and is cited from that vocabulary.
Do not define a second list in a component, skill, or style guide.

Use a technical name unchanged, including a name that ends in `-ing`.
Add a term to the product vocabulary only when it names a real software concept or action.

## UI and user-facing text

The people who read this text are developers who use Hephaestus.
They read it between other tasks, often on a small screen.
They need to know what happened, what it means for them, and what to do next.

STE was written for aircraft maintenance manuals.
[ASD](https://www.asd-europe.org/standards-specifications/simplified-technical-english/) scopes it to manuals and other safety-critical publications.
Its strict form (no contractions, no `-ing` nouns, one instruction per sentence) makes interface text stiff.
For UI text we take our rules from established UX writing guidance:

- [GOV.UK content design](https://www.gov.uk/guidance/content-design/writing-for-gov-uk).
- [GOV.UK Design System](https://design-system.service.gov.uk/).
- [Microsoft Writing Style Guide](https://learn.microsoft.com/en-us/style-guide/welcome/).
- [Shopify Polaris content guidelines](https://shopify.dev/docs/apps/design/content).
- [Material Design writing guidance](https://codelabs.developers.google.com/codelabs/material-communication-guidance).
- [Nielsen Norman Group](https://www.nngroup.com/articles/ui-copy/).

### The voice

Write the way a helpful colleague would speak: direct, calm, and specific.

- Address the reader as **you**. Say what Hephaestus does in the active voice.
- Put the point first. Put a condition before the action that depends on it.
- Use short, common words. Write for a reading age of 9, as [GOV.UK practice](https://design.homeoffice.gov.uk/accessibility/written-content/readability) recommends, even for expert readers.
- Write no more than 25 words per sentence ([GOV.UK](https://insidegovuk.blog.gov.uk/2014/08/04/sentence-length-why-25-words-is-our-limit/)).
- Do not use jokes, exclamation marks, "please", "sorry", "oops", or marketing words. They cost trust when the news is bad.
- Do not blame the reader. Say what the system could not do, not what the reader did wrong.

### Voice for each situation

The voice stays the same. The tone changes with what the reader is feeling.
[NN/g](https://www.nngroup.com/articles/ux-writing-faqs/) defines tone as how the voice "adapts to a specific situation and the emotion it evokes in the reader".

<!-- vale STE.Contractions = NO -->

| Situation | Tone and pattern | Example |
| --- | --- | --- |
| **Success** | Quiet and brief. Name what happened. Do not celebrate ([Polaris](https://shopify.dev/docs/apps/design/content/voice-and-tone)). | `Practice saved` |
| **Information** | Neutral. Say what is true and when it changes. | `Reviews start after you connect a repository.` |
| **Warning** | Calm. Give notice before a loss. Name the consequence and the way out. | `This repository has no webhook. New pull requests will not be reviewed until you add one.` |
| **Error** | Plain. Say what happened, then what to do next. Do not blame. | `We could not reach GitHub. Check the connection, then try again.` |
| **Empty** | Helpful. Say what will appear and how to get it. | `No feedback yet. It appears here after Heph reviews your next pull request.` |
| **Destructive** | Serious. Name the object and the consequence. Say if it can be undone. | `Delete practice "Code review"? This removes the practice and its observations. You cannot undo this.` |
| **Onboarding** | Encouraging, one step at a time. Lead to the first result. | `Connect a repository to get your first feedback.` |
| **Progress** | Present tense with a single ellipsis character. | `Saving…` |

<!-- vale STE.Contractions = YES -->

### Contractions

Write positive contractions.
Spell out negative ones.

- Use `you're`, `we'll`, `let's`, `it's`, and `that's`. They are what a person would say, so the text sounds like one. [Microsoft](https://learn.microsoft.com/en-us/style-guide/word-choice/use-contractions) and [Shopify](https://shopify.dev/docs/apps/design/content/grammar-and-mechanics) recommend them.
- Write `cannot`, `do not`, `does not`, `is not`, `will not`, and `has not`. Do not write `can't`, `don't`, or `won't`. Some readers miss the `n't` ending and read the opposite meaning. Readers with dyslexia and readers who learned English second misread them most ([GOV.UK](https://guidance.publishing.service.gov.uk/writing-to-gov-uk-standards/style-guides/a-to-z-style-guide/), [Civil Service Analysis Function](https://analysisfunction.civilservice.gov.uk/policy-store/making-analytical-publications-accessible/)). The evidence is practitioner research, not controlled studies.
- Keep `not` a separate word. `You're not a member` is correct. `You aren't a member` is not.
- Do not use contractions that are hard to scan: `it'll`, `they'd`, `there'd`, `should've`.
- Do not mix forms in one message. If one clause says `you're`, do not write `you are` in the next. Do not write `cannot` in one message of a flow and `can not` in another.
- Write the apostrophe as `’` (U+2019) in every UI string, so the glyph is the same everywhere.

The `ui-text-voice` lint rule rejects negative contractions and a straight apostrophe in a contraction.
Humans check the rest.

### Buttons, links, and labels

- Start a button with a verb, then the object: `Save practice`, `Connect repository`, `Delete workspace`. Name what happens next ([NN/g](https://www.nngroup.com/articles/ui-copy/)).
- Use 1 to 4 words. A button in a dialog repeats the verb of the dialog title.
- Do not use `OK`, `Yes`, `No`, or `Submit`. Use `Cancel` or `Keep practice` for the safe choice ([NN/g](https://www.nngroup.com/articles/confirmation-dialog/)).
- Use sentence case for buttons, labels, headings, tabs, and menu items ([GOV.UK](https://design-system.service.gov.uk/components/button/), [Microsoft](https://learn.microsoft.com/en-us/style-guide/capitalization)). Capitalize a proper name and the first word of a product term, such as `Practice profile`.
- Write link text that makes sense alone: `Read the privacy guide`, not `Click here` or `Learn more` ([WCAG 2.4.4](https://www.w3.org/WAI/WCAG22/Understanding/link-purpose-in-context.html)).
- Say `select`, not `click` or `tap`. Say `enter` for typing and `turn on` or `turn off` for a switch.
- Show a pending action in the control: `Saving…`, `Connecting…`. Use the single character `…`, not three dots.
- Do not end a button, label, heading, tab, badge, or other fragment with a period. End every complete sentence with a period.
- Do not use an exclamation mark. Do not use an em dash to join clauses. Use two sentences or a comma.

### Accessible names

- Start the accessible name with the visible label ([WCAG 2.5.3](https://www.w3.org/WAI/WCAG22/Understanding/label-in-name.html)). A visible `Delete` can have the name `Delete Code review`.
- Make each name unique on its screen. Add the object when the same action repeats in a list.
- Name an icon-only control by its action, not by its picture: `Close`, not `X`.
- Name the same function the same way on every screen ([WCAG 3.2.4](https://www.w3.org/WAI/WCAG22/Understanding/consistent-identification.html)).
- Write alt text for information. Leave decoration with an empty `alt` or `aria-hidden`.

### Error messages

An error says what happened and what the reader can do.
It does not blame the reader and does not use a code as its only content.
[GOV.UK](https://design-system.service.gov.uk/components/error-message/) says to "describe what has happened and tell them how to fix it".

- **Name the cause when you know it.** `We could not reach GitHub` is better than `Something went wrong`.
- **Use the generic message only for an unknown cause.** Then say what the reader can try: `We could not finish that. Try again. If it keeps failing, contact your workspace admin.`
- **Say what the system could not do.** Write `We could not save your changes`, not `You entered an invalid name`.
- **Fix the field.** A validation message names the field and the fix: `Enter a name of 3 to 40 characters`, not `Invalid input` or `Wrong name` ([WCAG 3.3.1](https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html), [3.3.3](https://www.w3.org/WAI/WCAG22/Understanding/error-suggestion.html)).
- **Do not repeat the title.** The title names the problem. The detail adds the cause and the next step.
- **Do not apologize or joke.** An error is the worst moment for either.
- **Do not lead with `Error:` or `Failed to`.** Write `We could not …` or name the object: `The webhook could not be verified`.

Use the same pattern for the title and detail of a server `ProblemDetail`, and for the text of Slack messages, emails, and provider comments.
The `type` URI, the error code, and field names are machine values and do not change.

### Empty states

An empty state says what will appear here and how to get it ([Shopify](https://shopify.dev/docs/api/app-home/patterns/compositions/empty-state), [NN/g](https://www.nngroup.com/articles/empty-state-interface-design/)).

- Never leave a bare `No items` or `No feedback`.
- Offer one primary action when the reader can take it.
- Separate "nothing yet" from "nothing matches". For filters, say `No practices match these filters` and offer `Clear filters`.
- Say who can fix it when the reader cannot: `Ask a workspace admin to connect a repository.`
- Follow the [vocabulary page](./practice-feedback-language.md) for the noun. Write `No practices here`, not `No practices in this group`.

### Destructive confirmations

A confirmation names the object and the consequence ([NN/g](https://www.nngroup.com/articles/confirmation-dialog/)).

- The title names the action and the object: `Delete practice "Code review"?`
- The description states what is lost, how much, and whether the reader can undo it. If the reader can undo it, prefer an `Undo` action to a confirmation.
- The confirm button repeats the action: `Delete practice`. The other button is `Cancel` or names the safe choice.
- Do not ask `Are you sure?`.
- Ask for confirmation only when the loss is serious. Too many confirmations teach readers to ignore them.

### Words we use and words we avoid

The [product vocabulary](./practice-feedback-language.md) owns the product terms.
This table covers the general words.

<!-- vale STE.Contractions = NO -->

| Use | Avoid |
| --- | --- |
| `sign in`, `sign out`, `sign-in page` | `log in`, `login`, `log out` |
| `select`, `enter`, `turn on` | `click`, `tap`, `toggle`, `type in` |
| `could not`, `cannot` | `failed to`, `unable to`, `can't` |
| `delete` (the object is gone), `remove` (it leaves a set and stays elsewhere) | `delete` and `remove` as synonyms |
| `connect`, `disconnect` | `link`, `unlink`, `integrate` |
| `use`, `with`, `through` | `utilize`, `leverage`, `via` |
| `Retry` on a button, `try again` in a sentence | `retry` in a sentence, `please try again` |
| `you`, `your` | `the user`, `my` (in an action label) |

<!-- vale STE.Contractions = YES -->

### User docs

The user docs in `docs/user/` use the product voice, because the same people read them and the UI.
Follow every rule above, plus these:

- Write headings in sentence case. Start a procedure heading with a verb.
- Use numbered steps, one action per step, in the imperative.
- Write the label of a UI element exactly as the UI shows it, in bold.
- Do not mention repository tooling or source paths.

The admin docs in `docs/admin/` and the contributor docs in `docs/contributor/` stay in STE.

### Review before you merge

Read each changed string on its screen, in context, as a user.
Then check these points:

1. Does the text say what happened and what to do next?
2. Does the button name the action, and does a dialog title repeat that verb?
3. Is each product term the one in the vocabulary page?
4. Does the text blame the reader, joke, or use a generic message when the cause is known?
5. Is the accessible name unique and does it contain the visible label?

Text that gets the user's consent is a special case.
The consent wording carries `WORDING_VERSION`, and a change needs a new version.

## STE for contributor and operator prose

Admin docs, contributor docs, and the other STE surfaces follow ASD-STE100 Simplified Technical English, Issue 9, dated January 15, 2025.
This page owns the project policy.
It does not replace the full standard.
Get the standard from [ASD](https://www.asd-ste100.org/).
Do not copy its specification or dictionary into this repository.

STE permits technical names and verbs for a project or industry.
These terms are not a way to approve ordinary complex words.
Keep a technical verb's meaning and object clear.

### Sentences and grammar

- Write one instruction per sentence.
- Use the active voice. Name the actor when it is not the reader.
- Use the imperative for an instruction: **Select the workspace.**
- Put a condition before its instruction: **If the test fails, read the log.**
- Use simple verb forms. Prefer the present tense for behavior and the past tense for a completed event.
- Do not use a noun or adjective that ends in `-ing`, unless it is an approved technical name.
- Replace that form with a plain noun or a clause with an active verb.
- Use articles such as **a**, **an**, and **the** where grammar requires them.
- Do not omit articles to make a sentence shorter.
- Use no more than 3 nouns in a noun cluster. A technical name can have more words.
- Use a preposition when the relationship is not clear.
- Use a list to show three or more separate items.
- Write full words instead of contractions.
- Write separate sentences instead of a semicolon.

Some contractions have more than one full form.
Choose the form that keeps the original meaning.

Do not ban every word that ends in `-ing`.
A verb form and a noun are different.
A technical name such as **writing standard** can keep its name.
A reviewer must check the part of speech and meaning.

### Procedures

A procedure tells the reader what to do.
Use numbered steps in the order that the reader must do them.
Write no more than 20 words per instruction sentence.
Put each instruction in its own sentence.
A step can contain related sentences, but do not join separate actions with **and**.

Put necessary conditions before the action.
Give the result after the action when the reader must check it.
Use a separate descriptive paragraph for an explanation that is not necessary to do the step.
Do not put an instruction in a note.

### Descriptions

A description tells the reader what a system is or does.
Write no more than 25 words per sentence.
Write no more than 6 sentences per paragraph.
Keep one subject in each paragraph.
Start with its main fact, then give the details in a logical order.

A label or heading can be a short noun phrase.
It does not need an article or a full sentence.
An error must state the problem and, when known, the action that the reader can take.

### Warnings, cautions, and notes

Put a safety instruction before the step to which it applies.
Use **WARNING** for a risk of injury and **CAUTION** for a risk of damage, including loss of data.
State the risk, the result, and the action that prevents it.
Use direct instructions, not vague advice.

Use a note only for information that helps the reader understand the text.
A note is not a place for a required action or safety instruction.
Keep a note short and about one subject.

### Word counts

Count a number, abbreviation, or alphanumeric identifier as one word.
Count a hyphenated word as one word.
A technical name counts as one word in a manual STE review.
Do not treat punctuation as a word.

The machine count is conservative: it counts the separate words of a technical name.
Vale does not count text in code spans.
A reviewer must check a sentence that combines prose and code.
Keep such sentences short. A green gate is not proof of full STE compliance.

## Machine checks and human review

[Vale](https://vale.sh/) checks Markdown and MDX with a repository-owned style.
The house oxlint plugin checks literal UI text in `webapp/src` and `extension/src`.
Both read `.vale/words.json`, and Vale reads the product vocabulary.
The [tool source record](https://github.com/hephaestus-build/Hephaestus/blob/main/.vale/README.md) gives the license, pins, and commands.

Vale reports these errors:

- General sentences have no more than 25 words.
- List sentences have no more than 20 words, a conservative project limit for steps and bullets.
- A paragraph has no more than 6 sentences.
- Words with clear replacements use those replacements.
- Contractions use full words, and semicolons become separate sentences.

In `docs/user/`, Vale allows positive contractions and still rejects negative ones.

The `ui-text-voice` rule reports these errors in UI text:

- A negative contraction, or a straight apostrophe in a contraction.
- A word with a clear replacement, such as `utilize`.
- A semicolon.
- A sentence of more than 25 words.

The rule checks literal JSX text, literal text expressions, and known text props.
It also checks the message of a `toast` call and the text keys of object literals, such as `label` and `description`.
It includes static branches, template text, and text concatenation, but does not follow variables or function calls.
It does not check class names, routes, query keys, or other machine strings.
The lint configuration of the webapp and of the extension runs it as an error on all of `src`, except tests and `src/mocks`.

Vocabulary, passive voice, and noun or adjective `-ing` checks give suggestions, not errors.
They apply to STE prose.
The open word list is not the ASD dictionary and does not cover every valid technical word or inflected form.
A part-of-speech tagger can also be wrong.
Do not make these checks errors merely to claim full compliance.

No tool can check tone, a button that names its action, or an error that gives the next step.
Reviewers must check those points on the rendered screen, and they must check the meaning, word senses, and warnings in STE prose.
They must also check text from variables, prompts, diagrams, and user-facing server messages.
These surfaces follow this standard even where the tools cannot check them.

## Add enforcement

1. Run `vp run lint:prose <path>` for a Markdown or MDX file.
2. Fix each error. Review the suggestions without changing a technical fact.
3. Add the exact path to `.vale/enforced-paths.json`.
4. Run `vp run gate:prose` and the scoped checks for that tree.

The list starts with the new foundation documents.
Each documentation rewrite lane adds the paths it has made clean.
The gate compares the list with the base branch and rejects removal.
No path loses enforcement silently.

A UI file needs no entry, because the lint configuration of each tree covers it.
Run `vp -C webapp lint <path>` or `vp -C extension lint <path>` with a path relative to that tree.

## Sources and limits

- [ASD](https://www.asd-ste100.org/) owns Issue 9 and the copyrighted specification and dictionary.
- [ASD's scope statement](https://www.asd-europe.org/standards-specifications/simplified-technical-english/) names maintenance manuals and other safety-critical technical publications.
- [ASD's explanation](https://www.asd-ste100.org/about.html) describes controlled vocabulary and project-specific technical names and verbs.
- [OpenSTE](https://github.com/openste/openste/tree/19f01781a6162246ac56d4cab84835842609a31c) supplies the openly licensed word titles, not a compliance certificate.
- [Vale scopes](https://docs.vale.sh/topics/scopes) select prose rather than code.
- [Vale MDX support](https://docs.vale.sh/formats/mdx) reads MDX without an external parser in the pinned version.
- [Vale checks](https://docs.vale.sh/checks) provide the native rule types that this style uses.
- [Apple Human Interface Guidelines: Writing](https://developer.apple.com/design/human-interface-guidelines/writing) and [Atlassian content](https://atlassian.design/foundations/content/) agree with the product voice rules above.

This policy applies STE to software prose and a plain voice to product text.
It does not claim that a machine can validate all 53 STE writing rules or all approved word senses.
