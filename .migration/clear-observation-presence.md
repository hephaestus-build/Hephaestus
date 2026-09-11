#### 🔴 Observation status, target assessment and outcome are now distinct

Upgrade the server, sandbox runtime and webapp together. Drain running practice reviews before the
upgrade: old runtimes emit a combined outcome contract that the new server deliberately rejects.
Before resuming reviews, use the existing catalogue adoption flow to apply the updated bundled
practice definitions to installed workspace practices. Review instance-level overrides and custom
criteria for obsolete combined outcome labels and missing-capture instructions. Each automated practice must name one fixed target and declare `TARGET ASSESSMENT: GOOD` for desirable behaviour or `TARGET ASSESSMENT: BAD` for undesirable behaviour. Do not change target desirability when its presence changes. Adoption creates
new practice revisions; historical revisions are deliberately not rewritten, and workspace
customizations are not silently overwritten.

Custom API consumers and custom runtime integrations must use:

- `assessmentStatus`: `ASSESSED`, `NOT_APPLICABLE` or `UNDETERMINED`.
- `presence`: `PRESENT` or `ABSENT` only for assessed observations, otherwise null.
- `assessment`: target desirability, `GOOD` or `BAD` only for assessed observations, otherwise null.
- `outcome`: read-only `POSITIVE` for PRESENT/GOOD or ABSENT/BAD, `NEGATIVE` for PRESENT/BAD or ABSENT/GOOD, null when unassessed. Never annotate it independently.
- `severity`: required exactly for negative outcomes, null otherwise.

Use outcome—not assessment alone—for severity, feedback eligibility, counts and trends. Developer summaries expose `positiveCount` and `negativeCount`; standing observations expose their descriptive `kind` separately from outcome.

Observation filters now have an independent assessment-status facet. Review observation counts use
`undetermined`, not `inconclusive`. Raw historical review outputs remain historical artifacts; they
are not rewritten to pretend that old runtimes emitted the new contract.

Back up and verify restoration before upgrading. Liquibase maps existing PRESENT/ABSENT rows to
ASSESSED, NOT_APPLICABLE rows to NOT_APPLICABLE and INCONCLUSIVE rows to UNDETERMINED. It clears
presence for the two unassessed statuses and clears non-judgmental legacy severity values on non-BAD
rows under the old assessment-as-verdict convention. It swaps GOOD/BAD on historical ABSENT rows to preserve their original outcome; it does not reinterpret their evidence against new criteria. Historical negative severity values and all evidence are retained. A historical BAD row without severity halts
the migration: inspect its recorded evidence and repair through an audited operator procedure rather
than assigning a fabricated default. Do not bypass this precondition.

Downgrading in place is unsupported because the runtime and wire contracts also changed. Recover by
restoring the verified pre-upgrade backup and the matching application/runtime versions together.
