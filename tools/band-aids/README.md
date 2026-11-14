# Suppression and marker baseline

The baseline stops new suppressions and failure bypasses while their owners fix the existing causes.
An entry records existing source. It does not approve that source as correct.

Run `node scripts/check-band-aids.ts` to check the baseline.
The tooling test suite runs `scripts/check-band-aids.test.ts`.

Each entry names a category, file, source, code context, occurrence count, owner, and reason tag:

- `constraint: <named platform or protocol constraint>` records a retained exception.
- `root-cause: <required fix>` records a cause that its owner must fix.
- `dead: <deletion proof>` records a path that its owner must delete.

A retained exception needs a specific constraint. A reference such as “as above” is insufficient.
A pending review is not a retained exception.
The lane inventory records the semantic decisions and current ownership.

The identity includes the source control and its enclosing and following code context.
The gate rejects a new identity, a move to another scope or file, or a larger occurrence count.
It compares the committed allowances with `origin/main`, so editing the baseline cannot admit an increase.
The first baseline needs review because `main` has no earlier baseline to compare.
Owner and reason corrections do not change the admitted source or count.
A cause or dead path cannot become a retained exception through a baseline edit.
Changes to the scanner and baseline need the independent review required for a contribution.

After you remove a marker, run `node scripts/check-band-aids.ts --shrink`.
This command deletes stale entries and reduces counts. It rejects every new identity or occurrence before writing.
Commit the reduced baseline with the fix. There is no command that adds allowances.

The categories cover compiler and linter suppressions, formatter and prose controls, architecture exclusions, test controls, ignored catches, and failure bypasses.
They also cover configuration exclusions and the requested comment words: TODO, FIXME, HACK, XXX, workaround, temporary, for now, and until.
These words are syntax markers even when a comment describes an ordinary state transition or a temporary directory.
Such an existing comment has a `constraint:` tag that states its actual phase, storage, or protocol fact.
The count does not claim that each word identifies an implementation defect.
Release-age exclusions and image vulnerability exceptions have separate categories.
The release vulnerability verifier still owns evidence, expiry, and subject checks.
This gate does not replace that verifier.

The scan reads tracked files and new, non-ignored source files.
Generated API clients, the route tree, and the MSW worker retain generator ownership.
Ignored build outputs are absent from the Git file list.
Handwritten generator templates remain in scope.
Historical release notes are outside this source baseline.
The [Vite/Rolldown parser](https://rolldown.rs/reference/Function.parseSync) owns JavaScript and TypeScript syntax, including JSX and template expressions.
String and regular-expression fixtures do not count as active source controls.
Markdown prose is outside the debt-comment category. Its HTML control comments remain in scope.
The scan counts explicit syntax. It does not infer whether a nullable value violates a model or whether a recovery result is correct.
Those decisions need contract review and focused tests.

`gate:quarantine` owns the issue, owner, and expiry requirements for unconditional test quarantines.
This baseline does not admit a quarantine or replace that gate.
Conditional platform and live-service prerequisites are counted here with their named constraint.
The count may only shrink, including when another gate would accept an expiring quarantine.
