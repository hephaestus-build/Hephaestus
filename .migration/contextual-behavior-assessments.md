#### 🔴 Upgrade contextual practice assessment and delivery together

Pause new practice reviews and let in-flight reviews and feedback dispatches finish before upgrading.
Deploy the matching server and review runtime versions together; deploy the matching webapp for the
updated assessment explanations. Resume reviews after the updated components are healthy.

Use the existing catalogue adoption flow to apply the updated bundled definitions to workspace
practices. Review instance overrides and customized criteria as well: each observation identifies a
specific behavior, records whether it occurred, and assesses whether that behavior is desirable or
undesirable in its evidenced context. Keep the behavior referent stable within the observation.
Different behaviors under one practice can have different assessments. Outcomes remain derived from
presence and assessment; unassessed statuses remain outside the outcome matrix. Catalogue adoption
creates new revisions and does not rewrite historical judgments or silently replace customizations.

Remove `PRACTICE_REVIEW_PROGRESS_FOOTER` and any
`hephaestus.practice-review.progress-footer` override. Automatic cross-review progress footers and
inferred resolved/regressed history summaries are no longer produced. Recorded observations,
delivered feedback and prepared feedback remain available. Matching a location or omitting a prior
observation does not establish that a concern was resolved.

Reactions and delivery receipts apply to their exact bound observations. They do not suppress a new
observation merely because its practice and file match an earlier one. Custom inline-delivery
integrations must preserve the supplied `deliveryKey` unchanged as an opaque receipt-correlation key;
newly composed placements use the observation occurrence identity rather than location grouping.
