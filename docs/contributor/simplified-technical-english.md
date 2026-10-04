---
title: Writing standard
description: The project policy for prose. Simplified Technical English for contributors and operators, and a plain, friendly voice for the product and its user docs.
---

# Writing standard

This project uses two writing profiles.
Each profile fits its reader.

| Profile | Surfaces |
| --- | --- |
| **Product voice** | UI text and accessible names in `webapp/src` and `extension/src`. Server responses, Slack and email text, and provider comments that people read. The user docs in `docs/user/`. |
| **Simplified Technical English (STE)** | Admin and contributor docs. Changesets, templates, repository instructions, and skills. Prompts and practice definitions that a model reads. |

The product voice is for people who use Hephaestus.
STE is for people who operate it, build it, or follow a procedure.
What a model writes for a person, such as feedback or a Heph reply, uses the product voice.
Both profiles use the same product terms.
Never add or drop a fact when you rewrite.

## Scope

Apply this standard to prose.
The following content is not prose that we can rewrite:

- Executable code, identifiers, API keys, URLs, commands, paths, schema values, and configuration keys.
- Provider payloads, exact error output, test data that must reproduce external input, and quotations from external sources.
- Generated or third-party content, license notices, and historical release records.
- Accepted ADRs are historical decision records and remain unchanged during a prose-only rewrite.
- User content received or stored by the application.
- Consent wording. It carries `WORDING_VERSION`, and a change needs a new version.

Keep exact external text unchanged.
Mark it as a quote or code, and explain it in the correct profile when necessary.
A sentence beside code is still in scope.
Do not hide prose in code spans to stop a check.

Code comments follow neither profile.
Write them plain and short, and state a constraint, not history.
Do not rewrite an existing comment only for style.
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

The readers are developers who use Hephaestus.
They read between other tasks, often on a small screen.
They need to know what happened, what it means for them, and what to do next.

