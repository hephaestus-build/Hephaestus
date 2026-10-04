# Prose checks

The [writing standard](../docs/contributor/simplified-technical-english.md) owns the project policy.
This directory contains the inputs for the checks.

## Word source

`words.json` contains approved word titles from OpenSTE and replacements that we wrote.
The source object records the commit, version, source file, SHA-256, and license.
We took only approved titles, not definitions or example sentences.
[OpenSTE](https://github.com/openste/openste/tree/19f01781a6162246ac56d4cab84835842609a31c) publishes these titles under the MIT license.
`OpenSTE-LICENSE.txt` contains its notice.

This list is not the ASD dictionary.
Do not copy the ASD specification or dictionary into this repository.
Ask ASD for [Issue 9](https://www.asd-ste100.org/request.html) if you need the full standard.

## Tool source

`toolchain.json` pins Vale 3.24.0 and the SHA-256 of each release archive.
It supports Linux, macOS, and Windows on x64 and arm64.
The first run downloads the archive from the pinned GitHub release.
Each run checks its hash and extracts a fresh executable into a temporary directory.
No system Vale, remote style package, or external MDX parser is used.
The cache can support later runs without network access.

Vale is under the MIT license.
See the [release](https://github.com/vale-cli/vale/releases/tag/v3.24.0) and its [license](https://github.com/vale-cli/vale/blob/v3.24.0/LICENSE).

## Commands

- Run `vp run gate:prose` to check the enforced paths and the fixtures.
- Run `vp run lint:prose <path>` to see errors and suggestions for a prose path.
- Run `vp run report:prose` to count alerts in the three docs trees and in the UI source of the webapp and the extension.
- Add exact paths to `enforced-paths.json` after their errors are fixed.

The list can grow, but the gate rejects removal of a path from the base branch.
A missing base revision is an error, not permission to skip this check.
A rename or deletion needs an explicit policy change and maintainer review.

The UI rule `hephaestus/ui-text-voice` uses [oxlint's JavaScript plugin API](https://oxc.rs/docs/guide/usage/linter/js-plugins.html) to read JSX literals.

JSX entity text is decoded with the [entities library](https://github.com/fb55/entities), pinned in the webapp dev dependencies.
It uses the BSD-2-Clause license and does not enter the application bundle.
Only JSX text and quoted JSX attributes are decoded.
JavaScript strings keep their literal meaning.

## Product voice

The user docs in `docs/user/` use the product voice, and the UI rule checks UI text with the same contraction profile.
`.vale.ini` has a section for `**/docs/user/**`.
It swaps `STE.Contractions` for `STE.NegativeContractions`, which rejects only negative contractions.
It also turns off the vocabulary, passive voice, and `-ing` suggestions, which describe STE.
The UI rule reads the same negative list through the `voice` profile in `scripts/lib/ste-words.ts`.

`.vale/fixtures/docs/user/` repeats the path of the real docs on purpose.
The `**/docs/user/**` glob matches it, so the fixtures prove the section.
Do not move them.

## Issue and discussion forms

The gate checks YAML files under `.github/ISSUE_TEMPLATE/` and `.github/DISCUSSION_TEMPLATE/`.
It uses the existing [YAML parser](https://eemeli.org/yaml/#parsing-yaml).
It selects only these prose fields from the [GitHub issue-form schema](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-githubs-form-schema):

- The form's `name` and `description`.
- Each block's `attributes.label`, `attributes.description`, and `attributes.placeholder`.
- `attributes.value` only for a `markdown` block.
- Each `attributes.options[].label` only for a `checkboxes` block.
- Each contact link's `name`.

IDs, types, validation keys, dropdown options, URLs, and non-Markdown values are not prose inputs.
The gate rejects invalid YAML, duplicate keys, and selected fields that are not strings.
Each selected field goes through Vale as a separate Markdown document.
This keeps sentence and paragraph boundaries within the field.
Alerts identify the source file, field path, and line within that field.
The gate removes the temporary documents after the check.

The good fixture places rejected words and contractions in machine fields.
The bad fixture places a rejected word in each selected prose field.
Together, they prove that prose is checked and configuration values stay unchanged.

## Skill metadata

For `.claude/skills/*/metadata.json`, the gate checks only the `abstract` prose field.
Version, organization, date, and reference URLs remain configuration values.
The gate uses the existing JSON helpers to validate the abstract before Vale checks it.

## Parser checks

The pinned Vale version includes native MDX support.
The fixtures check that component children are prose and that imports, expressions, and code are not prose.
They also check sentence and paragraph boundaries.
This protects against parser gaps such as [skipped component children](https://github.com/vale-cli/vale/issues/1155) and
[skipped text after an expression](https://github.com/vale-cli/vale/issues/1179).

An exact-source fixture also checks the boundaries of a rule-specific Vale exception.
Only the named rule stops inside the marked span.
Other rules still apply there, and the named rule applies again after the span.

The report scans Markdown and MDX in the three docs trees.
For UI source, it scans literal JSX text and text props, including stories, in `webapp/src` and `extension/src`.
It excludes generated clients, the route tree, tests, and mock data.
A dash in the report means that the UI rule does not implement that check.
Counts are alerts, not unique sentences or confirmed defects.
Vocabulary counts include valid technical terms and inflected forms that need manual review.
