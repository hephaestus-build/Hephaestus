#### 🔴 Review and update stored source policies before resuming reviews

Pause new practice reviews and let in-flight reviews finish before upgrading. This runtime uses source
contract `1.2.0`; it does not evaluate new reviews under `1.0.0` or `1.1.0`. A complete, verified empty diff now
qualifies as captured evidence, while each practice still establishes its own occasion and observation.

Review custom practices and instance catalogue overrides through their normal administration endpoints.
Read the stored definition and replace `automatedReviewPolicy.sourceContractVersion` with `1.2.0` in an
explicit policy update, preserving the remaining policy fields, bindings and criteria unless the review
calls for a deliberate change. Merely updating criteria preserves the old policy and is not sufficient.
Use the catalogue adoption flow for updated bundled definitions and for reviewed instance overrides in
workspaces. Confirm the effective definition reports `1.2.0` before resuming reviews.

Stored definitions remain readable and editable. Historical review evidence and its original contract
and catalogue digest remain unchanged; historical readiness reports are not re-derived under the new
policy. Do not edit stored evidence or rewrite released migrations to change their version.
