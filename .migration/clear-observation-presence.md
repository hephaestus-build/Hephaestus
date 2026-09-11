#### 🔴 Observation assessment status is now a separate field

Upgrade the server, sandbox runtime and webapp together. Drain running practice reviews before the
upgrade: old runtimes emit a combined outcome contract that the new server deliberately rejects.
Before resuming reviews, use the existing catalogue adoption flow to apply the updated bundled
practice definitions to installed workspace practices. Review instance-level overrides and custom
criteria for obsolete combined outcome labels and missing-capture instructions. Adoption creates
new practice revisions; historical revisions are deliberately not rewritten, and workspace
customizations are not silently overwritten.

Custom API consumers and custom runtime integrations must use:

- `assessmentStatus`: `ASSESSED`, `NOT_APPLICABLE` or `UNDETERMINED`.
- `presence`: `PRESENT` or `ABSENT` only for assessed observations, otherwise null.
- `assessment`: `GOOD` or `BAD` only for assessed observations, otherwise null.
- `severity`: required for bad assessments, null otherwise.

Observation filters now have an independent assessment-status facet. Review observation counts use
`undetermined`, not `inconclusive`. Raw historical review outputs remain historical artifacts; they
are not rewritten to pretend that old runtimes emitted the new contract.

Back up and verify restoration before upgrading. Liquibase maps existing PRESENT/ABSENT rows to
ASSESSED, NOT_APPLICABLE rows to NOT_APPLICABLE and INCONCLUSIVE rows to UNDETERMINED. It clears
presence for the two unassessed statuses and clears non-judgmental legacy severity values on non-BAD
rows. Existing BAD severity values and all evidence are retained. A BAD row without severity halts
the migration: inspect its recorded evidence and repair through an audited operator procedure rather
than assigning a fabricated default. Do not bypass this precondition.

Downgrading in place is unsupported because the runtime and wire contracts also changed. Recover by
restoring the verified pre-upgrade backup and the matching application/runtime versions together.