[ASD](https://www.asd-europe.org/standards-specifications/simplified-technical-english/) scopes STE to maintenance manuals and other safety-critical publications.
Its strict form makes interface text stiff.
The rules below come from published UX writing guidance and cite it where they apply.
They draw on [GOV.UK](https://www.gov.uk/guidance/content-design/writing-for-gov-uk), [Microsoft](https://learn.microsoft.com/en-us/style-guide/welcome/), [Shopify Polaris](https://shopify.dev/docs/apps/design/content), [Material Design](https://codelabs.developers.google.com/codelabs/material-communication-guidance), and [Nielsen Norman Group](https://www.nngroup.com/articles/ui-copy/).

### The voice

Write the way a helpful colleague speaks: direct and specific.

- Address the reader as **you**. Use the active voice.
- Say **we** only when the product reports its own failure: `We could not save your changes`. Heph says **I** in a conversation.
- Put the point first. Put a condition before the action that depends on it.
- Use short, common words. The Home Office [recommends](https://design.homeoffice.gov.uk/accessibility/written-content/readability) a reading age of 9, even for expert readers.
- Write no more than 25 words per sentence. This project limit follows [GOV.UK](https://insidegovuk.blog.gov.uk/2014/08/04/sentence-length-why-25-words-is-our-limit/).
- Do not joke. Do not use exclamation marks, "please", "sorry", "oops", or marketing words.
- Do not blame the reader. Say what the system could not do.

### Voice for each situation

Tone changes with the situation.
[NN/g](https://www.nngroup.com/articles/ux-writing-faqs/) defines it as how the voice "adapts to a specific situation and the emotion it evokes in the reader".

| Situation | Tone and pattern | Example |
| --- | --- | --- |
| **Success** | Quiet and brief. Name what happened. Do not celebrate ([Polaris](https://shopify.dev/docs/apps/design/content/voice-and-tone)). | `Practice saved` |
| **Information** | Neutral. Say what is true and when it changes. | `Reviews start after you connect a repository.` |
| **Warning** | Calm. Give notice before a loss. Name the consequence and the way out. | `This repository has no webhook. New pull requests will not be reviewed until you add one.` |
| **Error** | Plain. Say what happened, then what to do next. | `We could not reach GitHub. Check the connection, then try again.` |
| **Empty** | Helpful. Say why it is empty and what will appear. Offer the way to get it. | `No feedback yet. It appears here after Heph reviews your next pull request.` |
| **Destructive** | Serious. Name the object and the consequence. Say if it can be undone. | `Delete practice "Code review"? This removes the practice and its observations. You cannot undo this.` |
| **Onboarding** | Encouraging, one step at a time. Lead to the first result. | `Connect a repository to get your first feedback.` |
| **Progress** | Present participle with a single ellipsis character. | `Saving…` |

### Contractions

Write positive contractions.
Spell out negative ones.

- Prefer `you're`, `we'll`, `let's`, `it's`, and `that's` in conversational text. They sound like a person. [Microsoft](https://learn.microsoft.com/en-us/style-guide/word-choice/use-contractions) and [Shopify](https://shopify.dev/docs/apps/design/content/grammar-and-mechanics) recommend contractions.
- Write `cannot`, `do not`, `does not`, `is not`, `will not`, and `has not`. Some readers miss the `n't` ending and read the opposite meaning ([GOV.UK](https://guidance.publishing.service.gov.uk/writing-to-gov-uk-standards/writing-guidelines/clear-language/)). People with dyslexia and people who read English as a second language misread them more ([Civil Service Analysis Function](https://analysisfunction.civilservice.gov.uk/policy-store/making-analytical-publications-accessible/)). Microsoft and Shopify allow negative contractions. Banning them is a project decision.
- Keep `not` a separate word. `You are not a member` and `You're not a member` are correct. `You aren't a member` is not.
- Do not use contractions that are hard to scan: `it'll`, `they'd`, `there'd`, `should've`, `they've`.
- Do not mix forms in one message. If one clause says `you're`, the next does not say `you are`.
- Write the apostrophe as `’` (U+2019) in UI text, so the glyph is the same everywhere.

The `ui-text-voice` lint rule rejects negative contractions and a straight apostrophe.
Humans check the other points.

### Buttons, links, and labels

- Start a button with a verb, then the object: `Save practice`, `Connect repository`, `Delete workspace`. Say what happens next ([NN/g](https://www.nngroup.com/articles/ui-copy/)).
- Use 2 to 4 words. A button in a dialog repeats the verb of the dialog title.
- Do not use `OK`, `Yes`, or `No` to confirm ([NN/g](https://www.nngroup.com/articles/confirmation-dialog/)). Do not use `Submit`. Name the action.
- Use sentence case for buttons, labels, headings, tabs, and menu items ([GOV.UK](https://design-system.service.gov.uk/components/button/), [Microsoft](https://learn.microsoft.com/en-us/style-guide/capitalization)). Capitalize a proper name and the first word of a product term, such as `Practice profile`.
- Write link text that makes sense alone: `Read the privacy notice`, not `Click here` ([WCAG 2.4.4](https://www.w3.org/WAI/WCAG22/Understanding/link-purpose-in-context.html)).
- Say `select`, not `click` or `tap`. Say `enter` for typing and `turn on` or `turn off` for a switch.
- Show a pending action in the control: `Saving…`, `Connecting…`. Use the single character `…`, not three dots.
- Do not end a fragment with a period. Fragments are buttons, labels, headings, tabs, badges, and one-clause toasts. End every sentence with a period.
- Do not join clauses with an em dash.

### Accessible names

- Start the accessible name with the visible label. [WCAG 2.5.3](https://www.w3.org/WAI/WCAG22/Understanding/label-in-name.html) requires only that the name contains it. A visible `Delete` can have the name `Delete Code review`.
- Make each name unique on its screen. Add the object when the same action repeats in a list.
- Name an icon-only control by its action: `Close`, not `X`.
- Name the same function the same way on every screen ([WCAG 3.2.4](https://www.w3.org/WAI/WCAG22/Understanding/consistent-identification.html)).
- Give a decorative image an empty `alt`. Give an informative image text that says what it shows.

### Error messages

An error says what happened and what the reader can do.
[GOV.UK](https://design-system.service.gov.uk/components/error-message/) says to "describe what has happened and tell them how to fix it".

- **Use one form.** A title and a toast start `We could not …`. The detail adds the cause and the next step.
- **Name the cause when you know it.** `We could not reach GitHub` beats `Something went wrong`.
- **Use the generic message only for an unknown cause.** Then give the reader a step: `We could not finish that. Try again. If it keeps failing, contact your instance operator.`
- **Say what the system could not do.** Write `We could not save your changes`, not `You entered an invalid name`.
- **Fix the field.** A validation message names the field and the fix: `Enter a name of 3 to 40 characters`, not `Invalid input` ([WCAG 3.3.1](https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html), [3.3.3](https://www.w3.org/WAI/WCAG22/Understanding/error-suggestion.html)).
- **Do not apologize or joke.** Material warns that a joke in an error "might fail" to support the reader.
- **Do not lead with `Error:` or `Failed to`.**

The title and detail of a server `ProblemDetail` follow the same pattern, and so do Slack messages, emails, and provider comments.
The `type` URI, the error code, and field names are machine values and do not change.

### Empty states

An empty state says why it is empty, what will appear, and how to get it ([Shopify](https://shopify.dev/docs/api/app-home/patterns/compositions/empty-state), [NN/g](https://www.nngroup.com/articles/empty-state-interface-design/)).

- Do not leave a bare `No items`.
- Offer one primary action when the reader can take it.
- Separate "nothing yet" from "nothing matches". For filters, say that no result matches and offer `Clear filters`.
- Name who can fix it when the reader cannot: `Ask a workspace admin to connect a repository.`
- Use the noun from the [vocabulary page](./practice-feedback-language.md).

### Destructive confirmations

A confirmation names the object and the consequence ([NN/g](https://www.nngroup.com/articles/confirmation-dialog/)).

- The title names the action and the object: `Delete practice "Code review"?`
- The description says what is lost, how much, and whether the reader can undo it. Prefer an `Undo` action to a confirmation when the reader can undo it.
- The confirm button repeats the action: `Delete practice`. The other button is `Cancel` or names the safe choice.
- Do not ask `Are you sure?`.
- Ask only when the loss is serious. Too many confirmations teach readers to ignore them.

### Words we use and words we avoid

The [product vocabulary](./practice-feedback-language.md) owns the product terms.
This table covers general words.

| Use | Avoid |
| --- | --- |
| `sign in`, `sign out` | `log in`, `login`, `log out` |
| `select`, `enter`, `turn on` | `click`, `tap`, `type in` |
| `could not`, `cannot` | `failed to`, `unable to` |
| `delete` (the object is gone), `remove` (it leaves a set and stays elsewhere) | `delete` and `remove` as synonyms |
| `connect`, `disconnect` | `link`, `unlink`, `integrate` |
| `use`, `with`, `through` | `utilize`, `leverage`, `via` |
| `Retry` on a button, `try again` in a sentence | `please try again`, `try again later` |
| `you`, `your` | `the user` |

### User docs

The user docs use the product voice, because the same people read them and the UI.
Follow every rule above, plus these:

- Write headings in sentence case. Start a procedure heading with a verb.
- Use numbered steps, one action per step, in the imperative.
- Write the label of a UI element exactly as the UI shows it, in bold.
- Do not mention repository tooling or source paths.

Vale also checks sentence length there: 25 words, and 20 for steps and list items.

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
The house oxlint rule `ui-text-voice` checks literal UI text in `webapp/src` and `extension/src`.
Both read `.vale/words.json` and the product vocabulary.
The [tool source record](https://github.com/hephaestus-build/Hephaestus/blob/main/.vale/README.md) gives the license, pins, and commands.

Vale reports these errors in all docs:

- General sentences have no more than 25 words.
- List sentences have no more than 20 words, a conservative project limit for steps and bullets.
- A paragraph has no more than 6 sentences.
- Words with clear replacements use those replacements.
- Semicolons become separate sentences.

In STE docs, Vale also rejects every contraction.
In `docs/user/`, it rejects only negative contractions.

The `ui-text-voice` rule reports these errors in UI text:

- A negative contraction.
- A straight apostrophe.
- A word with a clear replacement, such as `utilize`.
- A semicolon.
- A sentence of more than 25 words.

The rule checks literal JSX text, literal text expressions, and known text props.
It also checks the message of a `toast` call and the text keys of object literals, such as `label` and `description`.
It includes static branches, template text, and text concatenation, but does not follow variables or function calls.
It does not check class names, routes, query keys, `code` elements, or other machine strings.
The lint configuration of each tree runs it as an error on all of `src`, except tests and, in the webapp, `src/mocks`.

Vocabulary, passive voice, and noun or adjective `-ing` checks give suggestions, not errors.
They apply to STE prose only.
The open word list is not the ASD dictionary and does not cover every valid technical word or inflected form.
A part-of-speech tagger can also be wrong.

No tool can check tone, a button that names its action, or an error that gives the next step.
Reviewers check those points on the rendered screen.
They also check the meaning, word senses, and warnings in STE prose.
They check text from variables, prompts, diagrams, and user-facing server messages, which the tools cannot reach.

## Add enforcement

1. Run `vp run lint:prose <path>` for a Markdown or MDX file.
2. Fix each error. Review the suggestions without changing a technical fact.
3. Add the exact path to `.vale/enforced-paths.json`.
4. Run `vp run gate:prose` and the scoped checks for that tree.

The gate compares the list with the base branch and rejects removal.

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

No machine can validate all 53 STE writing rules or all approved word senses.
