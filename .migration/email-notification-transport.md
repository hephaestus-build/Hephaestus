#### 🔴 Research participation uses the consent API only

Custom API clients must stop reading or writing `participateInResearch` on `/user/settings`.
That endpoint now manages practice-feedback delivery only. Read the current decision through
`GET /user/consent` and record a research choice through `PUT /user/consent/research`, using the
current wording version and research organisation returned by the consent API. Use the generated
OpenAPI contract for the complete request. Do not copy a historical preference flag into a new
consent decision: the person must answer the wording and organisation shown to them.

The shipped webapp already uses this consent flow. Slack App Home links to User settings instead of
maintaining a separate research toggle. Historical database records are retained; no destructive
migration or SMTP activation is required for this change.
