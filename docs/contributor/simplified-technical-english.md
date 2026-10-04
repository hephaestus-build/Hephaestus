---
title: Simplified Technical English
description: The project policy for clear prose and its machine checks.
---

# Simplified Technical English

All human-facing prose in this repository follows ASD-STE100 Simplified Technical English (STE), Issue 9, dated January 15, 2025.
This page owns the project policy.
It does not replace the full standard.
Get the standard from [ASD](https://www.asd-ste100.org/).
Do not copy its specification or dictionary into this repository.

## Scope

Use STE in these surfaces:

- User, admin, and contributor docs, including page titles, descriptions, diagrams, and image text.
- UI text, accessible names, tooltips, form errors, and user-facing server responses.
- Changesets, pull request and issue templates, and the prose that contributors put in those templates.
- Repository instructions and skills, including `AGENTS.md` files.
- Code comments that explain behavior, constraints, or use to a developer.

Comments and instructions are in scope because people read and maintain them.
They are contributor text, even when a tool also reads them.
The [repository guide](https://github.com/hephaestus-build/Hephaestus/blob/main/AGENTS.md) treats comments as explanations of constraints and skills as contribution instructions.
Apply the same policy to prose for Heph and practice reviews.
Do not change a prompt's meaning to pass a style check.

The following content is not prose that we can rewrite:

- Executable code, identifiers, API keys, URLs, commands, paths, schema values, and configuration keys.
- Provider payloads, exact error output, test data that must reproduce external input, and quotations from external sources.
- Generated or third-party content, license notices, and historical release records.
- Accepted ADRs are historical decision records and remain unchanged during a prose-only rewrite.
- User content received or stored by the application.

Keep exact external text unchanged.
Mark it as a quote or code, and explain it in STE when necessary.
A sentence beside code is still in scope.
Do not hide prose in code spans to stop a check.

## Words and product terms

Use one word for one meaning, and use it in the same part of speech.
Use American English.
Use a short approved word when it expresses the same fact.
Do not change a technical fact to replace a word.

The [product vocabulary](./practice-feedback-language.md) is the project's list of STE technical names and technical verbs.
It owns each term and its meaning.
The [review glossary](./practice-review-glossary.mdx) owns review terms and is cited from that vocabulary.
Do not define a second list in a component, skill, or style guide.

STE permits technical names and verbs for a project or industry.
These terms are not a way to approve ordinary complex words.
Add a term to the product vocabulary only when it names a real software concept or action.
Use a technical name unchanged, including a name that ends in `-ing`.
Keep a technical verb's meaning and object clear.

## Sentences and grammar

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

## Procedures

A procedure tells the reader what to do.
Use numbered steps in the order that the reader must do them.
Write no more than 20 words per instruction sentence.
Put each instruction in its own sentence.
A step can contain related sentences, but do not join separate actions with **and**.

Put necessary conditions before the action.
Give the result after the action when the reader must check it.
Use a separate descriptive paragraph for an explanation that is not necessary to do the step.
Do not put an instruction in a note.

## Descriptions

A description tells the reader what a system is or does.
Write no more than 25 words per sentence.
Write no more than 6 sentences per paragraph.
Keep one subject in each paragraph.
Start with its main fact, then give the details in a logical order.

A label or heading can be a short noun phrase.
It does not need an article or a full sentence.
An error must state the problem and, when known, the action that the reader can take.

## Warnings, cautions, and notes

Put a safety instruction before the step to which it applies.
Use **WARNING** for a risk of injury and **CAUTION** for a risk of damage, including loss of data.
State the risk, the result, and the action that prevents it.
Use direct instructions, not vague advice.

Use a note only for information that helps the reader understand the text.
A note is not a place for a required action or safety instruction.
Keep a note short and about one subject.

## Word counts

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
The house oxlint plugin checks literal UI text in `webapp/src/**`.
Both use `.vale/words.json` and the product vocabulary.
The [tool source record](https://github.com/hephaestus-build/Hephaestus/blob/main/.vale/README.md) gives the license, pins, and commands.

The gate uses these error checks:

- General sentences have no more than 25 words.
- List sentences have no more than 20 words, a conservative project limit for steps and bullets.
- A paragraph has no more than 6 sentences.
- Words with clear replacements use those replacements.
- Contractions use full words, and semicolons become separate sentences.

The UI rule checks literal JSX text, literal text expressions, and known text props.
It includes static branches, template text, and text concatenation, but does not follow variables or function calls.
It does not check class names, routes, query keys, or other machine strings.
The UI gate checks replacements, contractions, semicolons, and the 25-word limit.
It cannot prove paragraph structure across components.

Vocabulary, passive voice, and noun or adjective `-ing` checks give suggestions, not errors.
The open word list is not the ASD dictionary and does not cover every valid technical word or inflected form.
A part-of-speech tagger can also be wrong.
Do not make these checks errors merely to claim full compliance.

Reviewers must check meaning, word senses, articles, instruction order, one instruction per sentence, and warnings.
They must also check UI text from variables, prompts, diagrams, comments, and user-facing server messages.
These surfaces follow STE even where the current tools cannot check them.

## Add enforcement

1. Run `vp run lint:prose <path>` for a Markdown or MDX file.
2. Fix each error. Review the suggestions without changing a technical fact.
3. Add the exact path to `.vale/enforced-paths.json`.
4. Run `vp run gate:prose` and the scoped checks for that tree.

For a UI file, add its path, then run `vp -C webapp lint <path-relative-to-webapp>`.
The list starts with the new foundation documents.
Each rewrite lane adds the paths it has made clean.
The gate compares the list with the base branch and rejects removal.
No path loses enforcement silently.

## Sources and limits

- [ASD](https://www.asd-ste100.org/) owns Issue 9 and the copyrighted specification and dictionary.
- [ASD's explanation](https://www.asd-ste100.org/about.html) describes controlled vocabulary and project-specific technical names and verbs.
- [OpenSTE](https://github.com/openste/openste/tree/19f01781a6162246ac56d4cab84835842609a31c) supplies the openly licensed word titles, not a compliance certificate.
- [Vale scopes](https://docs.vale.sh/topics/scopes) select prose rather than code.
- [Vale MDX support](https://docs.vale.sh/formats/mdx) reads MDX without an external parser in the pinned version.
- [Vale checks](https://docs.vale.sh/checks) provide the native rule types that this style uses.

This policy applies STE to software prose.
It does not claim that a machine can validate all 53 writing rules or all approved word senses.
